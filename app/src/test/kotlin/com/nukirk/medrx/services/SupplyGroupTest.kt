package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SupplyGroupTest {

    private val day = LocalDate.of(2026, 10, 25)

    private fun medicine(
        id: Long,
        group: Long? = 10,
        left: Int? = 30,
        perRefill: Int? = 30
    ) = MedData(
        id = id,
        groupId = group,
        type = ItemType.Medicine,
        title = "Sertraline HCL",
        creationDate = day.minusDays(10),
        creationTime = LocalTime.of(12, 0),
        supplyDosesLeft = left,
        supplyDosesPerRefill = perRefill,
        supplyLowThreshold = 4,
        supplyUnit = SupplyUnit.TABLET
    )

    private fun twoSlots(left: Int = 30): MutableList<MedData> = mutableListOf(
        medicine(1, left = left),
        medicine(2, left = left)
    )

    @Test
    fun `refill adds one pack to the whole medication, not one per slot`() {
        val items = twoSlots()

        val updated = SupplyGroup.refill(items, 1, amount = 30, date = day)

        assertEquals(60, updated?.supplyDosesLeft)
        assertEquals(60, items.first { it.id == 2L }.supplyDosesLeft)
        // One refill, one ledger entry: the two slots must not add 60.
        assertEquals(1, updated!!.supplyLedger.count { it.kind == SupplyChangeKind.REFILL })
        assertEquals(30, updated.supplyLedger.last().delta)
        assertEquals(60, updated.supplyLedger.last().balanceAfter)
        assertEquals(updated.supplyLedger, items.first { it.id == 2L }.supplyLedger)
    }

    @Test
    fun `a take in one slot mirrors the lower balance to the other slot`() {
        val items = twoSlots()
        val taken = InventoryService.applyTakeIfNew(null, items[0], day, isTaken = true)
        items[0] = taken
        SupplyGroup.mirror(items, items[0])

        assertEquals(29, items[0].supplyDosesLeft)
        assertEquals(29, items[1].supplyDosesLeft)
        assertEquals(items[0].supplyLedger, items[1].supplyLedger)
    }

    @Test
    fun `exact count is recorded as a correction`() {
        val items = twoSlots(left = 29)

        val updated = SupplyGroup.setExact(items, 1, count = 5, date = day)

        assertEquals(5, updated?.supplyDosesLeft)
        assertEquals(5, items.first { it.id == 2L }.supplyDosesLeft)
        assertEquals(SupplyChangeKind.CORRECTION, updated!!.supplyLedger.last().kind)
        assertEquals(-24, updated.supplyLedger.last().delta)
        assertEquals(5, updated.supplyLedger.last().balanceAfter)
    }

    @Test
    fun `no-op corrections and refills write nothing`() {
        val items = twoSlots(left = 30)

        assertNull(SupplyGroup.setExact(items, 1, count = 30, date = day))
        assertNull(SupplyGroup.refill(items, 1, amount = 0, date = day))
        assertTrue(items.all { it.supplyLedger.isEmpty() })
    }

    @Test
    fun `untracked medication cannot be refilled`() {
        val items = mutableListOf(medicine(1, left = null))

        assertNull(SupplyGroup.refill(items, 1, amount = 30, date = day))
        assertNull(items[0].supplyDosesLeft)
    }

    @Test
    fun `a slot without a group stands alone`() {
        val items = mutableListOf(medicine(1, group = null, left = 2), medicine(2, left = 30))

        SupplyGroup.refill(items, 1, amount = 30, date = day)

        assertEquals(32, items[0].supplyDosesLeft)
        assertEquals(30, items[1].supplyDosesLeft)
        assertNotEquals(items[0].supplyLedger, items[1].supplyLedger)
    }

    @Test
    fun `members only includes medicine slots of the same group`() {
        val items = twoSlots() + medicine(3, group = 11) + MedData(
            id = 4,
            groupId = 10,
            type = ItemType.Event,
            title = "Headache",
            creationDate = day.minusDays(1)
        )

        val members = SupplyGroup.members(items, items[0]).map { it.id }.toSet()

        assertEquals(setOf(1L, 2L), members)
    }
}
