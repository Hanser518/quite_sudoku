package com.sudoku.quiet

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Random

@ConsistentCopyVisibility
data class Game internal constructor(
    val level: Int,
    val givens: List<Int>,
    val solution: List<Int>,
    val board: List<Int> = givens,
    val notes: List<Int> = List(81) { 0 },
    val selected: Int = -1,
    val armedDigit: Int = 0,
    val draft: Boolean = false,
    val elapsedMs: Long = 0,
    private val history: List<Step> = emptyList(),
    val inspected: Int = -1
) {
    internal data class Step(val board: List<Int>, val notes: List<Int>)
    val complete: Boolean get() = board == solution
    val canUndo: Boolean get() = history.isNotEmpty()
    val viewedDigit: Int get() = board.getOrNull(inspected) ?: armedDigit
    private val inputCell: Int get() = if (selected >= 0) selected else
        inspected.takeIf { it >= 0 && givens[it] == 0 } ?: -1

    fun select(index: Int): Game {
        if (index !in 0..80) return this
        if (selected == index || inspected == index) return copy(selected = -1, inspected = -1)
        if (board[index] != 0) return copy(selected = -1, inspected = index)
        val next = copy(selected = index, inspected = -1)
        return if (armedDigit != 0) next.enter(index, armedDigit) else next
    }

    fun input(digit: Int): Game = when {
        digit !in 0..9 || complete -> this
        digit == 0 -> copy(armedDigit = 0, selected = inputCell, inspected = -1).enter(inputCell, 0)
        armedDigit != 0 -> lock(digit)
        inputCell >= 0 -> copy(selected = inputCell, inspected = -1).enter(inputCell, digit)
        else -> lock(digit)
    }

    fun lock(digit: Int): Game = if (digit !in 1..9 || complete) this
        else copy(armedDigit = if (armedDigit == digit) 0 else digit, selected = -1, inspected = -1)

    fun enter(index: Int, digit: Int): Game {
        if (index !in 0..80 || digit !in 0..9 || givens[index] != 0 || complete) return this
        if (draft && digit > 0 && board[index] != 0) return this
        if (!draft && board[index] == digit && notes[index] == 0) return this
        if (digit == 0 && board[index] == 0 && notes[index] == 0) return this
        val nextBoard = board.toMutableList()
        val nextNotes = notes.toMutableList()
        if (draft && digit > 0) nextNotes[index] = nextNotes[index] xor (1 shl digit)
        else {
            nextBoard[index] = digit; nextNotes[index] = 0
            if (digit > 0) for (i in 0..80) if (Sudoku.peers(index, i)) nextNotes[i] = nextNotes[i] and (1 shl digit).inv()
        }
        return copy(board = nextBoard, notes = nextNotes, history = history.takeLast(99) + Step(board, notes))
    }

    fun hint(): Game {
        if (complete) return this
        val target = when {
            inputCell >= 0 && board[inputCell] != solution[inputCell] -> inputCell
            board.indices.any { board[it] != 0 && board[it] != solution[it] } ->
                board.indices.first { board[it] != 0 && board[it] != solution[it] }
            else -> {
                val values = board.toIntArray()
                board.indices.filter { board[it] == 0 }.minBy { Integer.bitCount(Sudoku.candidates(values, it)) }
            }
        }
        return copy(draft = false).enter(target, solution[target]).copy(draft = draft, selected = target, inspected = -1)
    }

    fun undo(): Game = history.lastOrNull()?.let {
        copy(board = it.board, notes = it.notes, history = history.dropLast(1), inspected = -1)
    } ?: this

    fun restart() = copy(board = givens, notes = List(81) { 0 }, selected = -1, armedDigit = 0, elapsedMs = 0, history = emptyList(), inspected = -1)

    fun isError(index: Int, enabled: Boolean) = enabled && index in 0..80 && givens[index] == 0 &&
        board[index] != 0 && board[index] != solution[index]

    // v2 adds the viewed cell; the reader still accepts all v1 games and undo history.
    fun save(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(2); out.writeInt(level); out.writeInt(selected); out.writeInt(armedDigit)
            out.writeBoolean(draft); out.writeLong(elapsedMs)
            writeArray(out, givens); writeArray(out, board); writeArray(out, notes)
            out.writeInt(history.size)
            for (step in history) { writeArray(out, step.board); writeArray(out, step.notes) }
            out.writeInt(inspected)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    companion object {
        fun new(level: Int, random: Random = Random()): Game {
            val givens = Sudoku.puzzle(level, random)
            return Game(level, givens.toList(), requireNotNull(Sudoku.solve(givens)).toList())
        }

        fun restore(saved: String): Game = try {
            require(saved.length <= 100000) { "Invalid save size" }
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(saved))).use { input ->
                val version = input.readInt()
                require(version in 1..2) { "Unknown save version" }
                val level = input.readInt(); val selected = input.readInt(); val digit = input.readInt()
                val draft = input.readBoolean(); val elapsed = input.readLong()
                require(level in 0..3 && selected in -1..80 && digit in 0..9 && elapsed >= 0) { "Invalid state" }
                val givens = readArray(input)
                require(Sudoku.countSolutions(givens.toIntArray()) == 1) { "Invalid puzzle" }
                val board = readArray(input); val notes = readArray(input)
                validate(givens, board, notes)
                val count = input.readInt()
                require(count in 0..100) { "Invalid undo history" }
                val history = List(count) {
                    val values = readArray(input); val pencil = readArray(input)
                    validate(givens, values, pencil); Step(values, pencil)
                }
                val inspected = if (version == 2) input.readInt() else -1
                require(inspected in -1..80 && (inspected == -1 || (board[inspected] != 0 && selected == -1))) { "Invalid viewed cell" }
                require(input.available() == 0) { "Trailing save data" }
                Game(level, givens, requireNotNull(Sudoku.solve(givens.toIntArray())).toList(), board, notes,
                    selected.takeIf { it >= 0 && givens[it] == 0 } ?: -1, digit, draft, elapsed, history, inspected)
            }
        } catch (e: IOException) { throw IllegalArgumentException("无法读取存档", e) }

        private fun validate(givens: List<Int>, board: List<Int>, notes: List<Int>) {
            require(board.indices.all {
                board[it] in 0..9 && (givens[it] == 0 || board[it] == givens[it]) &&
                    notes[it] and 0x3fe.inv() == 0 && (board[it] == 0 || notes[it] == 0)
            }) { "Invalid cell" }
        }
        private fun writeArray(out: DataOutputStream, values: List<Int>) = values.forEach(out::writeShort)
        private fun readArray(input: DataInputStream) = List(81) { input.readUnsignedShort() }
    }
}
