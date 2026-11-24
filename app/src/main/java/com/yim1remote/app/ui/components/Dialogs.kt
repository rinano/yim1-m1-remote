package com.yim1remote.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.CameraConfig

/**
 * Camera-address override dialog.
 *
 * The YI M1 always answers on `http://192.168.0.10`, but keeping the address
 * editable is the seam that lets the app talk to a mock camera server during
 * development (and covers unusual subnets). Validation uses the frozen
 * [CameraConfig] helpers; persistence lives in the ViewModel via
 * `CameraConfigStore`.
 */
@Composable
fun AddressEditDialog(
    currentBaseUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(currentBaseUrl) }
    val normalized = remember(text) { CameraConfig.normalizeBaseUrl(text) }
    val isValid = remember(text) { CameraConfig.isValidBaseUrl(text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_address_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dialog_address_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !isValid,
                    label = { Text(stringResource(R.string.label_camera_address)) },
                    placeholder = { Text(CameraConfig.DEFAULT_BASE_URL) },
                    supportingText = {
                        Text(
                            if (isValid) {
                                stringResource(R.string.hint_base_url_normalized, normalized)
                            } else {
                                stringResource(R.string.error_base_url_invalid)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    text = stringResource(R.string.hint_default_base_url, CameraConfig.DEFAULT_BASE_URL),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = isValid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.action_reset_default))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Simple destructive confirmation used before deleting camera files. */
@Composable
fun ConfirmDeleteDialog(
    title: String = stringResource(R.string.delete_confirm_title),
    message: String,
    note: String? = null,
    confirmLabel: String = stringResource(R.string.action_delete),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
                if (note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
