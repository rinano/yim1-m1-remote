package com.yim1remote.app.ui.ble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.ble.BleDeviceInfo
import com.yim1remote.app.data.ble.BleError
import com.yim1remote.app.data.ble.requiresCameraAction
import com.yim1remote.app.data.ble.userMessage
import com.yim1remote.app.ui.components.ErrorCard
import com.yim1remote.app.ui.components.SectionCard
import com.yim1remote.app.ui.theme.CameraAmber
import com.yim1remote.app.ui.theme.CameraHairline
import com.yim1remote.app.ui.theme.CameraTextMuted
import com.yim1remote.app.ui.theme.ExposureReadoutStyle

/**
 * 「蓝牙自动配网」 card on the 连接 tab.
 *
 * The camera's Wi-Fi is off by default and its WPA2 password is random per
 * session, so this card is the app's only automatic path to a usable hotspot:
 * scan → pair (accept on the camera screen) → read SSID/password → user joins the
 * hotspot in system Wi-Fi settings → 连接相机.
 *
 * Manual Wi-Fi join is intentionally **not** automated in v1; the card only
 * produces and displays the credentials.
 */
@Composable
fun BleProvisioningCard(
    state: BleUiState,
    onScan: () -> Unit,
    onProvision: (BleDeviceInfo) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onTogglePassword: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.ble_card_title), modifier = modifier) {
        when (state.stage) {
            BleUiStage.IDLE -> IdleBleContent(
                rememberedDevice = state.rememberedDevice,
                onScan = onScan,
                onProvision = onProvision,
            )

            BleUiStage.PERMISSIONS -> PermissionBleContent(
                missingPermissions = state.missingPermissions,
                permissionDeniedOnce = state.permissionDeniedOnce,
                onRequestPermissions = onRequestPermissions,
                onOpenAppSettings = { openAppSettings(context) },
            )

            BleUiStage.BLUETOOTH_OFF -> BluetoothOffBleContent(
                onOpenBluetoothSettings = { openBluetoothSettings(context) },
                onRefreshEnvironment = onRefreshEnvironment,
            )

            BleUiStage.SCANNING -> ScanningBleContent(onCancel = onCancel)

            BleUiStage.DEVICES -> DeviceListBleContent(
                devices = state.devices,
                onProvision = onProvision,
                onRescan = onScan,
            )

            BleUiStage.PROVISIONING -> ProvisioningBleContent(
                progressLabel = state.progressLabel,
                awaitingCameraConfirmation = state.awaitingCameraConfirmation,
                onCancel = onCancel,
            )

            BleUiStage.SUCCESS -> SuccessBleContent(
                state = state,
                onTogglePassword = onTogglePassword,
                onCopied = onCopied,
                onOpenWifiSettings = { openWifiSettings(context) },
                onScanAgain = onScan,
            )

            BleUiStage.FAILED -> FailedBleContent(
                state = state,
                onRetry = onRetry,
                onRescan = onScan,
                onRefreshEnvironment = onRefreshEnvironment,
                onRequestPermissions = onRequestPermissions,
            )
        }
    }
}

// =====================================================================
// State content
// =====================================================================

@Composable
private fun IdleBleContent(
    rememberedDevice: BleDeviceInfo?,
    onScan: () -> Unit,
    onProvision: (BleDeviceInfo) -> Unit,
) {
    Text(
        text = stringResource(R.string.ble_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = stringResource(R.string.ble_pairing_warning),
        style = MaterialTheme.typography.labelSmall,
        color = CameraTextMuted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onScan, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ble_scan_action), maxLines = 1)
        }
        if (rememberedDevice != null) {
            OutlinedButton(
                onClick = { onProvision(rememberedDevice) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.ble_provision_again_fmt, rememberedDevice.displayName),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.ble_sd_hint),
        style = MaterialTheme.typography.labelSmall,
        color = CameraTextMuted,
    )
}

