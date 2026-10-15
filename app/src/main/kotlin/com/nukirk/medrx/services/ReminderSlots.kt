package com.nukirk.medrx.services

import com.nukirk.medrx.ItemType
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Reminder slots: which doses share one reminder, and when that reminder stops
 * being legitimate.
 *
 * Doses that fall due together — same dose time, same day — are announced by a
 * single notification, posted under the *lowest* member id, because one alarm
 * cannot tell "Atorvastatin" from "Sertraline" apart on the lock screen. Taking
 * a dose back down therefore has to take down the notification the whole slot
 * shares: cancelling only the resolved dose's own id leaves the group's
 * notification behind, and because a full-screen reminder is posted as ongoing
 * the user cannot swipe it away either — it just sits in the shade.
 *
 * Pure and Android-free so the rules stay unit-testable on the JVM.
 */
object ReminderSlots {

    /**
     * Every id that the reminder for [itemIds] can be posted under: the
     * requested ids plus every stored dose sharing their dose time.
     *
     * The requested ids are always kept even when they are no longer in
     * [items] — a dose deleted today still owns the notification that was
     * posted under its id.
     */
    fun slotMembers(itemIds: Collection<Long>, items: List<MedData>): Set<Long> {
        if (itemIds.isEmpty()) return emptySet()
        val requested = itemIds.toSet()
        val slots = items.filter { it.id in requested }.map { it.creationTime }.toSet()
        val stored = items
            .filter { it.type == ItemType.Medicine && it.creationTime in slots }
            .map { it.id }
        return requested + stored
    }

    /** Notification ids the reminder for [itemIds] can be posted under. */
    fun reminderIds(itemIds: Collection<Long>, items: List<MedData>): List<Int> =
        slotMembers(itemIds, items).map { it.toInt() }

    /** True when [date] is a day the dose [item] is scheduled for. */
    fun isScheduledFor(item: MedData, date: LocalDate): Boolean {
        if (item.isPrn) return false
        if (date.isBefore(item.creationDate)) return false
        if (item.endDate != null && date.isAfter(item.endDate)) return false
        if (!item.recurrenceDays.isNullOrEmpty() && !item.recurrenceDays.contains(date.dayOfWeek)) {
            return false
        }
        if (item.intervalGap != null) {
            val daysBetween = ChronoUnit.DAYS.between(item.creationDate, date)
            if (daysBetween % item.intervalGap != 0L) return false
        }
        return true
    }

    /**
     * True while [item] still needs a reminder on [date]: a scheduled medicine
     * that has neither been taken nor skipped that day.
     */
    fun isDueOn(item: MedData, date: LocalDate): Boolean =
        item.type == ItemType.Medicine &&
                isScheduledFor(item, date) &&
                !item.takenHistory.containsKey(date) &&
                !item.skipHistory.containsKey(date)

    /**
     * True while at least one dose of the slot at [slotTime] still needs
     * reminding on [date].
     */
    fun slotStillDue(items: List<MedData>, slotTime: LocalTime, date: LocalDate): Boolean =
        items.any { it.creationTime == slotTime && isDueOn(it, date) }

    /**
     * Ids of reminders that no longer stand for a dose the user still has to
     * act on, and so have to be taken down on [date].
     *
     * This is the safety net for the paths that change or remove a dose without
     * resolving it — a schedule edit, a new version, an archive — where a
     * reminder would otherwise outlive the dose it announced and, being
     * ongoing, refuse to be dismissed.
     */
    fun staleReminderIds(items: List<MedData>, date: LocalDate): List<Int> =
        items.filter { it.type == ItemType.Medicine }
            .map { it.creationTime }
            .distinct()
            .filterNot { slotStillDue(items, it, date) }
            .flatMap { slot ->
                reminderIds(items.filter { it.creationTime == slot }.map { it.id }, items)
            }
            .distinct()
            .sorted()
}
