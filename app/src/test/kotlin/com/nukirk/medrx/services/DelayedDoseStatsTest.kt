package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import com.nukirk.medrx.elements.MainActivity.Tabs.DayStatus
import com.nukirk.medrx.elements.MainActivity.Tabs.getMedicationStatsForMonth
import com.nukirk.medrx.elements.MainActivity.Tabs.getSkipCountForDate
import com.nukirk.medrx.elements.MainActivity.Tabs.getStatusForDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * A dose postponed by the snooze limit is resolved (it stops reminding) but it
 * was neither taken nor refused, so it must be its own bucket: it may not credit
 * adherence, may not extend a streak, and must not be reported as a skip.
 */
class DelayedDoseStatsTest {

    private val jan2: LocalDate = LocalDate.of(2026, 1, 2)
    private val jan3: LocalDate = LocalDate.of(2026, 1, 3)

    private fun med(
        end: LocalDate? = LocalDate.of(2026, 1, 10),
        title: String = "Sertraline"
    ): MedData = MedData(
        id = 1L,
        groupId = 1L,
        type = ItemType.Medicine,
        title = title,
        creationDate = LocalDate.of(2026, 1, 1),
        creationTime = LocalTime.NOON,
        endDate = end
    )

    private fun record(reason: SkipReason) = SkipRecord(reason = reason, time = LocalTime.NOON)

    // --- month bucketing ---------------------------------------------------

    @Test
    fun `a postponed dose is its own bucket and never credits adherence`() {
        val item = med().copy(
            takenHistory = hashMapOf(LocalDate.of(2026, 1, 1) to LocalTime.NOON),
            skipHistory = hashMapOf(
                jan2 to record(SkipReason.DELAYED),
                jan3 to record(SkipReason.SNOOZED_AWAY)
            )
        )

        val stats = getMedicationStatsForMonth(YearMonth.of(2026, 1), listOf(item)).single()

        assertEquals(10, stats.scheduled)
        assertEquals(1, stats.taken)
        // The snooze-limit skip still counts as a skip; only the delay does not.
        assertEquals(1, stats.skipped)
        assertEquals(1, stats.delayed)
        assertEquals(7, stats.missed)
        assertEquals(20, stats.adherence)
    }

    @Test
    fun `a snooze-limit skip keeps crediting adherence`() {
        val item = med().copy(
            skipHistory = hashMapOf(jan2 to record(SkipReason.SNOOZED_AWAY))
        )

        val stats = getMedicationStatsForMonth(YearMonth.of(2026, 1), listOf(item)).single()

        assertEquals(1, stats.skipped)
        assertEquals(0, stats.delayed)
        assertEquals(9, stats.missed)
        assertEquals(10, stats.adherence)
    }

    // --- day status --------------------------------------------------------

    @Test
    fun `a postponed day has its own status and no skip dot`() {
        val delayed = med().copy(skipHistory = hashMapOf(jan2 to record(SkipReason.DELAYED)))
        assertEquals(DayStatus.DELAYED, getStatusForDate(jan2, listOf(delayed)))
        assertEquals(0, getSkipCountForDate(jan2, listOf(delayed)))

        // An untouched dose is still a miss, never a postponement.
        assertEquals(DayStatus.NONE_TAKEN, getStatusForDate(jan2, listOf(med())))

        // A real skip still resolves the day, exactly as before.
        val skipped = med().copy(skipHistory = hashMapOf(jan2 to record(SkipReason.OTHER)))
        assertEquals(DayStatus.SKIPPED, getStatusForDate(jan2, listOf(skipped)))
        assertEquals(1, getSkipCountForDate(jan2, listOf(skipped)))
    }

    @Test
    fun `a postponed dose never masks a genuinely missed one`() {
        val taken = med(title = "Sertraline").copy(takenHistory = hashMapOf(jan2 to LocalTime.NOON))
        val delayed =
            med(title = "Escitalopram").copy(skipHistory = hashMapOf(jan2 to record(SkipReason.DELAYED)))
        val missed = med(title = "Bupropion")

        // Taken next to postponed, nothing missed: the day reads as postponed.
        assertEquals(DayStatus.DELAYED, getStatusForDate(jan2, listOf(taken, delayed)))

        // One dose left genuinely unaccounted for: only partially resolved.
        assertEquals(DayStatus.PARTIAL, getStatusForDate(jan2, listOf(taken, delayed, missed)))
    }

    @Test
    fun `a skip next to a postponed dose still reads as postponed`() {
        val skipped =
            med(title = "Sertraline").copy(skipHistory = hashMapOf(jan2 to record(SkipReason.OTHER)))
        val delayed =
            med(title = "Escitalopram").copy(skipHistory = hashMapOf(jan2 to record(SkipReason.DELAYED)))

        assertEquals(DayStatus.DELAYED, getStatusForDate(jan2, listOf(skipped, delayed)))
    }

    // --- helpers -----------------------------------------------------------

    @Test
    fun `delayed and skipped are mutually exclusive`() {
        val delayed = med().copy(skipHistory = hashMapOf(jan2 to record(SkipReason.DELAYED)))
        assertTrue(delayed.isDelayed(jan2))
        assertFalse(delayed.isSkipped(jan2))

        val skipped = med().copy(skipHistory = hashMapOf(jan2 to record(SkipReason.OTHER)))
        assertTrue(skipped.isSkipped(jan2))
        assertFalse(skipped.isDelayed(jan2))

        val untouched = med()
        assertFalse(untouched.isDelayed(jan2))
        assertFalse(untouched.isSkipped(jan2))
    }

    @Test
    fun `taken doses are neither delayed nor skipped`() {
        val taken = med().copy(
            takenHistory = hashMapOf(jan2 to LocalTime.NOON),
            skipHistory = hashMapOf(jan3 to record(SkipReason.DELAYED))
        )
        assertFalse(taken.isDelayed(jan2))
        assertFalse(taken.isSkipped(jan2))
        assertTrue(taken.isDelayed(jan3))
    }
}
