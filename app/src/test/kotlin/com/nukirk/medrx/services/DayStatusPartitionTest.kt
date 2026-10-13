package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import com.nukirk.medrx.elements.MainActivity.Tabs.DayStatus
import com.nukirk.medrx.elements.MainActivity.Tabs.getSkipCountForDate
import com.nukirk.medrx.elements.MainActivity.Tabs.getStatusForDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * `getStatusForDate` collapses a whole day of doses into a single state, so the
 * resolved states have to form a *partition* of every possible mix of taken,
 * skipped (deliberately refused) and postponed doses:
 *
 *  - every combination resolves to at least one state (the function is total),
 *  - no combination resolves to two (the states do not overlap),
 *  - a day with no scheduled doses, or a day in the future, sits outside the
 *    partition entirely.
 *
 * The matrix below enumerates all 4^n combinations of the four per-dose
 * outcomes for n = 1..4 and checks the partition both against an independently
 * written predicate set and against closed-form bucket sizes.
 */
class DayStatusPartitionTest {

    private val day: LocalDate = LocalDate.of(2026, 1, 5)
    private val scheduleStart: LocalDate = LocalDate.of(2026, 1, 1)
    private val scheduleEnd: LocalDate = LocalDate.of(2026, 1, 10)

    /** What a single scheduled dose did on [day]. */
    private enum class Dose { TAKEN, SKIPPED, DELAYED, UNTOUCHED }

    private val resolvedStates = setOf(
        DayStatus.ALL_TAKEN,
        DayStatus.PARTIAL,
        DayStatus.NONE_TAKEN,
        DayStatus.SKIPPED,
        DayStatus.DELAYED
    )

    private fun doseFor(action: Dose, index: Int): MedData {
        // Distinct titles keep each dose its own medicationInstanceKey, so the
        // day really does have several scheduled slots instead of one deduped.
        val item = MedData(
            id = 1L + index,
            groupId = 1L + index,
            type = ItemType.Medicine,
            title = "Dose $index",
            creationDate = scheduleStart,
            creationTime = LocalTime.NOON,
            endDate = scheduleEnd
        )
        return when (action) {
            Dose.TAKEN -> item.copy(takenHistory = hashMapOf(day to LocalTime.NOON))
            Dose.SKIPPED ->
                item.copy(skipHistory = hashMapOf(day to SkipRecord(SkipReason.OTHER, LocalTime.NOON)))
            Dose.DELAYED ->
                item.copy(skipHistory = hashMapOf(day to SkipRecord(SkipReason.DELAYED, LocalTime.NOON)))
            Dose.UNTOUCHED -> item
        }
    }

    /** Every combination of [count] doses, one outcome each. */
    private fun combinations(count: Int): List<List<Dose>> {
        var acc: List<List<Dose>> = listOf(emptyList())
        repeat(count) { acc = acc.flatMap { prefix -> Dose.entries.map { prefix + it } } }
        return acc
    }

    private fun medsFor(combo: List<Dose>): List<MedData> =
        combo.mapIndexed { index, action -> doseFor(action, index) }

    private fun statusOf(combo: List<Dose>): DayStatus = getStatusForDate(day, medsFor(combo))

    /** The five resolved states as predicates over the raw tallies. */
    private fun matchingStates(combo: List<Dose>): Set<DayStatus> {
        val scheduled = combo.size
        val taken = combo.count { it == Dose.TAKEN }
        val skipped = combo.count { it == Dose.SKIPPED }
        val delayed = combo.count { it == Dose.DELAYED }
        val accounted = taken + skipped + delayed
        return buildSet {
            if (accounted == 0) add(DayStatus.NONE_TAKEN)
            if (taken == scheduled) add(DayStatus.ALL_TAKEN)
            if (accounted in 1 until scheduled) add(DayStatus.PARTIAL)
            if (accounted == scheduled && taken < scheduled && delayed == 0) add(DayStatus.SKIPPED)
            if (accounted == scheduled && taken < scheduled && delayed > 0) add(DayStatus.DELAYED)
        }
    }

    private fun pow(base: Int, exp: Int): Int =
        generateSequence(1) { it * base }.elementAt(exp)

