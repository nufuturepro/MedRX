package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Correcting the time recorded on a dose that is already logged.
 *
 * The rule these tests protect: a time correction moves the record and the
 * matching ledger row, and nothing else — no stock movement, no extra ledger
 * rows, no change to whether the dose counts as taken or skipped.
 */
class DoseTimeEditTest {
    private val day = LocalDate.of(2026, 9, 23)
    private val laterDay = day.plusDays(1)

    private fun medicine(id: Long = 1, group: Long = 10, left: Int? = 30) = MedData(
        id = id,
        groupId = group,
        type = ItemType.Medicine,
        title = "Sertraline",
        creationDate = day.minusDays(30),
        creationTime = LocalTime.of(12, 0),
        supplyDosesLeft = left,
        supplyDosesPerRefill = 30,
        supplyLowThreshold = 4
    )

    @Test
    fun `take time edit rewrites history and the ledger row only`() {
        val taken = InventoryService.applyTakeIfNew(
            null,
            medicine(),
            day,
            isTaken = true,
            takenAt = LocalTime.of(11, 40)
        )
        assertEquals(29, taken.supplyDosesLeft)
        val ledgerSize = taken.supplyLedger.size

        val edited = InventoryService.applyTakenTimeEdit(
            taken,
            day,
            LocalTime.of(8, 10),
            now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
        )

        assertEquals(LocalTime.of(8, 10), edited.takenHistory[day])
        assertEquals(29, edited.supplyDosesLeft)
        assertEquals(ledgerSize, edited.supplyLedger.size)

        val row = edited.supplyLedger.last()
        assertEquals(SupplyChangeKind.TAKEN, row.kind)
        assertEquals(LocalTime.of(8, 10), row.time)
        assertEquals(-1, row.delta)
        assertEquals(29, row.balanceAfter)
    }

    @Test
    fun `legacy item without a taken row keeps its ledger untouched`() {
        val legacy = medicine().copy(
            takenHistory = HashMap<LocalDate, LocalTime>().apply { put(day, LocalTime.of(11, 40)) }
        )

        val edited = InventoryService.applyTakenTimeEdit(
            legacy,
            day,
            LocalTime.of(8, 10),
            now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
        )

        assertEquals(LocalTime.of(8, 10), edited.takenHistory[day])
        assertTrue("no row may be invented for old data", edited.supplyLedger.isEmpty())
    }

    @Test
    fun `a date that was never logged comes back unchanged`() {
        val item = medicine()
        assertSame(item, InventoryService.applyTakenTimeEdit(item, day, LocalTime.of(8, 10)))
    }

    @Test
    fun `the same time twice does not churn the store`() {
        val taken = InventoryService.applyTakeIfNew(
            null,
            medicine(),
            day,
            isTaken = true,
            takenAt = LocalTime.of(8, 10)
        )
        assertSame(
            taken,
            InventoryService.applyTakenTimeEdit(
                taken,
                day,
                LocalTime.of(8, 10),
                now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
            )
        )
    }

    @Test
    fun `a future time is clamped to now but a past day keeps any time`() {
        val today = LocalDate.of(2026, 9, 23)
        val now = LocalDateTime.of(today, LocalTime.of(9, 0))
        val logged = medicine().copy(
            takenHistory = HashMap<LocalDate, LocalTime>().apply { put(today, LocalTime.of(8, 0)) }
        )

        val clamped = InventoryService.applyTakenTimeEdit(logged, today, LocalTime.of(23, 59), now)
        assertEquals(LocalTime.of(9, 0), clamped.takenHistory[today])

        val past = medicine().copy(
            takenHistory = HashMap<LocalDate, LocalTime>()
                .apply { put(today.minusDays(2), LocalTime.of(8, 0)) }
        )
        val kept = InventoryService.applyTakenTimeEdit(past, today.minusDays(2), LocalTime.of(23, 59), now)
        assertEquals(LocalTime.of(23, 59), kept.takenHistory[today.minusDays(2)])
    }

    @Test
    fun `skip detail edit corrects the time and note and keeps the reason`() {
        val skipped = medicine().copy(
            skipHistory = HashMap<LocalDate, SkipRecord>().apply {
                put(day, SkipRecord(SkipReason.OTHER, LocalTime.of(21, 20), "rushed"))
            }
        )

        val edited = skipped.withSkipDetail(
            day,
            LocalTime.of(20, 0),
            "  missed the alarm  ",
            now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
        )

        val record = edited.skipHistory[day]!!
        assertEquals(LocalTime.of(20, 0), record.time)
        assertEquals("missed the alarm", record.note)
        assertEquals(SkipReason.OTHER, record.reason)
        assertEquals(30, edited.supplyDosesLeft)

        val cleared = edited.withSkipDetail(
            day,
            LocalTime.of(20, 0),
            "   ",
            now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
        )
        assertNull(cleared.skipHistory[day]!!.note)
        assertEquals(SkipReason.OTHER, cleared.skipHistory[day]!!.reason)
    }

    @Test
    fun `an unchanged skip detail returns the same item`() {
        val skipped = medicine().copy(
            skipHistory = HashMap<LocalDate, SkipRecord>().apply {
                put(day, SkipRecord(SkipReason.PROCEDURE_FASTING, LocalTime.of(8, 0), "fasting"))
            }
        )
        assertSame(
            skipped,
            skipped.withSkipDetail(
                day,
                LocalTime.of(8, 0),
                " fasting ",
                now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
            )
        )
    }

    @Test
    fun `a corrected ledger reaches every dose slot of the medicine`() {
        val items = mutableListOf(
            medicine(id = 1, group = 10),
            medicine(id = 2, group = 10)
        )
        val taken = InventoryService.applyTakeIfNew(
            null,
            items[0],
            day,
            isTaken = true,
            takenAt = LocalTime.of(11, 40)
        )
        items[0] = taken
        SupplyGroup.mirror(items, taken)
        assertEquals(LocalTime.of(11, 40), items[1].supplyLedger.last().time)

        val edited = InventoryService.applyTakenTimeEdit(
            items[0],
            day,
            LocalTime.of(8, 10),
            now = LocalDateTime.of(laterDay, LocalTime.of(9, 0))
        )
        items[0] = edited
        SupplyGroup.mirror(items, edited)

        assertEquals(LocalTime.of(8, 10), items[1].supplyLedger.last().time)
        assertEquals(29, items[1].supplyDosesLeft)
        assertTrue(
            "history stays per dose slot; only the supply block mirrors",
            items[1].takenHistory.isEmpty()
        )
    }
}
