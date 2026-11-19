package com.sudoku.quiet

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

private val Ease = CubicBezierEasing(.2f, 0f, 0f, 1f)
private const val Motion = 240
private val ColorMotion = spring<Color>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)
private val FloatMotion = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

@Immutable
private data class Palette(val background: Color, val surface: Color, val ink: Color, val muted: Color,
    val accent: Color, val line: Color, val error: Color) {
    val normal get() = ink.copy(alpha = .10f).compositeOver(background)
    val preview get() = accent.copy(alpha = .36f).compositeOver(background)
    val active get() = accent.copy(alpha = .50f).compositeOver(background)
}

@Composable
private fun palette(light: Boolean): Palette {
    @Composable fun color(day: Long, night: Long): Color = animateColorAsState(
        Color(if (light) day else night), tween(Motion, easing = Ease), label = "theme"
    ).value
    return Palette(color(0xfff4f5f8, 0xff191a1e), color(0xffe5e9f0, 0xff272a31),
        color(0xff181818, 0xfff1f3f8), color(0xff656e7d, 0xff939aa9),
        color(0xff466da9, 0xffb6cbf6),
        color(0xffc7cdd7, 0xff363c47), color(0xffb32642, 0xffff9caa))
}

@Composable
internal fun SudokuScreen(game: Game, light: Boolean, showErrors: Boolean, paused: Boolean,
    time: () -> String, onEdit: ((Game) -> Game) -> Unit, onPause: () -> Unit,
    onSettings: () -> Unit, onDifficulty: () -> Unit, onRestart: () -> Unit) {
    val colors = palette(light)
    MaterialTheme(colorScheme = if (light) lightColorScheme(primary = colors.accent) else darkColorScheme(primary = colors.accent)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp).testTag("screen")) {
            val landscape = maxWidth > maxHeight
            val boardSize = if (landscape) minOf(maxHeight, maxWidth * .46f, 440.dp)
                else minOf(maxWidth, 380.dp, (maxHeight - 320.dp).coerceAtLeast(90.dp))
            val controls: @Composable ColumnScope.() -> Unit = {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconKey(if (paused) "play" else "pause", if (paused) "继续" else "暂停", "pause", colors, onClick = onPause)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(time(), color = colors.ink, fontSize = 20.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("timer"))
                    }
                    IconKey("settings", "设置", "settings", colors, onClick = onSettings)
                }
                Pressable(onClick = onDifficulty, modifier = Modifier.height(48.dp).testTag("difficulty"), colors = colors,
                    description = "选择难度", shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.background(colors.surface, CircleShape).padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (game.level == 3) "Extreme" else Sudoku.levels[game.level], color = colors.accent, fontSize = 16.sp)
                        Glyph("down", colors.accent, Modifier.size(14.dp))
                    }
                }
                if (!landscape) BoardArea(game, paused, showErrors, colors, boardSize, onEdit, onPause)
                ModeSwitch(game.draft, !paused && !game.complete, colors) { draft -> onEdit { it.copy(draft = draft) } }
                NumberPad(game, !paused && !game.complete, colors, onEdit)
                Row(Modifier.widthIn(max = 320.dp).fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconKey("new", "新游戏", "new-game", colors, onClick = onDifficulty)
                    IconKey("restart", "重新开始本题", "restart", colors, onClick = onRestart)
                    IconKey("hint", "提示", "hint", colors, enabled = !paused && !game.complete, onClick = { onEdit(Game::hint) })
                    IconKey("undo", "撤销", "undo", colors, enabled = !paused && game.canUndo, onClick = { onEdit(Game::undo) })
                }
            }
            if (landscape) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                BoardArea(game, paused, showErrors, colors, boardSize, onEdit, onPause)
                Column(Modifier.widthIn(max = 340.dp).weight(1f).padding(start = 16.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween, content = controls)
            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween, content = controls)
        }
    }
}

