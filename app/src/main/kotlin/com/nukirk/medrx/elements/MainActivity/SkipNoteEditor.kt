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
import com.nukirk.medrx.ui.theme.GoogleSansFlex

/**
 * Edits the note attached to a skip that has already been recorded.
 *
 * Notes are jotted down mid-reminder, so they need a way back in: this opens
 * prefilled from the card's skip detail line, and saving a blank field removes
 * the note. Only the note is replaceable — the reason and the skip itself are
 * changed elsewhere.
 */
@Composable
fun SkipNoteEditorDialog(
    initialNote: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var note by remember { mutableStateOf(initialNote.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        title = {
            Text(
                text = stringResource(R.string.skip_note_edit_title),
                fontFamily = GoogleSansFlex,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    text = stringResource(R.string.skip_note_edit_help),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = { onConfirm(note) },
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
