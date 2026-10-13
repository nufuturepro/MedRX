package com.nukirk.medrx.services

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * Counts how often a single dose slot has been snoozed, keyed by
 * `"<itemId>:<epochDay>"`.
 *
 * Deliberately kept in SharedPreferences rather than on [MedData]: the count
 * only has to survive the gap between two alarms (the receiver's process may be
 * killed in between), while the durable record of the outcome is the skip entry
 * written to `skipHistory`. Keys for past days are pruned on every write so the
 * blob cannot grow without bound.
 */
object SnoozeTracker {

    private const val PREFS_NAME = "med_settings"
    private const val KEY_COUNTS = "snooze_counts"

    /** Increments and returns the new count for [itemId] on [date]. */
    fun increment(context: Context, itemId: Long, date: LocalDate): Int {
        val counts = load(context)
        val key = key(itemId, date)
        val next = (counts[key] ?: 0) + 1
        counts[key] = next
        store(context, counts, date)
        return next
    }

    /** Current count for [itemId] on [date]; zero when never snoozed. */
    fun count(context: Context, itemId: Long, date: LocalDate): Int =
        load(context)[key(itemId, date)] ?: 0

    /**
     * Forgets the counts for [itemIds] on [date]. Called whenever a dose is
     * logged or skipped, so a later un-take/un-skip starts with a clean slate.
     */
    fun clear(context: Context, itemIds: LongArray, date: LocalDate) {
        if (itemIds.isEmpty()) return
        val counts = load(context)
        var changed = false
        for (id in itemIds) {
            if (counts.remove(key(id, date)) != null) changed = true
        }
        if (changed) store(context, counts, date)
    }

    private fun key(itemId: Long, date: LocalDate): String = "$itemId:${date.toEpochDay()}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableMap<String, Int> {
        val raw = prefs(context).getString(KEY_COUNTS, null) ?: return mutableMapOf()
        val map = mutableMapOf<String, Int>()
        try {
            val json = JSONObject(raw)
            json.keys().forEach { k -> map[k] = json.optInt(k, 0) }
        } catch (_: Exception) {
            // Corrupt blob: start over rather than blocking reminders.
            return mutableMapOf()
        }
        return map
    }

    /** Persists [counts] without any entry older than [today]. */
    private fun store(context: Context, counts: Map<String, Int>, today: LocalDate) {
        val oldest = today.toEpochDay()
        val json = JSONObject()
        counts.forEach { (k, v) ->
            val day = k.substringAfter(':', "").toLongOrNull() ?: return@forEach
            if (day >= oldest) json.put(k, v)
        }
        prefs(context).edit().putString(KEY_COUNTS, json.toString()).apply()
    }
}