@Composable
private fun BoardArea(game: Game, paused: Boolean, showErrors: Boolean, colors: Palette, side: Dp,
    onEdit: ((Game) -> Game) -> Unit, onPause: () -> Unit) {
    Box(Modifier.size(side).testTag("board")) {
        AnimatedVisibility(!paused, enter = fadeIn(tween(Motion, easing = Ease)) + scaleIn(tween(Motion, easing = Ease), initialScale = .97f),
            exit = fadeOut(tween(160, easing = Ease))) {
            SudokuBoard(game, showErrors, colors, side) { index -> onEdit { it.select(index) } }
        }
        AnimatedVisibility(paused, modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(Motion, easing = Ease)) + scaleIn(tween(Motion, easing = Ease), initialScale = .85f),
            exit = fadeOut(tween(120, easing = Ease))) {
            Pressable(onClick = onPause, colors = colors, description = "继续游戏", modifier = Modifier.size(72.dp).testTag("resume")) {
                Box(Modifier.fillMaxSize().background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Glyph("play", colors.accent, Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun SudokuBoard(game: Game, showErrors: Boolean, colors: Palette, side: Dp, onSelect: (Int) -> Unit) {
    val cell = side / 9
    val focus = if (game.selected >= 0) game.selected else game.inspected
    val position = remember { Animatable(Offset((focus.coerceAtLeast(0) % 9).toFloat(), (focus.coerceAtLeast(0) / 9).toFloat()), Offset.VectorConverter) }
    val visibility = remember { Animatable(if (focus >= 0) 1f else 0f) }
    LaunchedEffect(focus) {
        if (focus >= 0) {
            val target = Offset((focus % 9).toFloat(), (focus / 9).toFloat())
            if (visibility.value == 0f) position.snapTo(target)
            launch { position.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)) }
            visibility.animateTo(1f, FloatMotion)
        } else visibility.animateTo(0f, FloatMotion)
    }
    Box(Modifier.size(side).semantics { collectionInfo = CollectionInfo(9, 9) }) {
        Canvas(Modifier.matchParentSize()) {
            val step = size.width / 9
            val row = position.value.y
            val column = position.value.x
            val round = CornerRadius(step * .18f)
            val horizontal = Path().apply { addRoundRect(RoundRect(Rect(0f, row * step, size.width, (row + 1) * step), round)) }
            val vertical = Path().apply { addRoundRect(RoundRect(Rect(column * step, 0f, (column + 1) * step, size.height), round)) }
            val cross = Path.combine(PathOperation.Union, horizontal, vertical)
            drawPath(cross, colors.accent.copy(alpha = .10f * visibility.value.coerceIn(0f, 1f)))
            drawPath(cross, colors.accent.copy(alpha = .18f * visibility.value.coerceIn(0f, 1f)), style = Stroke(.75.dp.toPx()))
        }
        Column(Modifier.fillMaxSize()) {
            repeat(9) { r -> Row(Modifier.weight(1f)) {
                repeat(9) { c -> SudokuCell(game, r * 9 + c, showErrors, colors, cell, onSelect) }
            } }
        }
        Canvas(Modifier.matchParentSize()) {
            val step = size.width / 9
            for (line in 1..8) {
                if (line % 3 == 0) {
                    drawLine(colors.accent.copy(alpha = .85f), Offset(line * step, 1f), Offset(line * step, size.height - 1), 1.5.dp.toPx(), StrokeCap.Round)
                    drawLine(colors.accent.copy(alpha = .85f), Offset(1f, line * step), Offset(size.width - 1, line * step), 1.5.dp.toPx(), StrokeCap.Round)
                } else for (j in 0..8) {
                    drawLine(colors.line, Offset(line * step, (j + .2f) * step), Offset(line * step, (j + .8f) * step), .65.dp.toPx(), StrokeCap.Round)
                    drawLine(colors.line, Offset((j + .2f) * step, line * step), Offset((j + .8f) * step, line * step), .65.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun SudokuCell(game: Game, index: Int, showErrors: Boolean, colors: Palette, side: Dp, onSelect: (Int) -> Unit) {
    val value = game.board[index]
    val selected = game.selected == index
    val viewing = value != 0 && value == game.viewedDigit
    val error = game.isError(index, showErrors)
    val description = buildString {
        append("第 ${index / 9 + 1} 行，第 ${index % 9 + 1} 列，")
        append(if (value == 0) "空格" else value.toString())
        if (game.givens[index] != 0) append("，题目数字")
        if (game.notes[index] != 0) append("，草稿 " + (1..9).filter { game.notes[index] and (1 shl it) != 0 }.joinToString(" "))
        if (error) append("，错误")
    }
    val textSize = with(LocalDensity.current) { (side * .55f).toSp() }
    val noteSize = with(LocalDensity.current) { (side * .235f).toSp() }
    val cellModifier = Modifier.size(side).testTag("cell-$index").semantics {
        this.selected = selected
        collectionItemInfo = CollectionItemInfo(index / 9, 1, index % 9, 1)
    }
    val fill by animateColorAsState(when {
        selected -> colors.active
        viewing -> colors.preview
        game.givens[index] != 0 -> colors.preview.copy(alpha = 0f)
        value != 0 -> colors.normal
        else -> colors.preview.copy(alpha = 0f)
    }, ColorMotion, label = "cell-fill")
    val ink by animateColorAsState(if (error) colors.error else colors.ink,
        ColorMotion, label = "cell-ink")
    val selection by animateFloatAsState(if (selected) 1f else 0f, FloatMotion, label = "selection")
    Pressable(onClick = { onSelect(index) }, colors = colors, description = description,
        state = if (viewing) "查看数字 $value" else null, modifier = cellModifier) {
        Box(Modifier.size(side * .86f).graphicsLayer { scaleX = .96f + selection * .04f; scaleY = scaleX }
            .background(fill, CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(targetState = value, transitionSpec = {
                (fadeIn(tween(180, easing = Ease)) + scaleIn(tween(Motion, easing = Ease), initialScale = .8f)) togetherWith
                    (fadeOut(tween(100, easing = Ease)) + scaleOut(tween(140, easing = Ease), targetScale = .9f))
            }, label = "digit") { digit ->
                if (digit != 0) Text(digit.toString(), fontSize = textSize, lineHeight = textSize, color = ink, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clearAndSetSemantics {})
            }
            if (value == 0) Column(Modifier.fillMaxSize().padding(side * .06f)) {
                repeat(3) { r -> Row(Modifier.weight(1f)) {
                    repeat(3) { c ->
                        val n = r * 3 + c + 1
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            androidx.compose.animation.AnimatedVisibility(game.notes[index] and (1 shl n) != 0,
                                enter = fadeIn(tween(180, easing = Ease)) + scaleIn(tween(Motion, easing = Ease), initialScale = .7f),
                                exit = fadeOut(tween(140, easing = Ease)) + scaleOut(tween(140, easing = Ease), targetScale = .7f)) {
                                Text(n.toString(), fontSize = noteSize, lineHeight = noteSize, color = ink, modifier = Modifier.clearAndSetSemantics {})
                            }
                        }
                    }
                } }
            }
            val errorAlpha by animateFloatAsState(if (error) 1f else 0f, tween(Motion, easing = Ease), label = "error")
            Canvas(Modifier.fillMaxSize()) {
                drawLine(ink.copy(alpha = errorAlpha), Offset(size.width * .4f, size.height * .84f),
                    Offset(size.width * .6f, size.height * .84f), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ModeSwitch(draft: Boolean, enabled: Boolean, colors: Palette, onChange: (Boolean) -> Unit) {
    val offset by animateDpAsState(if (draft) 92.dp else 0.dp, tween(Motion, easing = Ease), label = "mode-slide")
    Box(Modifier.size(192.dp, 48.dp).clip(CircleShape).background(colors.surface)) {
        Box(Modifier.padding(4.dp).offset { IntOffset(offset.roundToPx(), 0) }.size(92.dp, 40.dp).background(colors.accent, CircleShape).testTag("mode-indicator"))
        Row {
            listOf(false, true).forEach { mode ->
                val ink by animateColorAsState(if (draft == mode) colors.background else colors.muted,
                    tween(Motion, easing = Ease), label = "mode-text")
                Pressable(onClick = { onChange(mode) }, colors = colors, enabled = enabled,
                    modifier = Modifier.size(96.dp, 48.dp).testTag(if (mode) "mode-draft" else "mode-fill")
                        .semantics { selected = draft == mode }, description = if (mode) "草稿" else "选数") {
                    Text(if (mode) "草稿" else "选数", color = ink, fontSize = 15.sp, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
}

@Composable
private fun NumberPad(game: Game, enabled: Boolean, colors: Palette, onEdit: ((Game) -> Game) -> Unit) {
    val numberSize = with(LocalDensity.current) { 27.dp.toSp() }
    val inputSize = with(LocalDensity.current) { 24.dp.toSp() }
    val countSize = with(LocalDensity.current) { 11.dp.toSp() }
    Column(Modifier.widthIn(max = 304.dp).fillMaxWidth().height(104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(2) { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(5) { column ->
                val digit = (row * 5 + column + 1) % 10
                val remaining = (9 - game.board.count { it == digit }).coerceAtLeast(0)
                val active = digit != 0 && (game.armedDigit == digit || (game.draft && game.selected >= 0 && game.notes[game.selected] and (1 shl digit) != 0))
                val viewing = digit != 0 && game.viewedDigit == digit
                val fill by animateColorAsState(when {
                    active -> colors.accent
                    viewing -> colors.preview
                    else -> colors.surface
                }, ColorMotion, label = "key-fill")
                val ink by animateColorAsState(if (active) colors.background else colors.ink, ColorMotion, label = "key-ink")
                Pressable(onClick = { onEdit { it.input(digit) } }, onLongClick = if (digit > 0) ({ onEdit { it.lock(digit) } }) else null,
                    colors = colors, enabled = enabled, modifier = Modifier.size(48.dp).testTag("key-$digit"),
                    description = if (digit == 0) "擦除" else "数字 $digit，待填 $remaining 个",
                    state = if (game.armedDigit == digit && digit > 0) "已锁定" else if (viewing) "正在查看" else null) {
                    Box(Modifier.fillMaxSize().background(fill, CircleShape), contentAlignment = Alignment.Center) {
                        if (digit == 0) Text("×", color = ink, fontSize = numberSize, lineHeight = numberSize,
                            fontWeight = FontWeight.Medium, modifier = Modifier.clearAndSetSemantics {})
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(digit.toString(), color = ink, fontSize = inputSize, lineHeight = inputSize,
                                fontWeight = FontWeight.Medium, modifier = Modifier.clearAndSetSemantics {})
                            AnimatedContent(remaining, modifier = Modifier.testTag("remaining-$digit"), transitionSpec = {
                                (fadeIn(tween(180, easing = Ease)) + scaleIn(tween(Motion, easing = Ease), initialScale = .8f)) togetherWith
                                    fadeOut(tween(100, easing = Ease))
                            }, label = "remaining") { count ->
                                Text(count.toString(), color = ink, fontSize = countSize, lineHeight = countSize,
                                    modifier = Modifier.clearAndSetSemantics {})
                            }
                        }
                    }
                }
            }
        } }
    }
}

@Composable
private fun Pressable(onClick: () -> Unit, colors: Palette, modifier: Modifier = Modifier, enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null, description: String, state: String? = null, shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, FloatMotion, label = "press")
    val alpha by animateFloatAsState(if (enabled) 1f else .35f, tween(Motion, easing = Ease), label = "enabled")
    val focusFill by animateColorAsState(colors.accent.copy(alpha = if (focused) .20f else 0f),
        ColorMotion, label = "focus")
    Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
            onClick = onClick, onLongClick = onLongClick)
        .semantics(mergeDescendants = true) { contentDescription = description; state?.let { stateDescription = it } },
        contentAlignment = Alignment.Center) {
        content()
        Box(Modifier.matchParentSize().background(focusFill, shape))
    }
}

@Composable
private fun IconKey(icon: String, description: String, tag: String, colors: Palette, enabled: Boolean = true, onClick: () -> Unit) {
    Pressable(onClick = onClick, colors = colors, enabled = enabled, description = description, modifier = Modifier.size(48.dp).testTag(tag)) {
        Glyph(icon, colors.ink, Modifier.size(23.dp))
    }
}

@Composable
private fun Glyph(name: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.width / 24
        val path = Path()
        fun move(x: Float, y: Float) = path.moveTo(x * s, y * s)
        fun line(x: Float, y: Float) = path.lineTo(x * s, y * s)
        when (name) {
            "pause" -> { move(8f, 5f); line(8f, 19f); move(16f, 5f); line(16f, 19f) }
            "play" -> { move(8f, 4f); line(20f, 12f); line(8f, 20f); path.close() }
            "down" -> { move(5f, 9f); line(12f, 16f); line(19f, 9f) }
            "new" -> { path.addRoundRect(RoundRect(Rect(3*s,3*s,21*s,21*s), CornerRadius(4*s))); move(12f,7f); line(12f,17f); move(7f,12f); line(17f,12f) }
            "restart" -> {
                drawArc(color, -55f, 300f, false, Offset(3*s,3*s), Size(18*s,18*s), style = Stroke(1.8f*s, cap = StrokeCap.Round))
                move(21f,3f); line(21f,9f); line(15f,9f)
            }
            "undo" -> { move(9f,5f); line(3f,11f); line(9f,17f); move(3f,11f); path.cubicTo(13*s,7*s,20*s,11*s,21*s,18*s) }
            "hint" -> {
                move(8f,16f); path.cubicTo(8*s,13*s,4*s,12*s,5*s,7*s); path.cubicTo(6*s,0f,18*s,0f,19*s,7*s)
                path.cubicTo(20*s,12*s,16*s,13*s,16*s,16*s); path.close(); move(9f,20f); line(15f,20f); move(10f,23f); line(14f,23f)
            }
            "settings" -> {
                for (i in 0..2) {
                    val y = (5 + i * 7).toFloat(); val x = if (i == 1) 15f else 8f
                    move(3f,y); line(x-3,y); move(x+3,y); line(21f,y)
                    path.addOval(Rect((x-3)*s,(y-3)*s,(x+3)*s,(y+3)*s))
                }
            }
        }
        drawPath(path, color, style = Stroke(1.8f*s, cap = StrokeCap.Round))
    }
}

@Composable
internal fun GameMenu(menu: Menu?, light: Boolean, showErrors: Boolean, time: String, onDismiss: () -> Unit,
    onLight: (Boolean) -> Unit, onErrors: (Boolean) -> Unit, onLevel: (Int) -> Unit, onConfirm: () -> Unit) {
    var lastMenu by remember { mutableStateOf(menu) }
    SideEffect { if (menu != null) lastMenu = menu }
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = menu != null
    if (!visible.currentState && !visible.targetState) return
    val display = menu ?: lastMenu ?: return
    val colors = palette(light)
    MaterialTheme(colorScheme = if (light) lightColorScheme(primary = colors.accent, surface = colors.surface, onSurface = colors.ink)
        else darkColorScheme(primary = colors.accent, surface = colors.surface, onSurface = colors.ink)) {
        Dialog(onDismissRequest = onDismiss) {
          AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(Motion, easing = Ease)) + scaleIn(tween(Motion, easing = Ease), initialScale = .93f),
            exit = fadeOut(tween(160, easing = Ease)) + scaleOut(tween(160, easing = Ease), targetScale = .95f)) {
            Surface(shape = RoundedCornerShape(28.dp), color = colors.surface,
                modifier = Modifier.fillMaxWidth().testTag("dialog")) {
                AnimatedContent(display, transitionSpec = {
                    (fadeIn(tween(Motion, easing = Ease)) + slideInVertically(tween(Motion, easing = Ease)) { it / 12 }) togetherWith fadeOut(tween(100, easing = Ease))
                }, label = "dialog-content") { current ->
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(when (current) {
                            Menu.Settings -> "设置"; Menu.Difficulty -> "选择难度"; Menu.NewGame -> "开始新游戏？"
                            Menu.Restart -> "重新开始？"; Menu.Complete -> "已完成"
                        }, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                        when (current) {
                            Menu.Settings -> {
                                SettingSwitch("错误提醒", "errors", showErrors, colors, onErrors)
                                SettingSwitch("浅色主题", "theme", light, colors, onLight)
                            }
                            Menu.Difficulty -> Sudoku.levels.forEachIndexed { index, title ->
                                Pressable(onClick = { onLevel(index) }, colors = colors, description = title,
                                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("level-$index"), shape = RoundedCornerShape(12.dp)) {
                                    Text(if (index == 3) "Extreme · 极难" else title, color = colors.ink, fontSize = 18.sp)
                                }
                            }
                            Menu.NewGame -> Text("当前对局将被替换。", color = colors.ink, fontSize = 16.sp)
                            Menu.Restart -> Text("清除本题填写、草稿和计时？", color = colors.ink, fontSize = 16.sp)
                            Menu.Complete -> Text(time, color = colors.accent, fontSize = 28.sp, fontFamily = FontFamily.Monospace)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss, modifier = Modifier.testTag("dialog-close")) {
                                Text(if (current == Menu.Settings) "完成" else "返回", fontSize = 16.sp)
                            }
                            if (current == Menu.NewGame || current == Menu.Restart || current == Menu.Complete) {
                                TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm")) {
                                    Text(if (current == Menu.Complete) "新游戏" else "确定", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, tag: String, checked: Boolean, colors: Palette, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag).toggleable(value = checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 17.sp, color = colors.ink)
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedThumbColor = colors.background, checkedTrackColor = colors.accent, checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.muted, uncheckedTrackColor = colors.surface, uncheckedBorderColor = colors.line))
    }
}
