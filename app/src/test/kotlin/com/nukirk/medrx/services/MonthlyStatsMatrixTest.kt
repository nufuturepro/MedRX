package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import com.nukirk.medrx.elements.MainActivity.Tabs.DayStatus
import com.nukirk.medrx.elements.MainActivity.Tabs.computeMonthlySummary
import com.nukirk.medrx.elements.MainActivity.Tabs.getCombinedTreatmentStats
import com.nukirk.medrx.elements.MainActivity.Tabs.getMedicationStatsForMonth
import com.nukirk.medrx.elements.MainActivity.Tabs.getScheduledMedsForDate
import com.nukirk.medrx.elements.MainActivity.Tabs.getSkipCountForDate
import com.nukirk.medrx.elements.MainActivity.Tabs.getStatusForDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.Random

/**
 * The monthly rollup is described three ways that must agree:
 *
 *  - [getStatusForDate] collapses each day into one state,
 *  - [getMedicationStatsForMonth] buckets each medication's doses,
 *  - [computeMonthlySummary] turns the whole month into streak / adherence /
 *    missed-per-week / delayed.
 *
 * This matrix lays a month of multi-medication schedules out from an outcome
 * model (every dose independently taken, skipped, postponed or left untouched),
 * then checks all three views against an independently written reference of the
 * same model. It also pins the arithmetic identities that make the buckets
 * auditable: the buckets sum to `scheduled`, adherence credits taken + skipped
 * only, and the summary's totals equal the summed per-medication buckets for a
 * past month.
 */
class MonthlyStatsMatrixTest {

    /**
     * A fully elapsed month: every day is in the past, so the special "today"
     * gating in [computeMonthlySummary] never perturbs the totals. Relative to
     * `now()` rather than hard-coded so the matrix can never fall into the
     * future of the machine it runs on.
     */
    private val month: YearMonth = YearMonth.now().minusMonths(2)
    private val days: List<LocalDate> = daysOf(month)
    private val today: LocalDate = LocalDate.now()

    private fun daysOf(month: YearMonth): List<LocalDate> =
        (1..month.lengthOfMonth()).map { month.atDay(it) }

    /** What one scheduled dose did on a given day. */
    private enum class Dose { TAKEN, SKIPPED, DELAYED, UNTOUCHED }

    /** A medication as the month planner sees it: a schedule plus an outcome rule. */
    private data class Planned(
        val title: String,
        val id: Long,
        val start: LocalDate,
        val end: LocalDate? = null,
        val recurrenceDays: List<DayOfWeek>? = null,
        val intervalGap: Int? = null,
        val outcome: (LocalDate) -> Dose
    ) {
        /** Reference schedule predicate, written from the model's own fields. */
        fun scheduledOn(day: LocalDate): Boolean =
            !day.isBefore(start) &&
                    (end == null || !day.isAfter(end)) &&
                    (recurrenceDays == null || recurrenceDays.contains(day.dayOfWeek)) &&
                    (intervalGap == null ||
                            ChronoUnit.DAYS.between(start, day) % intervalGap == 0L)
    }

    /** One day's worth of outcomes across every scheduled dose. */
    private data class DayReference(
        val scheduled: Int,
        val taken: Int,
        val skipped: Int,
        val delayed: Int
    ) {
        val accounted: Int get() = taken + skipped + delayed
        val missed: Int get() = scheduled - taken - skipped - delayed
    }

    private data class ReferenceSummary(
        val streak: Int,
        val adherence: Int,
        val missedPerWeek: String,
        val delayed: Int
    )

    // --- model -> data -----------------------------------------------------

