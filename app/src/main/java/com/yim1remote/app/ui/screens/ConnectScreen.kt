package com.yim1remote.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yim1remote.app.R
import com.yim1remote.app.data.ConnectionState
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.ble.BleUiState
import com.yim1remote.app.ui.ble.BleProvisioningCard
import com.yim1remote.app.ui.components.AddressEditDialog
import com.yim1remote.app.ui.components.ConnectionPill
import com.yim1remote.app.ui.components.ErrorCard
import com.yim1remote.app.ui.components.SectionCard
import com.yim1remote.app.ui.components.StatTile
import com.yim1remote.app.ui.theme.CameraAmber
import com.yim1remote.app.ui.theme.CameraGreen
import com.yim1remote.app.ui.util.CameraFormat

/**
 * 连接 tab — gets the camera's Wi-Fi hotspot up and verifies the local HTTP API.
 *
 * Two paths are offered, in this order:
 * 1. **蓝牙自动配网** — the camera's Wi-Fi is off by default and its hotspot
 *    password is random per session; pairing over BLE switches the hotspot on and
 *    reads the SSID/password (`data/ble`, wired by `ui/ble/BleProvisioningCard`).
 * 2. **手动连接** — the camera's own screen shows SSID/password; the user joins
 *    the hotspot in system Wi-Fi settings and returns to tap 连接相机.
 *
 * The app never joins the Wi-Fi network itself (intentional for v1).
 */
@Composable
fun ConnectScreen(
    viewModel: CameraAppViewModel,
    modifier: Modifier = Modifier,
) {
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.cameraStatus.collectAsState()
    val config by viewModel.config.collectAsState()
    val bleState by viewModel.bleState.collectAsState()
    val context = LocalContext.current

    var showAddressDialog by rememberSaveable { mutableStateOf(false) }
    var helpExpanded by rememberSaveable { mutableStateOf(false) }

    // Runtime BLE permissions (API 31+ scan/connect, API 28-30 location).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.bleOnPermissionResult() }

    // Re-check Bluetooth/permission state when the user returns from settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.bleRefreshEnvironment()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { viewModel.bleRefreshEnvironment() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CameraHeroCard(
            connection = connection,
            status = status,
            batteryExternalPower = status?.isExternalPower == true,
            batteryUnknown = stringResource(R.string.value_unknown),
            bleState = bleState,
            onRefreshIdentity = viewModel::bleRefreshIdentity,
        )

        val failure = connection as? ConnectionState.Failed
        if (failure != null) {
            ErrorCard(
                title = stringResource(R.string.connect_error_title),
                body = failure.message?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.connect_error_hint),
                onRetry = { viewModel.connect() },
            )
        }

        ConnectionActionRow(
            connection = connection,
            onConnect = { viewModel.connect() },
            onDisconnect = { viewModel.disconnect() },
        )

        // BLE auto-provisioning: opens the hotspot and reads this session's
        // random SSID/password before the manual Wi-Fi join below.
        BleProvisioningCard(
            state = bleState,
            onScan = viewModel::bleScan,
            onProvision = viewModel::bleProvision,
            onCancel = viewModel::bleCancel,
            onRetry = viewModel::bleRetry,
            onTogglePassword = viewModel::bleTogglePasswordReveal,
            onRequestPermissions = {
                val missing = viewModel.bleMissingPermissions()
                if (missing.isEmpty()) {
                    viewModel.bleScan()
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            onRefreshEnvironment = viewModel::bleRefreshEnvironment,
            onCopied = viewModel::bleNotifyCopied,
        )

        CameraTimeSyncCard(
            bleState = bleState,
            onSync = viewModel::bleSyncTimeNow,
        )

        SectionCard(title = stringResource(R.string.label_camera_address)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.action_edit_address),
                        onClick = { showAddressDialog = true },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = config.baseUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showAddressDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.action_edit_address),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.connect_address_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { showAddressDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_edit_address))
                }
                OutlinedButton(
                    onClick = { openWifiSettings(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_wifi_settings))
                }
            }
            Text(
                text = stringResource(R.string.connect_help_wifi_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HelpCard(
            expanded = helpExpanded,
            onToggle = { helpExpanded = !helpExpanded },
        )

        Text(
            text = stringResource(R.string.about_version_fmt, com.yim1remote.app.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
    }

    if (showAddressDialog) {
        AddressEditDialog(
            currentBaseUrl = config.baseUrl,
            onDismiss = { showAddressDialog = false },
            onSave = { raw ->
                viewModel.updateBaseUrl(raw)
                showAddressDialog = false
            },
            onReset = { viewModel.resetBaseUrlToDefault() },
        )
    }
}

/** Hero card: camera glyph, link state, battery and the four headline readouts. */
@Composable
private fun CameraHeroCard(
    connection: ConnectionState,
    status: CameraStatus?,
    batteryExternalPower: Boolean,
    batteryUnknown: String,
    bleState: BleUiState,
    onRefreshIdentity: () -> Unit,
) {
    val connected = connection is ConnectionState.Connected
    val accent = when (connection) {
        is ConnectionState.Connected -> CameraGreen
        is ConnectionState.Connecting -> CameraAmber
        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val headline = when (connection) {
        is ConnectionState.Connected -> stringResource(R.string.value_connected)
        is ConnectionState.Connecting -> stringResource(R.string.value_connecting)
        is ConnectionState.Failed -> stringResource(R.string.value_failed)
        ConnectionState.Disconnected -> stringResource(R.string.value_disconnected)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(accent.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.connect_hero_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ConnectionPill(state = connection)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = stringResource(R.string.label_battery),
                    value = if (batteryExternalPower) {
                        stringResource(R.string.value_external_power)
                    } else {
                        CameraFormat.battery(status) ?: batteryUnknown
                    },
                    icon = Icons.Outlined.BatteryFull,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.label_remaining_shots),
                    value = CameraFormat.remainingShots(status) ?: stringResource(R.string.value_unknown),
                    icon = Icons.Outlined.PhotoLibrary,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = stringResource(R.string.label_firmware),
                    value = bleState.identity?.bodyFirmware?.takeIf { it.isNotBlank() }
                        ?: CameraFormat.firmware(status)
                        ?: stringResource(R.string.value_unknown),
                    icon = Icons.Outlined.Memory,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.label_lens),
                    value = CameraFormat.lens(status) ?: stringResource(R.string.value_unknown),
                    icon = Icons.Outlined.Camera,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    label = stringResource(R.string.label_region),
                    value = bleState.identity?.regionMarker?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.identity_unknown),
                    icon = Icons.Outlined.Public,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.label_identity_lens),
                    value = bleState.identity?.lensFirmware?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.identity_unknown),
                    icon = Icons.Outlined.Camera,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onRefreshIdentity,
                    enabled = !bleState.identityLoading,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    if (bleState.identityLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.identity_reading))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_read_identity))
                    }
                }
                if (bleState.identity == null && bleState.identityError == null) {
                    Text(
                        text = stringResource(R.string.identity_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            bleState.identityError?.let { error ->
                Text(
                    text = stringResource(R.string.identity_error_fmt, error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (status != null && !status.lensReady) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = stringResource(R.string.value_lens_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                text = stringResource(
                    if (connected) R.string.connect_camera_hint else R.string.connect_hero_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Primary connect/disconnect control with the connecting progress + cancel. */
@Composable
private fun ConnectionActionRow(
    connection: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    when (connection) {
        is ConnectionState.Connecting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.connect_connecting))
                }
                OutlinedButton(onClick = onDisconnect) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }

        is ConnectionState.Connected -> {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(stringResource(R.string.action_disconnect))
            }
        }

        else -> {
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_connect))
            }
        }
    }
}