    // --- the matrix --------------------------------------------------------

    @Test
    fun `every combination hits exactly one resolved state`() {
        val seen = mutableSetOf<DayStatus>()

        for (doseCount in 1..4) {
            val combos = combinations(doseCount)
            assertEquals(pow(4, doseCount), combos.size)

            for (combo in combos) {
                // Independent predicate set: the states must not overlap here,
                // otherwise the single value getStatusForDate returns is
                // arbitrary and the day's colour is undefined.
                assertEquals(
                    "$combo must satisfy exactly one state predicate",
                    1,
                    matchingStates(combo).size
                )

                val status = statusOf(combo)
                assertEquals("$combo", matchingStates(combo).single(), status)
                seen += status
            }
        }

        // All five resolved states are reachable; NO_MEDS and FUTURE never leak
        // into a day that has scheduled doses.
        assertEquals(resolvedStates, seen)
    }

    @Test
    fun `the resolved states partition the combinations by closed-form counts`() {
        for (doseCount in 1..4) {
            val combos = combinations(doseCount)
            val byStatus = combos.groupBy { statusOf(it) }

            // Total: every combination lands somewhere.
            assertEquals(pow(4, doseCount), byStatus.values.sumOf { it.size })
            // Disjoint: nothing lands in an unresolved bucket.
            assertTrue(byStatus.keys.all { it in resolvedStates })

            val actual = resolvedStates.associateWith { byStatus[it]?.size ?: 0 }
            val n = doseCount
            val expected = mapOf(
                // Only two ways to touch every dose the same way.
                DayStatus.ALL_TAKEN to 1,
                DayStatus.NONE_TAKEN to 1,
                // Fully accounted, not all taken, via real skips only.
                DayStatus.SKIPPED to pow(2, n) - 1,
                // Fully accounted, not all taken, with at least one postponement.
                DayStatus.DELAYED to pow(3, n) - pow(2, n),
                // Some but not all of the day accounted for.
                DayStatus.PARTIAL to pow(4, n) - 1 - pow(3, n)
            )
            assertEquals(expected, actual)
        }
    }

    // --- named precedence rules the matrix encodes -------------------------

    @Test
    fun `taken doses win the day`() {
        assertEquals(DayStatus.ALL_TAKEN, statusOf(combo(Dose.TAKEN)))
        assertEquals(DayStatus.ALL_TAKEN, statusOf(combo(Dose.TAKEN, Dose.TAKEN)))
        // A real skip next to an untouched dose still leaves the dose unaccounted.
        assertEquals(DayStatus.PARTIAL, statusOf(combo(Dose.TAKEN, Dose.UNTOUCHED)))
    }

    @Test
    fun `a postponement outranks a skip but never a genuine miss`() {
        assertEquals(DayStatus.SKIPPED, statusOf(combo(Dose.SKIPPED, Dose.SKIPPED)))
        assertEquals(DayStatus.SKIPPED, statusOf(combo(Dose.TAKEN, Dose.SKIPPED)))
        assertEquals(DayStatus.DELAYED, statusOf(combo(Dose.DELAYED)))
        assertEquals(DayStatus.DELAYED, statusOf(combo(Dose.TAKEN, Dose.DELAYED)))
        assertEquals(DayStatus.DELAYED, statusOf(combo(Dose.SKIPPED, Dose.DELAYED)))

        // One dose with nothing recorded keeps the day only partially resolved.
        assertEquals(DayStatus.PARTIAL, statusOf(combo(Dose.TAKEN, Dose.DELAYED, Dose.UNTOUCHED)))
        assertEquals(DayStatus.PARTIAL, statusOf(combo(Dose.SKIPPED, Dose.DELAYED, Dose.UNTOUCHED)))
    }

    @Test
    fun `a wholly untouched day is unaccounted, not skipped`() {
        assertEquals(DayStatus.NONE_TAKEN, statusOf(combo(Dose.UNTOUCHED)))
        assertEquals(DayStatus.NONE_TAKEN, statusOf(combo(Dose.UNTOUCHED, Dose.UNTOUCHED)))
    }

    // --- boundary cases around the partition -------------------------------