    private fun buildMed(plan: Planned, month: YearMonth = this.month): MedData {
        val taken = HashMap<LocalDate, LocalTime>()
        val skips = HashMap<LocalDate, SkipRecord>()
        daysOf(month).filter { plan.scheduledOn(it) }.forEach { day ->
            when (plan.outcome(day)) {
                Dose.TAKEN -> taken[day] = LocalTime.NOON
                Dose.SKIPPED -> skips[day] = SkipRecord(SkipReason.OTHER, LocalTime.NOON)
                Dose.DELAYED -> skips[day] = SkipRecord(SkipReason.DELAYED, LocalTime.NOON)
                Dose.UNTOUCHED -> Unit
            }
        }
        return MedData(
            id = plan.id,
            groupId = plan.id,
            type = ItemType.Medicine,
            title = plan.title,
            creationDate = plan.start,
            creationTime = LocalTime.NOON,
            takenHistory = taken,
            skipHistory = skips,
            recurrenceDays = plan.recurrenceDays,
            endDate = plan.end,
            intervalGap = plan.intervalGap
        )
    }

    private fun buildMeds(
        plans: List<Planned>,
        month: YearMonth = this.month
    ): List<MedData> = plans.map { buildMed(it, month) }

    private fun med(
        id: Long,
        title: String,
        start: LocalDate = month.atDay(1),
        end: LocalDate? = null,
        groupId: Long? = null,
        medicationId: String? = null,
        doseAmount: String? = null,
        doseUnit: String? = null,
        taken: Collection<LocalDate> = emptyList(),
        skips: Map<LocalDate, SkipReason> = emptyMap()
    ): MedData = MedData(
        id = id,
        groupId = groupId,
        type = ItemType.Medicine,
        title = title,
        creationDate = start,
        creationTime = LocalTime.NOON,
        takenHistory = HashMap(taken.associateWith { LocalTime.NOON }),
        skipHistory = HashMap(skips.mapValues { (_, reason) -> SkipRecord(reason, LocalTime.NOON) }),
        endDate = end,
        medicationId = medicationId,
        doseAmount = doseAmount,
        doseUnit = doseUnit
    )

    // --- reference rollup --------------------------------------------------

    private fun tallyFor(day: LocalDate, plans: List<Planned>): DayReference {
        val active = plans.filter { it.scheduledOn(day) }
        return DayReference(
            scheduled = active.size,
            taken = active.count { it.outcome(day) == Dose.TAKEN },
            skipped = active.count { it.outcome(day) == Dose.SKIPPED },
            delayed = active.count { it.outcome(day) == Dose.DELAYED }
        )
    }

    /** The day-state the reference model implies for [tally]. */
    private fun referenceStatus(tally: DayReference): DayStatus = when {
        // A day the schedules do not reach sits outside the day-status partition.
        tally.scheduled == 0 -> DayStatus.NO_MEDS
        tally.accounted == 0 -> DayStatus.NONE_TAKEN
        tally.taken == tally.scheduled -> DayStatus.ALL_TAKEN
        tally.accounted == tally.scheduled ->
            if (tally.delayed > 0) DayStatus.DELAYED else DayStatus.SKIPPED

        else -> DayStatus.PARTIAL
    }

    private fun referenceSummary(
        plans: List<Planned>,
        month: YearMonth = this.month
    ): ReferenceSummary {
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        // A month that has not begun contributes nothing at all.
        if (monthStart.isAfter(today)) return ReferenceSummary(0, 0, "0.0", 0)

        val evalEnd = if (monthEnd.isAfter(today)) today else monthEnd
        var totalScheduled = 0
        var accounted = 0
        var totalMissed = 0
        var totalDelayed = 0
        var run = 0
        var best = 0

        var date = monthStart
        while (!date.isAfter(evalEnd)) {
            val tally = tallyFor(date, plans)
            // A day with no doses neither extends nor breaks a streak.
            if (tally.scheduled > 0) {
                val counts = !date.isEqual(today) || tally.taken > 0
                if (counts) {
                    totalScheduled += tally.scheduled
                    accounted += tally.taken + tally.skipped
                    totalMissed += tally.missed
                    totalDelayed += tally.delayed
                }
                if (tally.taken + tally.skipped == tally.scheduled) {
                    run++
                    if (run > best) best = run
                } else if (counts) {
                    run = 0
                }
            }
            date = date.plusDays(1)
        }

        val adherence = if (totalScheduled > 0) (accounted * 100f / totalScheduled).toInt() else 0
        val totalDays = Math.max(1L, ChronoUnit.DAYS.between(monthStart, evalEnd.plusDays(1)))
        val weeks = Math.max(1f, totalDays / 7f)
        return ReferenceSummary(
            streak = best,
            adherence = adherence,
            missedPerWeek = String.format(Locale.US, "%.1f", totalMissed / weeks),
            delayed = totalDelayed
        )
    }