/** Collapsible 连接帮助 with the four official-app-derived steps. */
@Composable
private fun HelpCard(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    SectionCard {
        Surface(
            onClick = onToggle,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.connect_help_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onToggle) {
                    Text(
                        stringResource(
                            if (expanded) R.string.cd_collapse else R.string.cd_expand,
                        ),
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpStep(number = 1, text = stringResource(R.string.connect_help_step_1))
                HelpStep(number = 2, text = stringResource(R.string.connect_help_step_2))
                HelpStep(number = 3, text = stringResource(R.string.connect_help_step_3))
                HelpStep(number = 4, text = stringResource(R.string.connect_help_step_4))
                Text(
                    text = stringResource(R.string.connect_help_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CameraTimeSyncCard(
    bleState: com.yim1remote.app.ui.ble.BleUiState,
    onSync: () -> Unit,
) {
    val canSync = bleState.rememberedDevice != null &&
        !bleState.timeSyncing &&
        !bleState.isBusy &&
        bleState.missingPermissions.isEmpty() &&
        bleState.bluetoothEnabled
    SectionCard(title = stringResource(R.string.time_sync_title)) {
        Text(
            text = if (bleState.rememberedDevice == null) {
                stringResource(R.string.time_sync_requires_ble)
            } else {
                stringResource(R.string.time_sync_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val lastSync = bleState.lastTimeSyncAtMillis
            Text(
                text = if (lastSync != null) {
                    stringResource(
                        R.string.time_sync_last_fmt,
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                            .format(java.util.Date(lastSync)),
                    )
                } else {
                    ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onSync, enabled = canSync) {
                if (bleState.timeSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(
                        if (bleState.timeSyncing) R.string.time_sync_running else R.string.time_sync_action,
                    ),
                )
            }
        }
        bleState.timeSyncError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun openWifiSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