    @Test
    fun `the skip count follows the real skips and never the postponements`() {
        for (doseCount in 1..4) {
            for (combo in combinations(doseCount)) {
                val realSkips = combo.count { it == Dose.SKIPPED }
                val dots = getSkipCountForDate(day, medsFor(combo))
                assertEquals("$combo", realSkips, dots)
                // A skip mark implies at least one real skip, and a day with no
                // real skip can never read as SKIPPED.
                if (dots == 0) assertTrue("$combo", statusOf(combo) != DayStatus.SKIPPED)
                if (statusOf(combo) == DayStatus.SKIPPED) assertTrue("$combo", dots > 0)
            }
        }
    }

    @Test
    fun `a day with no scheduled dose sits outside the partition`() {
        assertEquals(DayStatus.NO_MEDS, getStatusForDate(day, emptyList()))
        // A medicine whose schedule does not cover the day counts as no dose.
        val offSchedule = MedData(
            id = 1L,
            groupId = 1L,
            type = ItemType.Medicine,
            title = "Sertraline",
            creationDate = day.plusDays(1),
            creationTime = LocalTime.NOON,
            skipHistory = hashMapOf(day to SkipRecord(SkipReason.OTHER, LocalTime.NOON))
        )
        assertEquals(DayStatus.NO_MEDS, getStatusForDate(day, listOf(offSchedule)))
    }

    @Test
    fun `a future day stays future however it is recorded`() {
        val future = LocalDate.now().plusDays(3)
        val start = LocalDate.now().minusDays(10)
        val actions = listOf(Dose.TAKEN, Dose.SKIPPED, Dose.DELAYED)
        val meds = actions.mapIndexed { index, action ->
            val item = MedData(
                id = 1L + index,
                groupId = 1L + index,
                type = ItemType.Medicine,
                title = "Dose $index",
                creationDate = start,
                creationTime = LocalTime.NOON
            )
            when (action) {
                Dose.TAKEN -> item.copy(takenHistory = hashMapOf(future to LocalTime.NOON))
                Dose.SKIPPED ->
                    item.copy(skipHistory = hashMapOf(future to SkipRecord(SkipReason.OTHER, LocalTime.NOON)))
                Dose.DELAYED ->
                    item.copy(skipHistory = hashMapOf(future to SkipRecord(SkipReason.DELAYED, LocalTime.NOON)))
                Dose.UNTOUCHED -> item
            }
        }
        assertEquals(DayStatus.FUTURE, getStatusForDate(future, meds))
        assertEquals(DayStatus.FUTURE, getStatusForDate(future, meds + medsFor(listOf(Dose.UNTOUCHED))))
    }

    @Test
    fun `a dose recorded as both taken and skipped still resolves once, as taken`() {
        // Corrupt/legacy data can leave both maps populated for one slot. The
        // states must stay mutually exclusive, and taking outranks refusing.
        val contradictory = doseFor(Dose.TAKEN, 0).copy(
            skipHistory = hashMapOf(day to SkipRecord(SkipReason.OTHER, LocalTime.NOON))
        )
        assertEquals(DayStatus.ALL_TAKEN, getStatusForDate(day, listOf(contradictory)))

        val takenAndDelayed = doseFor(Dose.TAKEN, 0).copy(
            skipHistory = hashMapOf(day to SkipRecord(SkipReason.DELAYED, LocalTime.NOON))
        )
        assertEquals(DayStatus.ALL_TAKEN, getStatusForDate(day, listOf(takenAndDelayed)))
    }

    @Test
    fun `duplicate schedule fragments are one dose before the partition runs`() {
        // Two identical slots (same title, time and dose) dedupe to a single
        // scheduled dose, so one taken record makes the day fully compliant.
        val a = doseFor(Dose.TAKEN, 0)
        val b = doseFor(Dose.UNTOUCHED, 0)
        assertEquals(a.medicationInstanceKey(), b.medicationInstanceKey())
        assertEquals(DayStatus.ALL_TAKEN, getStatusForDate(day, listOf(a, b)))
    }

    private fun combo(vararg actions: Dose): List<Dose> = actions.toList()
}
