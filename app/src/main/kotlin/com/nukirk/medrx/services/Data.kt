package com.nukirk.medrx.services

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.Keep
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.nukirk.medrx.ItemType
import com.nukirk.medrx.PREF_SORT_ORDER
import com.nukirk.medrx.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Collections
import kotlin.math.max

sealed class EditItem {
    abstract val uniqueId: String

    data class Card(val med: MedData) : EditItem() {
        override val uniqueId = "card_${med.id}"
    }

    data class Group(val divider: MedData, val cards: List<MedData>) : EditItem() {
        override val uniqueId = "group_${divider.id}"
    }
}

/** Supply (inventory) settings for a medicine, as entered in the editor. */
data class InventoryEntry(
    val dosesLeft: Int,
    val dosesPerRefill: Int,
    val lowThreshold: Int,
    val unit: SupplyUnit = SupplyUnit.DOSE,
    val unitsPerDose: Int = 1
)

enum class SupplyUnit {
    DOSE, TABLET, CAPSULE, ML, SPRAY, PUFF
}

fun SupplyUnit.labelResId(): Int = when (this) {
    SupplyUnit.DOSE -> R.string.supply_unit_dose
    SupplyUnit.TABLET -> R.string.supply_unit_tablet
    SupplyUnit.CAPSULE -> R.string.supply_unit_capsule
    SupplyUnit.ML -> R.string.supply_unit_ml
    SupplyUnit.SPRAY -> R.string.supply_unit_spray
    SupplyUnit.PUFF -> R.string.supply_unit_puff
}

/** Why a scheduled dose was deliberately skipped. */
enum class SkipReason {
    ADVERSE_REACTION,       // Allergic / adverse reaction (rash, swelling, side effects)
    DOCTOR_DIRECTED,        // Doctor / clinic directed hold
    PROCEDURE_FASTING,      // Upcoming procedure / fasting
    VITALS_OUT_OF_RANGE,    // BP, heart rate or blood sugar threshold met
    DOUBLE_DOSE_PROTECTION, // Already taken or taken too recently
    ACUTE_ILLNESS,          // Acute illness / vomiting; cannot keep meds down
    SUPPLY_MISSING,         // Dose unavailable or compromised
    OTHER                   // Patient discretion
}

/** How intense a logged symptom episode is. */
enum class SymptomSeverity {
    MILD,
    MODERATE,
    SEVERE
}

/** A dose skipped on a given date: reason plus an optional note. */
data class SkipRecord(
    val reason: SkipReason,
    val time: LocalTime = LocalTime.now(),
    val note: String? = null
) : Serializable

/** What kind of stock change produced a [SupplyChange] entry. */
enum class SupplyChangeKind {
    /** One dose logged as taken (stock decreases by unitsPerDose). */
    TAKEN,
    /** A logged dose was un-taken (stock increases by unitsPerDose). */
    REFUND,
    /** Stock typed/stepped to a new value in the editor (no dose event). */
    CORRECTION,
    /** A correction that matches whole refills — most likely a restock. */
    REFILL,
    /** First supply value recorded for the entry. */
    INITIAL,
    /** Spray/puff priming: stock-only consumption, not a taken dose. */
    PRIMING,
    /** Other waste/spillage: stock-only consumption, not a taken dose. */
    WASTE
}

/**
 * One entry in a medicine's supply ledger: what changed, when, by how much,
 * and the resulting balance. Kept (bounded) so discrepancies like a dose
 * counted twice can be traced after the fact.
 */
@Keep
data class SupplyChange(
    val kind: SupplyChangeKind,
    val date: LocalDate,
    val time: LocalTime,
    val delta: Int,
    val balanceAfter: Int,
    val unit: SupplyUnit = SupplyUnit.DOSE
) : Serializable

/** One as-needed use: separate timestamp, never collapsed to one-per-day. */
@Keep
data class PrnUse(
    val date: LocalDate,
    val time: LocalTime,
    val quantity: Int = 1
) : Serializable

