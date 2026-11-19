package com.sudoku.quiet

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.Random

class GameTest {
    @Test fun keypadChoiceAlsoHighlightsWithoutChangingTheBoard() {
        val initial = Game.new(0, Random(72))
        var game = initial.input(3)
        assertEquals(3, game.viewedDigit)
        assertEquals(initial.board, game.board); assertFalse(game.canUndo)
        assertEquals(-1, game.selected); assertEquals(-1, game.inspected)
        game = game.input(7)
        assertEquals(7, game.viewedDigit)
        assertEquals(7, Game.restore(game.save()).viewedDigit)
        game = game.input(7)
        assertEquals(0, game.viewedDigit)
        game = game.lock(8)
        assertEquals(8, game.viewedDigit)
        val raw = game.givens.indices.first { game.givens[it] != 0 && game.givens[it] != 8 }
        game = game.select(raw)
        assertEquals(game.givens[raw], game.viewedDigit); assertEquals(8, game.armedDigit)
        game = game.select(raw)
        assertEquals(8, game.viewedDigit)
        assertEquals(initial.board, game.board); assertFalse(game.canUndo)
    }
    @Test fun puzzlesKeepUniquenessAndDifficulty() {
        val random = Random(213)
        for (level in 0..3) {
            val puzzles = HashSet<String>()
            repeat(100) {
                val puzzle = Sudoku.puzzle(level, random)
                puzzles += puzzle.joinToString("")
                assertEquals(1, Sudoku.countSolutions(puzzle))
                assertEquals(level, Sudoku.rate(puzzle))
                if (level < 2) {
                    assertTrue(puzzle.count { it != 0 } in if (level == 0) 60..64 else 44..48)
                    assertArrayEquals(Sudoku.solve(puzzle), solveWithoutNotes(puzzle, boxScan = level == 1))
                }
                val answer = requireNotNull(Sudoku.solve(puzzle))
                assertTrue(Sudoku.valid(answer))
                for (i in 0..80) assertTrue(answer[i] > 0 && (puzzle[i] == 0 || answer[i] == puzzle[i]))
            }
            assertEquals(100, puzzles.size)
        }
        assertEquals(2, Sudoku.countSolutions(IntArray(81)))
        val bad = IntArray(81).apply { this[0] = 5; this[8] = 5 }
        assertNull(Sudoku.solve(bad))
        bad[8] = 10
        assertFalse(Sudoku.valid(bad))
        assertThrows(IllegalArgumentException::class.java) { Sudoku.parse("123") }
    }

    @Test fun formerEasyPuzzleNoLongerPassesTheNoNotesTier() {
        val oldEasy = Sudoku.parse(Sudoku.seeds[1])
        assertEquals(1, Sudoku.countSolutions(oldEasy))
        assertNull(solveWithoutNotes(oldEasy, boxScan = true))
        assertEquals(2, Sudoku.rate(oldEasy))
    }

    // Independent set-based replay: only complete a unit or cross out occupied rows/columns in a box.
    private fun solveWithoutNotes(input: IntArray, boxScan: Boolean): IntArray? {
        val board = input.clone()
        val rows = (0..8).map { r -> (0..8).map { c -> r * 9 + c } }
        val columns = (0..8).map { c -> rows.map { it[c] } }
        val boxes = (0..8).map { b -> (0..8).map { j -> (b / 3 * 3 + j / 3) * 9 + b % 3 * 3 + j % 3 } }
        while (board.contains(0)) {
            var move: Pair<Int, Int>? = null
            for (unit in rows + columns + boxes) {
                val empty = unit.filter { board[it] == 0 }
                if (empty.size == 1) {
                    move = empty.single() to ((1..9).toSet() - unit.map { board[it] }.toSet()).single()
                    break
                }
            }
            if (move == null && boxScan) {
                scan@ for (box in boxes) for (digit in (1..9).toSet() - box.map { board[it] }.toSet()) {
                    val positions = box.filter { cell -> board[cell] == 0 &&
                        rows[cell / 9].none { board[it] == digit } && columns[cell % 9].none { board[it] == digit } }
                    if (positions.size == 1) { move = positions.single() to digit; break@scan }
                }
            }
            val (index, digit) = move ?: return null
            board[index] = digit
        }
        return board
    }

