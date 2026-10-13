@file:OptIn(ExperimentalTextApi::class)

package com.nukirk.medrx.elements.NotificationsSettingsActivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nukirk.medrx.R
import com.nukirk.medrx.elements.SettingsActivity.TextButtonWithAnimatedShape
import com.nukirk.medrx.services.SnoozePolicy

/**
 * Localized snooze length: whole hours when the value divides evenly into
 * hours, minutes otherwise.
 */
@Composable
fun snoozeDurationText(minutes: Int): String {
    val label = SnoozePolicy.label(minutes)
    return if (label.useHours) {
        stringResource(R.string.settings_snooze_duration_desc_hours, label.hours)
    } else {
        stringResource(R.string.settings_snooze_duration_desc, label.minutes)
    }
}

/**
 * Picks how long a snooze lasts: the common lengths, plus any custom value
 * between [SnoozePolicy.MIN_DURATION_MINUTES] and
 * [SnoozePolicy.MAX_DURATION_MINUTES].
 */
@Composable
fun SnoozeDurationPopup(
    currentDuration: Int,
    onOptionSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safeCurrent = SnoozePolicy.clampDuration(currentDuration)
    var showCustom by remember { mutableStateOf(!SnoozePolicy.isPreset(safeCurrent)) }
    var customText by remember { mutableStateOf(safeCurrent.toString()) }
    var isInvalid by remember { mutableStateOf(false) }

    OptionRadioPopup(
        icon = Icons.Rounded.Snooze,
        title = stringResource(R.string.settings_snooze_duration_title),
        onDismiss = onDismiss
    ) {
        SnoozePolicy.DURATION_PRESETS.forEach { duration ->
            RadioOptionRow(
                label = snoozeDurationText(duration),
                selected = !showCustom && safeCurrent == duration,
                onClick = { onOptionSelected(duration) }
            )
        }

        RadioOptionRow(
            label = stringResource(R.string.settings_snooze_duration_custom),
            selected = showCustom,
            onClick = { showCustom = true }
        )

        if (showCustom) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { typed ->
                        customText = typed.filter { it.isDigit() }.take(3)
                        isInvalid = false
                    },
                    label = { Text(stringResource(R.string.settings_snooze_duration_custom_hint)) },
                    singleLine = true,
                    isError = isInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                TextButtonWithAnimatedShape(
                    onClick = {
                        val minutes = customText.toIntOrNull()
                        if (minutes == null ||
                            minutes < SnoozePolicy.MIN_DURATION_MINUTES ||
                            minutes > SnoozePolicy.MAX_DURATION_MINUTES
                        ) {
                            isInvalid = true
                        } else {
                            onOptionSelected(SnoozePolicy.clampDuration(minutes))
                        }
                    },
                    text = stringResource(R.string.settings_snooze_duration_custom_apply)
                )
            }
        }
    }
}

/** Picks how many snoozes a dose gets before it is closed out as a skip. */
@Composable
fun SnoozeLimitPopup(
    currentLimit: Int,
    onOptionSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safeCurrent = SnoozePolicy.clampLimit(currentLimit)

    OptionRadioPopup(
        icon = Icons.Rounded.SkipNext,
        title = stringResource(R.string.settings_snooze_limit_after_title),
        onDismiss = onDismiss
    ) {
        SnoozePolicy.LIMIT_PRESETS.forEach { limit ->
            RadioOptionRow(
                label = stringResource(R.string.settings_snooze_limit_after_desc, limit),
                selected = safeCurrent == limit,
                onClick = { onOptionSelected(limit) }
            )
        }
    }
}
