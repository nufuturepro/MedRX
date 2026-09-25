package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure (JVM-testable) planner for editing an existing item.
 *
 * Extracted from [MedViewModel.updateItem] so the rules that protect taken
 * history can be unit tested without an Android device:
 *
 *  1. An edit that changes nothing (except supply) must not recreate entries —
 *     recreation resets takenHistory and silently destroys logged doses.
 *  2. The editor edits ONE entry of a medication, but the medication is a GROUP
 *     of entries (one per daily time slot). A rebuild must cover every slot,
 *     keeping each slot's own schedule and taken history.
 *  3. A changed time carries its slot's taken history to the new time.
 *  4. A range edit keeps history-bearing fragments before/after the edited
 *     window; empty fragments (phantom schedules) are dropped, and history is
 *     trimmed to the window each fragment actually covers.
 *
 * The planner only computes; the caller applies the result (assigns real IDs,
 * cancels stale notifications, re-arms alarms, persists).
 */
object MedUpdatePlanner {

    /** Sentinel groupId on planned entries meaning "assign a fresh group id". */
    const val NEW_GROUP = -1L

    /** Not-a-range sentinel used by the caller's [Request.rangeStart]. */
    const val NOT_A_RANGE = -2L

    data class Request(
        val originalItem: MedData,
        val title: String,
        val iconName: String?,
        val colorCode: String?,
        val times: List<LocalTime>,
        val days: List<DayOfWeek>?,
        val notes: String?,
        val intervalGap: Int?,
        val notificationType: Int = 0,
        val freqLabel: String = "",
        val rangeStart: Long? = NOT_A_RANGE,
        val rangeEnd: Long? = NOT_A_RANGE,
        val selectedDate: LocalDate = LocalDate.now(),
        val symptomSeverity: SymptomSeverity? = null,
        val doseAmount: String? = null,
        val doseUnit: String? = null,
        val doseFieldsProvided: Boolean = false,
        val isPrn: Boolean? = null,
        val prnMaxPerDay: Int? = null,
        val prnMinIntervalHours: Int? = null
    )

    sealed class Plan {
        /** Nothing changed — do not touch anything. */
        object None : Plan()

        /** Only supply changed — apply it to the whole group in place. */
        object SupplyOnly : Plan()

        /**
         * Remove [removeIds] and add [entries]. Entries are fully built except:
         * `id` is a 0-based placeholder and `groupId` is either a real id to
         * reuse or [NEW_GROUP] (caller assigns a fresh one). History maps are
         * fresh copies; the caller must not share them with existing items.
         */
        data class Rebuild(
            val removeIds: Set<Long>,
            val entries: List<MedData>
        ) : Plan()
    }

