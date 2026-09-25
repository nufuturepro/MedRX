package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import java.time.LocalDate
import java.time.LocalTime

/**
 * Universal CSV interchange format for medicines and events.
 *
 * One row per MedData entry; rows sharing a group_id belong to the same schedule
 * group. The format is deliberately plain so it can be edited in any spreadsheet
 * application:
 *
 *   id,group_id,type,title,icon_name,color_code,frequency_label,creation_date,
 *   creation_time,taken_dates,taken_times,recurrence_days,end_date,interval_days,
 *   notes,display_order,category,notification_type
 *
 * - type: Medicine | Event | Illness | Symptom
 * - dates/times are ISO (2026-08-15, 12:00)
 * - taken_dates and taken_times are parallel |-separated lists, e.g.
 *   "2026-08-15|2026-08-16" and "12:10|12:31". A missing time falls back to 00:00.
 * - recurrence_days: |-separated day names, e.g. "MONDAY|FRIDAY" (empty = daily)
 * - interval_days: repeat every N days (empty = not interval-based)
 *
 * Fully RFC 4180 compliant: cells containing commas, quotes or newlines are
 * quoted, and embedded quotes are doubled.
 */
object CsvPortability {

    val HEADERS = listOf(
        "id", "group_id", "type", "title", "icon_name", "color_code",
        "frequency_label", "creation_date", "creation_time",
        "taken_dates", "taken_times", "recurrence_days", "end_date",
        "interval_days", "notes", "display_order", "category", "notification_type",
        "supply_doses_left", "supply_refill_size", "supply_low_threshold",
        "skipped_dates", "skipped_reasons", "skipped_times", "skipped_notes",
        "symptom_severity", "medication_id", "dose_amount", "dose_unit",
        "supply_unit", "supply_units_per_dose", "supply_estimated",
        "is_prn", "prn_max_per_day", "prn_min_hours", "prn_usages"
    )

    private const val LIST_SEPARATOR = "|"

