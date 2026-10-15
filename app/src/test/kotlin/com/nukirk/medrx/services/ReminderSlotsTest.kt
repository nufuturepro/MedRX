package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Doses that fall due together share one reminder, posted under the lowest
 * member id. Resolving any one of them has to take that reminder down.
 *
 * The bug these tests pin down: skipping (or taking) a dose that was *not* the
 * lowest member of its slot cancelled only that dose's own id, so the group's
 * notification stayed in the shade — and because the full-screen reminder is
 * posted as ongoing, the user could not swipe it away either.
 */
class ReminderSlotsTest {

    private val day = LocalDate.of(2026, 10, 15)
    private val slot = LocalTime.of(13, 0)
    private val other = LocalTime.of(21, 30)

    private fun medicine(
        id: Long,
        time: LocalTime = slot,
        taken: LocalDate? = null,
        skipped: LocalDate? = null,
        createdDaysAgo: Long = 30,
        endDate: LocalDate? = null,
        recurrenceDays: List<DayOfWeek>? = null,
        intervalGap: Int? = null,
        isPrn: Boolean = false,
        type: ItemType = ItemType.Medicine
    ) = MedData(
        id = id,
        groupId = id + 7,
        type = type,
        title = "Med$id",
        creationDate = day.minusDays(createdDaysAgo),
        creationTime = time,
        takenHistory = taken?.let { HashMap(mapOf(it to LocalTime.of(13, 5))) } ?: HashMap(),
        skipHistory = skipped?.let {
            HashMap(mapOf(it to SkipRecord(reason = SkipReason.OTHER, time = LocalTime.of(13, 6))))
        } ?: HashMap(),
        recurrenceDays = recurrenceDays,
        endDate = endDate,
        intervalGap = intervalGap,
        isPrn = isPrn
    )

    // Leader 100, members 200/300 fall due in the same slot; 400 is a late dose.
    private val group = listOf(
        medicine(100),
        medicine(200),
        medicine(300),
        medicine(400, time = other)
    )

    @Test
    fun `resolving a non-leader dose still cancels the group's reminder`() {
        val ids = ReminderSlots.reminderIds(listOf(200L), group)

        // Posted under the lowest member id…
        assertTrue("leader id must be cancelled", ids.contains(100))
        // …and every member id is cancelled too, so no sibling notification
        // can outlive the dose that was resolved.
        assertEquals(listOf(100, 200, 300), ids.sorted())
    }

    @Test
    fun `a different dose time is not part of the slot`() {
        val ids = ReminderSlots.reminderIds(listOf(200L), group)

        assertFalse("the 21:30 dose shares no reminder", ids.contains(400))
    }

    @Test
    fun `the resolved dose's own id always counts, even when it is gone from the store`() {
        val survivors = group.filterNot { it.id == 100L }

        val ids = ReminderSlots.reminderIds(listOf(100L), survivors)

        // A dose deleted today still owns the notification it was posted under.
        assertTrue(ids.contains(100))
    }

    @Test
    fun `a dose is due until it is taken or skipped`() {
        assertTrue(ReminderSlots.isDueOn(medicine(100), day))
        assertFalse(ReminderSlots.isDueOn(medicine(100, taken = day), day))
        assertFalse(ReminderSlots.isDueOn(medicine(100, skipped = day), day))
        // Yesterday's resolution does not resolve today.
        assertTrue(ReminderSlots.isDueOn(medicine(100, skipped = day.minusDays(1)), day))
    }

    @Test
    fun `as-needed doses are never due for a reminder`() {
        assertFalse(ReminderSlots.isDueOn(medicine(100, isPrn = true), day))
    }

    @Test
    fun `an ended or off-schedule dose is not due`() {
        assertFalse(ReminderSlots.isScheduledFor(medicine(100, endDate = day.minusDays(1)), day))
        assertFalse(ReminderSlots.isScheduledFor(medicine(100, recurrenceDays = listOf(DayOfWeek.MONDAY)), day))
        assertFalse(ReminderSlots.isScheduledFor(medicine(100, intervalGap = 4), day))
        assertTrue(ReminderSlots.isScheduledFor(medicine(100, recurrenceDays = listOf(day.dayOfWeek)), day))
    }

    @Test
    fun `a slot stays due while any member is unresolved`() {
        val halfDone = listOf(medicine(100, taken = day), medicine(200))

        assertTrue(ReminderSlots.slotStillDue(halfDone, slot, day))
        assertFalse(ReminderSlots.slotStillDue(halfDone.map { it.copy(takenHistory = HashMap(mapOf(day to LocalTime.of(13, 5)))) }, slot, day))
    }

    @Test
    fun `nothing is swept while a dose still needs its reminder`() {
        assertTrue(ReminderSlots.staleReminderIds(group, day).isEmpty())

        // Taking the late dose resolves it, so only its own reminder is swept —
        // the 13:00 slot still has doses to remind about.
        val afterLateDose = group.map { if (it.id == 400L) medicine(400, time = other, taken = day) else it }
        assertEquals(listOf(400), ReminderSlots.staleReminderIds(afterLateDose, day))
    }

    @Test
    fun `a slot with no due dose is swept, including doses that vanished from it`() {
        // Every dose of the 13:00 slot resolved, the late one still pending.
        val resolved = listOf(
            medicine(100, taken = day),
            medicine(200, skipped = day),
            medicine(300, taken = day),
            medicine(400, time = other)
        )

        val stale = ReminderSlots.staleReminderIds(resolved, day)

        assertEquals(listOf(100, 200, 300), stale)
    }

    @Test
    fun `an archived dose's reminder is swept`() {
        // The era ended yesterday: today's dose is gone, so a reminder still up
        // for it has nothing left to announce.
        val archived = listOf(medicine(100, endDate = day.minusDays(1)))

        assertEquals(listOf(100), ReminderSlots.staleReminderIds(archived, day))
    }
}
