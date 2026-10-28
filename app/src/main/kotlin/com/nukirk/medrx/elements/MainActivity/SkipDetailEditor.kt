@file:OptIn(ExperimentalTextApi::class)

package com.nukirk.medrx.elements.MainActivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * Corrects what a recorded skip says: the time it was recorded and the note
 * that explains it.
 *
 * Both are written mid-reminder, one-handed, and both are easy to get wrong —
 * the skip is tapped at 21:20 for a dose that was already missed at 20:00. The
 * reason is deliberately not here: a wrong reason means the skip itself is
 * wrong, which is the card's Skip button (take the skip off, record it again).
 * Nothing else moves — the dose stays skipped, keeps its reason, and stock is
 * not involved. Saving a blank note removes the note.
 */
@Composable
fun SkipDetailEditorDialog(
    date: LocalDate,
    initialTime: LocalTime,
    initialNote: String?,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime, String?) -> Unit
) {
    var time by remember { mutableStateOf(initialTime) }
    var note by remember { mutableStateOf(initialNote.orEmpty()) }
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
                text = stringResource(R.string.skip_edit_title),
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
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.alarm_skip_note_label)) },
                    placeholder = { Text(stringResource(R.string.alarm_skip_note_hint)) },
                    minLines = 1,
                    maxLines = 3
                )
                Text(
                    text = stringResource(R.string.skip_edit_help),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = { onConfirm(time, note) },
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