@Composable
private fun PermissionBleContent(
    missingPermissions: List<String>,
    permissionDeniedOnce: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val labels = if (missingPermissions.isEmpty()) {
        listOf(stringResource(R.string.ble_permission_bluetooth))
    } else {
        missingPermissions.map { permissionLabel(it) }
    }
    Text(
        text = stringResource(R.string.ble_permission_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = stringResource(R.string.ble_permission_explain, labels.joinToString("、")),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ble_grant_permission), maxLines = 1)
        }
        if (permissionDeniedOnce) {
            OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ble_open_app_settings), maxLines = 1)
            }
        }
    }
    if (permissionDeniedOnce) {
        Text(
            text = stringResource(R.string.ble_permission_denied_hint),
            style = MaterialTheme.typography.labelSmall,
            color = CameraTextMuted,
        )
    }
}

@Composable
private fun BluetoothOffBleContent(
    onOpenBluetoothSettings: () -> Unit,
    onRefreshEnvironment: () -> Unit,
) {
    Text(
        text = stringResource(R.string.ble_bluetooth_off_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = stringResource(R.string.ble_bluetooth_off_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onOpenBluetoothSettings, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ble_open_bluetooth_settings), maxLines = 1)
        }
        OutlinedButton(onClick = onRefreshEnvironment, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.ble_recheck), maxLines = 1)
        }
    }
}

@Composable
private fun ScanningBleContent(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.ble_scanning),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.ble_scan_cancel))
        }
    }
}

@Composable
private fun DeviceListBleContent(
    devices: List<BleDeviceInfo>,
    onProvision: (BleDeviceInfo) -> Unit,
    onRescan: () -> Unit,
) {
    if (devices.isEmpty()) {
        Text(
            text = stringResource(R.string.ble_not_found),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.ble_not_found_hint),
            style = MaterialTheme.typography.labelSmall,
            color = CameraTextMuted,
        )
        Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ble_rescan_action))
        }
    } else {
        Text(
            text = stringResource(R.string.ble_devices_title),
            style = MaterialTheme.typography.labelSmall,
            color = CameraTextMuted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            devices.forEach { device ->
                BleDeviceRow(device = device, onClick = { onProvision(device) })
            }
        }
        TextButton(onClick = onRescan) {
            Text(stringResource(R.string.ble_rescan_action))
        }
    }
}

@Composable
private fun BleDeviceRow(device: BleDeviceInfo, onClick: () -> Unit) {
    val details = buildList {
        add(device.address)
        device.rssi?.let { add("$it dBm") }
        if (device.bonded) add(stringResource(R.string.ble_device_bonded))
    }.joinToString(" · ")
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, CameraHairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (device.isLikelyCamera) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (device.isLikelyCamera) {
                Text(
                    text = stringResource(R.string.ble_device_camera),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ProvisioningBleContent(
    progressLabel: String?,
    awaitingCameraConfirmation: Boolean,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = progressLabel ?: stringResource(R.string.ble_provisioning),
                style = MaterialTheme.typography.bodySmall,
            )
            if (awaitingCameraConfirmation) {
                Text(
                    text = stringResource(R.string.ble_awaiting_camera),
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraAmber,
                )
            }
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.ble_provision_cancel))
        }
    }
    Text(
        text = stringResource(R.string.ble_sd_hint),
        style = MaterialTheme.typography.labelSmall,
        color = CameraTextMuted,
    )
}