    fun plan(request: Request, supply: InventoryEntry?, groupMembers: List<MedData>): Plan {
        val original = request.originalItem
        val isMedicine = original.type == ItemType.Medicine
        val isRangeUpdate = request.rangeStart != NOT_A_RANGE

        val relatedItems = if (original.groupId != null) {
            groupMembers.filter { it.groupId == original.groupId && it.type == original.type }
        } else {
            listOf(original)
        }

        // Rule 1: a no-op (or supply-only) save must not recreate anything.
        // The editor opens one schedule slot at a time. Siblings are retained
        // by the planner, so a save is time-unchanged when that opened slot did
        // not move, regardless of how many grouped slots exist.
        val timesUnchanged = request.times.size == 1 &&
                request.times.single() == original.creationTime &&
                relatedItems.map { it.creationTime }.distinct().size == relatedItems.size
        val effectiveIsPrn = request.isPrn ?: original.isPrn
        val daysUnchanged = if (effectiveIsPrn) true else when {
            request.days == null && original.recurrenceDays == null -> true
            request.days == null || original.recurrenceDays == null -> false
            else -> request.days.toSet() == original.recurrenceDays.toSet()
        }
        val intervalUnchanged = if (effectiveIsPrn) true else request.intervalGap == original.intervalGap
        val notifUnchanged = if (effectiveIsPrn) true else request.notificationType == original.notificationType
        val prnFieldsMatch = (request.isPrn == null || request.isPrn == original.isPrn) &&
                ((request.prnMaxPerDay ?: original.prnMaxPerDay) == original.prnMaxPerDay) &&
                ((request.prnMinIntervalHours ?: original.prnMinIntervalHours) == original.prnMinIntervalHours)
        val fieldsUnchanged = request.title == original.title &&
                request.iconName == original.iconName &&
                request.colorCode == original.colorCode &&
                request.notes == original.notes &&
                intervalUnchanged &&
                notifUnchanged &&
                prnFieldsMatch &&
                (!isMedicine || daysUnchanged) &&
                (!isMedicine || !request.doseFieldsProvided ||
                        ((request.doseAmount ?: "") == (original.doseAmount ?: "") &&
                                (request.doseUnit ?: "") == (original.doseUnit ?: "")))

        val supplyFieldsUnchanged = supply == null || (
            supply.unit == original.supplyUnit &&
                supply.unitsPerDose.coerceAtLeast(1) == original.supplyUnitsPerDose
            )
        val supplyDisabled = supply == null && original.supplyDosesLeft != null
        val groupSupplyUnchanged = supply == null || relatedItems.all { member ->
            supply.unit == member.supplyUnit &&
                    supply.unitsPerDose.coerceAtLeast(1) == member.supplyUnitsPerDose
        }

        if (timesUnchanged && daysUnchanged && fieldsUnchanged && supplyFieldsUnchanged && groupSupplyUnchanged && !supplyDisabled) {
            if (!isMedicine) return Plan.None
            // Distinguish "nothing changed at all" from "only the supply":
            // only the latter may touch the item (in place, via SupplyOnly).
            val effectiveStoredSupply = original.supplyDosesLeft?.let {
                InventoryEntry(
                    it,
                    original.supplyDosesPerRefill ?: 0,
                    original.supplyLowThreshold ?: 0,
                    original.supplyUnit,
                    original.supplyUnitsPerDose
                )
            }
            return if (supply != effectiveStoredSupply) Plan.SupplyOnly else Plan.None
        }

        // Rule 3: history follows its time slot; a brand-new time inherits the
        // history of the entry that was being edited. Skip records (reason +
        // note) are treated exactly like taken history so they survive edits.
        val historyByTime = relatedItems.associate { it.creationTime to it.takenHistory }
        val editedHistory = relatedItems.firstOrNull { it.id == original.id }?.takenHistory
        val skipByTime = relatedItems.associate { it.creationTime to it.skipHistory }
        val editedSkips = relatedItems.firstOrNull { it.id == original.id }?.skipHistory

        // Rule 2: the sheet edits one entry (times holds exactly that one time);
        // the rebuild covers every slot — the opened slot follows the sheet's
        // (possibly changed) time, siblings keep their own.
        val sheetTime = request.times.firstOrNull()
        val rebuildTimes = relatedItems.map {
            if (it.id == original.id) (sheetTime ?: it.creationTime) else it.creationTime
        }.distinct()

        fun buildEntry(
            base: MedData,
            time: LocalTime,
            creationDate: LocalDate,
            endDate: LocalDate?,
            history: Map<LocalDate, LocalTime>,
            skips: Map<LocalDate, SkipRecord>,
            reuseOldGroup: Boolean
        ): MedData {
            val effectiveIsPrnForBuild = request.isPrn ?: base.isPrn
            val effectiveRecurrence = if (effectiveIsPrnForBuild) null else request.days
            val effectiveGap = if (effectiveIsPrnForBuild) null else request.intervalGap
            val effectiveNotif = if (effectiveIsPrnForBuild) 0 else request.notificationType
            return base.copy(
            id = 0,
            groupId = if (reuseOldGroup) base.groupId else NEW_GROUP,
            title = request.title,
            iconName = request.iconName,
            colorCode = request.colorCode,
            creationTime = time,
            creationDate = creationDate,
            recurrenceDays = effectiveRecurrence,
            notes = request.notes,
            intervalGap = effectiveGap,
            notificationType = effectiveNotif,
            frequencyLabel = request.freqLabel,
            symptomSeverity = request.symptomSeverity,
            doseAmount = if (isMedicine && request.doseFieldsProvided) request.doseAmount else base.doseAmount,
            doseUnit = if (isMedicine && request.doseFieldsProvided) request.doseUnit else base.doseUnit,
            medicationId = if (isMedicine) {
                base.medicationId ?: original.medicationId ?: original.groupId?.toString() ?: original.id.toString()
            } else base.medicationId,
            endDate = endDate,
            supplyDosesLeft = supply?.dosesLeft,
            supplyDosesPerRefill = supply?.dosesPerRefill?.takeIf { it > 0 },
            supplyLowThreshold = supply?.lowThreshold,
            supplyUnit = supply?.unit ?: base.supplyUnit,
            supplyUnitsPerDose = supply?.unitsPerDose?.coerceAtLeast(1) ?: base.supplyUnitsPerDose,
            supplyEstimated = if (supply == null) base.supplyEstimated else supply.unit == SupplyUnit.SPRAY || supply.unit == SupplyUnit.PUFF,
            supplyAlertShown = false,
            isPrn = request.isPrn ?: base.isPrn,
            prnMaxPerDay = request.prnMaxPerDay ?: base.prnMaxPerDay,
            prnMinIntervalHours = request.prnMinIntervalHours ?: base.prnMinIntervalHours,
            prnUsages = base.prnUsages,
            takenHistory = HashMap(history),
            skipHistory = HashMap(skips)
        )
        }

        if (isMedicine && isRangeUpdate) {
            val editStart: LocalDate
            val editEnd: LocalDate?
            if (request.rangeStart == null && request.rangeEnd == null) {
                editStart = request.selectedDate
                editEnd = request.selectedDate
            } else if (request.rangeStart == -1L) {
                editStart = request.selectedDate
                editEnd = original.endDate
            } else {
                val startEpoch = requireNotNull(request.rangeStart) { "rangeStart must not be null here" }
                editStart = LocalDate.ofEpochDay(startEpoch / 86400000)
                editEnd =
                    if (request.rangeEnd != null && request.rangeEnd != NOT_A_RANGE)
                        LocalDate.ofEpochDay(request.rangeEnd / 86400000)
                    else editStart
            }

            val entries = mutableListOf<MedData>()

            // History-bearing fragment before the edited window.
            relatedItems.forEach { oldItem ->
                if (editStart.isAfter(oldItem.creationDate)) {
                    val earlier = oldItem.takenHistory.filterKeys { it.isBefore(editStart) }
                    val earlierSkips = oldItem.skipHistory.filterKeys { it.isBefore(editStart) }
                    // Rule 4: no phantom schedule fragments without history.
                    if (earlier.isNotEmpty() || earlierSkips.isNotEmpty()) {
                        val newEndDate = editStart.minusDays(1)
                        val finalEndDate =
                            if (oldItem.endDate != null && oldItem.endDate.isBefore(newEndDate))
                                oldItem.endDate
                            else newEndDate
                        entries += buildEntry(oldItem, oldItem.creationTime, oldItem.creationDate, finalEndDate, earlier, earlierSkips, reuseOldGroup = true)
                    }
                }
            }

            // The edited window itself, one entry per slot.
            rebuildTimes.forEach { time ->
                val window = (historyByTime[time] ?: editedHistory ?: emptyMap<LocalDate, LocalTime>())
                    .filterKeys { !it.isBefore(editStart) && (editEnd == null || !it.isAfter(editEnd)) }
                val skipWindow = (skipByTime[time] ?: editedSkips ?: emptyMap<LocalDate, SkipRecord>())
                    .filterKeys { !it.isBefore(editStart) && (editEnd == null || !it.isAfter(editEnd)) }
                val base = relatedItems.firstOrNull { it.creationTime == time } ?: original
                entries += buildEntry(base, time, editStart, editEnd, window, skipWindow, reuseOldGroup = false)
            }

            // History-bearing fragment after the edited window.
            if (editEnd != null) {
                val newCreationDate = editEnd.plusDays(1)
                relatedItems.forEach { oldItem ->
                    if (oldItem.endDate == null || oldItem.endDate.isAfter(editEnd)) {
                        val later = oldItem.takenHistory.filterKeys { it.isAfter(editEnd) }
                        val laterSkips = oldItem.skipHistory.filterKeys { it.isAfter(editEnd) }
                        if (later.isNotEmpty() || laterSkips.isNotEmpty()) {
                            val finalCreationDate =
                                if (oldItem.creationDate.isAfter(newCreationDate)) oldItem.creationDate
                                else newCreationDate
                            entries += buildEntry(oldItem, oldItem.creationTime, finalCreationDate, oldItem.endDate, later, laterSkips, reuseOldGroup = true)
                        }
                    }
                }
            }

            return Plan.Rebuild(relatedItems.map { it.id }.toSet(), entries)
        }

        // Plain (non-range) edit: rebuild every slot with its full history.
        val entries = rebuildTimes.map { time ->
            val base = relatedItems.firstOrNull { it.creationTime == time } ?: original
            buildEntry(
                base,
                time,
                original.creationDate,
                original.endDate,
                historyByTime[time] ?: editedHistory ?: emptyMap(),
                skipByTime[time] ?: editedSkips ?: emptyMap(),
                reuseOldGroup = false
            )
        }
        return Plan.Rebuild(relatedItems.map { it.id }.toSet(), entries)
    }
}
