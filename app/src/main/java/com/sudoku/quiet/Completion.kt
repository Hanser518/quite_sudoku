package com.sudoku.quiet

internal data class Completion(val level: Int, val completedAt: Long, val elapsedMs: Long) {
    init { require(level in 0..3 && completedAt >= 0 && elapsedMs >= 0) }
}

internal object CompletionHistory {
    fun retain(records: List<Completion>): List<Completion> = records
        .groupBy { it.level }.values.flatMap { group -> group.sortedByDescending { it.completedAt }.take(20) }
        .sortedByDescending { it.completedAt }

    fun save(records: List<Completion>): String = "1\n" + retain(records).joinToString("\n") {
        "${it.level},${it.completedAt},${it.elapsedMs}"
    }

    fun restore(saved: String?): List<Completion> {
        if (saved == null) return emptyList()
        require(saved.length <= 16384) { "Invalid completion history size" }
        val lines = saved.lines()
        require(lines.first() == "1") { "Unknown completion history version" }
        return retain(lines.drop(1).filter { it.isNotEmpty() }.map { line ->
            val fields = line.split(',')
            require(fields.size == 3) { "Invalid completion record" }
            Completion(fields[0].toInt(), fields[1].toLong(), fields[2].toLong())
        })
    }
}