    @Test fun notesEditsAndUndoRemainAtomic() {
        var game = Game.new(0, Random(72))
        val index = game.givens.indices.first { i -> game.givens[i] == 0 && game.givens.indices.any { game.givens[it] == 0 && Sudoku.peers(i, it) } }
        val peer = game.givens.indices.first { game.givens[it] == 0 && Sudoku.peers(index, it) }
        val fixed = game.givens.indices.first { game.givens[it] != 0 }
        assertEquals(game, game.enter(fixed, 0))
        assertEquals(game, game.enter(-1, 2))
        assertEquals(game, game.enter(81, 2))
        assertEquals(game, game.enter(index, 10))
        game = game.copy(draft = true).enter(index, 3).enter(index, 7).enter(peer, 3)
        assertEquals((1 shl 3) or (1 shl 7), game.notes[index])
        assertEquals(0, game.board[index])
        assertEquals(1 shl 3, game.enter(index, 7).notes[index])
        val before = game
        game = game.copy(draft = false).enter(index, 3)
        assertEquals(0, game.notes[index]); assertEquals(0, game.notes[peer])
        assertEquals(0, before.board[index])
        game = game.undo()
        assertEquals(before.board, game.board); assertEquals(before.notes, game.notes)
        game = game.enter(index, 0)
        assertEquals(0, game.notes[index])
        game = game.undo()
        assertEquals(before.notes, game.notes)
        val hinted = game.copy(selected = index, draft = true).hint()
        assertEquals(hinted.solution[index], hinted.board[index]); assertTrue(hinted.draft)
        assertEquals(hinted, hinted.enter(index, 4))
        assertEquals(game.board, hinted.undo().board)
    }

    @Test fun secondTapDeselectsWithoutChangingBoardOrNotes() {
        var game = Game.new(1, Random(10))
        val cell = game.givens.indexOf(0)
        game = game.select(cell)
        assertEquals(cell, game.selected)
        val before = game
        game = game.select(cell)
        assertEquals(-1, game.selected); assertEquals(before.board, game.board); assertFalse(game.canUndo)
        game = game.copy(draft = true).lock(3).select(cell)
        assertEquals(1 shl 3, game.notes[cell])
        val notes = game.notes
        game = game.select(cell)
        assertEquals(-1, game.selected); assertEquals(notes, game.notes)
        game = game.select(cell)
        assertEquals(0, game.notes[cell])
        val given = game.givens.indices.first { game.givens[it] != 0 }
        assertEquals(game.copy(selected = -1), game.select(given).select(given))
        assertEquals(game, game.select(81))
    }

    @Test fun filledCellsAreViewedWithoutChangingContentOrInputChoice() {
        val initial = Game.new(0, Random(72))
        val raw = initial.givens.indices.first { initial.givens[it] != 0 }
        val editable = initial.givens.indexOf(0)
        val filled = initial.enter(editable, initial.solution[editable])
        for (draft in listOf(false, true)) for (digit in listOf(0, 3)) for (cell in listOf(raw, editable)) {
            val game = filled.copy(draft = draft, armedDigit = digit)
            val viewed = game.select(cell)
            assertEquals(-1, viewed.selected); assertEquals(cell, viewed.inspected)
            assertEquals(game.board[cell], viewed.viewedDigit)
            assertEquals(game.copy(inspected = cell), viewed)
            assertEquals(viewed, Game.restore(viewed.save()))
            assertEquals(game, viewed.select(cell))
        }
        assertEquals(filled.givens, filled.select(raw).undo().board)
        assertEquals(-1, filled.select(raw).undo().inspected)
        assertEquals(0, filled.select(editable).input(0).board[editable])
        assertEquals(filled.board, filled.select(raw).input(0).board)
        val oldSave = filled.copy(selected = raw, elapsedMs = 12345)
        assertEquals(oldSave.copy(selected = -1), Game.restore(oldSave.save()))
    }

    @Test fun cellFirstUsesKeypadOnlyAsAnInputDevice() {
        val initial = Game.new(0, Random(72))
        val cells = initial.givens.indices.filter { initial.givens[it] == 0 }
        var game = initial.select(cells[0]).input(3).input(5)
        assertEquals(5, game.board[cells[0]]); assertEquals(0, game.armedDigit)
        game = game.select(cells[1])
        assertEquals(0, game.board[cells[1]]); assertEquals(cells[1], game.selected)
        game = game.select(cells[0])
        assertEquals(5, game.board[cells[0]]); assertEquals(cells[0], game.inspected)
        game = game.input(7)
        assertEquals(7, game.board[cells[0]]); assertEquals(0, game.armedDigit)
        assertEquals(cells[0], game.selected); assertEquals(-1, game.inspected)
        game = game.copy(draft = true).select(cells[1]).input(2).input(8)
        assertEquals(260, game.notes[cells[1]]); assertEquals(0, game.armedDigit)
        game = game.select(cells[2])
        assertEquals(0, game.notes[cells[2]]); assertEquals(0, game.board[cells[2]])
    }