    fun toCsv(items: List<MedData>): String {
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",")).append("\r\n")
        items.forEach { m ->
            val dates = m.takenHistory.keys.sorted()
            val skips = m.skipHistory.keys.sorted()
            val cells = listOf(
                m.id.toString(),
                m.groupId?.toString() ?: "",
                m.type.name,
                m.title,
                m.iconName ?: "",
                m.colorCode ?: "",
                m.frequencyLabel ?: "",
                m.creationDate.toString(),
                m.creationTime.toString(),
                dates.joinToString(LIST_SEPARATOR),
                dates.joinToString(LIST_SEPARATOR) {
                    (m.takenHistory[it] ?: LocalTime.MIDNIGHT).toString()
                },
                m.recurrenceDays?.joinToString(LIST_SEPARATOR) { it.name } ?: "",
                m.endDate?.toString() ?: "",
                m.intervalGap?.toString() ?: "",
                m.notes ?: "",
                m.displayOrder.toString(),
                m.category ?: "",
                m.notificationType.toString(),
                m.supplyDosesLeft?.toString() ?: "",
                m.supplyDosesPerRefill?.toString() ?: "",
                m.supplyLowThreshold?.toString() ?: "",
                skips.joinToString(LIST_SEPARATOR),
                skips.joinToString(LIST_SEPARATOR) {
                    (m.skipHistory[it]?.reason ?: SkipReason.OTHER).name
                },
                skips.joinToString(LIST_SEPARATOR) {
                    (m.skipHistory[it]?.time ?: LocalTime.MIDNIGHT).toString()
                },
                skips.joinToString(LIST_SEPARATOR) { m.skipHistory[it]?.note ?: "" },
                m.symptomSeverity?.name ?: "",
                m.medicationId ?: "",
                m.doseAmount ?: "",
                m.doseUnit ?: "",
                m.supplyUnit.name,
                m.supplyUnitsPerDose.toString(),
                m.supplyEstimated.toString(),
                m.isPrn.toString(),
                m.prnMaxPerDay?.toString() ?: "",
                m.prnMinIntervalHours?.toString() ?: "",
                m.prnUsages.joinToString("|") { "${it.date}T${it.time}:${it.quantity}" }
            )
            sb.append(cells.joinToString(",") { encodeCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    fun parseCsv(text: String): List<MedData> {
        val rows = parseRows(text)
        if (rows.isEmpty()) return emptyList()

        val header = rows[0].map { it.trim().lowercase() }
        fun col(name: String) = header.indexOf(name)
        // id/type are optional (auto-generated / default to Medicine); the rest are
        // the minimum needed to place an entry on the calendar.
        val required = listOf("title", "creation_date", "creation_time")
        val missing = required.filter { col(it) == -1 }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("CSV is missing required columns: ${missing.joinToString(", ")}")
        }

        val items = mutableListOf<MedData>()
        rows.drop(1).forEachIndexed { ri, cells ->
            fun cell(name: String): String {
                val i = col(name)
                return if (i == -1 || i >= cells.size) "" else cells[i].trim()
            }
            try {
                val rawType = cell("type")
                val type = if (rawType.isEmpty()) ItemType.Medicine
                else ItemType.entries.firstOrNull { it.name.equals(rawType, ignoreCase = true) }
                    ?: throw IllegalArgumentException("unknown type '$rawType'")

                val dates = cell("taken_dates").split(LIST_SEPARATOR).map { it.trim() }
                    .filter { it.isNotEmpty() }
                val times = cell("taken_times").split(LIST_SEPARATOR).map { it.trim() }
                val history = HashMap<LocalDate, LocalTime>()
                dates.forEachIndexed { di, d ->
                    history[LocalDate.parse(d)] = times.getOrNull(di)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { LocalTime.parse(it) }
                        ?: LocalTime.MIDNIGHT
                }

                val recDays = cell("recurrence_days").split(LIST_SEPARATOR)
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .map { parseDayOfWeek(it) }

                val skipDates = cell("skipped_dates").split(LIST_SEPARATOR).map { it.trim() }
                    .filter { it.isNotEmpty() }
                val skipReasons = cell("skipped_reasons").split(LIST_SEPARATOR).map { it.trim() }
                val skipTimes = cell("skipped_times").split(LIST_SEPARATOR).map { it.trim() }
                val skipNotes = cell("skipped_notes").split(LIST_SEPARATOR)
                val skips = HashMap<LocalDate, SkipRecord>()
                skipDates.forEachIndexed { si, d ->
                    val reason = try {
                        SkipReason.valueOf(skipReasons.getOrNull(si) ?: "")
                    } catch (e: Exception) {
                        SkipReason.OTHER
                    }
                    skips[LocalDate.parse(d)] = SkipRecord(
                        reason = reason,
                        time = skipTimes.getOrNull(si)?.takeIf { it.isNotEmpty() }
                            ?.let { LocalTime.parse(it) } ?: LocalTime.MIDNIGHT,
                        note = skipNotes.getOrNull(si)?.ifEmpty { null }
                    )
                }

                items.add(
                    MedData(
                        id = cell("id").toLongOrNull() ?: System.nanoTime(),
                        groupId = cell("group_id").toLongOrNull(),
                        type = type,
                        title = cell("title"),
                        iconName = cell("icon_name").ifEmpty { null },
                        colorCode = cell("color_code").ifEmpty { null },
                        frequencyLabel = cell("frequency_label").ifEmpty { null },
                        creationDate = LocalDate.parse(cell("creation_date")),
                        creationTime = LocalTime.parse(cell("creation_time")),
                        takenHistory = history,
                        recurrenceDays = recDays.ifEmpty { null },
                        endDate = cell("end_date").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
                        notes = cell("notes").ifEmpty { null },
                        displayOrder = cell("display_order").toIntOrNull() ?: 0,
                        intervalGap = cell("interval_days").toIntOrNull(),
                        category = cell("category").ifEmpty { null },
                        notificationType = cell("notification_type").toIntOrNull() ?: 0,
                        supplyDosesLeft = cell("supply_doses_left").toIntOrNull(),
                        supplyDosesPerRefill = cell("supply_refill_size").toIntOrNull(),
                        supplyLowThreshold = cell("supply_low_threshold").toIntOrNull(),
                        skipHistory = skips,
                        symptomSeverity = cell("symptom_severity").takeIf { it.isNotEmpty() }?.let {
                            try {
                                SymptomSeverity.valueOf(it)
                            } catch (e: Exception) {
                                null
                            }
                        },
                        medicationId = cell("medication_id").takeIf { it.isNotEmpty() }
                            ?: cell("group_id").takeIf { it.isNotEmpty() },
                        doseAmount = cell("dose_amount").takeIf { it.isNotEmpty() },
                        doseUnit = cell("dose_unit").takeIf { it.isNotEmpty() },
                        supplyUnit = try { SupplyUnit.valueOf(cell("supply_unit").ifEmpty { "DOSE" }) } catch (_: Exception) { SupplyUnit.DOSE },
                        supplyUnitsPerDose = (cell("supply_units_per_dose").toIntOrNull() ?: 1).coerceAtLeast(1),
                        supplyEstimated = cell("supply_estimated").toBooleanStrictOrNull() ?: false,
                        isPrn = cell("is_prn").toBooleanStrictOrNull() ?: false,
                        prnMaxPerDay = cell("prn_max_per_day").toIntOrNull(),
                        prnMinIntervalHours = cell("prn_min_hours").toIntOrNull(),
                        prnUsages = cell("prn_usages").split("|").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { raw ->
                            try {
                                val lastColon = raw.lastIndexOf(":")
                                val qty = if (lastColon != -1) raw.substring(lastColon + 1).toIntOrNull() ?: 1 else 1
                                val dt = if (lastColon != -1) raw.substring(0, lastColon) else raw
                                val tIdx = dt.indexOf("T")
                                val d = LocalDate.parse(dt.substring(0, tIdx))
                                val tm = LocalTime.parse(dt.substring(tIdx + 1))
                                PrnUse(d, tm, qty.coerceAtLeast(1))
                            } catch (_: Exception) { null }
                        }
                    )
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("CSV row ${ri + 2}: ${e.message}")
            } catch (e: Exception) {
                throw IllegalArgumentException("CSV row ${ri + 2}: ${e.message ?: "invalid value"}")
            }
        }
        return items
    }

    private fun parseDayOfWeek(raw: String): java.time.DayOfWeek =
        java.time.DayOfWeek.valueOf(raw.uppercase())

    private fun encodeCell(raw: String?): String {
        val v = raw ?: ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v
    }

    /** Character-stream RFC 4180 parser (handles quoted cells spanning multiple lines). */
    private fun parseRows(text: String): List<List<String>> {
        val s = if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
        val rows = mutableListOf<List<String>>()
        var cell = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < s.length && s[i + 1] == '"' -> {
                        cell.append('"'); i++
                    }

                    c == '"' -> inQuotes = false
                    else -> cell.append(c)
                }

                c == '"' -> inQuotes = true
                c == ',' -> {
                    row.add(cell.toString())
                    cell = StringBuilder()
                }

                c == '\r' -> {
                    if (i + 1 < s.length && s[i + 1] == '\n') i++
                    row.add(cell.toString())
                    cell = StringBuilder()
                    rows.add(row)
                    row = mutableListOf()
                }

                c == '\n' -> {
                    row.add(cell.toString())
                    cell = StringBuilder()
                    rows.add(row)
                    row = mutableListOf()
                }

                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        return rows
    }
}