    // --- the shared cross-check -------------------------------------------

    /**
     * Asserts every way of reading the month agrees with the reference model:
     * per-day statuses and skip dots, summed per-medication buckets, the
     * per-medication arithmetic identities, and the headline summary.
     */
    private fun assertMonthIsConsistent(
        plans: List<Planned>,
        month: YearMonth = this.month
    ) {
        val days = daysOf(month)
        // Both rollups stop at today, so only these days can ever be counted.
        val countedDays = days.filter { !it.isAfter(today) }
        // Once a month has fully elapsed no day is "today", so the summary adds
        // up every dose and its totals must match the summed buckets exactly.
        val fullyElapsed = month.atEndOfMonth().isBefore(today)

        val meds = buildMeds(plans, month)
        // Distinct instance keys, otherwise the breakdown would silently dedupe.
        assertEquals(plans.size, meds.map { it.medicationInstanceKey() }.toSet().size)

        val stats = getMedicationStatsForMonth(month, meds)
        val summary = computeMonthlySummary(month, meds, today)

        var scheduledSum = 0
        var takenSum = 0
        var skippedSum = 0
        var delayedSum = 0

        for (day in days) {
            if (day.isAfter(today)) {
                // Past the horizon nothing is counted, whatever is on record.
                assertEquals("status on $day", DayStatus.FUTURE, getStatusForDate(day, meds))
                continue
            }
            val tally = tallyFor(day, plans)
            scheduledSum += tally.scheduled
            takenSum += tally.taken
            skippedSum += tally.skipped
            delayedSum += tally.delayed

            assertEquals("status on $day", referenceStatus(tally), getStatusForDate(day, meds))
            assertEquals("skip dots on $day", tally.skipped, getSkipCountForDate(day, meds))
        }

        // The day view and the bucket view agree, dose for dose.
        assertEquals(scheduledSum, stats.sumOf { it.scheduled })
        assertEquals(takenSum, stats.sumOf { it.taken })
        assertEquals(skippedSum, stats.sumOf { it.skipped })
        assertEquals(delayedSum, stats.sumOf { it.delayed })
        assertEquals(scheduledSum - takenSum - skippedSum - delayedSum, stats.sumOf { it.missed })

        // Per-medication buckets follow that medication's own schedule.
        for (plan in plans) {
            val ownDays = countedDays.filter { plan.scheduledOn(it) }
            val stat = stats.firstOrNull { it.title == plan.title }
            if (ownDays.isEmpty()) {
                assertTrue("${plan.title} has no doses this month and must be dropped", stat == null)
                continue
            }
            assertNotNull(stat)
            val outcomes = ownDays.map { plan.outcome(it) }
            assertEquals(ownDays.size, stat!!.scheduled)
            assertEquals(outcomes.count { it == Dose.TAKEN }, stat.taken)
            assertEquals(outcomes.count { it == Dose.SKIPPED }, stat.skipped)
            assertEquals(outcomes.count { it == Dose.DELAYED }, stat.delayed)
            assertEquals(
                "buckets must sum to scheduled",
                stat.scheduled,
                stat.taken + stat.skipped + stat.delayed + stat.missed
            )
            assertEquals(
                "adherence credits taken + skipped only",
                ((stat.taken + stat.skipped) * 100f / stat.scheduled).toInt(),
                stat.adherence
            )
        }

        // The summary must match the reference, whichever month this is.
        val reference = referenceSummary(plans, month)
        assertEquals("streak", reference.streak, summary.streak)
        assertEquals("adherence", reference.adherence, summary.adherence)
        assertEquals("missed per week", reference.missedPerWeek, summary.missedPerWeek)
        assertEquals("delayed", reference.delayed, summary.delayed)

        if (fullyElapsed) {
            val expectedAdherence =
                if (scheduledSum == 0) 0 else ((takenSum + skippedSum) * 100f / scheduledSum).toInt()
            val expectedMissed = scheduledSum - takenSum - skippedSum - delayedSum
            val totalDays =
                Math.max(1L, ChronoUnit.DAYS.between(month.atDay(1), days.last().plusDays(1)))
            val weeks = Math.max(1f, totalDays / 7f)
            assertEquals(
                "summary adherence must equal the summed buckets",
                expectedAdherence,
                summary.adherence
            )
            assertEquals("summary delayed must equal the delayed bucket", delayedSum, summary.delayed)
            assertEquals(
                "summary missed per week must equal the summed missed bucket",
                String.format(Locale.US, "%.1f", expectedMissed / weeks),
                summary.missedPerWeek
            )

            // Streak is exactly the longest run of days that are fully taken or
            // skipped per the day status; gaps with no doses do not break it.
            var run = 0
            var best = 0
            for (day in countedDays) {
                when (getStatusForDate(day, meds)) {
                    DayStatus.NO_MEDS -> Unit
                    DayStatus.ALL_TAKEN, DayStatus.SKIPPED -> {
                        run++
                        if (run > best) best = run
                    }

                    else -> run = 0
                }
            }
            assertEquals("streak must be the longest fully-accounted run", best, summary.streak)
        }
    }

