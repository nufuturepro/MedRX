@file:OptIn(ExperimentalTextApi::class)

package com.nukirk.medrx.elements.MainActivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nukirk.medrx.ExpressiveTextButton
import com.nukirk.medrx.R
import com.nukirk.medrx.TimeSelectorItem
import com.nukirk.medrx.elements.TimePicker
import com.nukirk.medrx.ui.theme.GoogleSansFlex
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Corrects the recorded clock time of a dose that is already logged.
 *
 * The picker opens on the stored time, so opening this just to look is
 * harmless. Saving moves the record only: the dose stays taken, the supply
 * count does not move, and the ledger row for the day follows the correction.
 * That is what makes this safe for the real case — a dose taken at 08:10 but
 * logged at 11:40 — where un-logging and taking it again would stamp the wrong
 * time twice.
 */
@Composable
fun DoseTimeEditorDialog(
    date: LocalDate,
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    var time by remember { mutableStateOf(initialTime) }
    var showTimePicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        TimePicker(
            onDismiss = { showTimePicker = false },
            onConfirm = { picked: LocalTime ->
                time = picked
                showTimePicker = false
            },
            initialTime = time
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        title = {
            Text(
                text = stringResource(R.string.dose_time_edit_title),
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TimeSelectorItem(
                    label = stringResource(R.string.time_label),
                    time = time,
                    onClick = { showTimePicker = true }
                )
                Text(
                    text = stringResource(R.string.dose_time_edit_help),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = { onConfirm(time) },
                text = stringResource(R.string.save_action),
                contentColor = MaterialTheme.colorScheme.primary
            )
        },
        dismissButton = {
            ExpressiveTextButton(
                onClick = onDismiss,
                text = stringResource(R.string.cancel_action)
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 6.dp
    )
}
