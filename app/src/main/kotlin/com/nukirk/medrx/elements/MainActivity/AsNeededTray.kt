@file:OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package com.nukirk.medrx.elements.MainActivity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nukirk.medrx.AVAILABLE_ICONS
import com.nukirk.medrx.R
import com.nukirk.medrx.services.MedData
import com.nukirk.medrx.services.labelResId
import com.nukirk.medrx.ui.theme.GoogleSansFlex
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun AsNeededTray(
    items: List<MedData>,
    pageDate: LocalDate,
    activeOnPage: (MedData) -> Boolean,
    onLog: (MedData) -> Unit,
    onClick: (MedData) -> Unit,
    onLongClick: (MedData) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val totalTodayUses = remember(items, pageDate) {
        items.sumOf { it.prnUsages.count { u -> u.date == pageDate } }
    }
    val headerLabel = if (items.size == 1)
        stringResource(R.string.as_needed_tray_expand, 1)
    else stringResource(R.string.as_needed_tray_expand_plural, items.size)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.as_needed_tray_title), style = MaterialTheme.typography.titleMedium, fontFamily = GoogleSansFlex)
                        Text(if (expanded) stringResource(R.string.as_needed_tray_collapse) else headerLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (totalTodayUses > 0) {
                            Text(stringResource(R.string.as_needed_logged_today, totalTodayUses), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (items.isEmpty()) {
                        Text(stringResource(R.string.as_needed_no_items), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        items.forEach { med ->
                            PrnMedRow(med = med, pageDate = pageDate, isActive = activeOnPage(med), onLog = { onLog(med) }, onClick = { onClick(med) }, onLongClick = { onLongClick(med) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun PrnMedRow(
    med: MedData,
    pageDate: LocalDate,
    isActive: Boolean,
    onLog: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val todayCount = med.prnUsages.count { it.date == pageDate }
    val last = med.prnUsages.maxWithOrNull(compareBy({ it.date }, { it.time }))
    val maxPerDay = med.prnMaxPerDay
    val atLimit = maxPerDay != null && maxPerDay > 0 && todayCount >= maxPerDay
    val icon = when (med.iconName) {
        "MixtureMed" -> ImageVector.vectorResource(R.drawable.ic_mixture)
        "Bed" -> ImageVector.vectorResource(R.drawable.ic_mind)
        "Mood" -> ImageVector.vectorResource(R.drawable.ic_sick)
        else -> if (med.iconName != null && AVAILABLE_ICONS.containsKey(med.iconName)) AVAILABLE_ICONS[med.iconName]!! else Icons.Rounded.Medication
    }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(med.title, style = MaterialTheme.typography.titleSmall, fontFamily = GoogleSansFlex, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!isActive) {
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.as_needed_archived_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                if (!med.doseAmount.isNullOrBlank() || !med.doseUnit.isNullOrBlank()) {
                    Text(listOfNotNull(med.doseAmount, med.doseUnit).joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    if (todayCount == 0) stringResource(R.string.as_needed_never) else stringResource(R.string.as_needed_logged_today, todayCount) + if (maxPerDay != null && maxPerDay > 0) " / $maxPerDay" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (atLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (last != null) {
                    Text(stringResource(R.string.as_needed_last_used, last.date.toString(), last.time.format(DateTimeFormatter.ofPattern("HH:mm"))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (med.supplyDosesLeft != null) {
                    val low = med.supplyLowThreshold != null && med.supplyDosesLeft <= med.supplyLowThreshold!!
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Inventory2, null, modifier = Modifier.size(12.dp), tint = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.supply_badge_format, med.supplyDosesLeft, stringResource(med.supplyUnit.labelResId())) + if (med.supplyEstimated) " \u2248" else "", style = MaterialTheme.typography.labelSmall, color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (atLimit) {
                    Text(stringResource(R.string.prn_max_reached), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onLog, enabled = isActive, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) {
                Text(stringResource(R.string.as_needed_log_action), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