    // --- exhaustive day-shape sweep across a full month --------------------

    private fun combinations(count: Int): List<List<Dose>> {
        var acc: List<List<Dose>> = listOf(emptyList())
        repeat(count) { acc = acc.flatMap { prefix -> Dose.entries.map { prefix + it } } }
        return acc
    }

    // --- seeded property test over generated months -----------------------

    /** Fixed so any failure replays exactly; bump it to search wider. */
    private val generatorSeed = 0x5EEDL

    /** Tallies proving the generator is not quietly producing degenerate data. */
    private class Coverage {
        val doses = mutableSetOf<Dose>()
        var elapsedMonths = 0
        var currentMonths = 0
        var futureMonths = 0
        var emptyMonths = 0
        var monthsWithMissed = 0
        var monthsWithDelayed = 0
        var meds = 0
        var medsStartingMidMonth = 0
        var medsEndingEarly = 0
        var medsWithRecurrence = 0
        var medsWithIntervalGap = 0
    }

    private fun randomMonth(random: Random): YearMonth {
        val now = YearMonth.now()
        return when (random.nextInt(10)) {
            // The only month where the "today" gating is actually in play.
            0 -> now
            // A month that has not started: nothing to roll up at all.
            1 -> now.plusMonths((1 + random.nextInt(3)).toLong())
            else -> now.minusMonths((1 + random.nextInt(24)).toLong())
        }
    }

    private fun randomPlans(
        random: Random,
        month: YearMonth,
        coverage: Coverage
    ): List<Planned> {
        val monthStart = month.atDay(1)
        val monthLength = month.lengthOfMonth()
        return (0 until random.nextInt(7)).map { index ->
            val start = monthStart.plusDays((random.nextInt(35) - 5).toLong())
            val end = if (random.nextInt(3) == 0) null
            else monthStart.plusDays(random.nextInt(monthLength + 10).toLong())
            val recurrenceDays = if (random.nextInt(3) == 0) null
            else DayOfWeek.entries.filter { random.nextBoolean() }
                .ifEmpty { listOf(DayOfWeek.entries[random.nextInt(7)]) }
            val intervalGap = if (random.nextInt(4) == 0) null else 1 + random.nextInt(4)

            val plan = Planned(
                title = "Med $index",
                id = 100L + index,
                start = start,
                end = end,
                recurrenceDays = recurrenceDays,
                intervalGap = intervalGap,
                outcome = { Dose.UNTOUCHED }
            )
            // Outcomes are materialised up front. `outcome` has to stay a pure
            // function of the day, or the reference rollup would disagree with
            // the very data it is being compared against.
            val outcomes = HashMap<LocalDate, Dose>()
            daysOf(month).filter { plan.scheduledOn(it) }.forEach { day ->
                outcomes[day] = Dose.entries[random.nextInt(Dose.entries.size)]
            }

            coverage.meds++
            if (start.isAfter(monthStart)) coverage.medsStartingMidMonth++
            if (end != null) coverage.medsEndingEarly++
            if (recurrenceDays != null) coverage.medsWithRecurrence++
            if (intervalGap != null) coverage.medsWithIntervalGap++
            coverage.doses += outcomes.values

            plan.copy(outcome = { day -> outcomes[day] ?: Dose.UNTOUCHED })
        }
    }

