package com.nukirk.medrx.services

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nukirk.medrx.MainActivity
import com.nukirk.medrx.R
import java.time.LocalDate
import java.time.LocalTime

/**
 * Per-medication supply (inventory) tracking.
 *
 * Supply data lives on each [MedData] as:
 *  - [MedData.supplyDosesLeft]      current stock in doses (null = tracking off)
 *  - [MedData.supplyDosesPerRefill] how many doses a refill adds (e.g. 30 tablets)
 *  - [MedData.supplyLowThreshold]   fire one alert when dosesLeft <= this
 *  - [MedData.supplyAlertShown]     deduplication flag for the low-supply alert
 *
 * Doses are counted per calendar day (each MedData entry is one daily time slot),
 * so taking or un-taking a dose adjusts the stock by exactly one dose.
 *
 * Low-supply alerts use per-item notification IDs so multiple medications can be
 * low at the same time without overwriting each other, and stale alerts are
 * cancelled when stock is refilled or the item is edited/deleted.
 */
object InventoryService {

    const val INVENTORY_CHANNEL_ID = "med_inventory_v1"

    /** Base for per-item alert IDs; dose alarms use `item.id.toInt()` directly. */
    private const val ALERT_ID_BASE = 900000000

    /** How many ledger entries to keep per medicine (newest wins). */
    private const val LEDGER_LIMIT = 60

    /**
     * Appends a stock change to the item's supply ledger (bounded) so future
     * discrepancies can be traced. Pure: returns the updated item.
     */
    fun logSupplyChange(
        item: MedData,
        kind: SupplyChangeKind,
        delta: Int,
        balanceAfter: Int,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now(),
        unit: SupplyUnit = item.supplyUnit
    ): MedData = item.copy(
        supplyLedger = (
            item.supplyLedger + SupplyChange(kind, date, time, delta, balanceAfter, unit)
        ).takeLast(LEDGER_LIMIT)
    )

    /** Consume stock without recording an adherence/taken event (priming or waste). */
    fun applySupplyUse(
        context: Context?,
        item: MedData,
        kind: SupplyChangeKind,
        quantity: Int,
        date: LocalDate = LocalDate.now(),
        time: LocalTime = LocalTime.now()
    ): MedData {
        require(kind == SupplyChangeKind.PRIMING || kind == SupplyChangeKind.WASTE)
        require(quantity > 0)
        val balance = item.supplyDosesLeft ?: return item
        if (quantity > balance) return item
        if (kind == SupplyChangeKind.PRIMING && item.supplyUnit != SupplyUnit.SPRAY && item.supplyUnit != SupplyUnit.PUFF) return item
        val newBalance = balance - quantity
        val isEstimatedUnit = item.supplyUnit == SupplyUnit.SPRAY || item.supplyUnit == SupplyUnit.PUFF
        val updated = evaluateItem(
            context,
            item.copy(supplyDosesLeft = newBalance, supplyEstimated = item.supplyEstimated || isEstimatedUnit)
        )
        return logSupplyChange(updated, kind, -quantity, newBalance, date, time, item.supplyUnit)
    }

    private fun alertId(item: MedData): Int = ALERT_ID_BASE + (item.id % 100000).toInt()

    /**
     * Applies the stock change for a dose being logged or un-logged.
     * Returns the updated item; the caller is responsible for persisting it.
     */
    fun applyInventoryChange(context: Context?, item: MedData, isTaken: Boolean): MedData {
        val left = item.supplyDosesLeft ?: return item
        val units = item.supplyUnitsPerDose.coerceAtLeast(1)
        val updated = item.copy(
            supplyDosesLeft = (left + if (isTaken) -units else units).coerceAtLeast(0)
        )
        return evaluateItem(context, updated)
    }