@Keep
data class MedData(
    val id: Long = System.currentTimeMillis(),
    val groupId: Long? = null,
    val type: ItemType,
    val title: String,
    val iconName: String? = null,
    val colorCode: String? = null,
    val frequencyLabel: String? = null,
    val creationDate: LocalDate,
    val creationTime: LocalTime = LocalTime.now(),
    val takenHistory: HashMap<LocalDate, LocalTime> = HashMap(),
    val skipHistory: HashMap<LocalDate, SkipRecord> = HashMap(),
    val recurrenceDays: List<DayOfWeek>? = null,
    val endDate: LocalDate? = null,
    val notes: String? = null,
    val displayOrder: Int = 0,
    val intervalGap: Int? = null,
    val category: String? = null,
    val notificationType: Int = 0,
    val supplyDosesLeft: Int? = null,
    val supplyDosesPerRefill: Int? = null,
    val supplyLowThreshold: Int? = null,
    val supplyAlertShown: Boolean = false,
    val supplyLedger: List<SupplyChange> = emptyList(),
    val symptomSeverity: SymptomSeverity? = null,
    /** Stable identity shared by dose/container versions of the same treatment. */
    val medicationId: String? = null,
    /** Human-readable dose amount, e.g. 10, 5, or 2.5. */
    val doseAmount: String? = null,
    /** Dose unit/form, e.g. mg, mL, tablet, spray, or puff. */
    val doseUnit: String? = null,
    /** Inventory count unit; dose strength (doseUnit) is intentionally separate. */
    val supplyUnit: SupplyUnit = SupplyUnit.DOSE,
    /** Number of stock units consumed by one scheduled administration. */
    val supplyUnitsPerDose: Int = 1,
    /** True for spray/puff estimates, which can drift because of priming/waste. */
    val supplyEstimated: Boolean = false,
    /** As-needed medication: no schedule, no missed, no alarms. */
    val isPrn: Boolean = false,
    val prnMaxPerDay: Int? = null,
    val prnMinIntervalHours: Int? = null,
    val prnUsages: List<PrnUse> = emptyList()
) : Serializable {

    /** Stable key used by Stats without collapsing separate medication versions. */
    fun medicationInstanceKey(): String =
        "$title|${groupId?.toString() ?: id.toString()}:$creationTime|${doseAmount ?: ""}|${doseUnit ?: ""}"

    fun treatmentKey(): String = medicationId ?: groupId?.toString() ?: id.toString()

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("groupId", groupId ?: JSONObject.NULL)
        json.put("type", type.name)
        json.put("title", title)
        json.put("iconName", iconName ?: JSONObject.NULL)
        json.put("colorCode", colorCode ?: JSONObject.NULL)
        json.put("frequencyLabel", frequencyLabel ?: JSONObject.NULL)
        json.put("creationDate", creationDate.toString())
        json.put("creationTime", creationTime.toString())

        val historyObj = JSONObject()
        takenHistory.forEach { (k, v) ->
            historyObj.put(k.toString(), v.toString())
        }
        json.put("takenHistory", historyObj)

        val skipsObj = JSONObject()
        skipHistory.forEach { (k, v) ->
            val rec = JSONObject()
            rec.put("reason", v.reason.name)
            rec.put("time", v.time.toString())
            if (v.note != null) rec.put("note", v.note)
            skipsObj.put(k.toString(), rec)
        }
        json.put("skipHistory", skipsObj)

        val recArray = JSONArray()
        recurrenceDays?.forEach { recArray.put(it.name) }
        json.put("recurrenceDays", if (recurrenceDays == null) JSONObject.NULL else recArray)

        json.put("endDate", endDate?.toString() ?: JSONObject.NULL)
        json.put("notes", notes ?: JSONObject.NULL)
        json.put("displayOrder", displayOrder)
        json.put("intervalGap", intervalGap ?: JSONObject.NULL)
        json.put("category", category ?: JSONObject.NULL)
        json.put("notificationType", notificationType)
        json.put("supplyDosesLeft", supplyDosesLeft ?: JSONObject.NULL)
        json.put("supplyDosesPerRefill", supplyDosesPerRefill ?: JSONObject.NULL)
        json.put("supplyLowThreshold", supplyLowThreshold ?: JSONObject.NULL)
        json.put("supplyAlertShown", supplyAlertShown)
        val ledgerArray = JSONArray()
        supplyLedger.forEach { change ->
            val obj = JSONObject()
            obj.put("kind", change.kind.name)
            obj.put("date", change.date.toString())
            obj.put("time", change.time.toString())
            obj.put("delta", change.delta)
            obj.put("balanceAfter", change.balanceAfter)
            obj.put("unit", change.unit.name)
            ledgerArray.put(obj)
        }
        json.put("supplyLedger", ledgerArray)
        json.put("symptomSeverity", symptomSeverity?.name ?: JSONObject.NULL)
        json.put("medicationId", medicationId ?: JSONObject.NULL)
        json.put("doseAmount", doseAmount ?: JSONObject.NULL)
        json.put("doseUnit", doseUnit ?: JSONObject.NULL)
        json.put("supplyUnit", supplyUnit.name)
        json.put("supplyUnitsPerDose", supplyUnitsPerDose)
        json.put("supplyEstimated", supplyEstimated)
        json.put("isPrn", isPrn)
        json.put("prnMaxPerDay", prnMaxPerDay ?: JSONObject.NULL)
        json.put("prnMinIntervalHours", prnMinIntervalHours ?: JSONObject.NULL)
        val prnArray = JSONArray()
        prnUsages.forEach { u ->
            val obj = JSONObject()
            obj.put("date", u.date.toString())
            obj.put("time", u.time.toString())
            obj.put("quantity", u.quantity)
            prnArray.put(obj)
        }
        json.put("prnUsages", prnArray)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): MedData {
            val history = HashMap<LocalDate, LocalTime>()
            val historyObj = json.optJSONObject("takenHistory")
            if (historyObj != null) {
                historyObj.keys().forEach { key ->
                    history[LocalDate.parse(key)] = LocalTime.parse(historyObj.getString(key))
                }
            }

            val skips = HashMap<LocalDate, SkipRecord>()
            val skipsObj = json.optJSONObject("skipHistory")
            if (skipsObj != null) {
                skipsObj.keys().forEach { key ->
                    val rec = skipsObj.getJSONObject(key)
                    val reason = try {
                        SkipReason.valueOf(rec.getString("reason"))
                    } catch (e: Exception) {
                        SkipReason.OTHER
                    }
                    skips[LocalDate.parse(key)] = SkipRecord(
                        reason = reason,
                        time = LocalTime.parse(rec.getString("time")),
                        note = if (rec.isNull("note")) null else rec.optString("note")
                    )
                }
            }

            val recDays: List<DayOfWeek>? = if (json.isNull("recurrenceDays")) null else {
                val arr = json.getJSONArray("recurrenceDays")
                val list = mutableListOf<DayOfWeek>()
                for (i in 0 until arr.length()) {
                    list.add(DayOfWeek.valueOf(arr.getString(i)))
                }
                list
            }

            return MedData(
                id = json.getLong("id"),
                groupId = if (json.isNull("groupId")) null else json.getLong("groupId"),
                type = ItemType.valueOf(json.getString("type")),
                title = json.getString("title"),
                iconName = if (json.isNull("iconName")) null else json.getString("iconName"),
                colorCode = if (json.isNull("colorCode")) null else json.getString("colorCode"),
                frequencyLabel = if (json.isNull("frequencyLabel")) null else json.getString("frequencyLabel"),
                creationDate = LocalDate.parse(json.getString("creationDate")),
                creationTime = LocalTime.parse(json.getString("creationTime")),
                takenHistory = history,
                skipHistory = skips,
                recurrenceDays = recDays,
                endDate = if (json.isNull("endDate")) null else LocalDate.parse(json.getString("endDate")),
                notes = if (json.isNull("notes")) null else json.getString("notes"),
                displayOrder = json.optInt("displayOrder", 0),
                intervalGap = if (json.isNull("intervalGap")) null else json.getInt("intervalGap"),
                category = if (json.isNull("category")) null else json.getString("category"),
                notificationType = json.optInt("notificationType", 0),
                supplyDosesLeft = if (json.isNull("supplyDosesLeft")) null else json.optInt("supplyDosesLeft"),
                supplyDosesPerRefill = if (json.isNull("supplyDosesPerRefill")) null else json.optInt("supplyDosesPerRefill"),
                supplyLowThreshold = if (json.isNull("supplyLowThreshold")) null else json.optInt("supplyLowThreshold"),
                supplyAlertShown = json.optBoolean("supplyAlertShown", false),
                symptomSeverity = if (json.isNull("symptomSeverity")) null else try {
                    SymptomSeverity.valueOf(json.getString("symptomSeverity"))
                } catch (e: Exception) {
                    null
                },
                medicationId = if (json.isNull("medicationId")) {
                    // Legacy records did not have an identity. Group IDs are
                    // already shared by dose-time slots, so use them as the
                    // version identity without rewriting old data.
                    json.optString("groupId").takeIf { it.isNotBlank() && it != "null" }
                } else json.optString("medicationId").takeIf { it.isNotBlank() },
                doseAmount = if (json.isNull("doseAmount")) null else json.optString("doseAmount").takeIf { it.isNotBlank() },
                doseUnit = if (json.isNull("doseUnit")) null else json.optString("doseUnit").takeIf { it.isNotBlank() },
                supplyUnit = try {
                    SupplyUnit.valueOf(json.optString("supplyUnit", "DOSE"))
                } catch (_: Exception) {
                    SupplyUnit.DOSE
                },
                supplyUnitsPerDose = json.optInt("supplyUnitsPerDose", 1).coerceAtLeast(1),
                supplyEstimated = json.optBoolean("supplyEstimated", false),
                isPrn = json.optBoolean("isPrn", false),
                prnMaxPerDay = if (json.isNull("prnMaxPerDay")) null else json.optInt("prnMaxPerDay"),
                prnMinIntervalHours = if (json.isNull("prnMinIntervalHours")) null else json.optInt("prnMinIntervalHours"),
                prnUsages = buildList {
                    json.optJSONArray("prnUsages")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            add(
                                PrnUse(
                                    date = LocalDate.parse(obj.getString("date")),
                                    time = LocalTime.parse(obj.getString("time")),
                                    quantity = obj.optInt("quantity", 1).coerceAtLeast(1)
                                )
                            )
                        }
                    }
                },

                supplyLedger = buildList {
                    json.optJSONArray("supplyLedger")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val kind = try {
                                SupplyChangeKind.valueOf(obj.getString("kind"))
                            } catch (e: Exception) {
                                SupplyChangeKind.CORRECTION
                            }
                            add(
                                SupplyChange(
                                    kind = kind,
                                    date = LocalDate.parse(obj.getString("date")),
                                    time = LocalTime.parse(obj.getString("time")),
                                    delta = obj.optInt("delta", 0),
                                    balanceAfter = obj.optInt("balanceAfter", 0),
                                    unit = try { SupplyUnit.valueOf(obj.optString("unit", "DOSE")) } catch (_: Exception) { SupplyUnit.DOSE }
                                )
                            )
                        }
                    }
                }
            )
        }
    }
}

