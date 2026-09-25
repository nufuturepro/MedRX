package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import com.nukirk.medrx.elements.MainActivity.Tabs.DayStatus
import com.nukirk.medrx.elements.MainActivity.Tabs.getScheduledMedsForDate
import com.nukirk.medrx.elements.MainActivity.Tabs.getStatusForDate
import com.nukirk.medrx.elements.MainActivity.Tabs.getMedicationStatsForMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Regression tests for the edit rules that protect taken history:
 * every path through [MedUpdatePlanner.plan] (no-op, supply-only, plain
 * rebuild, range rebuild) plus the Stats dedupe helper.
 */
class MedUpdatePlannerTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 14)
    private val noon: LocalTime = LocalTime.of(12, 0)
    private val eightAm: LocalTime = LocalTime.of(8, 0)

    private fun med(
        id: Long,
        title: String = "Sertraline",
        time: LocalTime = noon,
        start: LocalDate = LocalDate.of(2026, 8, 15),
        end: LocalDate? = null,
        history: Map<LocalDate, LocalTime> = emptyMap(),
        supplyLeft: Int? = null,
        groupId: Long? = 1L
    ): MedData = MedData(
        id = id,
        groupId = groupId,
        type = ItemType.Medicine,
        title = title,
        creationDate = start,
        creationTime = time,
        takenHistory = HashMap(history),
        endDate = end,
        supplyDosesLeft = supplyLeft,
        supplyDosesPerRefill = if (supplyLeft != null) 30 else null,
        supplyLowThreshold = if (supplyLeft != null) 4 else null
    )

    private fun hist(vararg days: Int): Map<LocalDate, LocalTime> =
        days.associate { today.withDayOfMonth(it) to LocalTime.of(12, 30)}


    private val noopRequest = MedUpdatePlanner.Request(
        originalItem = med(1),
        title = "Sertraline",
        iconName = null,
        colorCode = null,
        times = listOf(noon),
        days = null,
        notes = null,
        intervalGap = null,
        selectedDate = today
    )

    // ------------------------------------------------------------- no-op path

    @Test
    fun `unchanged save plans None and does not rebuild`() {
        val plan = MedUpdatePlanner.plan(noopRequest, null, listOf(med(1, history = hist(1, 2))))
        assertEquals(MedUpdatePlanner.Plan.None, plan)
   }


    @Test
    fun `unchanged save with supply change plans SupplyOnly`() {
        val supply = InventoryEntry(dosesLeft = 39, dosesPerRefill = 90, lowThreshold = 4)
        val plan = MedUpdatePlanner.plan(noopRequest, supply, listOf(med(1, supplyLeft = 40)))
        assertEquals(MedUpdatePlanner.Plan.SupplyOnly, plan)
   }


    @Test
    fun `non-medicine unchanged save plans None without supply handling`() {
        val event = med(1).copy(type = ItemType.Event)
        val request = noopRequest.copy(originalItem = event)
        val plan = MedUpdatePlanner.plan(request, InventoryEntry(10, 30, 4), listOf(event))
        assertEquals(MedUpdatePlanner.Plan.None, plan)
   }


    @Test
    fun `reordered days set is still unchanged`() {
        val item = med(1, history = hist(1)).copy(recurrenceDays = listOf(DayOfWeek.TUESDAY, DayOfWeek.MONDAY))
        val request = noopRequest.copy(
            originalItem = item,
            days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        )
        val plan = MedUpdatePlanner.plan(request, null, listOf(item))
        assertEquals(MedUpdatePlanner.Plan.None, plan)
   }


    // ---------------------------------------------------------- rebuild paths

    @Test
    fun `real edit rebuilds with full history preserved`() {
        val history = hist(1, 2, 3)
        val request = noopRequest.copy(title = "Sertraline HCL")
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = history))) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(setOf(1L), plan.removeIds)
        assertEquals(1, plan.entries.size)
        assertEquals("Sertraline HCL", plan.entries[0].title)
        assertEquals(history, plan.entries[0].takenHistory)
   }


    @Test
    fun `editing one slot preserves sibling slots and their history`() {
        val morning = med(1, time = eightAm, history = hist(1, 2))
        val evening = med(2, time = LocalTime.of(20, 0), history = hist(2, 3))
        val request = noopRequest.copy(originalItem = morning, title = "Renamed", times = listOf(eightAm))

        val plan = MedUpdatePlanner.plan(request, null, listOf(morning, evening)) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(setOf(1L, 2L), plan.removeIds)
        assertEquals(2, plan.entries.size)
        val rebuiltEvening = plan.entries.first { it.creationTime == LocalTime.of(20, 0)}

        assertEquals("Renamed", rebuiltEvening.title)
        assertEquals(hist(2, 3), rebuiltEvening.takenHistory)
        val rebuiltMorning = plan.entries.first { it.creationTime == eightAm}

        assertEquals(hist(1, 2), rebuiltMorning.takenHistory)
   }


    @Test
    fun `changed time carries its history to the new time`() {
        val history = hist(5, 10)
        val request = noopRequest.copy(times = listOf(LocalTime.of(13, 0)))
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = history))) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(1, plan.entries.size)
        assertEquals(LocalTime.of(13, 0), plan.entries[0].creationTime)
        assertEquals(history, plan.entries[0].takenHistory)
   }


    @Test
    fun `rebuild entries get fresh group and placeholder ids`() {
        val request = noopRequest.copy(title = "New")
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = hist(1)))) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(0L, plan.entries[0].id)
        assertEquals(MedUpdatePlanner.NEW_GROUP, plan.entries[0].groupId)
   }


    @Test
    fun `group scoping is type-safe so events sharing a groupId survive`() {
        val medicine = med(1, history = hist(1))
        val event = med(2).copy(type = ItemType.Event)
        val request = noopRequest.copy(originalItem = medicine, title = "Renamed")

        val plan = MedUpdatePlanner.plan(request, null, listOf(medicine, event)) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(setOf(1L), plan.removeIds)
        assertFalse(plan.removeIds.contains(event.id))
   }


    // ----------------------------------------------------------- range edits

    @Test
    fun `only-this-event edit splits history around the edited day`() {
        // Took Sep 1, 2, 14 (today), and 20; renaming only today's entry.
        val history = hist(1, 2, 14, 20)
        val request = noopRequest.copy(title = "Renamed", rangeStart = null, rangeEnd = null)
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = history))) as MedUpdatePlanner.Plan.Rebuild

        val edited = plan.entries.first { it.groupId == MedUpdatePlanner.NEW_GROUP}

        assertEquals(today, edited.creationDate)
        assertEquals(today, edited.endDate)
        assertEquals(mapOf(today to LocalTime.of(12, 30)), edited.takenHistory)

        val before = plan.entries.filter { it.groupId == 1L && it.endDate != null}

        assertEquals(1, before.size)
        assertEquals(today.minusDays(1), before[0].endDate)
        assertEquals(hist(1, 2), before[0].takenHistory)

        val after = plan.entries.filter { it.groupId == 1L && it.endDate == null}

        assertEquals(1, after.size)
        assertEquals(today.plusDays(1), after[0].creationDate)
        assertEquals(hist(20), after[0].takenHistory)
   }


    @Test
    fun `only-this-event edit on never-taken med produces no phantom fragments`() {
        val request = noopRequest.copy(title = "Renamed", rangeStart = null, rangeEnd = null)
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = emptyMap()))) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(1, plan.entries.size) // only the edited day
   }


    @Test
    fun `only-this-event edit keeps later records as an after fragment`() {
        // Med starts today, already logged a future date (Sep 20): the before
        // fragment is empty and must be dropped, but the Sep 20 record is real
        // history and must survive as an after fragment.
        val item = med(1, start = today, history = hist(20))
        val request = noopRequest.copy(originalItem = item, title = "Renamed", rangeStart = null, rangeEnd = null)
        val plan = MedUpdatePlanner.plan(request, null, listOf(item)) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(2, plan.entries.size) // edited day + after fragment
        val after = plan.entries.filter { it.endDate == null}

        assertEquals(1, after.size)
        assertEquals(hist(20), after[0].takenHistory)
   }


    @Test
    fun `all-following edit keeps before fragment with earlier history only`() {
        val history = hist(1, 2, 20)
        val request = noopRequest.copy(title = "Renamed", rangeStart = -1L, rangeEnd = null)
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = history))) as MedUpdatePlanner.Plan.Rebuild

        val edited = plan.entries.first { it.groupId == MedUpdatePlanner.NEW_GROUP}

        assertEquals(today, edited.creationDate)
        assertNull(edited.endDate)
        // Everything from editStart onward follows the ongoing entry.
        assertEquals(hist(20), edited.takenHistory)

        val before = plan.entries.first { it.groupId == 1L}

        assertEquals(today.minusDays(1), before.endDate)
        assertEquals(hist(1, 2), before.takenHistory)
   }


    @Test
    fun `range edit on never-taken med produces no phantom fragments`() {
        val request = noopRequest.copy(title = "Renamed", rangeStart = null, rangeEnd = null)
        val plan = MedUpdatePlanner.plan(request, null, listOf(med(1, history = emptyMap()))) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(1, plan.entries.size)
   }


    @Test
    fun `range edit applies supply to the rebuilt window`() {
        val supply = InventoryEntry(dosesLeft = 7, dosesPerRefill = 30, lowThreshold = 2)
        val request = noopRequest.copy(title = "Renamed", rangeStart = null, rangeEnd = null)
        val plan = MedUpdatePlanner.plan(request, supply, listOf(med(1, supplyLeft = 39, history = hist(1)))) as MedUpdatePlanner.Plan.Rebuild

        val edited = plan.entries.first { it.groupId == MedUpdatePlanner.NEW_GROUP}

        assertEquals(7, edited.supplyDosesLeft)
        assertFalse(edited.supplyAlertShown)
   }


    // ------------------------------------------------------ multi-slot range

    @Test
    fun `range edit covers every slot of a multi-dose med`() {
        val morning = med(1, time = eightAm, history = hist(1, 5, 14, 20))
        val evening = med(2, time = LocalTime.of(20, 0), history = hist(2, 5, 14, 20))
        val request = noopRequest.copy(
            originalItem = morning,
            title = "Renamed",
            times = listOf(eightAm),
            rangeStart = null,
            rangeEnd = null
        )

        val plan = MedUpdatePlanner.plan(request, null, listOf(morning, evening)) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(setOf(1L, 2L), plan.removeIds)
        val editedSlots = plan.entries.filter { it.groupId == MedUpdatePlanner.NEW_GROUP}

        assertEquals(2, editedSlots.size)
        assertEquals(setOf(eightAm, LocalTime.of(20, 0)), editedSlots.map { it.creationTime}
.toSet())
        // Both slots keep their taken record for the edited day.
        assertTrue(editedSlots.all { it.takenHistory.containsKey(today)}
)
        // After-fragments exist for both slots and keep their own later history.
        val afterFragments = plan.entries.filter { it.groupId == 1L && it.endDate == null}

        assertEquals(2, afterFragments.size)
        assertTrue(afterFragments.all { it.takenHistory.containsKey(today.plusDays(1).withDayOfMonth(20))}
)
   }


    // ------------------------------------------------------------ Stats dedupe

    @Test
    fun `stats dedupes duplicate schedule fragments`() {
        val original = med(1, history = hist(1))
        val phantom = med(2) // same title/time, no end date — duplicate fragment
        val duplicate = med(3)
        val items = listOf(original, phantom, duplicate, med(4, title = "Other"))

        val scheduled = getScheduledMedsForDate(today, items)
        assertEquals(
            listOf("Sertraline" to noon, "Other" to noon),
            scheduled.map { it.title to it.creationTime}

        )
   }


    @Test
    fun `stats keeps distinct time slots of the same med`() {
        val morning = med(1, time = eightAm)
        val evening = med(2, time = LocalTime.of(20, 0))
        val scheduled = getScheduledMedsForDate(today, listOf(morning, evening))
        assertEquals(2, scheduled.size)
   }


    // ------------------------------------------------------------ Archive guard

    @Test
    fun `evaluateAll never alerts for meds whose schedule has ended`() {
        val archived = med(
            1,
            end = today.minusDays(1),
            supplyLeft = 2
        ).copy(supplyAlertShown = false)
        val active = med(2, supplyLeft = 2)

        val items = mutableListOf(archived, active)
        val changed = InventoryService.evaluateAll(null, items)

        assertTrue(changed)
        assertFalse(items[0].supplyAlertShown)
        assertTrue(items[1].supplyAlertShown)
   }


    // ------------------------------------------------------- skip preservation

    @Test
    fun `plain rebuild keeps each slot's skip records`() {
        val skipDay = today.withDayOfMonth(10)
        val record = SkipRecord(SkipReason.ACUTE_ILLNESS, LocalTime.of(9, 0), "stomach bug")
        val slot = med(1, history = hist(5, 6))
            .copy(skipHistory = HashMap(mapOf(skipDay to record)))

        val result = MedUpdatePlanner.plan(
            noopRequest.copy(title = "Sertraline renamed"),
            supply = null,
            groupMembers = listOf(slot)
        )

        val plan = result as MedUpdatePlanner.Plan.Rebuild
        assertEquals(1, plan.entries.size)
        assertEquals(mapOf(skipDay to record), plan.entries[0].skipHistory)
   }


    @Test
    fun `range rebuild splits skip records around the edited window`() {
        val editDay = today.withDayOfMonth(12)
        val earlySkip = today.withDayOfMonth(5)
        val lateSkip = today.withDayOfMonth(20)
        val record = SkipRecord(SkipReason.DOCTOR_DIRECTED, LocalTime.of(8, 0), null)
        val slot = med(1, end = LocalDate.of(2026, 9, 30))
            .copy(
                skipHistory = HashMap(
                    mapOf(
                        earlySkip to record,
                        lateSkip to record
                    )
                )
            )

        val result = MedUpdatePlanner.plan(
            noopRequest.copy(title = "Renamed", rangeStart = editDay.toEpochDay() * 86400000, rangeEnd = null),
            supply = null,
            groupMembers = listOf(slot)
        )

        val plan = result as MedUpdatePlanner.Plan.Rebuild
        val before = plan.entries.first { it.endDate != null && it.endDate.isBefore(editDay)}

        val window = plan.entries.first { it.creationDate == editDay}

        val after = plan.entries.first { it.creationDate.isAfter(editDay)}

        assertEquals(setOf(earlySkip), before.skipHistory.keys)
        assertTrue(window.skipHistory.isEmpty())
        assertEquals(setOf(lateSkip), after.skipHistory.keys)
        // A skip alone is enough to keep a fragment alive (no phantom drop).
        assertTrue(before.skipHistory.isNotEmpty())
   }


    @Test
    fun `changed time carries its slot's skip records`() {
        val skipDay = today.withDayOfMonth(8)
        val record = SkipRecord(SkipReason.VITALS_OUT_OF_RANGE, LocalTime.of(7, 45), "BP 190/110")
        val slot = med(1).copy(skipHistory = HashMap(mapOf(skipDay to record)))

        val result = MedUpdatePlanner.plan(
            noopRequest.copy(times = listOf(LocalTime.of(13, 0))),
            supply = null,
            groupMembers = listOf(slot)
        )

        val plan = result as MedUpdatePlanner.Plan.Rebuild
        assertEquals(1, plan.entries.size)
        assertEquals(LocalTime.of(13, 0), plan.entries[0].creationTime)
        assertEquals(mapOf(skipDay to record), plan.entries[0].skipHistory)
   }


    @Test
    fun `mixed taken and skipped day counts as compliant with a skip mark`() {
        val skipped = med(1).copy(skipHistory = HashMap(mapOf(today to SkipRecord(SkipReason.OTHER))))
        val taken = med(2, history = mapOf(today to LocalTime.NOON))

        val status = getStatusForDate(today, listOf(skipped, taken))

        assertEquals(DayStatus.SKIPPED, status)
   }


    @Test
    fun `fully skipped day gets its own status`() {
        val a = med(1).copy(skipHistory = HashMap(mapOf(today to SkipRecord(SkipReason.OTHER))))
        val b = med(2, title = "Other").copy(skipHistory = HashMap(mapOf(today to SkipRecord(SkipReason.ACUTE_ILLNESS))))

        assertEquals(DayStatus.SKIPPED, getStatusForDate(today, listOf(a, b)))
   }


    @Test
    fun `skip report lists every skip with readable reasons`() {
        val day1 = today.withDayOfMonth(3)
        val day2 = today.withDayOfMonth(7)
        val a = med(1).copy(
            skipHistory = HashMap(
                mapOf(
                    day2 to SkipRecord(SkipReason.PROCEDURE_FASTING, LocalTime.NOON, "colonoscopy prep"),
                    day1 to SkipRecord(SkipReason.ACUTE_ILLNESS, LocalTime.of(8, 0), null)
                )
            )
        )
        val b = med(2, title = "Metformin, 500mg").copy(
            skipHistory = HashMap(mapOf(day1 to SkipRecord(SkipReason.OTHER, LocalTime.of(9, 30), "travel, forgot")))
        )

        val csv = SkipReport.toCsv(listOf(a, b))
        val lines = csv.trim().split("\r\n")

        assertEquals("medication,date,time,reason,note", lines[0])
        // Sorted by date, then med title (Metformin < Sertraline).
        assertTrue(lines[1].startsWith("\"Metformin, 500mg\",$day1,09:30,Patient discretion / other,\"travel, forgot\""))
        assertTrue(lines[2].startsWith("Sertraline,$day1,08:00,Acute illness / vomiting"))
        assertTrue(lines[3].startsWith("Sertraline,$day2,12:00,Upcoming procedure / fasting,colonoscopy prep"))
        assertEquals(4, lines.size)
   }


    // ------------------------------------------------------ stock idempotency

    @Test
    fun `taking an already-taken dose does not decrement stock twice`() {
        val withStock = med(1, supplyLeft = 5)

        val first = InventoryService.applyTakeIfNew(null, withStock, today, isTaken = true)
        val second = InventoryService.applyTakeIfNew(null, first, today, isTaken = true)

        assertEquals(4, second.supplyDosesLeft)
        assertEquals(1, second.takenHistory.size)
   }


    @Test
    fun `un-take refunds stock exactly once`() {
        val taken = med(1, supplyLeft = 4).copy(
            takenHistory = HashMap(mapOf(today to LocalTime.NOON))
        )

        val undone = InventoryService.applyTakeIfNew(null, taken, today, isTaken = false)
        val undoneAgain = InventoryService.applyTakeIfNew(null, undone, today, isTaken = false)

        assertEquals(5, undoneAgain.supplyDosesLeft)
        assertTrue(undoneAgain.takenHistory.isEmpty())
   }


    @Test
    fun `stock tracking off leaves history behavior intact`() {
        val noSupply = med(1, supplyLeft = null)

        val taken = InventoryService.applyTakeIfNew(null, noSupply, today, isTaken = true)

        assertTrue(taken.takenHistory.containsKey(today))
        assertNull(taken.supplyDosesLeft)
   }


    @Test
    fun `ledger records takes and refunds with balances, bounded`() {
        var item = med(1, supplyLeft = 3)

        // One dose per date (the design); five consecutive days.
        List(5) { today.minusDays((4 - it).toLong())}
.forEach { d ->
            item = InventoryService.applyTakeIfNew(null, item, d, isTaken = true)
       }


        val takenEntries = item.supplyLedger.filter { it.kind == SupplyChangeKind.TAKEN}

        assertEquals(5, takenEntries.size)
        // Balances clamp at zero and stay traceable.
        assertEquals(listOf(2, 1, 0, 0, 0), takenEntries.map { it.balanceAfter}
)

        val refunded = InventoryService.applyTakeIfNew(null, item, today, isTaken = false)
        val refundEntry = refunded.supplyLedger.last()
        assertEquals(SupplyChangeKind.REFUND, refundEntry.kind)
        assertEquals(+1, refundEntry.delta)
        assertEquals(1, refundEntry.balanceAfter)
   }


    @Test
    fun `duplicate take appends no ledger entry`() {
        val withStock = med(1, supplyLeft = 5)

        val first = InventoryService.applyTakeIfNew(null, withStock, today, isTaken = true)
        val second = InventoryService.applyTakeIfNew(null, first, today, isTaken = true)

        assertEquals(first.supplyLedger.size, second.supplyLedger.size)
   }


    @Test
    fun `stats keeps separate same-name dose versions`() {
        val oldVersion = med(1, title = "Atorvastatin", groupId = 10L, history = hist(1))
            .copy(medicationId = "atorvastatin", doseAmount = "10", doseUnit = "mg")
        val newVersion = med(2, title = "Atorvastatin", groupId = 20L, history = hist(2))
            .copy(medicationId = "atorvastatin", doseAmount = "20", doseUnit = "mg")

        val stats = getMedicationStatsForMonth(YearMonth.of(2026, 9), listOf(oldVersion, newVersion))

        assertEquals(2, stats.size)
        assertEquals(setOf("10 mg", "20 mg"), stats.map { it.doseLabel}
.toSet())
   }


    @Test
    fun `dose and medication identity survive CSV round trip`() {
        val item = med(1).copy(
            medicationId = "sertraline-treatment",
            doseAmount = "5",
            doseUnit = "mL"
        )

        val restored = CsvPortability.parseCsv(CsvPortability.toCsv(listOf(item))).single()

        assertEquals("sertraline-treatment", restored.medicationId)
        assertEquals("5", restored.doseAmount)
        assertEquals("mL", restored.doseUnit)
   }


    @Test
    fun `new version boundary excludes old schedule after effective date`() {
        val old = med(1, start = today.minusDays(10), end = today.minusDays(1))
        val new = med(2, start = today, end = null)

        assertEquals(1, getMedicationStatsForMonth(YearMonth.of(2026, 9), listOf(old)).size)
        assertEquals(1, getMedicationStatsForMonth(YearMonth.of(2026, 9), listOf(new)).size)
   }


    @Test
    fun `dose edit is retained when explicitly saving existing version`() {
        val item = med(1).copy(doseAmount = "10", doseUnit = "mg")
        val request = noopRequest.copy(
            originalItem = item,
            doseAmount = "20",
            doseUnit = "mg",
            doseFieldsProvided = true
        )

        val plan = MedUpdatePlanner.plan(request, null, listOf(item)) as MedUpdatePlanner.Plan.Rebuild

        assertEquals("20", plan.entries.single().doseAmount)
        assertEquals("mg", plan.entries.single().doseUnit)
        assertEquals(item.takenHistory, plan.entries.single().takenHistory)
   }


    @Test
    fun `changing supply unit or units per dose triggers a rebuild`() {
        val item = med(1, supplyLeft = 30)
        val supply = InventoryEntry(
            dosesLeft = 30,
            dosesPerRefill = 30,
            lowThreshold = 4,
            unit = SupplyUnit.TABLET,
            unitsPerDose = 2
        )

        val plan = MedUpdatePlanner.plan(noopRequest.copy(originalItem = item), supply, listOf(item)) as MedUpdatePlanner.Plan.Rebuild

        assertEquals(SupplyUnit.TABLET, plan.entries.single().supplyUnit)
        assertEquals(2, plan.entries.single().supplyUnitsPerDose)
   }


    @Test
    fun `stock unit settings survive CSV round trip`() {
        val item = med(1, supplyLeft = 36).copy(
            supplyUnit = SupplyUnit.PUFF,
            supplyUnitsPerDose = 2,
            supplyEstimated = true
        )

        val restored = CsvPortability.parseCsv(CsvPortability.toCsv(listOf(item))).single()

        assertEquals(SupplyUnit.PUFF, restored.supplyUnit)
        assertEquals(2, restored.supplyUnitsPerDose)
        assertTrue(restored.supplyEstimated)
   }


    @Test
    fun `duplicate review flags overlapping same-dose schedules but not dose versions`() {
        val duplicateA = med(1, title = "Atorvastatin", groupId = 10L)
        val duplicateB = med(2, title = "Atorvastatin", groupId = 20L)
        val changedDose = duplicateB.copy(groupId = 30L, doseAmount = "20", doseUnit = "mg")

        val candidates = MedicationDuplicateReview.findCandidates(listOf(duplicateA, duplicateB, changedDose))

        assertEquals(1, candidates.size)
        assertEquals(2, candidates.single().items.size)
   }

}
