@file:OptIn(
    ExperimentalMaterial3Api::class, ExperimentalTextApi::class,
    ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)

package com.nukirk.medrx.elements.MainActivity

import android.annotation.SuppressLint
import android.graphics.Color.parseColor
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.rememberDatePickerState
import com.nukirk.medrx.ItemType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.nukirk.medrx.AVAILABLE_ICONS
import com.nukirk.medrx.R
import com.nukirk.medrx.SkipReasonSheet
import com.nukirk.medrx.elements.TimePicker
import com.nukirk.medrx.services.InventoryEntry
import com.nukirk.medrx.services.MedData
import com.nukirk.medrx.services.SkipReason
import com.nukirk.medrx.services.SupplyChange
import com.nukirk.medrx.services.SupplyChangeKind
import com.nukirk.medrx.services.SupplyUnit
import com.nukirk.medrx.services.labelResId
import com.nukirk.medrx.ui.theme.GoogleSansFlex
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@SuppressLint("NewApi")
@Composable
fun MedicineBottomSheet(
    onDismiss: () -> Unit,
    onConfirm: (String, String?, String?, List<LocalTime>, List<DayOfWeek>?, String?, Int?, InventoryEntry?, Int, Long?, Long?, String?, String?, Boolean, Int?, Int?) -> Unit,
    initialItem: MedData? = null,
    initialText: String = "",
    onArchive: () -> Unit = {},
    onNewVersion: (LocalDate, String?, String?, InventoryEntry?) -> Unit = { _, _, _, _ -> },
    onPreSkip: (LocalDate, SkipReason, String?) -> Unit = { _, _, _ -> },
    onSupplyUse: (SupplyChangeKind, Int) -> Unit = { _, _ -> },
    initialVersionDate: LocalDate = LocalDate.now()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf(initialItem?.title ?: initialText) }
    var notes by remember { mutableStateOf(initialItem?.notes ?: "") }
    var doseAmount by remember { mutableStateOf(initialItem?.doseAmount ?: "") }
    var doseUnit by remember { mutableStateOf(initialItem?.doseUnit ?: "") }
    var nameError by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }

    var frequencyType by remember {
        mutableIntStateOf(
            when {
                initialItem?.intervalGap != null && initialItem.intervalGap > 1 -> 2
                initialItem?.recurrenceDays != null -> 1
                else -> 0
            }
        )
    }

    var selectedTimes by remember {
        mutableStateOf(
            if (initialItem != null) listOf(initialItem.creationTime) else listOf(LocalTime.now())
        )
    }

    var timesPerDay by remember {
        mutableIntStateOf(if (frequencyType == 0 && initialItem != null) selectedTimes.size else 1)
    }

    val initGap = initialItem?.intervalGap
    var intervalUnit by remember {
        mutableIntStateOf(
            when {
                initGap != null && initGap % 30 == 0 -> 2
                initGap != null && initGap % 7 == 0 -> 1
                else -> 0
            }
        )
    }

    var intervalDays by remember {
        mutableStateOf(
            when {
                initGap != null && initGap % 30 == 0 -> (initGap / 30).toString()
                initGap != null && initGap % 7 == 0 -> (initGap / 7).toString()
                initGap != null -> initGap.toString()
                else -> "2"
            }
        )
    }

    var expandedInterval by remember { mutableStateOf(false) }

    var selectedDays by remember {
        mutableStateOf(
            initialItem?.recurrenceDays?.toSet() ?: setOf(LocalDate.now().dayOfWeek)
        )
    }

    var selectedIconName by remember { mutableStateOf(initialItem?.iconName ?: "MedicalServices") }
    var selectedColor by remember { mutableStateOf(initialItem?.colorCode ?: "dynamic") }
    var showIconPicker by remember { mutableStateOf(false) }
    var showTimePickerForIndex by remember { mutableStateOf<Int?>(null) }
    var showSaveFrequencyPopup by remember { mutableStateOf(false) }
    var showDoseChangePrompt by remember { mutableStateOf(false) }
    var showVersionConfirmation by remember { mutableStateOf(false) }
    var showVersionDatePicker by remember { mutableStateOf(false) }
    var effectiveVersionDate by remember { mutableStateOf(initialVersionDate) }
    var saveDoseToExistingVersion by remember { mutableStateOf(false) }
    var showSupplyUseDialog by remember { mutableStateOf<SupplyChangeKind?>(null) }
    var supplyUseQuantity by remember { mutableStateOf("1") }

    var notificationType by remember { mutableIntStateOf(initialItem?.notificationType ?: 0) }
    var isPrn by remember { mutableStateOf(initialItem?.isPrn ?: false) }
    var prnMaxPerDay by remember { mutableStateOf(initialItem?.prnMaxPerDay?.toString() ?: "") }
    var prnMinIntervalHours by remember { mutableStateOf(initialItem?.prnMinIntervalHours?.toString() ?: "") }

    // --- Supply (inventory) tracking ---
    var supplyEnabled by remember {
        mutableStateOf(initialItem?.supplyDosesLeft != null)
    }
    var supplyLeft by remember {
        mutableIntStateOf(initialItem?.supplyDosesLeft ?: 30)
    }
    var supplyRefill by remember {
        mutableIntStateOf(initialItem?.supplyDosesPerRefill ?: 30)
    }
    var supplyThreshold by remember {
        mutableIntStateOf(initialItem?.supplyLowThreshold ?: 5)
    }
    var supplyUnit by remember { mutableStateOf(initialItem?.supplyUnit ?: SupplyUnit.DOSE) }
    var supplyUnitsPerDose by remember { mutableIntStateOf(initialItem?.supplyUnitsPerDose ?: 1) }
    // Non-null while one of the supply numbers is being typed in an
    // OutlinedTextField (steppers remain available around the field).
    var editingSupplyField by remember { mutableStateOf<String?>(null) }
    var supplyLeftInput by remember { mutableStateOf("") }
    var supplyRefillInput by remember { mutableStateOf("") }
    var supplyThresholdInput by remember { mutableStateOf("") }
    val supplyFocusRequester = remember { FocusRequester() }

    fun commitSupplyEdit(field: String) {
        val current = editingSupplyField
        if (current == field) {
            val raw = when (field) {
                "left" -> supplyLeftInput
                "refill" -> supplyRefillInput
                else -> supplyThresholdInput
            }
            val parsed = raw.toIntOrNull()
            val clamped = if (parsed != null) parsed.coerceIn(0, 9999) else null
            when (field) {
                "left" -> if (clamped != null) supplyLeft = clamped
                "refill" -> if (clamped != null && clamped >= 1) supplyRefill = clamped
                else -> if (clamped != null) supplyThreshold = clamped
            }
        }
        editingSupplyField = null
    }

    fun beginSupplyEdit(field: String) {
        // Commit any other field that was being edited before switching.
        editingSupplyField?.let { if (it != field) commitSupplyEdit(it) }
        editingSupplyField = field
        when (field) {
            "left" -> supplyLeftInput = supplyLeft.toString()
            "refill" -> supplyRefillInput = supplyRefill.toString()
            "threshold" -> supplyThresholdInput = supplyThreshold.toString()
        }
    }

    fun inventoryEntry(): InventoryEntry? = if (supplyEnabled) InventoryEntry(
        dosesLeft = supplyLeft,
        dosesPerRefill = supplyRefill,
        lowThreshold = supplyThreshold,
        unit = supplyUnit,
        unitsPerDose = supplyUnitsPerDose
    ) else null

    // Bring up the keyboard as soon as a supply value is tapped for typing.
    LaunchedEffect(editingSupplyField) {
        if (editingSupplyField != null) supplyFocusRequester.requestFocus()
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(frequencyType, timesPerDay) {
        if (frequencyType == 0) {
            val count = timesPerDay
            if (count > 0 && count != selectedTimes.size) {
                val newTimes = selectedTimes.toMutableList()
                if (count > newTimes.size) {
                    repeat(count - newTimes.size) { newTimes.add(LocalTime.now()) }
                } else {
                    // MutableList.removeLast() only resolves on API 35+; on older
                    // devices it throws NoSuchMethodError (upstream issue #19).
                    while (newTimes.size > count) newTimes.removeAt(newTimes.lastIndex)
                }
                selectedTimes = newTimes
            }
        } else {
            if (selectedTimes.isEmpty()) selectedTimes = listOf(LocalTime.now())
            if (selectedTimes.size > 1) selectedTimes = listOf(selectedTimes.first())
        }
    }

    if (showIconPicker) {
        IconPickerDialog(
            currentIcon = selectedIconName,
            currentColor = selectedColor,
            onDismiss = { showIconPicker = false },
            onConfirm = { icon, color ->
                selectedIconName = icon
                selectedColor = color
                showIconPicker = false
            }
        )
    }

    if (showTimePickerForIndex != null) {
        val index = showTimePickerForIndex!!
        val initialTime = selectedTimes.getOrElse(index) { LocalTime.now() }

        TimePicker(
            onDismiss = { showTimePickerForIndex = null },
            onConfirm = { newTime ->
                val newTimes = selectedTimes.toMutableList()
                if (index < newTimes.size) newTimes[index] = newTime else newTimes.add(newTime)
                selectedTimes = newTimes
                showTimePickerForIndex = null
            },
            initialTime = initialTime
        )
    }

    if (showDoseChangePrompt && initialItem != null) {
        AlertDialog(
            onDismissRequest = { showDoseChangePrompt = false },
            title = { Text(stringResource(R.string.dose_change_prompt_title)) },
            text = { Text(stringResource(R.string.dose_change_prompt_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDoseChangePrompt = false
                    showVersionConfirmation = true
                }) { Text(stringResource(R.string.dose_change_start_version)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDoseChangePrompt = false }) {
                        Text(stringResource(R.string.cancel_action))
                    }
                    TextButton(onClick = {
                        showDoseChangePrompt = false
                        saveDoseToExistingVersion = true
                        showSaveFrequencyPopup = true
                    }) { Text(stringResource(R.string.dose_change_save_existing)) }
                }
            }
        )
    }

    if (showVersionDatePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = effectiveVersionDate.toEpochDay() * 86400000L
        )
        DatePickerDialog(
            onDismissRequest = { showVersionDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { effectiveVersionDate = LocalDate.ofEpochDay(it / 86400000L) }
                    showVersionDatePicker = false
                    if (initialItem != null) showVersionConfirmation = true
                }) { Text(stringResource(R.string.ok_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showVersionDatePicker = false }) { Text(stringResource(R.string.cancel_action)) }
            }
        ) { DatePicker(state = picker) }
    }

    if (showVersionConfirmation && initialItem != null) {
        AlertDialog(
            onDismissRequest = { showVersionConfirmation = false },
            title = { Text(stringResource(R.string.version_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.version_confirm_message))
                    Text(stringResource(R.string.version_old_dose, listOfNotNull(initialItem.doseAmount, initialItem.doseUnit).joinToString(" ").ifBlank { "—" }))
                    Text(stringResource(R.string.version_new_dose, listOfNotNull(doseAmount.takeIf { it.isNotBlank() }, doseUnit.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "—" }))
                    TextButton(onClick = { showVersionDatePicker = true }) {
                        Text(stringResource(R.string.version_effective_date) + ": " + effectiveVersionDate.toString())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !effectiveVersionDate.isBefore(initialItem.creationDate),
                    onClick = {
                        showVersionConfirmation = false
                        onNewVersion(effectiveVersionDate, doseAmount.takeIf { it.isNotBlank() }, doseUnit.takeIf { it.isNotBlank() }, inventoryEntry())
                    }
                ) { Text(stringResource(R.string.version_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showVersionConfirmation = false }) { Text(stringResource(R.string.cancel_action)) }
            }
        )
    }

    if (showSupplyUseDialog != null) {
        val kind = showSupplyUseDialog!!
        AlertDialog(
            onDismissRequest = { showSupplyUseDialog = null },
            title = { Text(stringResource(if (kind == SupplyChangeKind.PRIMING) R.string.supply_log_priming else R.string.supply_log_waste)) },
            text = {
                OutlinedTextField(
                    value = supplyUseQuantity,
                    onValueChange = { supplyUseQuantity = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.supply_use_quantity, stringResource(supplyUnit.labelResId()))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val qty = supplyUseQuantity.toIntOrNull()
                    if (qty != null && qty > 0 && qty <= supplyLeft) {
                        onSupplyUse(kind, qty)
                        supplyLeft -= qty
                        supplyUseQuantity = "1"
                        showSupplyUseDialog = null
                    }
                }, enabled = (supplyUseQuantity.toIntOrNull() ?: 0) in 1..supplyLeft) { Text(stringResource(R.string.ok_action)) }
            },
            dismissButton = { TextButton(onClick = { showSupplyUseDialog = null }) { Text(stringResource(R.string.cancel_action)) } }
        )
    }

    if (showSaveFrequencyPopup) {
        SaveFrequencyPopup(
            onDismiss = { showSaveFrequencyPopup = false },
            onApply = { start, end ->
                showSaveFrequencyPopup = false
                scope.launch { sheetState.hide() }.invokeOnCompletion {
                    if (!sheetState.isVisible) {
                        val days = if (frequencyType == 1) selectedDays.toList() else null
                        val gap = if (frequencyType == 2) {
                            val base = intervalDays.toIntOrNull() ?: 2
                            when (intervalUnit) {
                                1 -> base * 7
                                2 -> base * 30
                                else -> base
                            }
                        } else null

                        onConfirm(
                            text,
                            selectedIconName,
                            selectedColor,
                            selectedTimes,
                            days,
                            notes.takeIf { it.isNotBlank() },
                            gap,
                            inventoryEntry(),
                            notificationType,
                            start,
                            end,
                            doseAmount.takeIf { it.isNotBlank() },
                            doseUnit.takeIf { it.isNotBlank() },
                            isPrn,
                            prnMaxPerDay.toIntOrNull(),
                            prnMinIntervalHours.toIntOrNull()
                        )
                    }
                }
            }
        )
    }

    val icSick = ImageVector.vectorResource(R.drawable.ic_sick)
    val icMind = ImageVector.vectorResource(R.drawable.ic_mind)
    val icMixture = ImageVector.vectorResource(R.drawable.ic_mixture)

    val cancelInteractionSource = remember { MutableInteractionSource() }
    val saveInteractionSource = remember { MutableInteractionSource() }
    val isCancelPressed by cancelInteractionSource.collectIsPressedAsState()
    val isSavePressed by saveInteractionSource.collectIsPressedAsState()

    val cancelCorner by animateIntAsState(
        targetValue = if (isCancelPressed) 15 else 50,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cancelCorner"
    )
    val saveCorner by animateIntAsState(
        targetValue = if (isSavePressed) 15 else 50,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "saveCorner"
    )

    val listState = rememberLazyListState()
    var wasAtTopWhenGestureStarted by remember { mutableStateOf(true) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return if (!wasAtTopWhenGestureStarted) available else Velocity.Zero
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.statusBarsPadding(),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitFirstDown(requireUnconsumed = false)
                                wasAtTopWhenGestureStarted = !listState.canScrollBackward
                            }
                        }
                    }
                    .nestedScroll(nestedScrollConnection),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text(
                        text = stringResource(if (initialItem != null) R.string.edit_medicine_title else R.string.new_medicine_title),
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }

                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val iconVector = when (selectedIconName) {
                            "MixtureMed" -> icSick
                            "Bed" -> icMind
                            "Mood" -> icMixture
                            else -> AVAILABLE_ICONS[selectedIconName] ?: Icons.Rounded.Event
                        }
                        val headerBg =
                            if (selectedColor == "dynamic") MaterialTheme.colorScheme.primary else try {
                                Color(parseColor(selectedColor))
                            } catch (e: Exception) {
                                MaterialTheme.colorScheme.primary
                            }
                        val headerTint =
                            if (selectedColor == "dynamic") MaterialTheme.colorScheme.onPrimary else Color.Black.copy(
                                0.7f
                            )

                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(headerBg)
                                .clickable { showIconPicker = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = headerTint
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset((-4).dp, (-4).dp)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }

                item {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; nameError = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                stringResource(R.string.name_hint),
                                fontFamily = GoogleSansFlex
                            )
                        },
                        singleLine = true,
                        isError = nameError
                    )
                }

                item { Spacer(modifier = Modifier.height(12.dp)) }

                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(R.string.notes_hint),
                                fontFamily = GoogleSansFlex
                            )
                        },
                        minLines = 2,
                        maxLines = 4
                    )
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = doseAmount,
                            onValueChange = { doseAmount = it.filter { c -> c.isDigit() || c == '.' } },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.dose_amount_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = doseUnit,
                            onValueChange = { doseUnit = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.dose_unit_label)) },
                            singleLine = true
                        )
                    }
                }

                item {
                    val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    SegmentedListItem(
                        onClick = { isPrn = !isPrn },
                        colors = itemColors,
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                        trailingContent = {
                            Switch(checked = isPrn, onCheckedChange = { isPrn = it })
                        },
                        content = {
                            Column {
                                Text(stringResource(R.string.as_needed_switch_label), style = MaterialTheme.typography.bodyLarge, fontFamily = GoogleSansFlex)
                                Text(stringResource(R.string.as_needed_switch_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    )
                }

                if (isPrn) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = prnMaxPerDay,
                                onValueChange = { prnMaxPerDay = it.filter(Char::isDigit).take(2) },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.prn_max_per_day_label)) },
                                placeholder = { Text(stringResource(R.string.prn_max_per_day_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = prnMinIntervalHours,
                                onValueChange = { prnMinIntervalHours = it.filter(Char::isDigit).take(2) },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.prn_min_interval_label)) },
                                placeholder = { Text(stringResource(R.string.prn_min_interval_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    SegmentedListItem(
                        onClick = { supplyEnabled = !supplyEnabled },
                        colors = itemColors,
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                        trailingContent = {
                            Switch(
                                checked = supplyEnabled,
                                onCheckedChange = { supplyEnabled = it }
                            )
                        },
                        content = {
                            Text(
                                text = stringResource(R.string.supply_track_label),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = GoogleSansFlex
                            )
                        }
                    )
                }

                if (supplyEnabled) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.supply_unit_label), style = MaterialTheme.typography.labelMedium)
                        val supplyUnitOptions = listOf(
                            SupplyUnit.DOSE to stringResource(R.string.supply_unit_dose),
                            SupplyUnit.TABLET to stringResource(R.string.supply_unit_tablet),
                            SupplyUnit.CAPSULE to stringResource(R.string.supply_unit_capsule),
                            SupplyUnit.ML to stringResource(R.string.supply_unit_ml),
                            SupplyUnit.SPRAY to stringResource(R.string.supply_unit_spray),
                            SupplyUnit.PUFF to stringResource(R.string.supply_unit_puff)
                        )
                        OutlinedSingleSelectButtonGroup(
                            options = supplyUnitOptions.map { it.second },
                            selectedIndex = supplyUnitOptions.indexOfFirst { it.first == supplyUnit }.coerceAtLeast(0),
                            onOptionSelected = { supplyUnit = supplyUnitOptions[it].first }
                        )
                        if (supplyUnit == SupplyUnit.SPRAY || supplyUnit == SupplyUnit.PUFF) {
                            Text(stringResource(R.string.supply_estimated_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SegmentedListItem(
                            onClick = {},
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { if (supplyUnitsPerDose > 1) supplyUnitsPerDose-- }) { Icon(Icons.Rounded.Remove, contentDescription = null) }
                                    Text(supplyUnitsPerDose.toString(), modifier = Modifier.padding(horizontal = 12.dp))
                                    IconButton(onClick = { if (supplyUnitsPerDose < 999) supplyUnitsPerDose++ }) { Icon(Icons.Rounded.Add, contentDescription = null) }
                                }
                            },
                            content = { Text(stringResource(R.string.supply_units_per_dose)) }
                        )
                        if (initialItem != null && (supplyUnit == SupplyUnit.SPRAY || supplyUnit == SupplyUnit.PUFF)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { showSupplyUseDialog = SupplyChangeKind.PRIMING }
                                ) {
                                    Text(stringResource(R.string.supply_log_priming))
                                }
                                OutlinedButton(
                                    onClick = { showSupplyUseDialog = SupplyChangeKind.WASTE }
                                ) {
                                    Text(stringResource(R.string.supply_log_waste))
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }

                    item {
                        val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                        ) {
                            SegmentedListItem(
                                onClick = {},
                                colors = itemColors,
                                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 3),
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {
                                            if (editingSupplyField == "left") commitSupplyEdit("left")
                                            if (supplyLeft > 0) supplyLeft--
                                        }) {
                                            Icon(Icons.Rounded.Remove, contentDescription = null)
                                        }
                                        if (editingSupplyField == "left") {
                                            var leftHadFocus by remember { mutableStateOf(false) }
                                            OutlinedTextField(
                                                value = supplyLeftInput,
                                                onValueChange = { supplyLeftInput = it.filter { c -> c.isDigit() }.take(4) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                keyboardActions = KeyboardActions(onDone = { commitSupplyEdit("left") }),
                                                singleLine = true,
                                                isError = supplyLeftInput.toIntOrNull() == null,
                                                textStyle = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier
                                                    .width(88.dp)
                                                    .height(56.dp)
                                                    .focusRequester(supplyFocusRequester)
                                                    .onFocusChanged {
                                                        if (it.isFocused) leftHadFocus = true
                                                        else if (leftHadFocus) commitSupplyEdit("left")
                                                    },
                                                placeholder = {
                                                    Text(supplyLeft.toString(), style = MaterialTheme.typography.titleMedium)
                                                }
                                            )
                                        } else {
                                            TextButton(onClick = { beginSupplyEdit("left") }) {
                                                Text(
                                                    text = supplyLeft.toString(),
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        }
                                        IconButton(onClick = {
                                            if (editingSupplyField == "left") commitSupplyEdit("left")
                                            if (supplyLeft < 9999) supplyLeft++
                                        }) {
                                            Icon(Icons.Rounded.Add, contentDescription = null)
                                        }
                                    }
                                },
                                content = {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.supply_doses_left, stringResource(supplyUnit.labelResId())),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = GoogleSansFlex
                                        )
                                        Text(
                                            text = stringResource(R.string.supply_tap_to_edit_value),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = GoogleSansFlex
                                        )
                                    }
                                }
                            )

                            SegmentedListItem(
                                onClick = {},
                                colors = itemColors,
                                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 3),
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {
                                            if (editingSupplyField == "refill") commitSupplyEdit("refill")
                                            if (supplyRefill > 1) supplyRefill--
                                        }) {
                                            Icon(Icons.Rounded.Remove, contentDescription = null)
                                        }
                                        if (editingSupplyField == "refill") {
                                            var refillHadFocus by remember { mutableStateOf(false) }
                                            OutlinedTextField(
                                                value = supplyRefillInput,
                                                onValueChange = { supplyRefillInput = it.filter { c -> c.isDigit() }.take(4) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                keyboardActions = KeyboardActions(onDone = { commitSupplyEdit("refill") }),
                                                singleLine = true,
                                                isError = (supplyRefillInput.toIntOrNull() ?: 1) < 1,
                                                textStyle = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier
                                                    .width(88.dp)
                                                    .height(56.dp)
                                                    .focusRequester(supplyFocusRequester)
                                                    .onFocusChanged {
                                                        if (it.isFocused) refillHadFocus = true
                                                        else if (refillHadFocus) commitSupplyEdit("refill")
                                                    },
                                                placeholder = {
                                                    Text(supplyRefill.toString(), style = MaterialTheme.typography.titleMedium)
                                                }
                                            )
                                        } else {
                                            TextButton(onClick = { beginSupplyEdit("refill") }) {
                                                Text(
                                                    text = supplyRefill.toString(),
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        }
                                        IconButton(onClick = {
                                            if (editingSupplyField == "refill") commitSupplyEdit("refill")
                                            if (supplyRefill < 9999) supplyRefill++
                                        }) {
                                            Icon(Icons.Rounded.Add, contentDescription = null)
                                        }
                                    }
                                },
                                content = {
                                    Text(
                                        text = stringResource(R.string.supply_refill_size, stringResource(supplyUnit.labelResId())),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = GoogleSansFlex
                                    )
                                }
                            )

                            SegmentedListItem(
                                onClick = {},
                                colors = itemColors,
                                shapes = ListItemDefaults.segmentedShapes(index = 2, count = 3),
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = {
                                            if (editingSupplyField == "threshold") commitSupplyEdit("threshold")
                                            if (supplyThreshold > 0) supplyThreshold--
                                        }) {
                                            Icon(Icons.Rounded.Remove, contentDescription = null)
                                        }
                                        if (editingSupplyField == "threshold") {
                                            var thresholdHadFocus by remember { mutableStateOf(false) }
                                            OutlinedTextField(
                                                value = supplyThresholdInput,
                                                onValueChange = { supplyThresholdInput = it.filter { c -> c.isDigit() }.take(4) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                keyboardActions = KeyboardActions(onDone = { commitSupplyEdit("threshold") }),
                                                singleLine = true,
                                                isError = supplyThresholdInput.toIntOrNull() == null,
                                                textStyle = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier
                                                    .width(88.dp)
                                                    .height(56.dp)
                                                    .focusRequester(supplyFocusRequester)
                                                    .onFocusChanged {
                                                        if (it.isFocused) thresholdHadFocus = true
                                                        else if (thresholdHadFocus) commitSupplyEdit("threshold")
                                                    },
                                                placeholder = {
                                                    Text(supplyThreshold.toString(), style = MaterialTheme.typography.titleMedium)
                                                }
                                            )
                                        } else {
                                            TextButton(onClick = { beginSupplyEdit("threshold") }) {
                                                Text(
                                                    text = supplyThreshold.toString(),
                                                    style = MaterialTheme.typography.titleMedium
                                                )
                                            }
                                        }
                                        IconButton(onClick = {
                                            if (editingSupplyField == "threshold") commitSupplyEdit("threshold")
                                            if (supplyThreshold < 9999) supplyThreshold++
                                        }) {
                                            Icon(Icons.Rounded.Add, contentDescription = null)
                                        }
                                    }
                                },
                                content = {
                                    Text(
                                        text = stringResource(R.string.supply_alert_when_below),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = GoogleSansFlex
                                    )
                                }
                            )
                        }
                    }
                }

                if (!isPrn) {
                                if (supplyEnabled && initialItem != null && initialItem.supplyLedger.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(16.dp)) }

                    item {
                        SupplyLedgerCard(ledger = initialItem.supplyLedger)
                    }
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.notification_type_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )

                        val notifOptions = listOf(
                            stringResource(R.string.notification_type_default),
                            stringResource(R.string.notification_type_none),
                            stringResource(R.string.notification_type_normal),
                            stringResource(R.string.notification_type_alarm)
                        )

                        OutlinedSingleSelectButtonGroup(
                            options = notifOptions,
                            selectedIndex = notificationType,
                            onOptionSelected = { notificationType = it }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.frequency_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )

                        val freqOptions = listOf(
                            stringResource(R.string.freq_mode_daily),
                            stringResource(R.string.freq_mode_days),
                            stringResource(R.string.freq_mode_interval)
                        )

                        OutlinedSingleSelectButtonGroup(
                            options = freqOptions,
                            selectedIndex = frequencyType,
                            onOptionSelected = { frequencyType = it }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                when (frequencyType) {
                    0 -> {
                        item {
                            val count = 1 + selectedTimes.size
                            val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                            ) {
                                SegmentedListItem(
                                    onClick = {},
                                    colors = itemColors,
                                    shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { if (timesPerDay > 1) timesPerDay-- }) {
                                                Icon(Icons.Rounded.Remove, contentDescription = null)
                                            }
                                            Text(
                                                text = timesPerDay.toString(),
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.padding(horizontal = 8.dp)
                                            )
                                            IconButton(onClick = { if (timesPerDay < 10) timesPerDay++ }) {
                                                Icon(Icons.Rounded.Add, contentDescription = null)
                                            }
                                        }
                                    },
                                    content = {
                                        Text(
                                            text = stringResource(R.string.times_per_day_label),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = GoogleSansFlex
                                        )
                                    }
                                )

                                selectedTimes.forEachIndexed { index, time ->
                                    SegmentedListItem(
                                        onClick = { showTimePickerForIndex = index },
                                        colors = itemColors,
                                        shapes = ListItemDefaults.segmentedShapes(index = index + 1, count = count),
                                        trailingContent = {
                                            val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                                            Text(
                                                text = time.format(formatter),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontFamily = GoogleSansFlex
                                            )
                                        },
                                        content = {
                                            Text(
                                                text = stringResource(R.string.schedule_label_format, index + 1),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontFamily = GoogleSansFlex
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }

                    1 -> {
                        item {
                            val days = listOf(
                                DayOfWeek.MONDAY,
                                DayOfWeek.TUESDAY,
                                DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY,
                                DayOfWeek.FRIDAY,
                                DayOfWeek.SATURDAY,
                                DayOfWeek.SUNDAY
                            )
                            val dayLabels = days.map { it.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
                            val selectedIndices = days.mapIndexedNotNull { index, day ->
                                if (selectedDays.contains(day)) index else null
                            }.toSet()

                            MultiSelectConnectedButtonGroupWithFlowLayout(
                                options = dayLabels,
                                selectedIndices = selectedIndices,
                                onOptionSelected = { index ->
                                    val day = days[index]
                                    selectedDays = if (selectedDays.contains(day)) {
                                        if (selectedDays.size > 1) selectedDays - day else selectedDays
                                    } else {
                                        selectedDays + day
                                    }
                                }
                            )
                        }

                        item { Spacer(modifier = Modifier.height(16.dp)) }

                        item {
                            val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            SegmentedListItem(
                                onClick = { showTimePickerForIndex = 0 },
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                                colors = itemColors,
                                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                                trailingContent = {
                                    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                                    Text(
                                        text = (selectedTimes.firstOrNull() ?: LocalTime.now()).format(formatter),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = GoogleSansFlex
                                    )
                                },
                                content = {
                                    Text(
                                        text = stringResource(R.string.time_label),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = GoogleSansFlex
                                    )
                                }
                            )
                        }
                    }

                    2 -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.interval_every),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                OutlinedTextField(
                                    value = intervalDays,
                                    onValueChange = {
                                        if (it.isEmpty() || it.all { char -> char.isDigit() }) intervalDays =
                                            it
                                    },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                                )

                                ExposedDropdownMenuBox(
                                    expanded = expandedInterval,
                                    onExpandedChange = { expandedInterval = !expandedInterval },
                                    modifier = Modifier.weight(1.5f)
                                ) {
                                    val options = listOf(
                                        stringResource(R.string.interval_days),
                                        stringResource(R.string.interval_weeks),
                                        stringResource(R.string.interval_months)
                                    )

                                    OutlinedTextField(
                                        value = options[intervalUnit],
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInterval)
                                        },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                                    )

                                    ExposedDropdownMenu(
                                        expanded = expandedInterval,
                                        onDismissRequest = { expandedInterval = false },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        options.forEachIndexed { index, selectionOption ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        selectionOption,
                                                        fontFamily = GoogleSansFlex
                                                    )
                                                },
                                                onClick = {
                                                    intervalUnit = index
                                                    expandedInterval = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(16.dp)) }

                        item {
                            val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                            SegmentedListItem(
                                onClick = { showTimePickerForIndex = 0 },
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                                colors = itemColors,
                                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                                trailingContent = {
                                    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                                    Text(
                                        text = (selectedTimes.firstOrNull() ?: LocalTime.now()).format(formatter),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = GoogleSansFlex
                                    )
                                },
                                content = {
                                    Text(
                                        text = stringResource(R.string.time_label),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontFamily = GoogleSansFlex
                                    )
                                }
                            )
                        }
                    }
                }

                }

                // Skip-in-advance lives at the bottom of the editor, just
                // above the action row, so it's reachable without scrolling
                // past the supply section.
                if (initialItem != null && initialItem.type == ItemType.Medicine) {
                    item { Spacer(modifier = Modifier.height(24.dp)) }

                    item {
                        PreskipCard(
                            item = initialItem,
                            onSkip = { date, reason, note ->
                                onPreSkip(date, reason, note)
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) onDismiss()
                                }
                            }
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Primary pair first: Cancel / Save at half width each so
                    // both labels stay fully legible on narrow screens.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    onDismiss()
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(cancelCorner),
                        interactionSource = cancelInteractionSource
                    ) {
                        Text(
                            stringResource(R.string.cancel_action),
                            fontFamily = GoogleSansFlex,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = {
                            // Commit any supply value still being typed before
                            // evaluating the save, so the typed count is what lands.
                            editingSupplyField?.let { commitSupplyEdit(it) }
                            if (text.isNotBlank()) {
                                if (initialItem != null) {
                                    val isModified = run {
                                        val initialFreqType = when {
                                            initialItem.intervalGap != null && initialItem.intervalGap > 1 -> 2
                                            initialItem.recurrenceDays != null -> 1
                                            else -> 0
                                        }
                                        val initialDaysSet = initialItem.recurrenceDays?.toSet()
                                            ?: setOf(LocalDate.now().dayOfWeek)

                                        val currentBase = intervalDays.toIntOrNull() ?: 2
                                        val currentGap = when (intervalUnit) {
                                            1 -> currentBase * 7
                                            2 -> currentBase * 30
                                            else -> currentBase
                                        }

                                        text != initialItem.title ||
                                                notes != (initialItem.notes ?: "") ||
                                                doseAmount != (initialItem.doseAmount ?: "") ||
                                                doseUnit != (initialItem.doseUnit ?: "") ||
                                                supplyUnit != initialItem.supplyUnit ||
                                                supplyUnitsPerDose != initialItem.supplyUnitsPerDose ||
                                                selectedIconName != (initialItem.iconName
                                            ?: "MedicalServices") ||
                                                selectedColor != (initialItem.colorCode
                                            ?: "dynamic") ||
                                                notificationType != initialItem.notificationType ||
                                                selectedTimes != listOf(initialItem.creationTime) ||
                                                frequencyType != initialFreqType ||
                                                (frequencyType == 1 && selectedDays != initialDaysSet) ||
                                                (frequencyType == 2 && currentGap != (initialItem.intervalGap
                                                    ?: 2)) ||
                                                isPrn != initialItem.isPrn ||
                                                prnMaxPerDay != (initialItem.prnMaxPerDay?.toString() ?: "") ||
                                                prnMinIntervalHours != (initialItem.prnMinIntervalHours?.toString() ?: "") ||
                                                supplyEnabled != (initialItem.supplyDosesLeft != null) ||
                                                (supplyEnabled && (supplyLeft != initialItem.supplyDosesLeft ||
                                                        supplyRefill != (initialItem.supplyDosesPerRefill
                                                            ?: 0) ||
                                                        supplyThreshold != (initialItem.supplyLowThreshold
                                                            ?: 0))) ||
                                                editingSupplyField != null
                                    }

                                    val doseChanged =
                                        doseAmount != (initialItem.doseAmount ?: "") ||
                                                doseUnit != (initialItem.doseUnit ?: "")
                                    if (!isPrn && isModified && doseChanged && !saveDoseToExistingVersion) {
                                        effectiveVersionDate = maxOf(
                                            initialVersionDate,
                                            initialItem.creationDate.plusDays(1)
                                        )
                                        showDoseChangePrompt = true
                                    } else if (isPrn && isModified) {
                                        // As-needed meds have no schedule to split, so apply
                                        // the field edit directly across the group instead of
                                        // opening the single-occurrence / range picker.
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            if (!sheetState.isVisible) {
                                                val days = if (frequencyType == 1) selectedDays.toList() else null
                                                val gap = if (frequencyType == 2) {
                                                    val base = intervalDays.toIntOrNull() ?: 2
                                                    when (intervalUnit) { 1 -> base * 7; 2 -> base * 30; else -> base }
                                                } else null
                                                onConfirm(
                                                    text,
                                                    selectedIconName,
                                                    selectedColor,
                                                    selectedTimes,
                                                    days,
                                                    notes.takeIf { it.isNotBlank() },
                                                    gap,
                                                    inventoryEntry(),
                                                    notificationType,
                                                    null,
                                                    null,
                                                    doseAmount.takeIf { it.isNotBlank() },
                                                    doseUnit.takeIf { it.isNotBlank() },
                                                    isPrn,
                                                    prnMaxPerDay.toIntOrNull(),
                                                    prnMinIntervalHours.toIntOrNull()
                                                )
                                            }
                                        }
                                    } else if (isModified) {
                                        showSaveFrequencyPopup = true
                                    } else {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            if (!sheetState.isVisible) {
                                                val days =
                                                    if (frequencyType == 1) selectedDays.toList() else null
                                                val gap = if (frequencyType == 2) {
                                                    val base = intervalDays.toIntOrNull() ?: 2
                                                    when (intervalUnit) {
                                                        1 -> base * 7
                                                        2 -> base * 30
                                                        else -> base
                                                    }
                                                } else null

                                                onConfirm(
                                                    text,
                                                    selectedIconName,
                                                    selectedColor,
                                                    selectedTimes,
                                                    days,
                                                    notes.takeIf { it.isNotBlank() },
                                                    gap,
                                                    inventoryEntry(),
                                                    notificationType,
                                                    null,
                                                    null,
                                                    doseAmount.takeIf { it.isNotBlank() },
                                                    doseUnit.takeIf { it.isNotBlank() },
                                                    isPrn,
                                                    prnMaxPerDay.toIntOrNull(),
                                                    prnMinIntervalHours.toIntOrNull()
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        if (!sheetState.isVisible) {
                                            val days =
                                                if (frequencyType == 1) selectedDays.toList() else null
                                            val gap = if (frequencyType == 2) {
                                                val base = intervalDays.toIntOrNull() ?: 2
                                                when (intervalUnit) {
                                                    1 -> base * 7
                                                    2 -> base * 30
                                                    else -> base
                                                }
                                            } else null

                                            onConfirm(
                                                text,
                                                selectedIconName,
                                                selectedColor,
                                                selectedTimes,
                                                days,
                                                notes.takeIf { it.isNotBlank() },
                                                gap,
                                                inventoryEntry(),
                                                notificationType,
                                                null,
                                                null,
                                                doseAmount.takeIf { it.isNotBlank() },
                                                doseUnit.takeIf { it.isNotBlank() },
                                                isPrn,
                                                prnMaxPerDay.toIntOrNull(),
                                                prnMinIntervalHours.toIntOrNull()
                                            )
                                        }
                                    }
                                }
                            } else {
                                nameError = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(saveCorner),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        interactionSource = saveInteractionSource
                    ) {
                        Text(
                            stringResource(R.string.save_action),
                            fontFamily = GoogleSansFlex,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                    }
                    }

                    // Structural actions (new version / archive) collapse
                    // behind an overflow toggle so they never crowd the bar.
                    if (initialItem != null && initialItem.type == ItemType.Medicine) {
                        if (showMoreActions) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (initialItem != null && initialItem.type == ItemType.Medicine) {
                        OutlinedButton(
                            onClick = {
                                effectiveVersionDate = maxOf(initialVersionDate, initialItem.creationDate.plusDays(1))
                                showVersionDatePicker = true
                            },
                            modifier = Modifier.height(50.dp),
                            shape = RoundedCornerShape(50),
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            Icon(
                                Icons.Rounded.Archive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.start_new_version),
                                fontFamily = GoogleSansFlex,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = { onArchive() },
                            modifier = Modifier.height(50.dp),
                            shape = RoundedCornerShape(50),
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            Icon(
                                Icons.Rounded.Archive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.archive_med),
                                fontFamily = GoogleSansFlex,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                        }
                    }
                            }
                        } else {
                            TextButton(
                                onClick = { showMoreActions = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Rounded.MoreHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.more_actions),
                                    fontFamily = GoogleSansFlex,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OutlinedSingleSelectButtonGroup(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            OutlinedToggleButton(
                checked = selectedIndex == index,
                onCheckedChange = { onOptionSelected(index) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.outlinedToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = option,
                    fontFamily = GoogleSansFlex,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Recent stock changes for this medicine (newest first) so count
 * discrepancies can be traced: doses taken, refunds, refills, corrections.
 */
@Composable
fun SupplyLedgerCard(ledger: List<SupplyChange>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        var ledgerExpanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .padding(16.dp)
                .clickable { ledgerExpanded = !ledgerExpanded }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ledger_title),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (ledgerExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (ledgerExpanded) {
            ledger.takeLast(8).reversed().forEach { change ->
                val deltaText = if (change.delta > 0) "+${change.delta}" else "${change.delta}"
                val kindLabel = stringResource(
                    when (change.kind) {
                        SupplyChangeKind.TAKEN -> R.string.ledger_kind_taken
                        SupplyChangeKind.REFUND -> R.string.ledger_kind_refund
                        SupplyChangeKind.CORRECTION -> R.string.ledger_kind_correction
                        SupplyChangeKind.REFILL -> R.string.ledger_kind_refill
                        SupplyChangeKind.INITIAL -> R.string.ledger_kind_initial
                        SupplyChangeKind.PRIMING -> R.string.ledger_kind_priming
                        SupplyChangeKind.WASTE -> R.string.ledger_kind_waste
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$kindLabel  $deltaText ${change.unit.name.lowercase()} → ${change.balanceAfter}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = GoogleSansFlex,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = change.date.format(
                            DateTimeFormatter.ofPattern("dd/MM").withLocale(Locale.getDefault())
                        ) + " " + change.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = GoogleSansFlex,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            } else {
                Text(
                    text = stringResource(R.string.ledger_collapsed_hint, ledger.size),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansFlex,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Card in the editor for pre-skipping a dose on a chosen future/today date
 * (travel, fasting window, procedure prep, …). Reason + optional note only;
 * the schedule itself is not modified.
 */
@Composable
fun PreskipCard(
    item: MedData,
    onSkip: (LocalDate, SkipReason, String?) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var showReason by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.preskip_title),
                fontFamily = GoogleSansFlex,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.preskip_desc),
                fontFamily = GoogleSansFlex,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = { showDatePicker = true },
                enabled = !item.skipHistory.containsKey(LocalDate.now())
            ) {
                Icon(Icons.Rounded.EventBusy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = selectedDate?.let {
                        stringResource(
                            R.string.preskip_pick_date_done,
                            it.format(DateTimeFormatter.ofPattern("EEE, dd MMM").withLocale(Locale.getDefault()))
                        )
                    } ?: stringResource(R.string.preskip_pick_date),
                    fontFamily = GoogleSansFlex
                )
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            selectedDate = LocalDate.ofEpochDay(millis / 86400000)
                            showDatePicker = false
                            showReason = true
                        }
                    },
                    enabled = state.selectedDateMillis != null
                ) { Text(stringResource(R.string.alarm_skip_next)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.alarm_skip_cancel)) }
            }
        ) {
            DatePicker(state = state)
        }
    }

    if (showReason) {
        SkipReasonSheet(
            onDismiss = { showReason = false },
            onConfirm = { reason, note ->
                showReason = false
                selectedDate?.let { onSkip(it, reason, note) }
            }
        )
    }
}

@Composable
fun MultiSelectConnectedButtonGroupWithFlowLayout(
    options: List<String>,
    selectedIndices: Set<Int>,
    onOptionSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            OutlinedToggleButton(
                checked = selectedIndices.contains(index),
                onCheckedChange = { onOptionSelected(index) },
                modifier = Modifier.weight(1f),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.outlinedToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = option,
                    fontFamily = GoogleSansFlex,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}