class MigrationObjectInputStream(inStream: InputStream) : ObjectInputStream(inStream) {
    override fun readClassDescriptor(): ObjectStreamClass {
        val desc = super.readClassDescriptor()
        return try {
            val clazz = Class.forName(desc.name)
            ObjectStreamClass.lookup(clazz) ?: desc
        } catch (e: Exception) {
            desc
        }
    }
}

object DataRepository {
    private const val OLD_FILE = "med_data.dat"
    private const val NEW_FILE = "med_data_v2.json"

    fun loadData(context: Context): List<MedData> {
        val newFile = File(context.filesDir, NEW_FILE)
        if (newFile.exists()) {
            try {
                val jsonString = newFile.readText()
                val jsonArray = JSONArray(jsonString)
                val list = mutableListOf<MedData>()
                for (i in 0 until jsonArray.length()) {
                    list.add(MedData.fromJson(jsonArray.getJSONObject(i)))
                }
                return list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val migrated = migrateLegacyData(context)
        if (migrated.isNotEmpty()) {
            saveData(context, migrated)
        }
        return migrated
    }

    fun saveData(context: Context, items: List<MedData>) {
        try {
            val jsonArray = JSONArray()
            items.forEach { jsonArray.put(it.toJson()) }
            File(context.filesDir, NEW_FILE).writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Every data mutation funnels through here — keep the supply widget current.
        MedSupplyWidgetProvider.updateAll(context)
    }

    private fun migrateLegacyData(context: Context): List<MedData> {
        val oldFile = File(context.filesDir, OLD_FILE)
        if (!oldFile.exists()) return emptyList()

        try {
            val fis = FileInputStream(oldFile)
            val ois = MigrationObjectInputStream(fis)

            val oldList = ois.readObject() as? ArrayList<*> ?: return emptyList()
            ois.close()

            val migratedList = oldList.mapNotNull { obj ->
                if (obj is j4.p1) {
                    val migratedType = when (obj.f.name) {
                        "Medicine", "b" -> ItemType.Medicine
                        else -> ItemType.Event
                    }

                    MedData(
                        id = obj.d,
                        groupId = obj.e,
                        type = migratedType,
                        title = obj.g,
                        iconName = obj.h,
                        colorCode = obj.i,
                        frequencyLabel = obj.j,
                        creationDate = obj.k,
                        creationTime = obj.l,
                        takenHistory = obj.m,
                        recurrenceDays = obj.n,
                        endDate = obj.o,
                        notes = null,
                        displayOrder = 0,
                        intervalGap = null,
                        category = null,
                        notificationType = 0
                    )
                } else null
            }

            oldFile.renameTo(File(context.filesDir, "med_data_migrated.dat"))
            return migratedList
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }
}

class MedViewModel(application: Application) : AndroidViewModel(application) {
    private val _items = mutableStateListOf<MedData>()
    val items: List<MedData> get() = _items

    var selectedDate by mutableStateOf(LocalDate.now())
    var editItems by mutableStateOf<List<EditItem>>(emptyList())
    var editHasChanges by mutableStateOf(false)

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.nukirk.medrx.RELOAD_DATA") {
                reloadData()
            }
        }
    }

    init {
        InventoryService.createNotificationChannel(application)
        loadData()
        // A supply restored from a backup or rebooted below its threshold must
        // alert without waiting for the next dose event.
        if (InventoryService.evaluateAll(application, _items)) {
            saveData()
        }
        syncToWear()

        // Re-arm dose alarms for every medicine on startup. Alarms die on
        // force-stop or a crashed process, and until now they only came back
        // when the user happened to edit or take a dose.
        _items.filter { it.type == ItemType.Medicine && !it.isPrn }.forEach { item ->
            try {
                NotificationReceiver.scheduleNotification(getApplication(), item)
            } catch (e: Exception) {
            }
        }

        val filter = IntentFilter("com.nukirk.medrx.RELOAD_DATA")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                application,
                updateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadEditItems(date: LocalDate) {
        val initial = _items.filter { item ->
            when (item.type) {
                ItemType.Event -> item.creationDate == date
                ItemType.Symptom -> item.creationDate == date
                ItemType.Illness -> false
                ItemType.Medicine -> {
                    if (item.isPrn) true else {
                        val isAfterStart = !date.isBefore(item.creationDate)
                        val isBeforeEnd = item.endDate == null || !date.isAfter(item.endDate)
                        val isCorrectDay =
                            item.recurrenceDays.isNullOrEmpty() || item.recurrenceDays.contains(date.dayOfWeek)
                        val isCorrectGap = item.intervalGap == null || ChronoUnit.DAYS.between(
                            item.creationDate,
                            date
                        ) % item.intervalGap == 0L
                        isAfterStart && isBeforeEnd && isCorrectDay && isCorrectGap
                    }
                }
            }
        }.sortedWith(compareBy({ it.displayOrder }, { it.creationTime }))

        val result = mutableListOf<EditItem>()
        var currentGroupDivider: MedData? = null
        val currentGroupCards = mutableListOf<MedData>()

        for (item in initial) {
            if (item.iconName == "DIVIDER") {
                if (currentGroupDivider != null) {
                    result.add(EditItem.Group(currentGroupDivider, currentGroupCards.toList()))
                } else if (currentGroupCards.isNotEmpty()) {
                    currentGroupCards.forEach { result.add(EditItem.Card(it)) }
                }

                if (item.title.isNotBlank()) {
                    currentGroupDivider = item
                    currentGroupCards.clear()
                } else {
                    currentGroupDivider = null
                    currentGroupCards.clear()
                }
            } else {
                if (currentGroupDivider != null) {
                    currentGroupCards.add(item)
                } else {
                    result.add(EditItem.Card(item))
                }
            }
        }
        if (currentGroupDivider != null) {
            result.add(EditItem.Group(currentGroupDivider, currentGroupCards.toList()))
        } else if (currentGroupCards.isNotEmpty()) {
            currentGroupCards.forEach { result.add(EditItem.Card(it)) }
        }
        editItems = result
        editHasChanges = false
    }

    fun swapTopEditItems(from: Int, to: Int) {
        val newItems = editItems.toMutableList()
        Collections.swap(newItems, from, to)
        editItems = newItems
        editHasChanges = true
    }

    fun swapInnerEditItems(group: EditItem.Group, from: Int, to: Int) {
        val gIdx = editItems.indexOf(group)
        if (gIdx != -1) {
            val newCards = group.cards.toMutableList()
            Collections.swap(newCards, from, to)
            val newItems = editItems.toMutableList()
            newItems[gIdx] = group.copy(cards = newCards)
            editItems = newItems
            editHasChanges = true
        }
    }

    fun removeEditItem(uniqueId: String) {
        editItems = editItems.filter { it.uniqueId != uniqueId }
        editHasChanges = true
    }

    fun removeGroupComplete(group: EditItem.Group) {
        val newItems = editItems.toMutableList()
        val gIdx = newItems.indexOf(group)
        if (gIdx != -1) {
            newItems.removeAt(gIdx)
            newItems.addAll(gIdx, group.cards.map { EditItem.Card(it) })
            editItems = newItems
            editHasChanges = true
        }
    }

    fun removeCardFromGroup(group: EditItem.Group, cardId: Long) {
        val newItems = editItems.toMutableList()
        val gIdx = newItems.indexOf(group)
        if (gIdx != -1) {
            val newCards = group.cards.filter { it.id != cardId }
            val theCard = group.cards.find { it.id == cardId }
            if (newCards.isEmpty()) {
                newItems.removeAt(gIdx)
                if (theCard != null) newItems.add(gIdx, EditItem.Card(theCard))
            } else {
                newItems[gIdx] = group.copy(cards = newCards)
                if (theCard != null) newItems.add(gIdx + 1, EditItem.Card(theCard))
            }
            editItems = newItems
            editHasChanges = true
        }
    }

    fun addCardToGroup(cardItem: EditItem.Card, targetGroupId: Long) {
        val newItems = editItems.toMutableList()
        newItems.remove(cardItem)
        val gIdx = newItems.indexOfFirst { it is EditItem.Group && it.divider.id == targetGroupId }
        if (gIdx != -1) {
            val group = newItems[gIdx] as EditItem.Group
            newItems[gIdx] = group.copy(cards = group.cards + cardItem.med)
        }
        editItems = newItems
        editHasChanges = true
    }

    fun deleteCardFromGroup(group: EditItem.Group, cardId: Long) {
        val newItems = editItems.toMutableList()
        val gIdx = newItems.indexOf(group)
        if (gIdx != -1) {
            val newCards = group.cards.filter { it.id != cardId }
            if (newCards.isEmpty()) newItems.removeAt(gIdx)
            else newItems[gIdx] = group.copy(cards = newCards)
            editItems = newItems
            editHasChanges = true
        }
    }

    fun addEditGroup(groupName: String, selectedDate: LocalDate) {
        val newDivider = MedData(
            id = System.nanoTime(),
            groupId = System.currentTimeMillis(),
            type = ItemType.Event,
            title = groupName,
            iconName = "DIVIDER",
            colorCode = "dynamic",
            frequencyLabel = "",
            creationDate = selectedDate,
            creationTime = LocalTime.MIN,
            takenHistory = hashMapOf(),
            recurrenceDays = null,
            endDate = null,
            notes = null,
            displayOrder = 0,
            intervalGap = null,
            category = null
        )
        editItems = editItems + EditItem.Group(newDivider, emptyList())
        editHasChanges = true
    }

    fun saveEditOrder(selectedDate: LocalDate) {
        val flatList = mutableListOf<MedData>()
        var lastWasGroup = false

        for (item in editItems) {
            when (item) {
                is EditItem.Card -> {
                    if (lastWasGroup) {
                        flatList.add(
                            MedData(
                                id = System.nanoTime(),
                                groupId = System.currentTimeMillis(),
                                type = ItemType.Event,
                                title = "",
                                iconName = "DIVIDER",
                                colorCode = "dynamic",
                                frequencyLabel = "",
                                creationDate = selectedDate,
                                creationTime = LocalTime.MIN,
                                takenHistory = hashMapOf(),
                                recurrenceDays = null,
                                endDate = null,
                                notes = null,
                                displayOrder = 0,
                                intervalGap = null,
                                category = null
                            )
                        )
                    }
                    flatList.add(item.med)
                    lastWasGroup = false
                }

                is EditItem.Group -> {
                    flatList.add(item.divider)
                    flatList.addAll(item.cards)
                    lastWasGroup = true
                }
            }
        }

        val flatListIds = flatList.map { it.id }.toSet()
        val updatedAllItems = _items.filter {
            it.creationDate != selectedDate || it.iconName != "DIVIDER" || flatListIds.contains(it.id)
        }.toMutableList()

        flatList.forEachIndexed { newIndex, item ->
            val globalIndex = updatedAllItems.indexOfFirst { it.id == item.id }
            if (globalIndex != -1) {
                updatedAllItems[globalIndex] =
                    updatedAllItems[globalIndex].copy(displayOrder = newIndex)
            } else if (item.iconName == "DIVIDER") {
                updatedAllItems.add(item.copy(displayOrder = newIndex))
            }
        }

        _items.clear()
        _items.addAll(updatedAllItems)
        saveData()
        editHasChanges = false
        val prefs =
            getApplication<Application>().getSharedPreferences("med_settings", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SORT_ORDER, "custom").apply()
        getApplication<Application>().sendBroadcast(
            Intent("com.nukirk.medrx.REFRESH_DATA").setPackage(
                getApplication<Application>().packageName
            )
        )
    }

    fun addItem(
        type: ItemType,
        title: String,
        iconName: String? = null,
        colorCode: String? = null,
        times: List<LocalTime>,
        days: List<DayOfWeek>?,
        notes: String? = null,
        category: String? = null,
        intervalGap: Int? = null,
        notificationType: Int = 0,
        supply: InventoryEntry? = null,
        symptomSeverity: SymptomSeverity? = null,
        doseAmount: String? = null,
        doseUnit: String? = null,
        medicationId: String? = null,
        isPrn: Boolean = false,
        prnMaxPerDay: Int? = null,
        prnMinIntervalHours: Int? = null
    ) {
        val groupId = System.currentTimeMillis()
        val stableMedicationId = medicationId ?: groupId.toString()
        val baseDate = selectedDate
        val context = getApplication<Application>()

        var currentOrder = (_items.maxOfOrNull { it.displayOrder } ?: 0) + 1

        val itemsOnDate = _items.filter { item ->
            when (item.type) {
                ItemType.Event -> item.creationDate == selectedDate
                ItemType.Symptom -> item.creationDate == selectedDate
                ItemType.Illness -> {
                    val isAfterStart = !selectedDate.isBefore(item.creationDate)
                    val isBeforeEnd = item.endDate == null || !selectedDate.isAfter(item.endDate)
                    isAfterStart && isBeforeEnd
                }

                ItemType.Medicine -> {
                    if (item.isPrn) false else {
                        val isAfterStart = !selectedDate.isBefore(item.creationDate)
                        val isBeforeEnd = item.endDate == null || !selectedDate.isAfter(item.endDate)
                        val isCorrectDay =
                            item.recurrenceDays.isNullOrEmpty() || item.recurrenceDays.contains(
                                selectedDate.dayOfWeek
                            )
                        val isCorrectGap = item.intervalGap == null || ChronoUnit.DAYS.between(
                            item.creationDate,
                            selectedDate
                        ) % item.intervalGap == 0L
                        isAfterStart && isBeforeEnd && isCorrectDay && isCorrectGap
                    }
                }
            }
        }.sortedBy { it.displayOrder }

        var inGroup = false
        for (item in itemsOnDate) {
            if (item.iconName == "DIVIDER") {
                inGroup = item.title.isNotBlank()
            }
        }
        if (inGroup) {
            val emptyDivider = MedData(
                id = System.nanoTime(),
                groupId = System.currentTimeMillis(),
                type = ItemType.Event,
                title = "",
                iconName = "DIVIDER",
                colorCode = "dynamic",
                frequencyLabel = "",
                creationDate = selectedDate,
                creationTime = LocalTime.MIN,
                takenHistory = hashMapOf(),
                recurrenceDays = null,
                endDate = null,
                notes = null,
                displayOrder = currentOrder++,
                category = null,
                intervalGap = null,
                notificationType = 0
            )
            _items.add(emptyDivider)
        }

        // As-needed meds have no schedule slots: exactly one entry per group.
        val entryTimes = if (isPrn) listOf(times.firstOrNull() ?: LocalTime.now()) else times
        entryTimes.forEach { time ->
            val effectiveDays = if (isPrn) null else days
            val effectiveGap = if (isPrn) null else intervalGap
            val effectiveNotif = if (isPrn) 0 else notificationType
            val newItem = MedData(
                id = System.nanoTime(),
                groupId = groupId,
                type = type,
                title = title,
                iconName = iconName,
                colorCode = colorCode,
                frequencyLabel = if (isPrn) context.getString(R.string.prn_label) else if (effectiveGap == 14) context.getString(R.string.frequency_unit_biweek)
                else if (effectiveDays != null) context.getString(R.string.frequency_specific_days)
                else if (times.size > 1) context.getString(
                    R.string.frequency_daily_multiple,
                    times.size
                )
                else context.getString(R.string.frequency_daily),
                creationDate = baseDate,
                creationTime = time,
                recurrenceDays = effectiveDays,
                symptomSeverity = symptomSeverity,
                endDate = null,
                notes = notes,
                displayOrder = currentOrder++,
                category = category,
                intervalGap = effectiveGap,
                notificationType = effectiveNotif,
                medicationId = stableMedicationId,
                doseAmount = doseAmount,
                doseUnit = doseUnit,
                isPrn = isPrn,
                prnMaxPerDay = prnMaxPerDay,
                prnMinIntervalHours = prnMinIntervalHours
            )
            _items.add(newItem)
            if (type == ItemType.Medicine) {
                NotificationReceiver.scheduleNotification(getApplication(), newItem)
                applySupplySettings(newItem, supply)
            }
        }
        saveData()
    }

    fun updateItem(
        originalItem: MedData,
        title: String,
        iconName: String?,
        colorCode: String?,
        times: List<LocalTime>,
        days: List<DayOfWeek>?,
        notes: String?,
        intervalGap: Int?,
        notificationType: Int = 0,
        rangeStart: Long? = -2L,
        rangeEnd: Long? = -2L,
        supply: InventoryEntry? = null,
        symptomSeverity: SymptomSeverity? = null,
        doseAmount: String? = null,
        doseUnit: String? = null,
        isPrn: Boolean? = null,
        prnMaxPerDay: Int? = null,
        prnMinIntervalHours: Int? = null
    ) {
        val context = getApplication<Application>()
        val freqLabel = if (intervalGap == 14) context.getString(R.string.frequency_unit_biweek)
        else if (days != null) context.getString(R.string.frequency_specific_days)
        else if (times.size > 1) context.getString(
            R.string.frequency_daily_multiple,
            times.size
        )
        else context.getString(R.string.frequency_daily)

        // As-needed meds carry one flat usage log and no schedule, so an edit
        // must never take the occurrence/range split path (that would fork the
        // groups and duplicate PRN usages). Force a plain field rebuild.
        val effectiveRangeStart = if (originalItem.isPrn) MedUpdatePlanner.NOT_A_RANGE else rangeStart
        val effectiveRangeEnd = if (originalItem.isPrn) MedUpdatePlanner.NOT_A_RANGE else rangeEnd

        // All edit rules (no-op guard, slot preservation, history inheritance,
        // phantom-fragment dropping) live in the pure, unit-tested planner.
        val plan = MedUpdatePlanner.plan(
            MedUpdatePlanner.Request(
                originalItem = originalItem,
                title = title,
                iconName = iconName,
                colorCode = colorCode,
                times = times,
                days = days,
                notes = notes,
                intervalGap = intervalGap,
                notificationType = notificationType,
                freqLabel = freqLabel,
                rangeStart = effectiveRangeStart,
                rangeEnd = effectiveRangeEnd,
                selectedDate = selectedDate,
                symptomSeverity = symptomSeverity,
                doseAmount = doseAmount,
                doseUnit = doseUnit,
                doseFieldsProvided = true,
                isPrn = isPrn,
                prnMaxPerDay = prnMaxPerDay,
                prnMinIntervalHours = prnMinIntervalHours
            ),
            supply,
            _items.filter { it.type == originalItem.type }
        )

        when (plan) {
            is MedUpdatePlanner.Plan.None -> return

            is MedUpdatePlanner.Plan.SupplyOnly -> {
                applySupplySettings(originalItem, supply)
                return
            }

            is MedUpdatePlanner.Plan.Rebuild -> {
                // Entries being replaced get new IDs — cancel alerts keyed to
                // the old IDs so no orphaned low-supply notification survives.
                plan.removeIds.forEach { id ->
                    _items.firstOrNull { it.id == id }?.let {
                        InventoryService.cancelLowSupplyNotification(context, it)
                    }
                }
                val removedById = _items.filter { it.id in plan.removeIds }.associateBy { it.id }
                _items.removeAll { it.id in plan.removeIds }

                val newGroupId = System.currentTimeMillis()
                plan.entries.forEachIndexed { i, entry ->
                    var withIds = entry.copy(
                        id = System.nanoTime() + i,
                        groupId = if (entry.groupId == MedUpdatePlanner.NEW_GROUP) newGroupId else entry.groupId,
                        medicationId = entry.medicationId ?: originalItem.medicationId ?: originalItem.groupId?.toString()
                    )
                    // Ledger: a rebuild that changes the tracked count is a
                    // correction too — log it against the slot it replaces.
                    val oldSlot = removedById.values.firstOrNull { it.groupId == entry.groupId && it.creationTime == entry.creationTime }
                        ?: removedById.values.firstOrNull { it.creationTime == entry.creationTime && it.endDate == entry.endDate }
                    if (oldSlot != null && (withIds.supplyDosesLeft != oldSlot.supplyDosesLeft ||
                                withIds.supplyUnit != oldSlot.supplyUnit ||
                                withIds.supplyUnitsPerDose != oldSlot.supplyUnitsPerDose)) {
                        val delta = (withIds.supplyDosesLeft ?: 0) - (oldSlot.supplyDosesLeft ?: 0)
                        val kind = when {
                            oldSlot.supplyDosesLeft == null -> SupplyChangeKind.INITIAL
                            delta > 0 && (withIds.supplyDosesPerRefill ?: 0) > 0 &&
                                    delta % withIds.supplyDosesPerRefill!! == 0 -> SupplyChangeKind.REFILL
                            else -> SupplyChangeKind.CORRECTION
                        }
                        withIds = InventoryService.logSupplyChange(
                            withIds, kind, delta = delta, balanceAfter = withIds.supplyDosesLeft ?: 0, unit = withIds.supplyUnit
                        )
                    }
                    _items.add(withIds)
                    if (withIds.type == ItemType.Medicine) {
                        NotificationReceiver.scheduleNotification(getApplication(), withIds)
                    }
                }
            }
        }
        saveData()
        refreshLowSupplyAlerts()
    }

    /** Re-evaluates every item against its low-supply threshold and persists changes. */
    private fun refreshLowSupplyAlerts() {
        if (InventoryService.evaluateAll(getApplication(), _items)) {
            saveData()
        }
    }

    fun deleteItem(item: MedData, deleteDate: LocalDate) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return

        if (!deleteDate.isAfter(item.creationDate)) {
            _items.removeAt(index)
        } else {
            val updatedItem = item.copy(endDate = deleteDate.minusDays(1))
            _items[index] = updatedItem
        }

        val itemsOnDate = _items.filter {
            when (it.type) {
                ItemType.Event -> it.creationDate == deleteDate
                ItemType.Symptom -> it.creationDate == deleteDate
                ItemType.Illness -> {
                    val isAfterStart = !deleteDate.isBefore(it.creationDate)
                    val isBeforeEnd = it.endDate == null || !deleteDate.isAfter(it.endDate)
                    isAfterStart && isBeforeEnd
                }

                ItemType.Medicine -> {
                    if (it.isPrn) false else {
                        val isAfterStart = !deleteDate.isBefore(it.creationDate)
                        val isBeforeEnd = it.endDate == null || !deleteDate.isAfter(it.endDate)
                        val isCorrectDay =
                            it.recurrenceDays.isNullOrEmpty() || it.recurrenceDays.contains(deleteDate.dayOfWeek)
                        val isCorrectGap = it.intervalGap == null || ChronoUnit.DAYS.between(
                            it.creationDate,
                            deleteDate
                        ) % it.intervalGap == 0L
                        isAfterStart && isBeforeEnd && isCorrectDay && isCorrectGap
                    }
                }
            }
        }.sortedWith(compareBy({ it.displayOrder }, { it.creationTime }))

        var changed = true
        var currentList = itemsOnDate
        val dividersToRemove = mutableSetOf<Long>()
        while (changed) {
            changed = false
            val toRemove = currentList.filterIndexed { i, current ->
                if (current.iconName == "DIVIDER") {
                    val next = currentList.getOrNull(i + 1)
                    next == null || next.iconName == "DIVIDER"
                } else false
            }
            if (toRemove.isNotEmpty()) {
                dividersToRemove.addAll(toRemove.map { it.id })
                currentList = currentList.filterNot { it in toRemove }
                changed = true
            }
        }

        if (dividersToRemove.isNotEmpty()) {
            _items.removeAll { it.id in dividersToRemove }
        }

        saveData()
    }

    /**
     * Applies supply (inventory) settings from the editor to all entries of the
     * item's group. `null` supply turns tracking off. The editor always sends
     * the user's explicit values, so [InventoryEntry.dosesLeft] is stored as-is:
     * typing a corrected count must win over any derived value.
     */
    fun refillSupply(item: MedData, amount: Int? = null) {
        if (item.type != ItemType.Medicine) return
        val refillSize = amount ?: item.supplyDosesPerRefill ?: return
        if (refillSize <= 0) return

        val targets = if (item.groupId != null) {
            _items.filter { it.groupId == item.groupId }
        } else {
            listOf(item)
        }

        targets.forEach { target ->
            val index = _items.indexOfFirst { it.id == target.id }
            if (index != -1 && target.supplyDosesLeft != null) {
                val newLeft = target.supplyDosesLeft + refillSize
                _items[index] = InventoryService.logSupplyChange(
                    target.copy(
                        supplyDosesLeft = newLeft,
                        supplyAlertShown = false
                    ),
                    SupplyChangeKind.REFILL,
                    delta = +refillSize,
                    balanceAfter = newLeft
                )
            }
        }
        saveData()
    }

    fun applySupplySettings(item: MedData, supply: InventoryEntry?) {
        val targets = if (item.groupId != null) {
            _items.filter { it.groupId == item.groupId }
        } else {
            listOf(item)
        }

        val refillsEnabled = supply != null && supply.dosesPerRefill > 0
        var supplySettingsChanged = false

        targets.forEach { target ->
            val newLeft = supply?.dosesLeft
            val index = _items.indexOfFirst { it.id == target.id }
            if (index != -1) {
                var updated = target.copy(
                    supplyDosesLeft = newLeft,
                    supplyDosesPerRefill = supply?.dosesPerRefill?.takeIf { refillsEnabled },
                    supplyLowThreshold = supply?.lowThreshold,
                    supplyUnit = supply?.unit ?: SupplyUnit.DOSE,
                    supplyUnitsPerDose = supply?.unitsPerDose?.coerceAtLeast(1) ?: 1,
                    supplyEstimated = supply?.unit == SupplyUnit.SPRAY || supply?.unit == SupplyUnit.PUFF,
                    supplyAlertShown = false
                )
                // Ledger: record manual corrections (and refill-sized bumps) per
                // slot, only where the count actually changed.
                val supplyBasisChanged = supply != null &&
                        (target.supplyUnit != supply.unit || target.supplyUnitsPerDose != supply.unitsPerDose.coerceAtLeast(1))
                if (newLeft != null && (newLeft != target.supplyDosesLeft || supplyBasisChanged)) {
                    val delta = newLeft - (target.supplyDosesLeft ?: 0)
                    val kind = when {
                        target.supplyDosesLeft == null -> SupplyChangeKind.INITIAL
                        delta > 0 && refillsEnabled && delta % supply.dosesPerRefill == 0 -> SupplyChangeKind.REFILL
                        else -> SupplyChangeKind.CORRECTION
                    }
                    updated = InventoryService.logSupplyChange(
                        updated, kind, delta = delta, balanceAfter = newLeft
                    )
                }
                _items[index] = if (supply != null) InventoryService.evaluateItem(getApplication(), updated) else updated
                supplySettingsChanged = supplySettingsChanged || _items[index] != target
            }
        }
        saveData()
        if (supplySettingsChanged) refreshLowSupplyAlerts()
    }

    /**
     * Applies supply settings to the group that was just created by [addItem].
     * The new group carries the entered title on each of its entries.
     */
    fun setSupplyOnNewestGroup(title: String, supply: InventoryEntry?) {
        if (supply == null) return
        val newest = _items.lastOrNull { it.type == ItemType.Medicine && it.title == title } ?: return
        applySupplySettings(newest, supply)
    }

    fun restoreItem(item: MedData) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index != -1) {
            _items[index] = item
        } else {
            _items.add(item)
        }
        if (item.type == ItemType.Medicine) {
            NotificationReceiver.scheduleNotification(getApplication(), item)
        }
        saveData()
    }

    /**
     * Archives a medicine: schedules stop going forward (tomorrow onward),
     * past history and dose-log days stay intact, and the med can be restored
     * later from Settings. Undoing any alert shown for it.
     */
    fun archiveItem(item: MedData) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return

        // A medicine can have one entry per dose time. Archive the complete
        // group, not only the slot that happened to open the editor; otherwise
        // sibling doses remain active and continue appearing/reminding.
        val original = _items[index]
        val relatedIds = if (original.groupId != null) {
            _items.filter { it.type == ItemType.Medicine && it.groupId == original.groupId }
                .map { it.id }
                .toSet()
        } else {
            setOf(original.id)
        }
        val archiveEnd = LocalDate.now().minusDays(1)

        _items.replaceAll { current ->
            if (current.id in relatedIds) current.copy(endDate = archiveEnd) else current
        }
        _items.filter { it.id in relatedIds }.forEach { archived ->
            // Re-scheduling an ended item cancels its dose alarm; also cancel
            // any low-supply alert keyed to every dose slot.
            NotificationReceiver.scheduleNotification(getApplication(), archived)
            InventoryService.cancelLowSupplyNotification(getApplication(), archived)
        }
        saveData()
        syncToWear()
    }

    /** Starts a new dose/container version while preserving the old version's history and ledger. */
    fun startNewMedicationVersion(
        item: MedData,
        effectiveDate: LocalDate,
        doseAmount: String?,
        doseUnit: String?,
        supply: InventoryEntry?
    ) {
        if (item.type != ItemType.Medicine) return
        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return
        val original = _items[index]
        if (!effectiveDate.isAfter(original.creationDate)) return

        val related = if (original.groupId != null) {
            _items.filter { it.type == ItemType.Medicine && it.groupId == original.groupId }
        } else listOf(original)
        val newGroupId = System.currentTimeMillis()
        val treatmentId = original.medicationId ?: original.groupId?.toString() ?: original.id.toString()
        val inheritedMedicationId = original.medicationId ?: original.groupId?.toString() ?: original.id.toString()

        val treatmentKey = original.treatmentKey()
        val versionFamilyIds = _items.filter {
            it.type == ItemType.Medicine && it.treatmentKey() == treatmentKey
        }.map { it.groupId ?: it.id }.toSet()
        val conflictingVersion = versionFamilyIds.any { familyId ->
            familyId != (original.groupId ?: original.id) &&
                    _items.filter { (it.groupId ?: it.id) == familyId }.any { candidate ->
                        candidate.type == ItemType.Medicine &&
                                !candidate.creationDate.isAfter(effectiveDate) &&
                                (candidate.endDate == null || !candidate.endDate.isBefore(effectiveDate))
                    }
        }
        if (conflictingVersion) return

        related.forEach { old ->
            val oldIndex = _items.indexOfFirst { it.id == old.id }
            if (oldIndex != -1) {
                val versionEnd = effectiveDate.minusDays(1)
                val preservedEndDate = old.endDate?.takeIf { it.isBefore(versionEnd) } ?: versionEnd
                _items[oldIndex] = old.copy(
                    endDate = preservedEndDate,
                    takenHistory = HashMap(old.takenHistory.filterKeys { it.isBefore(effectiveDate) }),
                    skipHistory = HashMap(old.skipHistory.filterKeys { it.isBefore(effectiveDate) }),
                    prnUsages = old.prnUsages.filter { it.date.isBefore(effectiveDate) }
                )
                NotificationReceiver.scheduleNotification(getApplication(), _items[oldIndex])
                InventoryService.cancelLowSupplyNotification(getApplication(), old)
            }
        }

        val newItems = related.mapIndexed { offset, old ->
            old.copy(
                id = System.nanoTime() + offset,
                groupId = newGroupId,
                medicationId = inheritedMedicationId,
                creationDate = effectiveDate,
                endDate = null,
                takenHistory = HashMap(old.takenHistory.filterKeys { !it.isBefore(effectiveDate) }),
                skipHistory = HashMap(old.skipHistory.filterKeys { !it.isBefore(effectiveDate) }),
                // Let applySupplySettings apply the new balance after creation,
                // so the new version receives an INITIAL ledger entry.
                supplyDosesLeft = null,
                supplyDosesPerRefill = null,
                supplyLowThreshold = supply?.lowThreshold,
                supplyAlertShown = false,
                supplyLedger = emptyList(),
                doseAmount = doseAmount,
                doseUnit = doseUnit,
                supplyUnit = supply?.unit ?: SupplyUnit.DOSE,
                supplyUnitsPerDose = supply?.unitsPerDose?.coerceAtLeast(1) ?: 1,
                supplyEstimated = supply?.unit == SupplyUnit.SPRAY || supply?.unit == SupplyUnit.PUFF,
                isPrn = old.isPrn,
                prnMaxPerDay = old.prnMaxPerDay,
                prnMinIntervalHours = old.prnMinIntervalHours,
                prnUsages = old.prnUsages.filter { !it.date.isBefore(effectiveDate) }
            )
        }
        _items.addAll(newItems)
        newItems.forEach { NotificationReceiver.scheduleNotification(getApplication(), it) }
        if (supply != null) {
            applySupplySettings(newItems.first(), supply)
        } else {
            newItems.forEach { newItem ->
                val newIndex = _items.indexOfFirst { it.id == newItem.id }
                if (newIndex != -1) {
                    _items[newIndex] = _items[newIndex].copy(
                        supplyUnit = original.supplyUnit,
                        supplyUnitsPerDose = original.supplyUnitsPerDose,
                        supplyEstimated = original.supplyEstimated
                    )
                }
            }
            saveData()
        }
        refreshLowSupplyAlerts()
        syncToWear()
    }

    fun logSupplyUse(item: MedData, kind: SupplyChangeKind, quantity: Int) {
        if (item.type != ItemType.Medicine || kind !in setOf(SupplyChangeKind.PRIMING, SupplyChangeKind.WASTE)) return
        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return
        val updated = InventoryService.applySupplyUse(getApplication(), _items[index], kind, quantity)
        if (updated == _items[index]) return
        _items[index] = updated
        saveData()
    }

    fun isPrnActiveOn(item: MedData, date: LocalDate): Boolean =
        item.type == ItemType.Medicine && item.isPrn && !date.isBefore(item.creationDate)

    fun logPrnUse(item: MedData, date: LocalDate = LocalDate.now(), time: LocalTime = LocalTime.now(), quantity: Int = 1) {
        if (item.type != ItemType.Medicine || !item.isPrn) return
        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return
        val current = _items[index]
        val qty = quantity.coerceAtLeast(1)
        val logged = InventoryService.logPrnUsage(getApplication(), current, qty, date, time)
        _items[index] = logged.copy(
            prnUsages = logged.prnUsages.sortedWith(compareBy({ it.date }, { it.time }))
        )
        val updated = _items[index]
        saveData()
        try { NotificationReceiver.scheduleNotification(getApplication(), updated) } catch (_: Exception) {}
    }

    fun toggleMedicine(item: MedData, date: LocalDate) {
        if (item.type != ItemType.Medicine) return
        if (date.isAfter(LocalDate.now())) return

        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return
        // Work from the stored item, not the (possibly stale) UI copy: a dose
        // logged from a notification or watch may have changed history or stock
        // since this card was rendered. applyTakeIfNew keeps the stock change
        // idempotent — one dose can never be decremented twice.
        val current = _items[index]
        val wasTaken = current.takenHistory.containsKey(date)
        _items[index] = InventoryService.applyTakeIfNew(
            getApplication(),
            current,
            date,
            isTaken = !wasTaken
        )
        saveData()

        // Re-arm the alarm so a dose logged early (or un-done) updates the
        // schedule immediately, matching the notification's Take action.
        try {
            NotificationReceiver.scheduleNotification(getApplication(), _items[index])
        } catch (e: Exception) {
        }
    }

    /**
     * Records a deliberate skip for [date] (or clears it when a skip already
     * exists — the card's skip affordance doubles as un-skip). Mirrors
     * [toggleMedicine]: works on the stored copy, saves, and re-arms the alarm
     * so a skipped slot stops firing reminders.
     */
    fun toggleSkip(item: MedData, date: LocalDate, reason: SkipReason, note: String?) {
        if (item.type != ItemType.Medicine) return
        if (date.isAfter(LocalDate.now())) return

        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return
        val current = _items[index]
        val newSkips = HashMap(current.skipHistory)
        if (newSkips.containsKey(date)) newSkips.remove(date) else newSkips[date] =
            SkipRecord(reason = reason, time = LocalTime.now(), note = note?.trim()?.ifEmpty { null })

        _items[index] = current.copy(skipHistory = newSkips)
        saveData()

        try {
            NotificationReceiver.scheduleNotification(getApplication(), _items[index])
        } catch (e: Exception) {
        }
    }

    /**
     * Pre-skip a scheduled dose on a future (or today) date without touching
     * anything else — reason + optional note recorded immediately.
     */
    fun preSkipDose(item: MedData, date: LocalDate, reason: SkipReason, note: String?) {
        if (item.type != ItemType.Medicine) return
        if (date.isBefore(LocalDate.now())) return

        val index = _items.indexOfFirst { it.id == item.id }
        if (index == -1) return
        val current = _items[index]
        if (current.skipHistory.containsKey(date)) return

        _items[index] = current.copy(
            skipHistory = HashMap(current.skipHistory).apply { put(date, SkipRecord(reason, LocalTime.now(), note?.trim()?.ifEmpty { null }) ) }
        )
        saveData()
    }

    fun confirmIllness(item: MedData, date: LocalDate) {
        if (item.type != ItemType.Illness) return
        val newHistory = HashMap(item.takenHistory)
        newHistory[date] = LocalTime.now()
        val index = _items.indexOfFirst { it.id == item.id }
        if (index != -1) _items[index] = item.copy(takenHistory = newHistory)
        saveData()
    }

    /**
     * In-place edit for a logged symptom (single entry, no schedule): updates
     * fields, the editable date/time, and severity while preserving the id —
     * no planner/rebuild needed because symptoms have no slots or history.
     */
    fun updateSymptom(
        originalItem: MedData,
        title: String,
        iconName: String?,
        colorCode: String?,
        date: LocalDate,
        time: LocalTime,
        severity: SymptomSeverity?,
        notes: String?
    ) {
        if (originalItem.type != ItemType.Symptom) return
        val index = _items.indexOfFirst { it.id == originalItem.id }
        if (index == -1) return
        _items[index] = originalItem.copy(
            title = title,
            iconName = iconName,
            colorCode = colorCode,
            creationDate = date,
            creationTime = time,
            symptomSeverity = severity,
            notes = notes
        )
        saveData()
    }

    fun reloadData() {
        loadData()
        syncToWear()
    }

    private fun syncToWear() {
        WearSyncManager.initialize(getApplication())
        val today = LocalDate.now()

        val itemsToday = _items.filter { item ->
            when (item.type) {
                ItemType.Event -> item.creationDate == today
                ItemType.Symptom -> item.creationDate == today
                ItemType.Illness -> {
                    val isAfterStart = !today.isBefore(item.creationDate)
                    val isBeforeEnd = item.endDate == null || !today.isAfter(item.endDate)
                    isAfterStart && isBeforeEnd
                }

                ItemType.Medicine -> {
                    if (item.isPrn) false else {
                        val isAfterStart = !today.isBefore(item.creationDate)
                        val isBeforeEnd = item.endDate == null || !today.isAfter(item.endDate)
                        val isCorrectDay =
                            item.recurrenceDays.isNullOrEmpty() || item.recurrenceDays.contains(today.dayOfWeek)
                        val isCorrectGap = item.intervalGap == null || ChronoUnit.DAYS.between(
                            item.creationDate,
                            today
                        ) % item.intervalGap == 0L
                        isAfterStart && isBeforeEnd && isCorrectDay && isCorrectGap
                    }
                }
            }
        }.sortedWith(compareBy({ it.displayOrder }, { it.creationTime }))

        val formatter = DateTimeFormatter.ofPattern("HH:mm")

        val meds = itemsToday.filter { it.type == ItemType.Medicine }.map {
            val taken = if (it.takenHistory.containsKey(today)) "✅ " else ""
            "$taken${it.creationTime.format(formatter)} - ${it.title}"
        }

        val evs =
            itemsToday.filter {
                (it.type == ItemType.Event || it.type == ItemType.Illness || it.type == ItemType.Symptom) &&
                    it.title.isNotBlank() && it.iconName != "DIVIDER"
            }
                .map {
                    "${it.creationTime.format(formatter)} - ${it.title}"
                }

        WearSyncManager.syncData(meds, evs)
    }

    private fun saveData() {
        DataRepository.saveData(getApplication(), _items)
        syncToWear()
    }

    private fun loadData() {
        val list = DataRepository.loadData(getApplication())
        _items.clear()
        _items.addAll(list)
    }
}