@Composable
private fun SuccessBleContent(
    state: BleUiState,
    onTogglePassword: () -> Unit,
    onCopied: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val credentials = state.credentials ?: return
    val context = LocalContext.current
    val ssidClipLabel = stringResource(R.string.ble_clip_ssid_label)
    val passwordClipLabel = stringResource(R.string.ble_clip_password_label)
    Text(
        text = stringResource(R.string.ble_success_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(R.string.ble_success_ssid_fmt, credentials.ssid),
        style = MaterialTheme.typography.bodyMedium,
    )

    // Credentials stay visible so they can also be typed into the system Wi-Fi dialog.
    CredentialRow(
        label = "SSID",
        value = credentials.ssid,
        monospace = true,
    )
    CredentialRow(
        label = stringResource(R.string.ble_password_label),
        value = if (state.passwordRevealed) {
            credentials.password
        } else {
            "•".repeat(credentials.password.length.coerceIn(4, 12))
        },
        monospace = true,
        trailing = {
            IconButton(onClick = onTogglePassword) {
                Icon(
                    imageVector = if (state.passwordRevealed) {
                        Icons.Outlined.VisibilityOff
                    } else {
                        Icons.Outlined.Visibility
                    },
                    contentDescription = stringResource(
                        if (state.passwordRevealed) R.string.ble_hide_password else R.string.ble_show_password,
                    ),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                copyToClipboard(context, ssidClipLabel, credentials.ssid)
                onCopied()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.ble_copy_ssid), maxLines = 1)
        }
        OutlinedButton(
            onClick = {
                copyToClipboard(context, passwordClipLabel, credentials.password)
                onCopied()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.ble_copy_password), maxLines = 1)
        }
    }
    Button(onClick = onOpenWifiSettings, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Outlined.Wifi,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.action_wifi_settings))
    }
    Text(
        text = stringResource(R.string.ble_success_instruction),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (credentials.wifiEnabled == false) {
        Text(
            text = stringResource(R.string.ble_wifi_off_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        text = stringResource(R.string.ble_radio_note),
        style = MaterialTheme.typography.labelSmall,
        color = CameraTextMuted,
    )
    Text(
        text = stringResource(R.string.ble_security_note),
        style = MaterialTheme.typography.labelSmall,
        color = CameraTextMuted,
    )
    TextButton(onClick = onScanAgain) {
        Text(stringResource(R.string.ble_provision_again))
    }
}

/** One credential line: label, monospace value, optional trailing icon. */
@Composable
private fun CredentialRow(
    label: String,
    value: String,
    monospace: Boolean,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = CameraTextMuted,
            )
            Text(
                text = value,
                style = if (monospace) ExposureReadoutStyle else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) trailing()
        }
    }
}

@Composable
private fun FailedBleContent(
    state: BleUiState,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val error = state.error
    val message = error?.userMessage() ?: stringResource(R.string.snackbar_connect_failed)
    val requiresCameraAction = error?.requiresCameraAction == true
    val unsupported = error is BleError.BluetoothUnsupported

    ErrorCard(
        title = stringResource(R.string.ble_failed_title),
        body = message,
    )
    if (requiresCameraAction) {
        Text(
            text = stringResource(R.string.ble_camera_action_hint),
            style = MaterialTheme.typography.labelSmall,
            color = CameraAmber,
        )
    }
    if (unsupported) {
        Text(
            text = stringResource(R.string.ble_unsupported_hint),
            style = MaterialTheme.typography.labelSmall,
            color = CameraTextMuted,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            error is BleError.PermissionDenied -> {
                Button(onClick = onRequestPermissions, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ble_grant_permission), maxLines = 1)
                }
            }

            unsupported -> {
                OutlinedButton(onClick = onRefreshEnvironment, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ble_recheck), maxLines = 1)
                }
            }

            else -> {
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ble_retry_action), maxLines = 1)
                }
            }
        }
        if (!unsupported) {
            OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ble_rescan_action), maxLines = 1)
            }
        }
    }
}

// =====================================================================
// Platform helpers
// =====================================================================

@Composable
private fun permissionLabel(permission: String): String = when {
    permission.endsWith("BLUETOOTH_SCAN", ignoreCase = true) ||
        permission.endsWith("BLUETOOTH_CONNECT", ignoreCase = true) ->
        stringResource(R.string.ble_permission_bluetooth)

    permission.endsWith("ACCESS_FINE_LOCATION", ignoreCase = true) ||
        permission.endsWith("ACCESS_COARSE_LOCATION", ignoreCase = true) ->
        stringResource(R.string.ble_permission_location)

    else -> permission.substringAfterLast('.')
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun openWifiSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openBluetoothSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
