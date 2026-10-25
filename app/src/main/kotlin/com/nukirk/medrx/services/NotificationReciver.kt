package com.nukirk.medrx.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nukirk.medrx.AlarmActivity
import com.nukirk.medrx.ItemType
import com.nukirk.medrx.MainActivity
import com.nukirk.medrx.PREF_FULL_SCREEN_ALARM
import com.nukirk.medrx.PREF_SHOW_NOTIFICATIONS
import com.nukirk.medrx.PREF_SNOOZE_DURATION
import com.nukirk.medrx.PREF_SNOOZE_LIMIT
import com.nukirk.medrx.PREF_SNOOZE_LIMIT_ACTION
import com.nukirk.medrx.PREF_SNOOZE_LIMIT_ENABLED
import com.nukirk.medrx.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Applies the stock change for a dose being logged or un-logged and updates the
 * in-memory item. Central helper shared by the UI (MedViewModel) and the
 * notification's Take action so inventory stays consistent everywhere.
 */
fun applyInventoryChange(context: Context, item: MedData, isTaken: Boolean): MedData =
    InventoryService.applyInventoryChange(context, item, isTaken)

class NotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ALARM_CHANNEL_ID = "med_alarms_fullscreen_v1"
        const val SIMPLE_NOTIF_CHANNEL_ID = "med_alarms_simple_v1"

        /** Low-importance channel for quiet, informational notices. */
        const val NOTICE_CHANNEL_ID = "med_notices_v1"
        const val ACTION_SHOW_NOTIFICATION = "ACTION_SHOW_NOTIFICATION"
        const val ACTION_TAKEN = "ACTION_TAKEN"
        const val ACTION_SNOOZE = "ACTION_SNOOZE"
        const val ACTION_SKIP = "ACTION_SKIP"
        const val ACTION_RESCHEDULE_ALL = "ACTION_RESCHEDULE_ALL"

        /** Undoes a skip that the snooze limit wrote automatically. */
        const val ACTION_UNDO_SNOOZE_SKIP = "ACTION_UNDO_SNOOZE_SKIP"

        /** Fires when a posted reminder was left unanswered for one snooze interval. */
        const val ACTION_NAG = "ACTION_NAG"

        /** Refills a medication straight from its low-supply alert. */
        const val ACTION_REFILL = "ACTION_REFILL"

        /**
         * Request-code offset for the unanswered-reminder follow-up alarm. Kept
         * clear of the snooze (100000), content (200000), skip (300000) and
         * skip-limit notice (400000) codes.
         */
        const val NAG_REQUEST_OFFSET = 500_000

        /** Default length of one unanswered-reminder follow-up, in minutes. */
        private fun nagMinutes(prefs: android.content.SharedPreferences): Int =
            SnoozePolicy.clampDuration(
                prefs.getInt(
                    PREF_SNOOZE_DURATION,
                    SnoozePolicy.DEFAULT_SNOOZE_DURATION_MINUTES
                )
            )

        /**
         * Arms the follow-up that turns ignoring a reminder into a re-alert
         * instead of a free snooze.
         */
        private fun armNagAlarm(
            context: Context,
            itemIds: LongArray,
            notifId: Int,
            minutes: Int
        ) {
            if (itemIds.isEmpty()) return
            try {
                val leaderId = itemIds.min()
                val intent = Intent(context, NotificationReceiver::class.java).apply {
                    action = ACTION_NAG
                    putExtra("ITEM_IDS", itemIds)
                    putExtra("NOTIF_ID", notifId)
                    putExtra("NAG_MINUTES", minutes)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    leaderId.toInt() + NAG_REQUEST_OFFSET,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                setAlarm(
                    alarmManager,
                    System.currentTimeMillis() + SnoozePolicy.clampDuration(minutes) * 60_000L,
                    pendingIntent
                )
            } catch (_: Exception) {
            }
        }

        /** Cancels the pending unanswered-reminder follow-up for [itemId]. */
        private fun cancelPendingNagAlarm(
            context: Context,
            alarmManager: AlarmManager,
            itemId: Long
        ) {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_NAG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                itemId.toInt() + NAG_REQUEST_OFFSET,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        /**
         * Request-code offset for the pending snooze alarm. Kept clear of the
         * fresh-fire (item id), content (200000), skip (300000) and skip-limit
         * notice (400000) codes.
         */
        const val SNOOZE_REQUEST_OFFSET = 100_000

        /** Notification id offset for the "closed out as skipped" notice. */
        const val SNOOZE_LIMIT_NOTICE_OFFSET = 400_000

        /**
         * Builds the PendingIntent used by alarm UI / notification actions to
         * record a deliberate skip (with reason + note) for the given meds.
         */
        fun prepareSkipPendingIntent(
            context: Context,
            itemIds: LongArray,
            notifId: Int,
            reason: String,
            note: String?
        ): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SKIP
                putExtra("ITEM_IDS", itemIds)
                putExtra("NOTIF_ID", notifId)
                putExtra("SKIP_REASON", reason)
                putExtra("SKIP_NOTE", note)
            }
            return PendingIntent.getBroadcast(
                context,
                notifId + 300000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Dismisses the posted reminder for [itemIds] and drops any snooze alarm
         * still pending for them. Called whenever a dose is logged or skipped
         * outside the notification itself (the app card, a pre-skip, a delete,
         * or the watch), so a stale reminder cannot be tapped again later and
         * produce a second entry.
         *
         * Doses that fall due together share one reminder, posted under the
         * lowest member id, so every id the slot can own is cancelled —
         * cancelling only the resolved dose's own id leaves the group's
         * notification behind, and being ongoing it cannot be swiped away.
         */
        fun cancelReminder(context: Context, itemIds: LongArray) {
            if (itemIds.isEmpty()) return
            try {
                val notifManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val stored = try {
                    DataRepository.loadData(context)
                } catch (_: Exception) {
                    emptyList()
                }
                ReminderSlots.reminderIds(itemIds.toList(), stored).forEach {
                    notifManager.cancel(it)
                }

                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                itemIds.forEach { cancelPendingSnoozeAlarm(context, alarmManager, it) }
                itemIds.forEach { cancelPendingNagAlarm(context, alarmManager, it) }

                context.sendBroadcast(
                    Intent("ACTION_CLOSE_ALARM_ACTIVITY").setPackage(context.packageName)
                )
            } catch (_: Exception) {
            }
        }

        /**
         * Takes down reminders that no longer stand for a dose the user still
         * has to act on. Runs after every data mutation, so a schedule edit, a
         * new version or an archive cannot strand a reminder that outlived the
         * dose it announced.
         */
        fun sweepStaleReminders(context: Context, items: List<MedData>) {
            try {
                val stale = ReminderSlots.staleReminderIds(items, LocalDate.now())
                if (stale.isEmpty()) return
                val notifManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                stale.forEach { notifManager.cancel(it) }
            } catch (_: Exception) {
            }
        }

        /** Cancels the snooze alarm for [itemId], if one is actually armed. */
        private fun cancelPendingSnoozeAlarm(
            context: Context,
            alarmManager: AlarmManager,
            itemId: Long
        ) {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHOW_NOTIFICATION
                putExtra("ITEM_ID", itemId)
            }
            // PendingIntent identity ignores extras, so the action + request code
            // are enough to find an alarm armed by a group snooze. FLAG_NO_CREATE
            // keeps this a no-op when nothing was armed.
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                itemId.toInt() + SNOOZE_REQUEST_OFFSET,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        /** Arms [pendingIntent] at [triggerAt], degrading when exact alarms are denied. */
        private fun setAlarm(
            alarmManager: AlarmManager,
            triggerAt: Long,
            pendingIntent: PendingIntent
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (_: SecurityException) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }

        fun scheduleNotification(context: Context, item: MedData) {
            if (item.type != ItemType.Medicine) return
            if (item.isPrn) return

            val prefs = context.getSharedPreferences("med_settings", Context.MODE_PRIVATE)
            val globalShowNotif = prefs.getBoolean(PREF_SHOW_NOTIFICATIONS, true)
            val globalUseFullScreen = prefs.getBoolean(PREF_FULL_SCREEN_ALARM, true)

            val effectiveType = when (item.notificationType) {
                1 -> "NONE"
                2 -> "NORMAL"
                3 -> "ALARM"
                else -> {
                    if (!globalShowNotif) "NONE"
                    else if (globalUseFullScreen) "ALARM" else "NORMAL"
                }
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHOW_NOTIFICATION
                putExtra("ITEM_ID", item.id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                item.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (effectiveType == "NONE") {
                alarmManager.cancel(pendingIntent)
                return
            }

            val nextDateTime = getNextOccurrence(item)

            if (nextDateTime != null) {
                val timeInMillis =
                    nextDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            timeInMillis,
                            pendingIntent
                        )
                    }
                } catch (e: SecurityException) {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, timeInMillis, pendingIntent)
                }
            } else {
                alarmManager.cancel(pendingIntent)
            }
        }

        private fun getNextOccurrence(item: MedData): LocalDateTime? {
            if (item.isPrn) return null
            var date = LocalDate.now()
            val now = LocalTime.now()

            if (isValidDate(item, date) && !item.takenHistory.containsKey(date) && item.creationTime.isAfter(now)) {
                // Today's slot is still ahead and not taken yet -> alarm for today.
                // (If it was already taken, e.g. early, fall through to the next day.)
                return LocalDateTime.of(date, item.creationTime)
            }

            for (i in 1..30) {
                date = date.plusDays(1)
                if (isValidDate(item, date)) {
                    return LocalDateTime.of(date, item.creationTime)
                }
            }
            return null
        }

        private fun isValidDate(item: MedData, date: LocalDate): Boolean =
            ReminderSlots.isScheduledFor(item, date)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val prefs = context.getSharedPreferences("med_settings", Context.MODE_PRIVATE)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, ACTION_RESCHEDULE_ALL -> {
                val items = DataRepository.loadData(context)
                items.forEach { item ->
                    if (item.type == ItemType.Medicine) {
                        scheduleNotification(context, item)
                    }
                }
                InventoryService.createNotificationChannel(context)
                // A supply that is already below its threshold at boot/reinstall
                // must alert without waiting for the next dose event.
                val evalList = items.toMutableList()
                if (InventoryService.evaluateAll(context, evalList)) {
                    DataRepository.saveData(context, evalList)
                }
            }

            ACTION_SHOW_NOTIFICATION -> {
                val triggerItemId = intent.getLongExtra("ITEM_ID", -1L)
                val isSnooze = intent.getBooleanExtra("IS_SNOOZE", false)
                if (triggerItemId == -1L) return

                val items = DataRepository.loadData(context)
                val triggerItem = items.find { it.id == triggerItemId } ?: return

                // The same eligibility rules apply to snoozed and fresh
                // reminders alike: a dose that was taken (or skipped) between
                // the snooze and its re-fire must not pop up again.
                val isDueNow: (MedData) -> Boolean = { item ->
                    // Same slot, and still unresolved: a dose taken (or skipped)
                    // between the snooze and its re-fire must not pop up again.
                    item.creationTime == triggerItem.creationTime &&
                            ReminderSlots.isDueOn(item, LocalDate.now())
                }
                // A snoozed group comes back whole: the snooze alarm carries
                // every id that was still due when it was tapped, so rebuilding
                // from that set restores the original reminder instead of
                // re-posting one medication at a time.
                val requestedIds = intent.getLongArrayExtra("ITEM_IDS")?.toSet()
                val groupItems = if (isSnooze && requestedIds != null) {
                    items.filter { it.id in requestedIds }.filter(isDueNow)
                } else if (isSnooze) {
                    listOf(triggerItem).filter(isDueNow)
                } else {
                    items.filter(isDueNow)
                }

                val globalShowNotif = prefs.getBoolean(PREF_SHOW_NOTIFICATIONS, true)
                val globalUseFullScreen = prefs.getBoolean(PREF_FULL_SCREEN_ALARM, true)

                val validGroupItems = groupItems.filter { item ->
                    val effectiveType = when (item.notificationType) {
                        1 -> "NONE"
                        2 -> "NORMAL"
                        3 -> "ALARM"
                        else -> if (!globalShowNotif) "NONE" else if (globalUseFullScreen) "ALARM" else "NORMAL"
                    }
                    effectiveType != "NONE"
                }

                if (validGroupItems.isEmpty()) return

                if (!isSnooze && validGroupItems.size > 1) {
                    val leaderId = validGroupItems.minOf { it.id }
                    if (triggerItemId != leaderId) return
                }

                val useFullScreen = validGroupItems.any { item ->
                    val effectiveType = when (item.notificationType) {
                        1 -> "NONE"
                        2 -> "NORMAL"
                        3 -> "ALARM"
                        else -> if (!globalShowNotif) "NONE" else if (globalUseFullScreen) "ALARM" else "NORMAL"
                    }
                    effectiveType == "ALARM"
                }

                val wearAlarmsEnabled = prefs.getBoolean("pref_wear_ring_alarms", false)
                val shouldTriggerWear = useFullScreen && wearAlarmsEnabled

                createNotificationChannels(context)
                val channelId =
                    if (useFullScreen && !shouldTriggerWear) ALARM_CHANNEL_ID else SIMPLE_NOTIF_CHANNEL_ID

                val notifId = validGroupItems.minOf { it.id }.toInt()
                val isGrouped = validGroupItems.size > 1
                val titles = validGroupItems.joinToString(", ") { it.title }
                val itemIds = validGroupItems.map { it.id }.toLongArray()

                WearSyncManager.initialize(context)

                val builder = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(
                        if (isGrouped) context.getString(R.string.notif_grouped_title) else context.getString(
                            R.string.notif_reminder_title,
                            triggerItem.title
                        )
                    )
                    .setContentText(if (isGrouped) titles else context.getString(R.string.notif_reminder_desc))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setAutoCancel(!useFullScreen || shouldTriggerWear)

                if (useFullScreen && !shouldTriggerWear) {
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    builder.setCategory(NotificationCompat.CATEGORY_ALARM)
                    builder.setSound(soundUri)
                    builder.setVibrate(longArrayOf(0, 1000, 1000))
                } else if (shouldTriggerWear) {
                    WearSyncManager.triggerWatchAlarm(itemIds, titles, notifId)
                    builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
                    builder.setSilent(true)
                } else {
                    builder.setCategory(NotificationCompat.CATEGORY_REMINDER)
                    builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                }

                val takeIntent = Intent(context, NotificationReceiver::class.java).apply {
                    this.action = ACTION_TAKEN
                    putExtra("ITEM_IDS", itemIds)
                    putExtra("NOTIF_ID", notifId)
                }
                val takePendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId,
                    takeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val snoozeIntent = Intent(context, NotificationReceiver::class.java).apply {
                    this.action = ACTION_SNOOZE
                    putExtra("ITEM_IDS", itemIds)
                    putExtra("NOTIF_ID", notifId)
                }
                val snoozePendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId + 100000,
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val takeActionText =
                    if (isGrouped) context.getString(R.string.notif_action_take_all) else context.getString(
                        R.string.notif_action_taken
                    )

                builder.addAction(R.drawable.ic_notification, takeActionText, takePendingIntent)
                builder.addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.notif_action_snooze),
                    snoozePendingIntent
                )

                if (useFullScreen && !shouldTriggerWear) {
                    val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("ITEM_IDS", itemIds)
                        putExtra("ITEM_TITLE", titles)
                        putExtra("NOTIF_ID", notifId)
                    }
                    val fullScreenPendingIntent = PendingIntent.getActivity(
                        context,
                        notifId,
                        fullScreenIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setFullScreenIntent(fullScreenPendingIntent, true)
                    builder.setContentIntent(fullScreenPendingIntent)
                    builder.setOngoing(true)
                } else {
                    val contentIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val contentPendingIntent = PendingIntent.getActivity(
                        context,
                        notifId + 200000,
                        contentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setContentIntent(contentPendingIntent)
                }

                val notification = builder.build()

                if (useFullScreen && !shouldTriggerWear) {
                    notification.flags = notification.flags or Notification.FLAG_INSISTENT
                }

                val notifManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notifManager.notify(notifId, notification)
                // Ignoring a reminder must not silently delay the dose: if this
                // one is still unanswered after the snooze interval, ask again.
                armNagAlarm(context, itemIds, notifId, nagMinutes(prefs))
            }

            ACTION_NAG -> {
                val requested = intent.getLongArrayExtra("ITEM_IDS") ?: return
                if (requested.isEmpty()) return
                val notifId = intent.getIntExtra("NOTIF_ID", -1)
                val today = LocalDate.now()
                val items = DataRepository.loadData(context)
                val due = items.filter { item ->
                    item.id in requested.toSet() && ReminderSlots.isDueOn(item, today)
                }
                if (due.isEmpty()) return
                val dueIds = due.map { it.id }
                val leaderId = dueIds.min()
                val count = SnoozeTracker.increment(context, leaderId, today)
                val limitEnabled = prefs.getBoolean(PREF_SNOOZE_LIMIT_ENABLED, false)
                val snoozeLimit = prefs.getInt(PREF_SNOOZE_LIMIT, SnoozePolicy.DEFAULT_SNOOZE_LIMIT)
                val limitAction = SnoozeLimitAction.fromPref(
                    prefs.getString(PREF_SNOOZE_LIMIT_ACTION, null)
                )
                val decision = ReminderNagPolicy.decide(
                    count,
                    limitEnabled,
                    snoozeLimit,
                    limitAction
                )
                if (decision == NagDecision.RE_ALERT) {
                    // Re-post the same reminder; posting arms the next
                    // follow-up, so this loops only while the setting allows.
                    val again = Intent(context, NotificationReceiver::class.java).apply {
                        this.action = ACTION_SHOW_NOTIFICATION
                        putExtra("ITEM_ID", leaderId)
                        putExtra("ITEM_IDS", dueIds.toLongArray())
                        putExtra("IS_SNOOZE", true)
                    }
                    context.sendBroadcast(again)
                    return
                }

                val notifManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (notifId != -1) notifManager.cancel(notifId)
                ReminderSlots.reminderIds(dueIds, items).forEach { notifManager.cancel(it) }

                if (decision == NagDecision.STOP) {
                    // Out of nudges: stop making noise and leave the dose
                    // unlogged, so it reads as missed rather than as a skip the
                    // user never chose.
                    SnoozeTracker.clear(context, dueIds.toLongArray(), today)
                } else {
                    recordSnoozeLimitOutcome(
                        context,
                        dueIds,
                        today,
                        if (decision == NagDecision.CONVERT_TO_DELAY) SkipReason.DELAYED
                        else SkipReason.SNOOZED_AWAY,
                        snoozeLimit
                    )
                }
                context.sendBroadcast(
                    Intent("ACTION_CLOSE_ALARM_ACTIVITY").setPackage(context.packageName)
                )
            }

            ACTION_REFILL -> {
                val id = intent.getLongExtra("ITEM_ID", -1L)
                if (id == -1L) return
                val items = DataRepository.loadData(context).toMutableList()
                val updated = SupplyGroup.refill(items, id) ?: return
                DataRepository.saveData(context, items)
                InventoryService.cancelLowSupplyNotification(context, updated)
                InventoryService.evaluateAll(context, items)
                DataRepository.saveData(context, items)
                context.sendBroadcast(
                    Intent("com.nukirk.medrx.REFRESH_DATA").setPackage(context.packageName)
                )
            }

            ACTION_TAKEN -> {
                val itemIds = intent.getLongArrayExtra("ITEM_IDS") ?: run {
                    val singleId = intent.getLongExtra("ITEM_ID", -1L)
                    if (singleId != -1L) longArrayOf(singleId) else null
                }

                if (itemIds != null) {
                    val items = DataRepository.loadData(context).toMutableList()
                    var isDataUpdated = false

                    for (id in itemIds) {
                        val index = items.indexOfFirst { it.id == id }
                        if (index != -1) {
                            val item = items[index]
                            // Idempotent: a second Take for an already-logged
                            // dose must not decrement stock again.
                            val updated = InventoryService.applyTakeIfNew(
                                context,
                                item,
                                LocalDate.now(),
                                isTaken = true
                            )
                            if (updated !== item) {
                                items[index] = updated
                                // One balance per medication: every dose slot
                                // of this medicine shows the same count.
                                SupplyGroup.mirror(items, items[index])
                                isDataUpdated = true
                                scheduleNotification(context, items[index])
                            }
                        }
                    }

                    if (isDataUpdated) {
                        DataRepository.saveData(context, items)
                        InventoryService.evaluateAll(context, items)
                        DataRepository.saveData(context, items)
                        context.sendBroadcast(
                            Intent("com.nukirk.medrx.REFRESH_DATA").setPackage(
                                context.packageName
                            )
                        )
                    }

                    // The dose is resolved, so its snooze tally is spent.
                    SnoozeTracker.clear(context, itemIds, LocalDate.now())

                    val notifId = intent.getIntExtra("NOTIF_ID", -1)
                    if (notifId != -1) {
                        val notifManager =
                            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notifManager.cancel(notifId)
                    }
                    // Also retire by slot: an action carrying a stale or partial
                    // id set must not leave the group's reminder behind.
                    cancelReminder(context, itemIds)

                    context.sendBroadcast(
                        Intent("ACTION_CLOSE_ALARM_ACTIVITY").setPackage(context.packageName)
                    )
                }
            }

            ACTION_SKIP -> {
                val itemIds = intent.getLongArrayExtra("ITEM_IDS") ?: run {
                    val singleId = intent.getLongExtra("ITEM_ID", -1L)
                    if (singleId != -1L) longArrayOf(singleId) else null
                }

                if (itemIds != null) {
                    val reasonName = intent.getStringExtra("SKIP_REASON")
                    val reason = try {
                        SkipReason.valueOf(reasonName ?: "")
                    } catch (e: Exception) {
                        SkipReason.OTHER
                    }
                    val note = intent.getStringExtra("SKIP_NOTE")
                    val today = LocalDate.now()
                    val items = DataRepository.loadData(context).toMutableList()
                    var isDataUpdated = false

                    for (id in itemIds) {
                        val index = items.indexOfFirst { it.id == id }
                        if (index != -1 && items[index].type == ItemType.Medicine &&
                            !items[index].skipHistory.containsKey(today)
                        ) {
                            val item = items[index]
                            val newSkips = HashMap(item.skipHistory)
                            newSkips[today] = SkipRecord(
                                reason = reason,
                                time = LocalTime.now(),
                                note = note?.trim()?.ifEmpty { null }
                            )
                            items[index] = item.copy(skipHistory = newSkips)
                            isDataUpdated = true
                            scheduleNotification(context, items[index])
                        }
                    }

                    if (isDataUpdated) {
                        DataRepository.saveData(context, items)
                        context.sendBroadcast(
                            Intent("com.nukirk.medrx.REFRESH_DATA").setPackage(
                                context.packageName
                            )
                        )
                    }

                    // The dose is resolved, so its snooze tally is spent.
                    SnoozeTracker.clear(context, itemIds, LocalDate.now())

                    val notifId = intent.getIntExtra("NOTIF_ID", -1)
                    if (notifId != -1) {
                        val notifManager =
                            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notifManager.cancel(notifId)
                    }
                    // Also retire by slot: an action carrying a stale or partial
                    // id set must not leave the group's reminder behind.
                    cancelReminder(context, itemIds)

                    context.sendBroadcast(
                        Intent("ACTION_CLOSE_ALARM_ACTIVITY").setPackage(context.packageName)
                    )
                }
            }

            ACTION_UNDO_SNOOZE_SKIP -> {
                val itemIds = intent.getLongArrayExtra("ITEM_IDS") ?: run {
                    val singleId = intent.getLongExtra("ITEM_ID", -1L)
                    if (singleId != -1L) longArrayOf(singleId) else null
                }

                if (itemIds != null) {
                    val today = LocalDate.now()
                    val items = DataRepository.loadData(context).toMutableList()
                    var isDataUpdated = false

                    for (id in itemIds) {
                        val index = items.indexOfFirst { it.id == id }
                        if (index == -1) continue
                        val item = items[index]
                        // Only the automatic conversion is undoable; a skip the
                        // user picked deliberately is left untouched.
                        if (item.skipHistory[today]?.reason != SkipReason.SNOOZED_AWAY) continue
                        items[index] = item.copy(
                            skipHistory = HashMap(item.skipHistory).apply { remove(today) }
                        )
                        scheduleNotification(context, items[index])
                        isDataUpdated = true
                    }

                    if (isDataUpdated) {
                        DataRepository.saveData(context, items)
                        context.sendBroadcast(
                            Intent("com.nukirk.medrx.REFRESH_DATA").setPackage(
                                context.packageName
                            )
                        )
                    }

                    val notifId = intent.getIntExtra("NOTIF_ID", -1)
                    if (notifId != -1) {
                        val notifManager =
                            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notifManager.cancel(notifId)
                    }
                }
            }

            ACTION_SNOOZE -> {
                val itemIds = intent.getLongArrayExtra("ITEM_IDS") ?: run {
                    val singleId = intent.getLongExtra("ITEM_ID", -1L)
                    if (singleId != -1L) longArrayOf(singleId) else null
                }

                if (itemIds != null) {
                    val snoozeDuration = SnoozePolicy.clampDuration(
                        intent.getIntExtra(
                            "SNOOZE_MINUTES",
                            prefs.getInt(
                                PREF_SNOOZE_DURATION,
                                SnoozePolicy.DEFAULT_SNOOZE_DURATION_MINUTES
                            )
                        )
                    )
                    val limitEnabled = prefs.getBoolean(PREF_SNOOZE_LIMIT_ENABLED, false)
                    val snoozeLimit = prefs.getInt(PREF_SNOOZE_LIMIT, SnoozePolicy.DEFAULT_SNOOZE_LIMIT)
                    val limitAction = SnoozeLimitAction.fromPref(
                        prefs.getString(PREF_SNOOZE_LIMIT_ACTION, null)
                    )
                    val today = LocalDate.now()
                    val stored = DataRepository.loadData(context)

                    // Count the tap first, then let the policy decide per dose
                    // whether it is asked again or closed out as a skip.
                    val reArm = mutableListOf<Long>()
                    val escalate = mutableListOf<Pair<Long, SkipReason>>()
                    for (id in itemIds) {
                        val item = stored.find { it.id == id }
                        // Nothing left to nag about when the dose is already
                        // logged or skipped (e.g. it was taken from the app
                        // while the reminder was up).
                        if (item == null ||
                            item.takenHistory.containsKey(today) ||
                            item.skipHistory.containsKey(today)
                        ) {
                            continue
                        }

                        val count = SnoozeTracker.increment(context, id, today)
                        val decision = SnoozePolicy.decide(
                            count,
                            limitEnabled,
                            snoozeLimit,
                            limitAction
                        )
                        if (decision == SnoozeDecision.RE_ARM) {
                            reArm += id
                        } else {
                            escalate += id to SnoozePolicy.outcomeReason(decision)
                        }
                    }

                    val alarmManager =
                        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    itemIds.forEach { cancelPendingNagAlarm(context, alarmManager, it) }

                    if (reArm.isNotEmpty()) {
                        // One alarm carries the whole surviving group, so a
                        // snoozed reminder returns as a single notification /
                        // alarm listing every dose that is still due.
                        val leaderId = reArm.min()
                        val snoozeIntent =
                            Intent(context, NotificationReceiver::class.java).apply {
                                this.action = ACTION_SHOW_NOTIFICATION
                                putExtra("ITEM_ID", leaderId)
                                putExtra("ITEM_IDS", reArm.toLongArray())
                                putExtra("IS_SNOOZE", true)
                            }
                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            leaderId.toInt() + SNOOZE_REQUEST_OFFSET,
                            snoozeIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        setAlarm(
                            alarmManager,
                            System.currentTimeMillis() + (snoozeDuration * 60 * 1000L),
                            pendingIntent
                        )

                        // A shrunk group must not leave an orphan alarm under an
                        // earlier leader's request code.
                        itemIds.filter { id -> reArm.none { it == id } }.forEach {
                            cancelPendingSnoozeAlarm(context, alarmManager, it)
                        }
                    } else {
                        itemIds.forEach {
                            cancelPendingSnoozeAlarm(context, alarmManager, it)
                        }
                    }

                    // Doses in one tap share the configured action, but counts
                    // can differ, so outcomes are grouped by what they record.
                    escalate.groupBy { it.second }.forEach { (reason, entries) ->
                        recordSnoozeLimitOutcome(
                            context,
                            entries.map { it.first },
                            today,
                            reason,
                            snoozeLimit
                        )
                    }

                    val notifManager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notifId = intent.getIntExtra("NOTIF_ID", -1)
                    if (notifId != -1) {
                        notifManager.cancel(notifId)
                    } else {
                        itemIds.forEach { notifManager.cancel(it.toInt()) }
                    }

                    context.sendBroadcast(
                        Intent("ACTION_CLOSE_ALARM_ACTIVITY").setPackage(context.packageName)
                    )
                }
            }
        }
    }

    /**
     * Closes out doses that exhausted their snooze budget. [reason] is either
     * [SkipReason.SNOOZED_AWAY] (the dose is not being taken) or
     * [SkipReason.DELAYED] (it moves to the next scheduled slot); both keep the
     * dose out of "missed" and label it in the doctor report, since repeatedly
     * snoozing past the limit is a choice rather than an unnoticed omission.
     */
    private fun recordSnoozeLimitOutcome(
        context: Context,
        itemIds: List<Long>,
        date: LocalDate,
        reason: SkipReason,
        limit: Int
    ) {
        val items = DataRepository.loadData(context).toMutableList()
        val skippedIds = mutableListOf<Long>()
        val skippedTitles = mutableListOf<String>()

        for (id in itemIds) {
            val index = items.indexOfFirst { it.id == id }
            if (index == -1) continue
            val item = items[index]
            if (item.type != ItemType.Medicine) continue
            // Never overwrite a dose that was taken or skipped meanwhile.
            if (item.takenHistory.containsKey(date) || item.skipHistory.containsKey(date)) continue

            items[index] = item.copy(
                skipHistory = HashMap(item.skipHistory).apply {
                    put(date, SkipRecord(reason = reason, time = LocalTime.now()))
                }
            )
            scheduleNotification(context, items[index])
            skippedIds += id
            skippedTitles += item.title
        }

        if (skippedTitles.isEmpty()) return

        DataRepository.saveData(context, items)
        // The tally has served its purpose, so undoing the skip restores a full
        // budget of snoozes.
        SnoozeTracker.clear(context, skippedIds.toLongArray(), date)
        context.sendBroadcast(
            Intent("com.nukirk.medrx.REFRESH_DATA").setPackage(context.packageName)
        )
        notifySnoozeLimit(context, skippedTitles, skippedIds, limit, reason)
    }

    /**
     * Quiet notice that a dose was closed out as skipped, with a way back, so an
     * automatic write is never invisible or irreversible.
     */
    private fun notifySnoozeLimit(
        context: Context,
        titles: List<String>,
        itemIds: List<Long>,
        limit: Int,
        reason: SkipReason
    ) {
        try {
            createNotificationChannels(context)
            val notifId = itemIds.min().toInt() + SNOOZE_LIMIT_NOTICE_OFFSET
            val idArray = itemIds.toLongArray()

            val undoIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_UNDO_SNOOZE_SKIP
                putExtra("ITEM_IDS", idArray)
                putExtra("NOTIF_ID", notifId)
            }
            val undoPending = PendingIntent.getBroadcast(
                context,
                notifId + 1,
                undoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val takeIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_TAKEN
                putExtra("ITEM_IDS", idArray)
                putExtra("NOTIF_ID", notifId)
            }
            val takePending = PendingIntent.getBroadcast(
                context,
                notifId + 2,
                takeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val contentIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                notifId + 3,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val isDelay = reason == SkipReason.DELAYED
            val summary = titles.joinToString(", ")
            val body = context.getString(
                if (isDelay) R.string.snooze_limit_delayed_desc else R.string.snooze_limit_skipped_desc,
                summary,
                limit
            )
            val notification = NotificationCompat.Builder(context, NOTICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    context.getString(
                        if (isDelay) {
                            R.string.snooze_limit_delayed_title
                        } else {
                            R.string.snooze_limit_skipped_title
                        }
                    )
                )
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.action_undo),
                    undoPending
                )
                .addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.notif_action_taken),
                    takePending
                )
                .build()

            val notifManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(notifId, notification)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val name = context.getString(R.string.notif_channel_alarms_name)
            val descriptionText = context.getString(R.string.notif_channel_alarms_desc)

            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = descriptionText
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(alarmUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000)
            }

            val simpleNotifChannel = NotificationChannel(
                SIMPLE_NOTIF_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = descriptionText
            }

            val noticeChannel = NotificationChannel(
                NOTICE_CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = descriptionText
                setSound(null, null)
            }

            notificationManager.createNotificationChannel(alarmChannel)
            notificationManager.createNotificationChannel(simpleNotifChannel)
            notificationManager.createNotificationChannel(noticeChannel)
        }
    }
}