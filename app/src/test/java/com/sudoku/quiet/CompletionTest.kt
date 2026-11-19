package com.sudoku.quiet

import org.junit.Assert.*
import org.junit.Test

class CompletionTest {
    @Test fun keepsNewestTwentyIndependentlyOfDifficultyAndDuration() {
        val records = (0..3).flatMap { level ->
            (1L..25L).map { time -> Completion(level, time, 26000 - time * 1000) }
        }.reversed()
        val retained = CompletionHistory.restore(CompletionHistory.save(records))
        assertEquals(80, retained.size)
        for (level in 0..3) {
            assertEquals((25L downTo 6L).toList(), retained.filter { it.level == level }.map { it.completedAt })
        }
        val fewer = records.filter { it.level == 0 }.take(2) + records.filter { it.level == 1 }
        assertEquals(22, CompletionHistory.retain(fewer).size)
    }

    @Test fun emptyAndInvalidSavesAreHandled() {
        assertEquals(emptyList<Completion>(), CompletionHistory.restore(null))
        assertEquals(emptyList<Completion>(), CompletionHistory.restore(CompletionHistory.save(emptyList())))
        for (bad in listOf("2\n", "1\n4,1,1", "1\n0,-1,1", "1\n0,1,-1", "1\n0,1", "1\na,1,1", "1".repeat(16385))) {
            assertThrows(IllegalArgumentException::class.java) { CompletionHistory.restore(bad) }
        }
    }
}
