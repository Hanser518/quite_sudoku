package com.sudoku.quiet

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Use a dedicated emulator: each case resets this app's saved game. */
class GameUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val preferences get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("sudoku", 0)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var initial: Game
    private fun state() = Game.restore(requireNotNull(preferences.getString("game", null)))
    private fun node(tag: String) = compose.onNodeWithTag(tag)
    private fun click(tag: String) { node(tag).performClick(); compose.waitForIdle() }
    private fun cellDescription(index: Int) = node("cell-$index").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    @Before fun launch() {
        initial = Game.new(0, Random(1337)).copy(elapsedMs = 12000)
        preferences.edit().clear().putString("game", initial.save()).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }
    @After fun close() { scenario.close() }

    @Test fun switchingAndClearingHighlightsFadeWithoutDarkFlashes() {
        val raw = initial.givens.indices.first { initial.givens[it] == 3 }
        fun background(): Color {
            val pixels = node("cell-$raw").captureToImage().toPixelMap()
            return pixels[pixels.width / 4, pixels.height / 4]
        }
        for (light in listOf(false, true)) {
            if (light) { click("settings"); click("theme"); click("dialog-close") }
            val plain = background()
            click("key-3")
            val highlighted = background()
            compose.mainClock.autoAdvance = false
            try {
                node("key-7").performClick()
                compose.mainClock.advanceTimeByFrame()
                repeat(12) { frame ->
                    compose.mainClock.advanceTimeBy(16)
                    val color = background()
                    assertTrue("Highlight stays between its endpoints at frame $frame: $color",
                        color.luminance() >= minOf(highlighted.luminance(), plain.luminance()) - .005f &&
                            color.luminance() <= maxOf(highlighted.luminance(), plain.luminance()) + .005f)
                    if (frame == 3) node("key-3").performClick()
                    if (frame == 6) node("key-3").performClick()
                }
                compose.mainClock.advanceTimeBy(1000)
                assertEquals(plain, background())
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test fun crosshairAppearsAtTheNewCellAndFadesAtItsLastPosition() {
        fun corner(index: Int): Color {
            val pixels = node("cell-$index").captureToImage().toPixelMap()
            return pixels[pixels.width / 10, pixels.height / 10]
        }
        val plain = corner(74)
        val top = corner(0)
        compose.mainClock.autoAdvance = false
        try {
            node("cell-80").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            assertEquals("First selection must not sweep from the top-left", top, corner(0))
            assertNotEquals(plain, corner(74))
            compose.mainClock.advanceTimeBy(1000)
            val visible = corner(74)
            node("cell-80").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val fading = corner(74)
            assertTrue(fading.luminance() > plain.luminance() && fading.luminance() < visible.luminance())
            compose.mainClock.advanceTimeBy(1000)
            assertEquals(plain, corner(74))
            node("cell-10").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            assertEquals("New selection must not flash at the old location", plain, corner(74))
            assertNotEquals(plain, corner(12))
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun keypadHighlightsMatchingRawAndPlayerDigitsUsingBackgrounds() {
        val cell = initial.givens.indexOf(0)
        val other = initial.givens.indices.last { initial.givens[it] == 0 }
        val raw = initial.givens.indices.first { initial.givens[it] == 3 }
        fun background(index: Int): Color {
            val pixels = node("cell-$index").captureToImage().toPixelMap()
            return pixels[pixels.width / 4, pixels.height / 4]
        }
        click("cell-$cell"); click("key-3"); click("cell-$cell")
        click("cell-$other"); click("key-7"); click("cell-$other")
        val board = state().board
        for (light in listOf(false, true)) {
            if (light) { click("settings"); click("theme"); click("dialog-close") }
            val normal = background(cell)
            val otherNormal = background(other)
            assertTrue("Unselected normal uses a neutral background", maxOf(normal.red, normal.green, normal.blue) - minOf(normal.red, normal.green, normal.blue) < .04f)
            val plain = background(raw)
            click("key-3")
            assertEquals(board, state().board); assertEquals(-1, state().selected)
            for (index in board.indices.filter { board[it] == 3 }) {
                node("cell-$index").assertIsNotSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "查看数字 3"))
            }
            assertNotEquals(normal, background(cell)); assertNotEquals(plain, background(raw))
            assertEquals("Unmatched normal remains subdued", otherNormal, background(other))
            val highlighted = background(cell)
            assertTrue("Matching normal uses the theme accent", highlighted.blue - highlighted.red > .06f)
            assertEquals(background(cell), background(raw))
            click("key-7")
            assertEquals(board, state().board)
            node("cell-$cell").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            assertEquals(normal, background(cell)); assertEquals(plain, background(raw))
            assertEquals(highlighted, background(other))
            for (index in board.indices.filter { board[it] == 7 }) {
                node("cell-$index").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "查看数字 7"))
            }
            click("key-7"); assertEquals(0, state().viewedDigit)
            assertEquals(otherNormal, background(other))
        }
    }

    @Test fun remainingCountsFollowEditsIgnoreNotesAndNeverGoNegative() {
        fun assertCount(digit: Int, count: Int) {
            node("key-$digit").assertContentDescriptionEquals("数字 $digit，待填 $count 个")
            val label = compose.onNodeWithTag("remaining-$digit", useUnmergedTree = true)
            label.assertIsDisplayed()
            val bounds = label.fetchSemanticsNode().boundsInRoot
            val key = node("key-$digit").fetchSemanticsNode().boundsInRoot
            assertTrue("Count fits below the digit", bounds.top >= key.center.y && bounds.bottom <= key.bottom)
        }
        for (digit in 1..9) assertCount(digit, 9 - initial.givens.count { it == digit })
        compose.onNodeWithTag("remaining-0", useUnmergedTree = true).assertDoesNotExist()
        val index = initial.givens.indexOf(0)
        val digit = initial.solution[index]
        val remaining = 9 - initial.givens.count { it == digit }
        click("mode-draft"); click("cell-$index"); click("key-$digit")
        assertCount(digit, remaining)
        click("mode-fill"); click("key-$digit"); assertCount(digit, remaining - 1)
        click("key-0"); assertCount(digit, remaining)
        click("undo"); assertCount(digit, remaining - 1)
        click("cell-$index"); click("key-$digit")
        val empty = state().board.indices.filter { state().board[it] == 0 }
        for (cell in empty.take(remaining + 1)) click("cell-$cell")
        assertCount(digit, 0)
        click("settings"); click("errors"); click("dialog-close"); assertCount(digit, 0)
        click("restart"); click("confirm"); assertCount(digit, remaining)
    }

    @Test fun rawNormalAndActiveHaveDistinctAppearanceAndInteraction() {
        val raw = initial.givens.indices.first { initial.givens[it] != 0 }
        val index = initial.givens.indexOf(0)
        val blank = initial.givens.indices.last { initial.givens[it] == 0 }
        fun background(index: Int): Color {
            val pixels = node("cell-$index").captureToImage().toPixelMap()
            return pixels[pixels.width / 4, pixels.height / 4]
        }
        for (light in listOf(false, true)) {
            if (light) { click("settings"); click("theme"); click("dialog-close") }
            node("cell-$raw").assertHasClickAction()
            val boardColor = background(raw)
            assertEquals(Color(if (light) 0xfff4f5f8 else 0xff191a1e), boardColor)
            node("cell-$raw").performTouchInput { click() }; compose.waitForIdle()
            assertEquals(-1, state().selected)
            assertEquals(raw, state().inspected)
            click("cell-$raw")
            click("cell-$index"); click("key-${initial.solution[index]}")
            node("cell-$index").assertIsSelected()
            val active = background(index)
            assertTrue("Selection uses the theme accent", active.blue > active.red)
            click("cell-$index"); node("cell-$index").assertIsNotSelected()
            val normal = background(index)
            assertTrue("Normal is distinct from the board", abs(normal.luminance() - boardColor.luminance()) > .015f)
            assertTrue("Active is stronger than normal", abs(active.luminance() - boardColor.luminance()) > abs(normal.luminance() - boardColor.luminance()))
            click("cell-$blank"); node("cell-$blank").assertIsSelected()
            assertEquals(active, background(blank))
            click("cell-$blank"); node("cell-$blank").assertIsNotSelected()
            assertEquals(0, state().board[blank])
            assertEquals(initial.solution[index], state().board[index])
        }
    }

    @Test fun inputOrderAndReadOnlyHighlightsRemainSeparate() {
        val cells = initial.givens.indices.filter { initial.givens[it] == 0 }
        click("key-3"); click("cell-${cells[0]}")
        assertEquals(3, state().board[cells[0]])
        click("key-7"); node("cell-${cells[0]}").assertIsNotSelected()
        assertEquals(-1, state().selected); assertEquals(3, state().board[cells[0]])
        click("cell-${cells[0]}")
        assertEquals(cells[0], state().inspected); assertEquals(3, state().board[cells[0]])
        for (index in state().board.indices.filter { state().board[it] == 3 }) {
            node("cell-$index").assertIsNotSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "查看数字 3"))
        }
        val raw = initial.givens.indices.first { initial.givens[it] != 0 && initial.givens[it] != 7 }
        click("cell-$raw"); assertEquals(raw, state().inspected)
        assertEquals(7, state().armedDigit)
        node("key-7").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已锁定"))
        scenario.recreate(); compose.waitForIdle()
        assertEquals(raw, state().inspected); assertEquals(7, state().armedDigit)
        click("key-9"); assertEquals(-1, state().inspected)
        click("cell-${cells[1]}"); assertEquals(9, state().board[cells[1]])
        click("key-9"); assertEquals(0, state().armedDigit)
        click("cell-${cells[2]}"); click("key-2")
        assertEquals(2, state().board[cells[2]]); assertEquals(0, state().armedDigit)
        click("cell-${cells[3]}")
        assertEquals(0, state().board[cells[3]])
        click("cell-${cells[0]}"); click("key-0")
        assertEquals(0, state().board[cells[0]])
    }

    @Test fun selectionNotesEraseUndoAndLongPress() {
        val index = initial.givens.indexOf(0)
        click("cell-$index"); node("cell-$index").assertIsSelected()
        click("cell-$index"); node("cell-$index").assertIsNotSelected()
        assertEquals(-1, state().selected); assertFalse(state().canUndo)
        click("mode-draft"); node("mode-draft").assertIsSelected()
        click("cell-$index"); click("key-3"); click("key-8")
        assertEquals(264, state().notes[index]); assertEquals(0, state().board[index])
        click("cell-$index"); assertEquals(264, state().notes[index])
        click("cell-$index"); click("key-3"); assertEquals(256, state().notes[index])
        click("undo"); assertEquals(264, state().notes[index])
        click("mode-fill"); click("key-4"); assertEquals(4, state().board[index])
        click("undo"); assertEquals(264, state().notes[index])
        click("key-0"); assertEquals(0, state().notes[index])
        node("key-6").performTouchInput { longClick() }; compose.waitForIdle()
        assertEquals(6, state().armedDigit); assertEquals(-1, state().selected)
        click("cell-$index"); assertEquals(6, state().board[index])
        click("cell-$index"); assertEquals(-1, state().selected); assertEquals(6, state().board[index])
        click("key-6"); assertEquals(0, state().armedDigit)
        click("cell-$index"); click("hint"); assertEquals(initial.solution[index], state().board[index])
    }

    @Test fun errorsCanBeHiddenAndPreferenceSurvivesRecreation() {
        val index = initial.givens.indexOf(0)
        val wrong = initial.solution[index] % 9 + 1
        click("cell-$index"); click("key-$wrong")
        assertTrue(cellDescription(index).contains("错误"))
        click("settings"); node("errors").assertIsOn(); click("errors"); node("errors").assertIsOff()
        click("theme"); node("theme").assertIsOn(); click("dialog-close")
        assertFalse(cellDescription(index).contains("错误")); assertEquals(wrong, state().board[index])
        scenario.recreate(); compose.waitForIdle()
        assertFalse(cellDescription(index).contains("错误")); assertEquals(wrong, state().board[index])
        click("settings"); node("errors").assertIsOff(); node("theme").assertIsOn()
        click("errors"); click("dialog-close"); assertTrue(cellDescription(index).contains("错误"))
        click("undo"); assertEquals(0, state().board[index]); assertFalse(cellDescription(index).contains("错误"))
    }

    @Test fun pauseAndSaveSurviveActivityRecreation() {
        val index = initial.givens.indexOf(0)
        click("cell-$index"); click("mode-draft"); click("key-2")
        click("pause"); node("key-2").assertIsNotEnabled()
        val pausedAt = state().elapsedMs
        Thread.sleep(1200)
        scenario.recreate(); compose.waitForIdle()
        node("resume").assertIsDisplayed(); node("key-2").assertIsNotEnabled()
        assertEquals(4, state().notes[index]); assertTrue(state().draft)
        assertEquals(pausedAt, state().elapsedMs)
        click("resume"); click("undo"); assertEquals(0, state().notes[index])
        assertTrue(state().elapsedMs - pausedAt < 1500)
    }

    @Test fun difficultiesAndRestartKeepTheirFlows() {
        for (level in 1..3) {
            val index = state().givens.indexOf(0)
            click("cell-$index"); click("key-1")
            val previous = state().givens
            click("difficulty"); click("level-$level")
            node("confirm").assertIsDisplayed()
            click("dialog-close"); assertEquals(previous, state().givens)
            click("new-game"); click("level-$level"); click("confirm")
            assertEquals(level, state().level); assertFalse(state().canUndo)
            assertEquals(1, Sudoku.countSolutions(state().givens.toIntArray()))
        }
        val index = state().givens.indexOf(0)
        click("cell-$index"); click("key-1")
        val givens = state().givens
        click("restart"); click("confirm")
        assertEquals(givens, state().board); assertFalse(state().canUndo)
    }

    @Test fun portraitControlsFitWithoutScrollingOrHelperCopy() {
        val screen = node("screen").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("board", "difficulty", "mode-fill", "mode-draft", "key-1", "key-5", "key-6", "key-0", "new-game", "restart", "hint", "undo")) {
            node(tag).assertIsDisplayed()
            val bounds = node(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag fits vertically: $bounds in $screen", bounds.top >= screen.top - 1 && bounds.bottom <= screen.bottom + 1)
            assertTrue("$tag fits horizontally", bounds.left >= screen.left - 1 && bounds.right <= screen.right + 1)
        }
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)
        for (copy in listOf("先选格", "已填", "每行", "轻点数字", "SUDOKU")) {
            compose.onAllNodes(hasText(copy, substring = true)).assertCountEquals(0)
        }
    }

    @Test fun modeIndicatorActuallyInterpolatesWithEasing() {
        compose.mainClock.autoAdvance = false
        val start = node("mode-indicator").fetchSemanticsNode().boundsInRoot.left
        node("mode-draft").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(112)
        val middle = node("mode-indicator").fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.advanceTimeBy(300)
        val end = node("mode-indicator").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Indicator moves between endpoints", middle > start && middle < end)
        assertTrue("Nonlinear easing advances past the halfway position", (middle - start) / (end - start) > .6f)
        compose.mainClock.autoAdvance = true
    }
}