    @Test fun digitFirstClearsFocusWhenChangingDigitsAndOnlyFillsEmptyCells() {
        val initial = Game.new(0, Random(72))
        val cells = initial.givens.indices.filter { initial.givens[it] == 0 }
        val raw = initial.givens.indices.first { initial.givens[it] != 0 }
        for (draft in listOf(false, true)) {
            var game = initial.copy(draft = draft).input(3).select(cells[0])
            assertEquals(cells[0], game.selected)
            val before = game
            game = game.input(7)
            assertEquals(-1, game.selected); assertEquals(-1, game.inspected)
            assertEquals(before.board, game.board); assertEquals(before.notes, game.notes)
            game = game.select(raw)
            assertEquals(raw, game.inspected); assertEquals(7, game.armedDigit)
            game = game.select(cells[1])
            assertEquals(-1, game.inspected)
            assertEquals(if (draft) 0 else 7, game.board[cells[1]])
            assertEquals(if (draft) 1 shl 7 else 0, game.notes[cells[1]])
            game = game.input(7)
            assertEquals(0, game.armedDigit); assertEquals(-1, game.selected)
            game = game.select(cells[2])
            assertEquals(0, game.board[cells[2]]); assertEquals(0, game.notes[cells[2]])
        }
        val filled = initial.enter(cells[0], 3).input(7).select(cells[0])
        assertEquals(3, filled.board[cells[0]]); assertEquals(cells[0], filled.inspected)
        val switched = filled.input(9)
        assertEquals(-1, switched.inspected); assertEquals(3, switched.board[cells[0]])
        assertEquals(9, switched.select(cells[1]).board[cells[1]])
    }

    @Test fun errorPreferenceDoesNotAlterGameOrValidation() {
        var game = Game.new(0, Random(13))
        val cell = game.givens.indexOf(0)
        val wrong = game.solution[cell] % 9 + 1
        game = game.enter(cell, wrong)
        val saved = game.save()
        assertTrue(game.isError(cell, true)); assertFalse(game.isError(cell, false))
        assertEquals(saved, game.save())
        assertFalse(game.complete)
        assertFalse(game.isError(game.givens.indices.first { game.givens[it] != 0 }, true))
        assertFalse(game.enter(cell, game.solution[cell]).isError(cell, true))
        assertFalse(game.enter(cell, 0).isError(cell, true))
        assertFalse(game.isError(-1, true))
    }

    @Test fun javaV1SaveMigratesIncludingUndo() {
        val legacy = requireNotNull(javaClass.getResource("/legacy-v1.txt")).readText().trim()
        val game = Game.restore(legacy)
        assertEquals(game, Game.restore(game.save()))
        assertEquals(-1, game.inspected)
        val oldBytes = Base64.getDecoder().decode(legacy)
        val newBytes = Base64.getDecoder().decode(game.save())
        assertArrayEquals(oldBytes.copyOfRange(4, oldBytes.size), newBytes.copyOfRange(4, newBytes.size - 4))
        assertEquals(1, game.level); assertEquals(0, game.selected); assertEquals(5, game.armedDigit)
        assertTrue(game.draft); assertEquals(123456L, game.elapsedMs)
        assertEquals(264, game.notes[0]); assertEquals(8, game.undo().notes[0])
        assertEquals(0, game.undo().undo().notes[0])
    }

    @Test fun saveValidationAndHistoryBound() {
        var game = Game.new(0, Random(213))
        val cell = game.givens.indexOf(0)
        game = game.copy(selected = cell, draft = true, armedDigit = 8, elapsedMs = 98765)
        repeat(150) { game = game.enter(cell, 2) }
        assertEquals(game.save(), Game.restore(game.save()).save())
        var restored = Game.restore(game.save())
        repeat(100) { assertTrue(restored.canUndo); restored = restored.undo() }
        assertFalse(restored.canUndo)
        val raw = game.givens.indices.first { game.givens[it] != 0 }
        for (bad in listOf(game.copy(inspected = raw), game.copy(selected = -1, inspected = cell), game.copy(inspected = 81))) {
            assertThrows(IllegalArgumentException::class.java) { Game.restore(bad.save()) }
        }
        for (bad in listOf("", "bad", game.save().drop(4), "a".repeat(100001))) {
            assertThrows(IllegalArgumentException::class.java) { Game.restore(bad) }
        }
        val bytes = Base64.getDecoder().decode(game.save())
        bytes[3] = 3
        assertThrows(IllegalArgumentException::class.java) { Game.restore(Base64.getEncoder().encodeToString(bytes)) }
    }

    @Test fun completionAndRestart() {
        var game = Game.new(3, Random(123))
        for (cell in 0..80) if (game.givens[cell] == 0) game = game.enter(cell, game.solution[cell])
        assertTrue(game.complete); assertEquals(game, game.hint())
        assertEquals(game, game.enter(game.givens.indexOf(0), 0))
        assertFalse(game.undo().complete)
        game = game.restart()
        assertEquals(game.givens, game.board); assertTrue(game.notes.all { it == 0 })
        assertEquals(0L, game.elapsedMs); assertFalse(game.canUndo)
    }
}
