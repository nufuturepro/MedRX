package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType

/**
 * Flat CSV report of every recorded skipped dose — one row per
 * (medication, date) skip — meant to be shared with a doctor.
 *
 * Fully RFC 4180 compliant (same quoting rules as [CsvPortability]).
 */
object SkipReport {

    val HEADERS = listOf("medication", "date", "time", "reason", "note")

    fun toCsv(items: List<MedData>): String {
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",")).append("\r\n")

        items.asSequence()
            .filter { it.type == ItemType.Medicine }
            .flatMap { med ->
                med.skipHistory.entries.asSequence()
                    .map { (date, rec) -> Triple(med.title, date, rec) }
            }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .forEach { (title, date, rec) ->
                val cells = listOf(
                    title,
                    date.toString(),
                    rec.time.toString(),
                    skipReasonName(rec.reason),
                    rec.note ?: ""
                )
                sb.append(cells.joinToString(",") { encodeCell(it) }).append("\r\n")
            }
        return sb.toString()
    }

    /** Human-readable reason for the report. */
    fun skipReasonName(reason: SkipReason): String = when (reason) {
        SkipReason.ADVERSE_REACTION -> "Allergic / adverse reaction"
        SkipReason.DOCTOR_DIRECTED -> "Doctor / clinic directed"
        SkipReason.PROCEDURE_FASTING -> "Upcoming procedure / fasting"
        SkipReason.VITALS_OUT_OF_RANGE -> "Vitals out of range"
        SkipReason.DOUBLE_DOSE_PROTECTION -> "Double dose protection"
        SkipReason.ACUTE_ILLNESS -> "Acute illness / vomiting"
        SkipReason.SUPPLY_MISSING -> "Missing supply / expired"
        SkipReason.SNOOZED_AWAY -> "Snoozed away (reminder limit)"
        SkipReason.DELAYED -> "Delayed to the next dose"
        SkipReason.OTHER -> "Patient discretion / other"
    }

    private fun encodeCell(raw: String?): String {
        val v = raw ?: ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }
}
