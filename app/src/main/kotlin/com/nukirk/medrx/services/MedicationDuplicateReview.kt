package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import java.time.LocalDate

data class MedicationDuplicateCandidate(
    val items: List<MedData>,
    val overlappingFrom: LocalDate,
    val overlappingTo: LocalDate?
) {
    val signature: String get() = items.map { it.id }.sorted().joinToString("-")
}

object DuplicateReviewState {
    const val PREF_PREFIX = "med_duplicate_review_"
    const val REVIEWED = "reviewed"
    const val DISMISSED = "dismissed"
}

object MedicationDuplicateReview {
    fun reviewPreferenceKey(candidate: MedicationDuplicateCandidate): String =
        DuplicateReviewState.PREF_PREFIX + candidate.signature

    /** Flag truly overlapping records only; dose/form changes are distinct versions. */
    fun findCandidates(items: List<MedData>): List<MedicationDuplicateCandidate> {
        val buckets = items.filter { it.type == ItemType.Medicine && !it.isPrn }.groupBy {
            listOf(
                it.title.trim().lowercase(),
                it.creationTime,
                it.doseAmount.orEmpty(),
                it.doseUnit.orEmpty(),
                it.recurrenceDays.orEmpty().sortedBy { day -> day.value },
                it.intervalGap ?: 0,
                it.supplyUnit,
                it.supplyUnitsPerDose
            )
        }
        return buckets.values.flatMap { bucket ->
            val schedules: List<List<MedData>> = bucket
                .groupBy { it.groupId ?: it.id }
                .values
                .map { members -> members.toList() }
            buildList<MedicationDuplicateCandidate> {
                for (leftIndex in schedules.indices) {
                    for (rightIndex in (leftIndex + 1) until schedules.size) {
                        val leftSchedule = schedules[leftIndex]
                        val rightSchedule = schedules[rightIndex]
                        for (left in leftSchedule) {
                            for (right in rightSchedule) {
                                val start = maxOf(left.creationDate, right.creationDate)
                                val end = listOfNotNull(left.endDate, right.endDate).minOrNull()
                                if (end == null || !start.isAfter(end)) {
                                    add(MedicationDuplicateCandidate(listOf(left, right).sortedBy { it.creationDate }, start, end))
                                }
                            }
                        }
                    }
                }
            }
        }.distinctBy { it.signature }
            .sortedWith(compareBy({ it.items.first().title.lowercase() }, { it.overlappingFrom }))
    }
}