    @Test
    fun `randomly generated months keep every view of the rollup consistent`() {
        val scenarios = 200
        val random = Random(generatorSeed)
        val coverage = Coverage()

        for (index in 0 until scenarios) {
            // A per-scenario seed so a single failure can be replayed in
            // isolation by pinning it back into Random(scenarioSeed).
            val scenarioSeed = random.nextLong()
            val scenario = Random(scenarioSeed)
            val month = randomMonth(scenario)
            val plans = randomPlans(scenario, month, coverage)

            if (month.atDay(1).isAfter(today)) {
                coverage.futureMonths++
            } else if (month.atEndOfMonth().isBefore(today)) {
                coverage.elapsedMonths++
            } else {
                coverage.currentMonths++
            }

            var scheduled = 0
            var missed = 0
            var delayed = 0
            for (day in daysOf(month)) {
                if (day.isAfter(today)) continue
                val tally = tallyFor(day, plans)
                scheduled += tally.scheduled
                missed += tally.missed
                delayed += tally.delayed
            }
            if (scheduled == 0) coverage.emptyMonths++
            if (missed > 0) coverage.monthsWithMissed++
            if (delayed > 0) coverage.monthsWithDelayed++

            try {
                assertMonthIsConsistent(plans, month)
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "scenario $index failed (seed=$scenarioSeed, month=$month, today=$today): " +
                            "${failure.message}\n${describe(plans)}",
                    failure
                )
            }
        }