    /**
     * Idempotent take/un-take for one dose on [date], shared by every entry
     * point (card toggle, notification action, fullscreen alarm, Wear). A take
     * only logs and decrements stock when the dose isn't already logged; an
     * un-take only refunds when it was. This is what keeps a dose tapped on
     * the card AND confirmed via notification/alarm from counting twice.
     */
    fun applyTakeIfNew(
        context: Context?,
        item: MedData,
        date: LocalDate,
        isTaken: Boolean,
        takenAt: LocalTime = LocalTime.now()
    ): MedData {
        val alreadyLogged = item.takenHistory.containsKey(date)
        return when {
            isTaken && alreadyLogged -> item
            isTaken -> {
                val updated = applyInventoryChange(
                    context,
                    item.copy(takenHistory = HashMap(item.takenHistory).apply { put(date, takenAt) }),
                    isTaken = true
                )
                logSupplyChange(
                    updated,
                    SupplyChangeKind.TAKEN,
                    delta = if (item.supplyDosesLeft == null) 0 else -item.supplyUnitsPerDose.coerceAtLeast(1),
                    balanceAfter = updated.supplyDosesLeft ?: 0,
                    date = date,
                    time = takenAt,
                    unit = updated.supplyUnit
                )
            }
            !alreadyLogged -> item
            else -> {
                val updated = applyInventoryChange(
                    context,
                    item.copy(takenHistory = HashMap(item.takenHistory).apply { remove(date) }),
                    isTaken = false
                )
                logSupplyChange(
                    updated,
                    SupplyChangeKind.REFUND,
                    delta = if (item.supplyDosesLeft == null) 0 else item.supplyUnitsPerDose.coerceAtLeast(1),
                    balanceAfter = updated.supplyDosesLeft ?: 0,
                    date = date,
                    unit = updated.supplyUnit
                )
            }
        }
    }

    /**
     * Evaluates one item against its threshold: posts the low-supply alert when the
     * stock is newly at/below it, and cancels a stale alert once stock is refilled.
     * Returns the item with [MedData.supplyAlertShown] updated accordingly.
     * A null [context] (plain-JVM tests) skips notification posting/cancelling
     * but still records the alert state.
     */
    fun evaluateItem(context: Context?, item: MedData): MedData {
        val left = item.supplyDosesLeft ?: return item
        val threshold = item.supplyLowThreshold ?: return item
        if (left > threshold) {
            context?.let { cancelLowSupplyNotification(it, item) }
            return item
        }
        if (item.supplyAlertShown) return item
        if (context != null) postLowSupplyNotification(context, item)
        return item.copy(supplyAlertShown = true)
    }

    /**
     * Evaluates every item in place — used after app start, boot/reinstall, or
     * import so a supply that is *already* low alerts even without a dose event.
     * Within one pass only the first entry per title alerts (dose slots of one
     * medication share a title). Returns true when any item changed and the list
     * should be persisted.
     */
    fun evaluateAll(context: Context?, items: MutableList<MedData>): Boolean {
        var changed = false
        val alertedTitles = mutableSetOf<String>()
        for (i in items.indices) {
            val item = items[i]
            val left = item.supplyDosesLeft ?: continue
            val threshold = item.supplyLowThreshold
            // Archived meds (schedule already ended) never raise new alerts.
            val today = java.time.LocalDate.now()
            val ended = item.endDate != null && today.isAfter(item.endDate)
            if (!ended && threshold != null && left <= threshold && !item.supplyAlertShown &&
                alertedTitles.add(item.title)
            ) {
                items[i] = evaluateItem(context, item)
                changed = true
            }        }
        return changed
    }

    private fun postLowSupplyNotification(context: Context, item: MedData) {
        val remaining = item.supplyDosesPerRefill ?: 0
        val unitName = context.getString(item.supplyUnit.labelResId())
        val text = if (remaining > 0) {
            context.getString(R.string.inventory_low_desc_refill, item.supplyDosesLeft ?: 0, unitName, remaining, unitName)
        } else {
            context.getString(R.string.inventory_low_desc, item.supplyDosesLeft ?: 0, unitName)
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            item.id.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, INVENTORY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.inventory_low_title, item.title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(alertId(item), notification)
    }

    /** Clears the low-supply alert notification for this item, if one is showing. */
    fun cancelLowSupplyNotification(context: Context, item: MedData) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(alertId(item))
    }

    fun logPrnUsage(context: Context?, item: MedData, quantity: Int = 1, date: LocalDate = LocalDate.now(), time: LocalTime = LocalTime.now()): MedData {
        val units = item.supplyUnitsPerDose.coerceAtLeast(1) * quantity.coerceAtLeast(1)
        val left = item.supplyDosesLeft ?: return item.copy(prnUsages = item.prnUsages + PrnUse(date, time, quantity.coerceAtLeast(1)))
        val newLeft = (left - units).coerceAtLeast(0)
        var updated = item.copy(supplyDosesLeft = newLeft, prnUsages = item.prnUsages + PrnUse(date, time, quantity.coerceAtLeast(1)))
        updated = logSupplyChange(updated, SupplyChangeKind.TAKEN, delta = -units, balanceAfter = newLeft, date = date, time = time, unit = updated.supplyUnit)
        return evaluateItem(context, updated)
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = android.app.NotificationChannel(
                INVENTORY_CHANNEL_ID,
                context.getString(R.string.inventory_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.inventory_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
