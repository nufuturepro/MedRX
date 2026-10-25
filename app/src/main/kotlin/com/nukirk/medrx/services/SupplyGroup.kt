package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import java.time.LocalDate
import java.time.LocalTime

/**
 * One medication, one stock count.
 *
 * A medicine with several daily times is stored as several [MedData] slots.
 * The stock belongs to the medication, not to the slot: refilling adds one pack
 * (not one per slot), and a dose taken from one slot lowers the number every
 * slot shows. Only the supply block is mirrored — history, schedule, notes and
 * ledger ordering stay per slot; the ledger itself is shared so any slot can
 * explain the current balance.
 *
 * Pure and Android-free so the arithmetic stays unit-testable on the JVM.
 */
object SupplyGroup {

    /** Every slot that shares [item]'s stock (just [item] when it has no group). */
    fun members(items: List<MedData>, item: MedData): List<MedData> {
        if (item.type != ItemType.Medicine) return listOf(item)
        val groupId = item.groupId ?: return listOf(item)
        val same = items.filter { it.type == ItemType.Medicine && it.groupId == groupId }
        return same.ifEmpty { listOf(item) }
    }

    /**
     * Copies [source]'s supply block onto every slot of its group. Returns true
     * when at least one slot changed.
     */
    fun mirror(items: MutableList<MedData>, source: MedData): Boolean {
        var changed = false
        members(items, source).forEach { member ->
            val index = items.indexOfFirst { it.id == member.id }
            if (index == -1) return@forEach
            val current = items[index]
            val synced = current.copy(
                supplyDosesLeft = source.supplyDosesLeft,
                supplyDosesPerRefill = source.supplyDosesPerRefill,
                supplyLowThreshold = source.supplyLowThreshold,
                supplyUnit = source.supplyUnit,
                supplyUnitsPerDose = source.supplyUnitsPerDose,
                supplyEstimated = source.supplyEstimated,
                supplyAlertShown = source.supplyAlertShown,
                supplyLedger = source.supplyLedger.takeIf { it.isNotEmpty() } ?: current.supplyLedger
            )
            if (synced != current) {
                items[index] = synced
                changed = true
            }
        }
        return changed
    }

    /**
     * Adds [amount] (default: the configured per-refill size) to the
     * medication's single balance once, then mirrors it to every slot.
     * Returns the updated item, or null when tracking is off or there is
     * nothing to add.
     */
    fun refill(
        items: MutableList<MedData>,
        itemId: Long,
        amount: Int? = null,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now()
    ): MedData? {
        val index = items.indexOfFirst { it.id == itemId }
        if (index == -1) return null
        val item = items[index]
        val left = item.supplyDosesLeft ?: return null
        val size = amount ?: item.supplyDosesPerRefill ?: return null
        if (size <= 0) return null
        val balance = left + size
        val updated = InventoryService.logSupplyChange(
            item.copy(supplyDosesLeft = balance, supplyAlertShown = false),
            SupplyChangeKind.REFILL,
            delta = size,
            balanceAfter = balance,
            date = date,
            time = time,
            unit = item.supplyUnit
        )
        items[index] = updated
        mirror(items, updated)
        return items[index]
    }

    /**
     * Stores a counted balance exactly as typed — the correction path for a
     * bottle that was mis-counted. Recorded as a CORRECTION (or REFILL when the
     * difference is a whole number of packs) so the ledger still explains it.
     */
    fun setExact(
        items: MutableList<MedData>,
        itemId: Long,
        count: Int,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now()
    ): MedData? {
        val index = items.indexOfFirst { it.id == itemId }
        if (index == -1) return null
        val item = items[index]
        val previous = item.supplyDosesLeft ?: return null
        val safe = count.coerceAtLeast(0)
        if (safe == previous) return null
        val delta = safe - previous
        val perRefill = item.supplyDosesPerRefill
        val kind = when {
            item.supplyDosesLeft == null -> SupplyChangeKind.INITIAL
            delta > 0 && perRefill != null && perRefill > 0 && delta % perRefill == 0 ->
                SupplyChangeKind.REFILL
            else -> SupplyChangeKind.CORRECTION
        }
        val updated = InventoryService.logSupplyChange(
            item.copy(supplyDosesLeft = safe, supplyAlertShown = false),
            kind,
            delta = delta,
            balanceAfter = safe,
            date = date,
            time = time,
            unit = item.supplyUnit
        )
        items[index] = updated
        mirror(items, updated)
        return items[index]
    }
}