        // The invariants only mean something if the generator reaches the
        // corners they cover, so fail loudly if the search went stale.
        assertEquals(Dose.entries.toSet(), coverage.doses)
        assertTrue("no elapsed month generated", coverage.elapsedMonths > 0)
        assertTrue("the current month was never generated", coverage.currentMonths > 0)
        assertTrue("no future month generated", coverage.futureMonths > 0)
        assertTrue("no empty month generated", coverage.emptyMonths > 0)
        assertTrue("no month with a missed dose", coverage.monthsWithMissed > 0)
        assertTrue("no month with a postponement", coverage.monthsWithDelayed > 0)
        assertTrue("no medication started mid-month", coverage.medsStartingMidMonth > 0)
        assertTrue("no medication ended early", coverage.medsEndingEarly > 0)
        assertTrue("no recurrence-limited schedule", coverage.medsWithRecurrence > 0)
        assertTrue("no interval-gap schedule", coverage.medsWithIntervalGap > 0)
    }

    private fun describe(plans: List<Planned>): String =
        plans.joinToString("\n") { plan ->
            "  ${plan.title}: start=${plan.start} end=${plan.end} " +
                    "recurrence=${plan.recurrenceDays} gap=${plan.intervalGap}"
        }

    @Test
    fun `every day shape kept constant across the month is consistent end to end`() {
        val checked = mutableSetOf<DayStatus>()

        for (doseCount in 1..4) {
            for (combo in combinations(doseCount)) {
                val plans = combo.mapIndexed { index, action ->
                    Planned("Dose $index", 10L + index, month.atDay(1)) { action }
                }
                assertMonthIsConsistent(plans)

                val meds = buildMeds(plans)
                val expectedStatus = referenceStatus(tallyFor(days.first(), plans))
                // The shape repeats, so the day state must be identical every day.
                for (day in days) {
                    assertEquals("$combo on $day", expectedStatus, getStatusForDate(day, meds))
                }
                checked += expectedStatus

                // Closed-form per-medication buckets for the repetition.
                val stats = getMedicationStatsForMonth(month, meds)
                assertEquals(doseCount, stats.size)
                combo.forEachIndexed { index, action ->
                    val stat = stats.first { it.title == "Dose $index" }
                    assertEquals(days.size, stat.scheduled)
                    assertEquals(if (action == Dose.TAKEN) days.size else 0, stat.taken)
                    assertEquals(if (action == Dose.SKIPPED) days.size else 0, stat.skipped)
                    assertEquals(if (action == Dose.DELAYED) days.size else 0, stat.delayed)
                    val credited = (stat.taken + stat.skipped) * 100f / stat.scheduled
                    assertEquals(credited.toInt(), stat.adherence)
                }
            }
        }

        // The whole sweep must reach every resolved day state, including the
        // postponed one that the adherence credit deliberately ignores.
        assertEquals(
            setOf(
                DayStatus.ALL_TAKEN,
                DayStatus.NONE_TAKEN,
                DayStatus.SKIPPED,
                DayStatus.DELAYED,
                DayStatus.PARTIAL
            ),
            checked
        )
    }

    // --- named scenarios the sweep cannot reach ----------------------------

    @Test
    fun `a month of rotating outcomes keeps every view in agreement`() {
        assertMonthIsConsistent(rotatingPlans())
    }

    @Test
    fun `weekday-only doses leave weekends outside the month without breaking streaks`() {
        val weekdays = listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )
        val plans =
            listOf(Planned("Sertraline", 1, month.atDay(1), recurrenceDays = weekdays) { Dose.TAKEN })
        assertMonthIsConsistent(plans)

        val meds = buildMeds(plans)
        val stats = getMedicationStatsForMonth(month, meds).single()
        val summary = computeMonthlySummary(month, meds, today)

        val expectedWeekdays = days.count { it.dayOfWeek in weekdays }
        assertEquals(expectedWeekdays, stats.scheduled)
        assertTrue(expectedWeekdays < days.size) // some weekends really are outside the schedule
        assertEquals(100, stats.adherence)
        assertEquals(100, summary.adherence)
        // The weekend gaps are skipped by the loop, so the run reaches every dose.
        assertEquals(expectedWeekdays, summary.streak)
    }

    @Test
    fun `a medication that ends mid-month stops contributing doses`() {
        val plans = listOf(
            Planned("Amoxicillin", 1, month.atDay(1), end = month.atDay(10)) { Dose.TAKEN }
        )
        assertMonthIsConsistent(plans)

        val stats = getMedicationStatsForMonth(month, buildMeds(plans)).single()
        assertEquals(10, stats.scheduled)
        assertEquals(10, stats.taken)
        assertEquals(0, stats.missed)
        assertEquals(100, stats.adherence)
        assertEquals(10, computeMonthlySummary(month, buildMeds(plans), today).streak)
    }

    @Test
    fun `an interval gap counts only its on-cycle days`() {
        val plans = listOf(Planned("Methotrexate", 1, month.atDay(1), intervalGap = 3) { Dose.TAKEN })
        assertMonthIsConsistent(plans)

        val meds = buildMeds(plans)
        val expected = 1 + (days.size - 1) / 3
        assertEquals(expected, getMedicationStatsForMonth(month, meds).single().scheduled)
        // On-cycle days are consecutive in the streak's eyes; off-cycle days are
        // skipped entirely, so the run reaches every scheduled dose.
        assertEquals(expected, computeMonthlySummary(month, meds, today).streak)
    }

    @Test
    fun `a fully missed month reports no adherence and a zero streak`() {
        val plans = listOf(Planned("Sertraline", 1, month.atDay(1)) { Dose.UNTOUCHED })
        assertMonthIsConsistent(plans)

        val meds = buildMeds(plans)
        val stats = getMedicationStatsForMonth(month, meds).single()
        assertEquals(days.size, stats.scheduled)
        assertEquals(days.size, stats.missed)
        assertEquals(0, stats.taken + stats.skipped + stats.delayed)
        assertEquals(0, stats.adherence)

        val summary = computeMonthlySummary(month, meds, today)
        assertEquals(0, summary.adherence)
        assertEquals(0, summary.streak)
        assertEquals(0, summary.delayed)
        val weeks = Math.max(1f, days.size / 7f)
        assertEquals(String.format(Locale.US, "%.1f", days.size / weeks), summary.missedPerWeek)
    }

    @Test
    fun `a month of postponements never credits adherence`() {
        val plans = listOf(Planned("Sertraline", 1, month.atDay(1)) { Dose.DELAYED })
        assertMonthIsConsistent(plans)

        val meds = buildMeds(plans)
        val stats = getMedicationStatsForMonth(month, meds).single()
        assertEquals(days.size, stats.delayed)
        assertEquals(0, stats.taken + stats.skipped)
        assertEquals(0, stats.adherence)
        assertEquals(0, stats.missed) // postponed doses are not missed either

        val summary = computeMonthlySummary(month, meds, today)
        assertEquals(days.size, summary.delayed)
        assertEquals(0, summary.adherence)
        assertEquals(0, summary.streak) // and they never extend a streak
    }

    @Test
    fun `a single postponement breaks the streak and costs its share of adherence`() {
        val delayedDay = days[days.size / 2]
        val plans = listOf(
            Planned("Sertraline", 1, month.atDay(1)) { day ->
                if (day == delayedDay) Dose.DELAYED else Dose.TAKEN
            }
        )
        assertMonthIsConsistent(plans)

        val meds = buildMeds(plans)
        val summary = computeMonthlySummary(month, meds, today)
        assertEquals(1, summary.delayed)
        assertEquals(((days.size - 1) * 100f / days.size).toInt(), summary.adherence)

        val before = days.count { it.isBefore(delayedDay) }
        val after = days.count { it.isAfter(delayedDay) }
        assertEquals(Math.max(before, after), summary.streak)
    }

    private fun rotatingPlans(): List<Planned> {
        val firstHalfEnd = month.atDay(days.size / 2)
        val secondHalfStart = month.atDay(days.size / 2 + 1)
        val weekdays = listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )
        return listOf(
            // Alternating taken / skipped: full credit, unbroken streak.
            Planned("Sertraline", 1, month.atDay(1)) { day ->
                if (day.dayOfMonth % 2 == 1) Dose.TAKEN else Dose.SKIPPED
            },
            // A postponement every fourth day.
            Planned("Escitalopram", 2, month.atDay(1)) { day ->
                when (day.dayOfMonth % 4) {
                    0 -> Dose.DELAYED
                    1 -> Dose.TAKEN
                    else -> Dose.UNTOUCHED
                }
            },
            // Weekday-only, taken on odd weekdays.
            Planned("Bupropion", 3, month.atDay(1), recurrenceDays = weekdays) { day ->
                if (day.dayOfWeek == DayOfWeek.MONDAY ||
                    day.dayOfWeek == DayOfWeek.WEDNESDAY ||
                    day.dayOfWeek == DayOfWeek.FRIDAY
                ) Dose.TAKEN else Dose.SKIPPED
            },
            // A course that finishes mid-month.
            Planned("Amoxicillin", 4, month.atDay(1), end = firstHalfEnd) { Dose.TAKEN },
            // A course that starts mid-month, refused every dose.
            Planned("Vitamin D", 5, secondHalfStart) { Dose.SKIPPED },
            // Every third day, always taken.
            Planned("Methotrexate", 6, month.atDay(1), intervalGap = 3) { Dose.TAKEN }
        )
    }

    // --- structural edge cases --------------------------------------------

    @Test
    fun `duplicate schedule fragments count as one dose in the month`() {
        val start = month.atDay(1)
        val base = med(id = 1L, title = "Sertraline", start = start, groupId = 1L)
        val fragment = base.copy(id = 2L) // same title, group and time => same key

        assertEquals(base.medicationInstanceKey(), fragment.medicationInstanceKey())
        assertEquals(1, getScheduledMedsForDate(start, listOf(base, fragment)).size)

        val stats = getMedicationStatsForMonth(month, listOf(base, fragment))
        assertEquals(1, stats.size)
        assertEquals(days.size, stats.single().scheduled)
    }

    @Test
    fun `dose versions of one treatment combine into a single treatment stat`() {
        val morning = med(
            id = 1L,
            title = "Sertraline",
            groupId = 1L,
            medicationId = "M1",
            doseAmount = "10",
            doseUnit = "mg",
            taken = days
        )
        val evening = med(
            id = 2L,
            title = "Sertraline",
            groupId = 2L,
            medicationId = "M1",
            doseAmount = "20",
            doseUnit = "mg",
            end = month.atDay(days.size / 2),
            skips = days.take(days.size / 2).associateWith { SkipReason.OTHER }
        )

        val perMed = getMedicationStatsForMonth(month, listOf(morning, evening))
        assertEquals(2, perMed.size)

        val combined = getCombinedTreatmentStats(perMed)
        assertEquals(1, combined.size)
        val treatment = combined.single()
        assertEquals("Sertraline", treatment.title)
        assertEquals(perMed.sumOf { it.scheduled }, treatment.scheduled)
        assertEquals(perMed.sumOf { it.taken + it.skipped }, treatment.accounted)
        // Postponed doses are excluded from the treatment credit too.
        assertEquals(
            treatment.scheduled - perMed.sumOf { it.missed } - perMed.sumOf { it.delayed },
            treatment.accounted
        )
    }

    @Test
    fun `a month with nothing scheduled rolls up to empty and zeroed`() {
        assertTrue(getMedicationStatsForMonth(month, emptyList()).isEmpty())
        val summary = computeMonthlySummary(month, emptyList(), today)
        assertEquals(0, summary.streak)
        assertEquals(0, summary.adherence)
        assertEquals(0, summary.delayed)
        assertEquals("0.0", summary.missedPerWeek)

        // A medicine whose schedule never reaches this month contributes nothing.
        val after = med(id = 1L, title = "Future", start = month.plusMonths(1).atDay(1))
        assertTrue(getMedicationStatsForMonth(month, listOf(after)).isEmpty())
        assertEquals(0, computeMonthlySummary(month, listOf(after), today).adherence)
    }

    // --- the "today" gating ------------------------------------------------

    @Test
    fun `today counts in the buckets but not the summary until a dose is taken`() {
        val currentMonth = YearMonth.from(today)
        val start = currentMonth.atDay(1)

        // Taken every day strictly before today, today left untouched.
        val pastDays = (1 until today.dayOfMonth).map { currentMonth.atDay(it) }
        val meds = listOf(med(id = 1L, title = "Sertraline", start = start, taken = pastDays))

        // The per-medication bucket view walks through today unconditionally...
        val stats = getMedicationStatsForMonth(currentMonth, meds).single()
        assertEquals(today.dayOfMonth, stats.scheduled)
        assertEquals(pastDays.size, stats.taken)
        assertEquals(1, stats.missed) // today's outstanding dose

        // ...while the headline summary leaves an untouched today out entirely.
        val untouchedSummary = computeMonthlySummary(currentMonth, meds, today)
        assertEquals(if (pastDays.isEmpty()) 0 else 100, untouchedSummary.adherence)
        assertEquals(pastDays.size, untouchedSummary.streak)
        assertEquals("0.0", untouchedSummary.missedPerWeek)

        // Once today is taken, it joins the totals and the streak.
        val takenMeds = listOf(
            med(id = 1L, title = "Sertraline", start = start, taken = pastDays + today)
        )
        val takenStats = getMedicationStatsForMonth(currentMonth, takenMeds).single()
        assertEquals(today.dayOfMonth, takenStats.scheduled)
        assertEquals(today.dayOfMonth, takenStats.taken)
        assertEquals(0, takenStats.missed)

        val takenSummary = computeMonthlySummary(currentMonth, takenMeds, today)
        assertEquals(100, takenSummary.adherence)
        assertEquals(today.dayOfMonth, takenSummary.streak)
    }
}
