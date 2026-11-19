package com.sudoku.quiet

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

internal enum class Menu { Settings, Difficulty, NewGame, Restart, Complete, History }

class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("sudoku", MODE_PRIVATE) }
    private var game by mutableStateOf<Game?>(null)
    private var elapsed by mutableLongStateOf(0)
    private var paused by mutableStateOf(false)
    private var light by mutableStateOf(false)
    private var showErrors by mutableStateOf(true)
    private var menu by mutableStateOf<Menu?>(null)
    private var completions by mutableStateOf<List<Completion>>(emptyList())
    private var completionRecorded = false
    private var nextLevel = 0
    private var resumed = false
    private var lastTick = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { accountTime(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = preferences.getString("game", null)
        val restored = saved?.let {
            try { Game.restore(it) }
            catch (_: IllegalArgumentException) {
                Toast.makeText(this, "存档无法读取", Toast.LENGTH_LONG).show(); null
            }
        }
        game = restored ?: Game.new(0)
        elapsed = requireNotNull(game).elapsedMs
        completions = try { CompletionHistory.restore(preferences.getString("completions", null)) }
        catch (_: IllegalArgumentException) {
            Toast.makeText(this, "通关记录无法读取", Toast.LENGTH_LONG).show(); emptyList()
        }
        // A legacy completed save has no completion date; do not invent one on upgrade.
        completionRecorded = restored != null && preferences.getBoolean("completionRecorded", restored.complete)
        light = preferences.getBoolean("light", false)
        showErrors = preferences.getBoolean("showErrors", true)
        paused = savedInstanceState?.getBoolean("paused") ?: false
        menu = savedInstanceState?.getString("menu")?.let { value -> Menu.entries.find { it.name == value } }
            ?: if (saved == null) Menu.Difficulty else null
        nextLevel = savedInstanceState?.getInt("nextLevel") ?: 0
        updateBars()
        persist()
        setContent {
            val current = game ?: return@setContent
            BackHandler(enabled = !paused && menu == null) { togglePause() }
            SudokuScreen(
                game = current, light = light, showErrors = showErrors, paused = paused,
                time = { timeText(elapsed) }, onEdit = ::edit, onPause = ::togglePause,
                onSettings = { open(Menu.Settings) }, onDifficulty = { open(Menu.Difficulty) },
                onRestart = { open(Menu.Restart) }
            )
            GameMenu(
                menu = menu, light = light, showErrors = showErrors, time = timeText(elapsed),
                completions = completions, level = current.level, onHistory = { open(Menu.History) },
                onDismiss = { menu = null; lastTick = SystemClock.elapsedRealtime() },
                onLight = {
                    light = it; preferences.edit().putBoolean("light", it).apply(); updateBars()
                },
                onErrors = { showErrors = it; preferences.edit().putBoolean("showErrors", it).apply() },
                onLevel = { level ->
                    nextLevel = level
                    if (current.canUndo && !current.complete) menu = Menu.NewGame else startGame(level)
                },
                onConfirm = {
                    when (menu) {
                        Menu.NewGame -> startGame(nextLevel)
                        Menu.Restart -> {
                            game = current.restart(); elapsed = 0; paused = false; menu = null
                            completionRecorded = false
                            lastTick = SystemClock.elapsedRealtime(); persist()
                        }
                        Menu.Complete -> menu = Menu.Difficulty
                        else -> Unit
                    }
                }
            )
        }
    }

    private fun edit(change: (Game) -> Game) {
        if (paused) return
        accountTime()
        val before = requireNotNull(game)
        val next = change(before.copy(elapsedMs = elapsed))
        game = next; elapsed = next.elapsedMs
        if (!before.complete && next.complete && !completionRecorded) {
            completions = CompletionHistory.retain(completions + Completion(next.level, System.currentTimeMillis(), elapsed))
            completionRecorded = true
        }
        persist()
        if (!before.complete && next.complete) open(Menu.Complete)
    }

    private fun open(value: Menu) { accountTime(); menu = value }
    private fun startGame(level: Int) {
        game = Game.new(level); elapsed = 0; paused = false; menu = null
        completionRecorded = false
        lastTick = SystemClock.elapsedRealtime(); persist()
    }
    private fun togglePause() {
        accountTime(); paused = !paused; lastTick = SystemClock.elapsedRealtime(); persist()
    }
    private fun accountTime() {
        val now = SystemClock.elapsedRealtime()
        if (resumed && !paused && menu == null && game?.complete == false && lastTick != 0L) elapsed += now - lastTick
        lastTick = now
    }
    private fun persist() { game?.let {
        preferences.edit().putString("game", it.copy(elapsedMs = elapsed).save())
            .putString("completions", CompletionHistory.save(completions))
            .putBoolean("completionRecorded", completionRecorded).apply()
    } }
    private fun updateBars() {
        val style = if (light) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onResume() {
        super.onResume(); resumed = true; lastTick = SystemClock.elapsedRealtime(); handler.post(tick)
    }
    override fun onPause() {
        accountTime(); resumed = false; handler.removeCallbacks(tick); persist(); super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("paused", paused); outState.putString("menu", menu?.name); outState.putInt("nextLevel", nextLevel)
        super.onSaveInstanceState(outState)
    }
}

internal fun timeText(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
