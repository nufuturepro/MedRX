@file:OptIn(ExperimentalTextApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.nukirk.medrx

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nukirk.medrx.elements.AdvancedSettingsActivity.ResetPopup
import com.nukirk.medrx.elements.AdvancedSettingsActivity.RestorePopup
import com.nukirk.medrx.services.CsvPortability
import com.nukirk.medrx.services.SkipReport
import com.nukirk.medrx.services.DataRepository
import com.nukirk.medrx.services.InventoryService
import com.nukirk.medrx.services.MedifixImporter
import com.nukirk.medrx.services.NotificationReceiver
import com.nukirk.medrx.ItemType
import com.nukirk.medrx.services.MedData
import com.nukirk.medrx.services.MedicationDuplicateReview
import com.nukirk.medrx.services.DuplicateReviewState
import com.nukirk.medrx.ui.theme.GoogleSansFlex
import com.nukirk.medrx.ui.theme.MedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Calendar

class AdvancedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("med_settings", MODE_PRIVATE) }
            val savedTheme = prefs.getInt(PREF_THEME, THEME_SYSTEM)

            MedTheme(themeOverride = savedTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AdvancedSettingsScreen(
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

const val PREF_AUTO_UPDATES = "pref_auto_updates"
const val PREF_EXPERIMENTAL_NAV_BAR = "ExperimentalNavBar"
private const val PRE_IMPORT_BACKUP_FILE = "med_data_pre_import.json"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("med_settings", Context.MODE_PRIVATE) }
    var autoUpdates by remember { mutableStateOf(prefs.getBoolean(PREF_AUTO_UPDATES, true)) }

    var showRestartDialog by remember { mutableStateOf(false) }
    var showResetPopup by remember { mutableStateOf(false) }

    fun loadArchivedMeds(): List<MedData> = DataRepository.loadData(context).filter {
        it.type == ItemType.Medicine && it.endDate != null && !LocalDate.now().isBefore(it.endDate)
    }
    var archivedMeds by remember { mutableStateOf(loadArchivedMeds()) }
    var duplicateReviewRevision by remember { mutableStateOf(false) }
    val duplicateCandidates = remember(duplicateReviewRevision, archivedMeds) {
        MedicationDuplicateReview.findCandidates(DataRepository.loadData(context))
            .filter { prefs.getString(MedicationDuplicateReview.reviewPreferenceKey(it), null) == null }
    }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    exportSettings(context, it)
                }
            }
        }

    val exportCsvLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    exportCsv(context, it)
                }
            }
        }

    val exportSkipReportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    exportSkipReport(context, it)
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    val success = importSettings(context, it)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            showRestartDialog = true
                        }
                    }
                }
            }
        }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val appBarTypography = MaterialTheme.typography.copy(
        headlineMedium = MaterialTheme.typography.displaySmall.copy(
            fontFamily = GoogleSansFlex,
            fontWeight = FontWeight.Normal
        ),
        titleLarge = MaterialTheme.typography.titleLarge.copy(
            fontFamily = GoogleSansFlex,
            fontWeight = FontWeight.Normal
        )
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Scaffold(
            topBar = {
                MaterialTheme(typography = appBarTypography) {
                    LargeTopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.settings_advanced_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                                ExpressiveIconButton(
                                    onClick = onBack,
                                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                }
            },
            containerColor = Color.Transparent,
            modifier = Modifier
                .widthIn(max = 700.dp)
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) { padding ->
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(20.dp)) }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.Settings,
                            title = stringResource(R.string.settings_auto_updates_title),
                            subtitle = stringResource(R.string.settings_auto_updates_desc),
                            containerColor = Color(0xFFfcbd00),
                            iconColor = Color(0xFF6d3a01),
                            index = 0,
                            count = 2,
                            onClick = {
                                autoUpdates = !autoUpdates
                                prefs.edit().putBoolean(PREF_AUTO_UPDATES, autoUpdates).apply()
                            },
                            trailingContent = {
                                Switch(
                                    checked = autoUpdates,
                                    onCheckedChange = {
                                        autoUpdates = it
                                        prefs.edit().putBoolean(PREF_AUTO_UPDATES, it).apply()
                                    },
                                    thumbContent = {
                                        if (autoUpdates) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        }
                                    }
                                )
                            }
                        )
                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.Flag,
                            title = stringResource(R.string.settings_setup_title),
                            subtitle = stringResource(R.string.settings_setup_desc),
                            containerColor = Color(0xFFffaee4),
                            iconColor = Color(0xFF8d0053),
                            index = 1,
                            count = 2,
                            onClick = {
                                val intent = Intent(context, WelcomeActivity::class.java).apply {
                                    putExtra("FORCE_SHOW", true)
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }

                item {
                    Text(
                        text = stringResource(R.string.settings_backup_header),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = GoogleSansFlex,
                            fontWeight = FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.CloudUpload,
                            title = stringResource(R.string.settings_export_title),
                            subtitle = stringResource(R.string.settings_export_desc),
                            containerColor = Color(0xFF80da88),
                            iconColor = Color(0xFF00522c),
                            index = 0,
                            count = 4,
                            onClick = {
                                val timestamp = Calendar.getInstance().timeInMillis
                                exportLauncher.launch("med_backup_$timestamp.json")
                            }
                        )

                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.CloudDownload,
                            title = stringResource(R.string.settings_import_title),
                            subtitle = stringResource(R.string.settings_import_desc),
                            containerColor = Color(0xFF67d4ff),
                            iconColor = Color(0xFF004e5d),
                            index = 1,
                            count = 4,
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain"))
                            }
                        )

                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.TableChart,
                            title = stringResource(R.string.settings_export_csv_title),
                            subtitle = stringResource(R.string.settings_export_csv_desc),
                            containerColor = Color(0xFFb5ccff),
                            iconColor = Color(0xFF1c2f5c),
                            index = 2,
                            count = 4,
                            onClick = {
                                val timestamp = Calendar.getInstance().timeInMillis
                                exportCsvLauncher.launch("med_data_$timestamp.csv")
                            }
                        )

                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.EventBusy,
                            title = stringResource(R.string.skip_report_title),
                            subtitle = stringResource(R.string.skip_report_desc),
                            containerColor = Color(0xFFffd8be),
                            iconColor = Color(0xFF5c3b00),
                            index = 3,
                            count = 4,
                            onClick = {
                                val timestamp = Calendar.getInstance().timeInMillis
                                exportSkipReportLauncher.launch("skipped_doses_$timestamp.csv")
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }

                item {
                    Text(
                        text = stringResource(R.string.duplicate_review_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = GoogleSansFlex),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.duplicate_review_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )
                    if (duplicateCandidates.isEmpty()) {
                        Text(stringResource(R.string.duplicate_review_none), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            duplicateCandidates.forEachIndexed { index, candidate ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        candidate.items.forEach { med ->
                                            val end = med.endDate?.toString() ?: stringResource(R.string.duplicate_review_date_open)
                                            val dose = listOfNotNull(med.doseAmount, med.doseUnit).joinToString(" ").ifBlank { "—" }
                                            Text("${med.title} · $dose · ${med.creationTime}", fontWeight = FontWeight.Medium)
                                            Text("${med.creationDate} – $end · ${stringResource(R.string.duplicate_review_taken_skipped, med.takenHistory.size, med.skipHistory.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        val overlapEnd = candidate.overlappingTo?.toString() ?: stringResource(R.string.duplicate_review_date_open)
                                        Text(stringResource(R.string.duplicate_review_overlap, candidate.overlappingFrom.toString(), overlapEnd), style = MaterialTheme.typography.labelMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = {
                                                prefs.edit().putString(MedicationDuplicateReview.reviewPreferenceKey(candidate), DuplicateReviewState.REVIEWED).apply()
                                                duplicateReviewRevision = !duplicateReviewRevision
                                            }) { Text(stringResource(R.string.duplicate_review_reviewed)) }
                                            TextButton(onClick = {
                                                prefs.edit().putString(MedicationDuplicateReview.reviewPreferenceKey(candidate), DuplicateReviewState.DISMISSED).apply()
                                                duplicateReviewRevision = !duplicateReviewRevision
                                            }) { Text(stringResource(R.string.duplicate_review_dismiss)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }

                item {
                    Text(
                        text = stringResource(R.string.settings_testing_header),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = GoogleSansFlex,
                            fontWeight = FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.Notifications,
                            title = stringResource(R.string.settings_test_alarm_title),
                            subtitle = stringResource(R.string.settings_test_alarm_desc),
                            containerColor = Color(0xFFa0d57b),
                            iconColor = Color(0xFF1c4a00),
                            index = 0,
                            count = 2,
                            onClick = {
                                val now = LocalTime.now()
                                val items = DataRepository.loadData(context)
                                val closest =
                                    items.filter { it.type == ItemType.Medicine }.minByOrNull {
                                        val diff = ChronoUnit.MINUTES.between(now, it.creationTime)
                                        if (diff >= 0) diff else diff + 24 * 60
                                    }

                                if (closest != null) {
                                    val intent = Intent(context, AlarmActivity::class.java).apply {
                                        putExtra("ITEM_TITLE", closest.title)
                                        putExtra("ITEM_ID", closest.id)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        )

                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.BugReport,
                            title = stringResource(R.string.settings_test_crash_title),
                            subtitle = stringResource(R.string.settings_test_crash_desc),
                            containerColor = Color(0xFFffb869),
                            iconColor = Color(0xFF5c3000),
                            index = 1,
                            count = 2,
                            onClick = {
                                throw RuntimeException("Test Crash Triggered")
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }

                if (archivedMeds.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.archived_meds_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = GoogleSansFlex,
                                fontWeight = FontWeight.Normal
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            archivedMeds.forEachIndexed { idx, med ->
                                AdvancedSegmentedItem(
                                    icon = Icons.Rounded.Archive,
                                    title = med.title,
                                    subtitle = stringResource(R.string.archived_med_badge),
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    index = idx,
                                    count = archivedMeds.size,
                                    onClick = {
                                        val items = DataRepository.loadData(context).toMutableList()
                                        val selected = items.firstOrNull { it.id == med.id }
                                        if (selected != null) {
                                            val groupIds = if (selected.groupId != null) {
                                                items.filter {
                                                    it.type == ItemType.Medicine &&
                                                        it.groupId == selected.groupId
                                                }.map { it.id }.toSet()
                                            } else {
                                                setOf(selected.id)
                                            }
                                            val restoredItems = items.map { current ->
                                                if (current.id in groupIds) {
                                                    current.copy(
                                                        endDate = null,
                                                        supplyAlertShown = false
                                                    )
                                                } else current
                                            }
                                            DataRepository.saveData(context, restoredItems)
                                            restoredItems.filter { it.id in groupIds }.forEach {
                                                NotificationReceiver.scheduleNotification(context, it)
                                            }
                                            archivedMeds = loadArchivedMeds()
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.med_restored, med.title),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.Restore,
                                            contentDescription = stringResource(R.string.restore_med),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.settings_danger_zone_header),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = GoogleSansFlex,
                            fontWeight = FontWeight.Normal
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        AdvancedSegmentedItem(
                            icon = Icons.Rounded.DeleteForever,
                            title = stringResource(R.string.settings_reset_title),
                            subtitle = stringResource(R.string.settings_reset_desc),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            iconColor = MaterialTheme.colorScheme.onErrorContainer,
                            index = 0,
                            count = 1,
                            onClick = {
                                showResetPopup = true
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(48.dp)) }
            }
        }
    }

    if (showResetPopup) {
        ResetPopup(
            onDismiss = { showResetPopup = false },
            onConfirm = {
                showResetPopup = false
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.clearApplicationUserData()
            }
        )
    }

    if (showRestartDialog) {
        RestorePopup(
            onDismiss = { showRestartDialog = false },
            onRestart = {
                val packageManager = context.packageManager
                val intent = packageManager.getLaunchIntentForPackage(context.packageName)
                val componentName = intent?.component
                val mainIntent = Intent.makeRestartActivityTask(componentName)
                context.startActivity(mainIntent)
                Runtime.getRuntime().exit(0)
            }
        )
    }
}

@Composable
private fun AdvancedSegmentedItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    containerColor: Color,
    iconColor: Color,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null
) {
    SegmentedListItem(
        onClick = onClick,
        modifier = if (count == 1) Modifier.clip(RoundedCornerShape(20.dp)) else Modifier,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        content = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(containerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.Normal,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            fontFamily = GoogleSansFlex,
                            fontWeight = FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (trailingContent != null) {
                    Spacer(modifier = Modifier.width(16.dp))
                    trailingContent()
                }
            }
        }
    )
}

private suspend fun exportSettings(context: Context, uri: Uri) {
    withContext(Dispatchers.IO) {
        try {
            val root = JSONObject()
            val prefs = context.getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
            val settings = context.getSharedPreferences("med_settings", Context.MODE_PRIVATE)

            val prefsJson = JSONObject()
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Set<*> -> prefsJson.put(k, JSONArray(v))
                    else -> prefsJson.put(k, v)
                }
            }
            root.put("med_prefs", prefsJson)

            val settingsJson = JSONObject()
            settings.all.forEach { (k, v) ->
                when (v) {
                    is Set<*> -> settingsJson.put(k, JSONArray(v))
                    else -> settingsJson.put(k, v)
                }
            }
            root.put("med_settings", settingsJson)

            try {
                val allItems = DataRepository.loadData(context)
                val dataArray = JSONArray()
                allItems.forEach { dataArray.put(it.toJson()) }
                root.put("med_data_v2", dataArray)
            } catch (e: Exception) {
            }

            try {
                val fileInputStream = context.openFileInput("med_data.dat")
                val bytes = fileInputStream.readBytes()
                fileInputStream.close()
                val base64Data = Base64.encodeToString(bytes, Base64.DEFAULT)
                root.put("med_data_file", base64Data)
            } catch (e: FileNotFoundException) {
            } catch (e: Exception) {
            }

            context.contentResolver.openOutputStream(uri)?.use {
                it.write(root.toString().toByteArray())
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.export_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.export_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

private suspend fun importSettings(context: Context, uri: Uri): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val fileText = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            } ?: ""
            val isJson = fileText.trimStart().startsWith("{")
            val root = if (isJson) JSONObject(fileText) else JSONObject()

            if (root.has("med_prefs")) {
                try {
                    val prefs = context.getSharedPreferences("med_prefs", Context.MODE_PRIVATE)
                    val editor = prefs.edit().clear()
                    val json = root.getJSONObject("med_prefs")
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (val value = json.get(key)) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is String -> editor.putString(key, value)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) set.add(value.getString(i))
                                editor.putStringSet(key, set)
                            }
                        }
                    }
                    editor.apply()
                } catch (e: Exception) {
                }
            }

            if (root.has("med_settings")) {
                try {
                    val settings =
                        context.getSharedPreferences("med_settings", Context.MODE_PRIVATE)
                    val editor = settings.edit().clear()
                    val json = root.getJSONObject("med_settings")
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (val value = json.get(key)) {
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is String -> editor.putString(key, value)
                            is JSONArray -> {
                                val set = mutableSetOf<String>()
                                for (i in 0 until value.length()) set.add(value.getString(i))
                                editor.putStringSet(key, set)
                            }
                        }
                    }
                    editor.apply()
                } catch (e: Exception) {
                }
            }

            val importedItems = mutableListOf<MedData>()

            if (root.has("med_data_v2")) {
                try {
                    val dataArray = root.getJSONArray("med_data_v2")
                    for (i in 0 until dataArray.length()) {
                        try {
                            importedItems.add(MedData.fromJson(dataArray.getJSONObject(i)))
                        } catch (e: Exception) {
                        }
                    }
                } catch (e: Exception) {
                }
            }

            if (root.has("med_data_file")) {
                try {
                    val base64Data = root.getString("med_data_file")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    context.openFileOutput("med_data_temp.dat", Context.MODE_PRIVATE).use {
                        it.write(bytes)
                    }
                    val fis = context.openFileInput("med_data_temp.dat")
                    val ois = LegacyObjectInputStream(fis)
                    val oldList = ois.readObject() as? java.util.ArrayList<*>
                    ois.close()
                    context.deleteFile("med_data_temp.dat")

                    oldList?.forEach { old ->
                        if (old is j4.p1) {
                            try {
                                val migratedType = when (old.f.name) {
                                    "Medicine", "b" -> ItemType.Medicine
                                    else -> ItemType.Event
                                }
                                importedItems.add(
                                    MedData(
                                        id = old.d,
                                        groupId = old.e,
                                        type = migratedType,
                                        title = old.g,
                                        iconName = old.h,
                                        colorCode = old.i,
                                        frequencyLabel = old.j,
                                        creationDate = old.k,
                                        creationTime = old.l,
                                        takenHistory = old.m,
                                        recurrenceDays = old.n,
                                        endDate = old.o,
                                        notes = null,
                                        displayOrder = 0,
                                        intervalGap = null,
                                        category = null
                                    )
                                )
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }

            // Universal CSV import: any spreadsheet following the documented CSV
            // schema can be turned into medicines/events.
            var csvCount = -1
            if (!isJson) {
                try {
                    val csvItems = CsvPortability.parseCsv(fileText)
                    csvCount = csvItems.size
                    csvItems.forEach { item ->
                        val dupe = importedItems.indexOfFirst { it.id == item.id }
                        if (dupe == -1) importedItems.add(item)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            e.message ?: context.getString(R.string.import_error),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext false
                }
            }

            // MediFix import: detect MediFix exports (medications/schedules/intakeLogs)
            // and convert them to MedData entries before merging.
            var medifixCount = -1
            if (MedifixImporter.isMedifixExport(root)) {
                try {
                    val medifixItems = MedifixImporter.parse(context, root)
                    medifixCount = medifixItems.size
                    medifixItems.forEach { item ->
                        val dupe = importedItems.indexOfFirst { it.id == item.id }
                        if (dupe == -1) importedItems.add(item)
                    }
                } catch (e: Exception) {
                }
            }

            val currentItems = try {
                DataRepository.loadData(context)
            } catch (e: Exception) {
                emptyList()
            }

            val mergedItems = currentItems.toMutableList()
            val existingIds = currentItems.map { it.id }.toSet()

            importedItems.forEach { item ->
                if (!existingIds.contains(item.id)) {
                    mergedItems.add(item)
                } else {
                    val existingItemIndex = mergedItems.indexOfFirst { it.id == item.id }
                    if (existingItemIndex != -1) {
                        val existingItem = mergedItems[existingItemIndex]
                        val mergedHistory = java.util.HashMap(existingItem.takenHistory)
                        item.takenHistory.forEach { (date, time) ->
                            if (!mergedHistory.containsKey(date)) {
                                mergedHistory[date] = time
                            }
                        }
                        mergedItems[existingItemIndex] =
                            existingItem.copy(takenHistory = mergedHistory)
                    }
                }
            }

            try {
                // Safety net: snapshot current data before applying the import, so a
                // bad import file can never destroy the user's existing records.
                if (currentItems.isNotEmpty()) {
                    val backupArray = JSONArray()
                    currentItems.forEach { backupArray.put(it.toJson()) }
                    val backupRoot = JSONObject()
                    backupRoot.put("med_data_v2", backupArray)
                    java.io.File(context.filesDir, PRE_IMPORT_BACKUP_FILE)
                        .writeText(backupRoot.toString())
                }

                DataRepository.saveData(context, mergedItems)
                context.deleteFile("med_data.dat")
            } catch (e: Exception) {
            }

            // Schedule alarms for all medicines now, so reminders work immediately
            // after the restart — imported meds would otherwise stay silent until
            // the next device reboot (alarms are only auto-rescheduled on boot).
            try {
                mergedItems.forEach { item ->
                    if (item.type == ItemType.Medicine) {
                        NotificationReceiver.scheduleNotification(context, item)
                    }
                }
            } catch (e: Exception) {
            }

            // An imported backup that is already below its low-supply threshold
            // must alert immediately, not after the next dose event.
            try {
                InventoryService.createNotificationChannel(context)
                if (InventoryService.evaluateAll(context, mergedItems)) {
                    DataRepository.saveData(context, mergedItems)
                }
            } catch (e: Exception) {
            }

            if (medifixCount >= 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (medifixCount > 0) context.getString(R.string.import_medifix_summary, medifixCount)
                        else context.getString(R.string.import_medifix_zero),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            if (csvCount >= 0) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (csvCount > 0) context.getString(R.string.import_csv_summary, csvCount)
                        else context.getString(R.string.import_csv_zero),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.import_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
            false
        }
    }
}

private suspend fun exportCsv(context: Context, uri: Uri) {
    withContext(Dispatchers.IO) {
        try {
            val items = DataRepository.loadData(context)
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(CsvPortability.toCsv(items).toByteArray(Charsets.UTF_8))
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.export_error), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun exportSkipReport(context: Context, uri: Uri) {
    withContext(Dispatchers.IO) {
        try {
            val items = DataRepository.loadData(context)
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(SkipReport.toCsv(items).toByteArray(Charsets.UTF_8))
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.export_success), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.export_error), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class LegacyObjectInputStream(inputStream: InputStream) : ObjectInputStream(inputStream) {
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