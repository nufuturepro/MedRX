@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalTextApi::class)

package com.nukirk.medrx.elements.MainActivity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nukirk.medrx.R
import com.nukirk.medrx.services.MedData
import com.nukirk.medrx.services.labelResId
import com.nukirk.medrx.ui.theme.GoogleSansFlex
import kotlinx.coroutines.launch

/**
 * Restocking a medication.
 *
 * Replaces the old hidden two-tap arrow: the current count, the pack size and
 * the resulting total are all visible before anything is written, and the
 * counted-balance path lets a bottle that was counted wrong be corrected in one
 * step instead of by repeated stepper taps.
 */
@Composable
fun RefillSupplySheet(
    item: MedData,
    onRefill: (Int) -> Unit,
    onSetExact: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val left = item.supplyDosesLeft ?: 0
    val perRefill = item.supplyDosesPerRefill ?: 0
    val unitName = stringResource(item.supplyUnit.labelResId())

    var amount by remember(item.id) {
        mutableStateOf(perRefill.coerceAtLeast(1).toString())
    }
    var exactMode by remember(item.id) { mutableStateOf(false) }
    var exactText by remember(item.id) { mutableStateOf(left.toString()) }

    val addAmount = amount.toIntOrNull()
    val preview = if (addAmount != null && addAmount > 0) left + addAmount else left
    val exactValue = exactText.toIntOrNull()

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.refill_sheet_title, item.title),
                fontFamily = GoogleSansFlex,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.refill_current, left, unitName),
                fontFamily = GoogleSansFlex,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            if (!exactMode) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { typed -> amount = typed.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.refill_add_label, unitName)) },
                    singleLine = true,
                    isError = (addAmount ?: 0) <= 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.refill_preview, left, preview, unitName),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val qty = addAmount
                        if (qty != null && qty > 0) {
                            onRefill(qty)
                            close()
                        }
                    },
                    enabled = (addAmount ?: 0) > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = stringResource(R.string.refill_confirm, addAmount ?: 0, unitName),
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = {
                    exactText = left.toString()
                    exactMode = true
                }) {
                    Text(stringResource(R.string.refill_set_exact))
                }
            } else {
                OutlinedTextField(
                    value = exactText,
                    onValueChange = { typed -> exactText = typed.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.refill_exact_label, unitName)) },
                    singleLine = true,
                    isError = exactValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.refill_exact_desc),
                    fontFamily = GoogleSansFlex,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = { exactMode = false },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.alarm_skip_cancel))
                    }
                    Button(
                        onClick = {
                            val value = exactValue
                            if (value != null) {
                                onSetExact(value)
                                close()
                            }
                        },
                        enabled = exactValue != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.refill_exact_apply))
                    }
                }
            }

            if (item.supplyLedger.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                SupplyLedgerCard(ledger = item.supplyLedger)
            }
        }
    }
}
