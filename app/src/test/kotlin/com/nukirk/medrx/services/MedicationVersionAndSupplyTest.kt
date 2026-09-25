package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MedicationVersionAndSupplyTest {
    private val day = LocalDate.of(2026, 9, 23)

    private fun medicine(
        id: Long,
        group: Long,
        start: LocalDate = day.minusDays(10),
        end: LocalDate? = null,
        amount: String? = null,
        unit: String? = null,
        supplyUnit: SupplyUnit = SupplyUnit.SPRAY,
        left: Int? = 100
    ) = MedData(
        id = id,
        groupId = group,
        type = ItemType.Medicine,
        title = "Nasal spray",
        creationDate = start,
        creationTime = LocalTime.of(8, 0),
        endDate = end,
        doseAmount = amount,
        doseUnit = unit,
        supplyDosesLeft = left,
        supplyDosesPerRefill = 120,
        supplyLowThreshold = 10,
        supplyUnit = supplyUnit,
        supplyUnitsPerDose = 2,
        supplyEstimated = supplyUnit == SupplyUnit.SPRAY || supplyUnit == SupplyUnit.PUFF
    )

    @Test
    fun `priming decreases estimated stock but not adherence`() {
        val original = medicine(1, 10)
        val primed = InventoryService.applySupplyUse(null, original, SupplyChangeKind.PRIMING, 3, day)

        assertEquals(97, primed.supplyDosesLeft)
        assertTrue(primed.supplyEstimated)
        assertTrue(primed.takenHistory.isEmpty())
        assertTrue(primed.skipHistory.isEmpty())
        assertEquals(SupplyChangeKind.PRIMING, primed.supplyLedger.last().kind)
        assertEquals(-3, primed.supplyLedger.last().delta)
        assertEquals(SupplyUnit.SPRAY, primed.supplyLedger.last().unit)
    }

    @Test
    fun `waste reduces stock only and quantity cannot overdraw`() {
        val original = medicine(1, 10, left = 2)
        val waste = InventoryService.applySupplyUse(null, original, SupplyChangeKind.WASTE, 2, day)
        val rejected = InventoryService.applySupplyUse(null, waste, SupplyChangeKind.WASTE, 1, day)

        assertEquals(0, waste.supplyDosesLeft)
        assertEquals(waste, rejected)
        assertTrue(waste.takenHistory.isEmpty())
        assertTrue(waste.skipHistory.isEmpty())
    }

    @Test
    fun `one scheduled spray administration consumes configured actuations`() {
        val original = medicine(1, 10)
        val taken = InventoryService.applyTakeIfNew(null, original, day, true, LocalTime.NOON)

        assertEquals(98, taken.supplyDosesLeft)
        assertEquals(SupplyUnit.SPRAY, taken.supplyLedger.last().unit)
        assertTrue(taken.takenHistory.containsKey(day))
        assertEquals(-2, taken.supplyLedger.last().delta)
    }

    @Test
    fun `duplicate candidates require same dose identity and actual overlapping dates`() {
        val original = medicine(1, 1, start = day, end = day)
        val duplicate = medicine(2, 2, start = day, end = day)
        val changedDose = medicine(3, 3, amount = "2", unit = "mg")
        val nonOverlapping = medicine(4, 4, start = day.plusDays(1), end = day.plusDays(4))
        val endedBefore = medicine(5, 5, start = day.minusDays(9), end = day.minusDays(2))

        val candidates = MedicationDuplicateReview.findCandidates(
            listOf(original, duplicate, changedDose, nonOverlapping, endedBefore)
        )

        assertEquals(1, candidates.size)
        assertEquals(setOf(1L, 2L), candidates.single().items.map { it.id }.toSet())
        assertTrue(MedicationDuplicateReview.reviewPreferenceKey(candidates.single()).startsWith("med_duplicate_review_"))
    }

    @Test
    fun `priming is rejected for non-spray inventory`() {
        val tablet = medicine(1, 1, supplyUnit = SupplyUnit.TABLET)
        val unchanged = InventoryService.applySupplyUse(null, tablet, SupplyChangeKind.PRIMING, 1, day)

        assertEquals(tablet, unchanged)
    }

    @Test
    fun `invalid stock use quantities are rejected`() {
        val item = medicine(1, 1)

        assertThrows(IllegalArgumentException::class.java) {
            InventoryService.applySupplyUse(null, item, SupplyChangeKind.WASTE, 0, day)
        }
        assertThrows(IllegalArgumentException::class.java) {
            InventoryService.applySupplyUse(null, item, SupplyChangeKind.TAKEN, 1, day)
        }
    }

    @Test
    fun `tablet supply is not marked estimated`() {
        val tablet = medicine(1, 1, supplyUnit = SupplyUnit.TABLET)
        assertFalse(tablet.supplyEstimated)
    }
}
