package com.sudoku.quiet

import java.util.Random

object Sudoku {
    val levels = listOf("入门", "简单", "困难", "Extreme")
    // shortcut: hard/extreme still use one structural seed each; easier tiers carve fresh clue layouts.
    internal val seeds = listOf(
        "100006089456089123000103450200074895070910064600538207007205048500097001060041072",
        "100006080456080103000003400200074095070010060600538007007200000500090001060001000",
        "023000780000709100080100000001004000070000060600008200010005008500097031900040000",
        "100006080450080103000000000200070090070010060000538007007200000500090001060001000"
    )

    fun parse(text: String): IntArray {
        require(text.length == 81 && text.all { it in '0'..'9' }) { "Invalid board" }
        return IntArray(81) { text[it] - '0' }
    }

    fun peers(a: Int, b: Int) = a != b && (a / 9 == b / 9 || a % 9 == b % 9 ||
        (a / 27 == b / 27 && a % 9 / 3 == b % 9 / 3))

    fun candidates(board: IntArray, index: Int): Int {
        var mask = 0x3fe
        for (j in 0..80) if (peers(index, j)) mask = mask and (1 shl board[j]).inv()
        return mask
    }

    fun valid(board: IntArray) = board.size == 81 && board.all { it in 0..9 } &&
        board.indices.all { board[it] == 0 || candidates(board, it) and (1 shl board[it]) != 0 }

    fun countSolutions(board: IntArray) = if (valid(board)) search(board.clone(), null, 2) else 0

    fun solve(board: IntArray): IntArray? {
        if (!valid(board)) return null
        val result = IntArray(81)
        return if (search(board.clone(), result, 1) == 0) null else result
    }

    private fun search(board: IntArray, solution: IntArray?, limit: Int): Int {
        var index = -1
        var mask = 0
        var best = 10
        for (i in 0..80) if (board[i] == 0) {
            val next = candidates(board, i)
            val count = Integer.bitCount(next)
            if (count == 0) return 0
            if (count < best) { best = count; mask = next; index = i }
            if (count == 1) break
        }
        if (index == -1) { solution?.let { board.copyInto(it) }; return 1 }
        var total = 0
        for (n in 1..9) if (mask and (1 shl n) != 0) {
            board[index] = n
            total += search(board, solution, limit - total)
            if (total >= limit) break
        }
        board[index] = 0
        return total
    }

    fun puzzle(level: Int, random: Random): IntArray {
        require(level in levels.indices)
        val seed = parse(seeds[level])
        val source = if (level < 2) requireNotNull(solve(seed)) else seed
        val digits = (1..9).toMutableList().also { java.util.Collections.shuffle(it, random) }
        val rows = order(random)
        val columns = order(random)
        val transpose = random.nextBoolean()
        val puzzle = IntArray(81) { i ->
            val r = i / 9; val c = i % 9
            val value = source[if (transpose) columns[c] * 9 + rows[r] else rows[r] * 9 + columns[c]]
            if (value == 0) 0 else digits[value - 1]
        }
        if (level >= 2) return puzzle
        val target = (if (level == 0) 60 else 44) + random.nextInt(5)
        val cells = (0..80).toMutableList().also { java.util.Collections.shuffle(it, random) }
        var clues = 81
        for (cell in cells) {
            val value = puzzle[cell]
            puzzle[cell] = 0
            if (solveVisually(puzzle, allowBoxScan = level == 1)) clues-- else puzzle[cell] = value
            if (clues == target) break
        }
        return puzzle
    }

    private fun order(random: Random): List<Int> = (0..2).toMutableList()
        .also { java.util.Collections.shuffle(it, random) }.flatMap { group ->
            (0..2).map { group * 3 + it }.toMutableList().also { java.util.Collections.shuffle(it, random) }
        }

    private fun solveVisually(input: IntArray, allowBoxScan: Boolean): Boolean {
        val board = input.clone()
        while (board.any { it == 0 }) {
            var index = -1
            var digit = 0
            for (u in 0..26) {
                var empty = -1
                var count = 0
                var missing = 0x3fe
                for (j in 0..8) {
                    val cell = unit(u, j)
                    if (board[cell] == 0) { empty = cell; count++ }
                    else missing = missing and (1 shl board[cell]).inv()
                }
                if (count == 1) { index = empty; digit = Integer.numberOfTrailingZeros(missing); break }
            }
            if (index < 0 && allowBoxScan) {
                // Recompute only from placed digits: no pencil marks or remembered eliminations.
                val masks = IntArray(81) { if (board[it] == 0) candidates(board, it) else 0 }
                scan@ for (u in 18..26) for (n in 1..9) {
                    var target = -1
                    var count = 0
                    for (j in 0..8) {
                        val cell = unit(u, j)
                        if (masks[cell] and (1 shl n) != 0) { target = cell; count++ }
                    }
                    if (count == 1) { index = target; digit = n; break@scan }
                }
            }
            if (index < 0) return false
            board[index] = digit
        }
        return true
    }

    /** 0: single-gap units; 1: box scans; 2: candidate reasoning/pairs; 3: beyond these. */
    fun rate(input: IntArray): Int {
        if (!valid(input)) return 3
        if (solveVisually(input, allowBoxScan = false)) return 0
        if (solveVisually(input, allowBoxScan = true)) return 1
        val board = input.clone()
        val masks = IntArray(81) { if (board[it] == 0) candidates(board, it) else 0 }
        while (true) {
            var progress = false
            var complete = true
            for (i in 0..80) if (board[i] == 0) {
                complete = false
                if (masks[i] == 0) return 3
                if (Integer.bitCount(masks[i]) == 1) {
                    place(board, masks, i, Integer.numberOfTrailingZeros(masks[i])); progress = true
                }
            }
            if (complete) return 2
            if (progress) continue
            hidden@ for (u in 0..26) for (n in 1..9) {
                val positions = (0..8).map { unit(u, it) }.filter { masks[it] and (1 shl n) != 0 }
                if (positions.size == 1) {
                    place(board, masks, positions[0], n)
                    progress = true; break@hidden
                }
            }
            if (progress) continue
            for (u in 0..26) {
                for (j in 0..8) {
                    val mask = masks[unit(u, j)]
                    if (Integer.bitCount(mask) != 2) continue
                    for (k in j + 1..8) if (masks[unit(u, k)] == mask) {
                        for (x in 0..8) if (x != j && x != k) {
                            val target = unit(u, x)
                            val before = masks[target]
                            masks[target] = before and mask.inv()
                            progress = progress || before != masks[target]
                        }
                    }
                }
                for (n in 1..9) {
                    val positions = (0..8).map { unit(u, it) }.filter { masks[it] and (1 shl n) != 0 }
                    if (positions.size < 2) continue
                    for (target in 0..80) if (masks[target] and (1 shl n) != 0 && positions.all { peers(target, it) }) {
                        masks[target] = masks[target] and (1 shl n).inv(); progress = true
                    }
                }
            }
            if (!progress) return 3
        }
    }

    private fun unit(u: Int, j: Int): Int = when {
        u < 9 -> u * 9 + j
        u < 18 -> j * 9 + u - 9
        else -> ((u - 18) / 3 * 3 + j / 3) * 9 + (u - 18) % 3 * 3 + j % 3
    }

    private fun place(board: IntArray, masks: IntArray, index: Int, n: Int) {
        board[index] = n; masks[index] = 0
        for (j in 0..80) if (peers(index, j)) masks[j] = masks[j] and (1 shl n).inv()
    }
}
