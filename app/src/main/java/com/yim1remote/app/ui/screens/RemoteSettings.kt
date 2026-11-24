package com.yim1remote.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.model.CameraOption
import com.yim1remote.app.data.model.CameraOptions
import com.yim1remote.app.data.model.CameraSetting
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.components.CameraSettingRow
import com.yim1remote.app.ui.components.OptionPickerDialog
import com.yim1remote.app.ui.theme.CameraPanel
import com.yim1remote.app.ui.util.CameraFormat

/**
 * Declarative description of one writable camera setting.
 *
 * Both the live-view quick chips and the full 拍摄设置 sheet are generated from
 * these specs, so an option list (wire strings) exists in exactly one place: the
 * frozen `CameraOptions` table recovered from the official app.
 */
data class CameraSettingSpec(
    val key: String,
    val labelRes: Int,
    val setting: CameraSetting,
    val options: (CameraStatus?) -> List<CameraOption>,
    val currentWire: (CameraStatus?) -> String?,
    val currentLabel: (CameraStatus?) -> String?,
    val isEnabled: (CameraStatus?) -> Boolean = { it != null },
)

/** All camera settings exposed by the UI, grouped the way the camera menu is. */
object CameraSettingSpecs {

    private val always: (CameraStatus?) -> Boolean = { it != null }

    val driveMode = CameraSettingSpec(
        key = "drive",
        labelRes = R.string.remote_label_drive,
        setting = CameraSetting.DRIVE_MODE,
        options = { CameraOptions.driveMode },
        currentWire = { it?.driveMode },
        currentLabel = { CameraFormat.driveModeLabel(it) },
        isEnabled = always,
    )

    val imageQuality = CameraSettingSpec(
        key = "quality",
        labelRes = R.string.remote_label_quality,
        setting = CameraSetting.IMAGE_QUALITY,
        options = { CameraOptions.imageQuality },
        currentWire = { it?.imageQuality },
        currentLabel = { CameraFormat.qualityLabel(it) },
        isEnabled = always,
    )

    val imageAspect = CameraSettingSpec(
        key = "aspect",
        labelRes = R.string.remote_label_aspect,
        setting = CameraSetting.IMAGE_ASPECT,
        options = { CameraOptions.imageAspect },
        currentWire = { it?.imageAspect },
        currentLabel = { CameraFormat.aspectLabel(it) },
        isEnabled = always,
    )

    val fileFormat = CameraSettingSpec(
        key = "format",
        labelRes = R.string.remote_label_format,
        setting = CameraSetting.FILE_FORMAT,
        options = { CameraOptions.fileFormat },
        currentWire = { it?.fileFormat },
        currentLabel = { CameraFormat.fileFormatLabel(it) },
        isEnabled = always,
    )

    val colorMode = CameraSettingSpec(
        key = "color",
        labelRes = R.string.remote_label_color,
        setting = CameraSetting.COLOR_MODE,
        options = { CameraOptions.colorMode },
        currentWire = { it?.colorMode },
        currentLabel = { CameraFormat.colorModeLabel(it) },
        isEnabled = always,
    )

    val metering = CameraSettingSpec(
        key = "metering",
        labelRes = R.string.remote_label_metering,
        setting = CameraSetting.METERING_MODE,
        options = { CameraOptions.metering },
        currentWire = { it?.meteringMode },
        currentLabel = { CameraFormat.meteringLabel(it) },
        isEnabled = always,
    )

    val focusMode = CameraSettingSpec(
        key = "focusMode",
        labelRes = R.string.remote_label_focus,
        setting = CameraSetting.FOCUS_MODE,
        options = { CameraOptions.focusMode },
        currentWire = { it?.focusMode },
        currentLabel = { CameraFormat.focusModeLabel(it) },
        isEnabled = always,
    )

    val whiteBalance = CameraSettingSpec(
        key = "wb",
        labelRes = R.string.remote_label_wb,
        setting = CameraSetting.WHITE_BALANCE,
        options = { CameraOptions.whiteBalance },
        currentWire = { it?.whiteBalance },
        currentLabel = { CameraFormat.wbLabel(it) },
        isEnabled = always,
    )

    val aperture = CameraSettingSpec(
        key = "aperture",
        labelRes = R.string.remote_label_aperture,
        setting = CameraSetting.FNUMBER,
        options = { CameraFormat.apertureOptions(it) },
        currentWire = { it?.fnumber },
        currentLabel = { CameraFormat.apertureLabel(it) },
        isEnabled = { status -> status != null && status.capabilities.canSetAperture },
    )

    val shutterSpeed = CameraSettingSpec(
        key = "shutter",
        labelRes = R.string.remote_label_shutter,
        setting = CameraSetting.SHUTTER_SPEED,
        options = { CameraOptions.shutterSpeed },
        currentWire = { it?.shutterSpeed },
        currentLabel = { CameraFormat.shutterLabel(it) },
        isEnabled = { status -> status != null && status.capabilities.canSetShutter },
    )

    val iso = CameraSettingSpec(
        key = "iso",
        labelRes = R.string.remote_label_iso,
        setting = CameraSetting.ISO,
        options = { CameraOptions.iso },
        currentWire = { it?.iso },
        currentLabel = { CameraFormat.isoLabel(it) },
        isEnabled = { status -> status != null && status.capabilities.canSetIso },
    )

    val exposureCompensation = CameraSettingSpec(
        key = "ev",
        labelRes = R.string.remote_label_ev,
        setting = CameraSetting.EV,
        options = { CameraOptions.ev },
        currentWire = { it?.ev },
        currentLabel = { CameraFormat.evLabel(it) },
        isEnabled = { status -> status != null && status.capabilities.canSetEv },
    )

    val delayShoot = CameraSettingSpec(
        key = "delay",
        labelRes = R.string.remote_label_delay,
        setting = CameraSetting.DELAY_SHOOT_COUNT,
        options = { CameraOptions.delayShootCount },
        currentWire = { it?.delayShootCount?.toString() },
        currentLabel = { CameraFormat.delayLabel(it) },
        isEnabled = always,
    )

    /** Quick chips above the shutter: the exposure readouts users tweak most. */
    val quickChips: List<CameraSettingSpec> = listOf(
        shutterSpeed,
        iso,
        aperture,
        exposureCompensation,
        whiteBalance,
        focusMode,
        driveMode,
    )

    /** Settings-sheet groups (order matches the camera's own menu). */
    val groups: List<SettingsGroup> = listOf(
        SettingsGroup(
            titleRes = R.string.settings_group_capture,
            specs = listOf(driveMode, imageQuality, imageAspect, fileFormat, colorMode),
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_exposure,
            specs = listOf(metering, focusMode, whiteBalance, aperture, shutterSpeed, iso, exposureCompensation),
        ),
        SettingsGroup(
            titleRes = R.string.settings_group_other,
            specs = listOf(delayShoot),
        ),
    )

    data class SettingsGroup(
        val titleRes: Int,
        val specs: List<CameraSettingSpec>,
    )

    /** Exposure-dial modes as pickable options for the shared option dialog. */
    fun modeOptions(): List<CameraOption> =
        ExposureMode.entries.map { CameraOption(it.wire, it.label) }
}

/**
 * 拍摄设置 bottom sheet: every writable camera setting with the current value,
 * disabled when the active exposure mode locks it. MF adjustment is a dedicated
 * −/+ row because it is a relative command (`RCMFAdjust`), not an enumeration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSettingsSheet(
    status: CameraStatus?,
    viewModel: CameraAppViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeSpec by remember { mutableStateOf<CameraSettingSpec?>(null) }
    var modePickerOpen by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CameraPanel,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.remote_settings_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cd_close),
                    )
                }
            }

            if (status == null) {
                Text(
                    text = stringResource(R.string.remote_settings_read_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    CameraSettingRow(
                        label = stringResource(R.string.remote_label_exposure_mode),
                        value = CameraFormat.exposureModeLabel(status) ?: stringResource(R.string.value_unknown),
                        enabled = status != null,
                        onClick = { modePickerOpen = true },
                    )
                }
                CameraSettingSpecs.groups.forEach { group ->
                    item(key = "header-${group.titleRes}") {
                        GroupHeader(title = stringResource(group.titleRes))
                    }
                    items(items = group.specs, key = { it.key }) { spec ->
                        CameraSettingRow(
                            label = stringResource(spec.labelRes),
                            value = spec.currentLabel(status) ?: stringResource(R.string.value_unknown),
                            enabled = spec.isEnabled(status),
                            onClick = { activeSpec = spec },
                        )
                    }
                }
                item(key = "header-mf") {
                    GroupHeader(title = stringResource(R.string.settings_group_focus))
                }
                item(key = "mf-row") {
                    ManualFocusRow(
                        status = status,
                        onAdjust = { step -> viewModel.adjustManualFocus(step) },
                    )
                }
                item(key = "bottom-space") { Spacer(Modifier.width(1.dp)) }
            }
        }
    }

    activeSpec?.let { spec ->
        OptionPickerDialog(
            title = stringResource(R.string.remote_picker_title, stringResource(spec.labelRes)),
            options = spec.options(status),
            selectedWire = spec.currentWire(status),
            onSelect = { option ->
                viewModel.applySetting(spec.setting, option.wire)
                activeSpec = null
            },
            onDismiss = { activeSpec = null },
        )
    }

    if (modePickerOpen) {
        OptionPickerDialog(
            title = stringResource(
                R.string.remote_picker_title,
                stringResource(R.string.remote_label_exposure_mode),
            ),
            options = CameraSettingSpecs.modeOptions(),
            selectedWire = status?.exposureMode?.wire,
            onSelect = { option ->
                viewModel.setExposureMode(ExposureMode.fromWire(option.wire))
                modePickerOpen = false
            },
            onDismiss = { modePickerOpen = false },
        )
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

/** 手动对焦: −/+ relative steps via `RCMFAdjust`, disabled while no status. */
@Composable
private fun ManualFocusRow(
    status: CameraStatus?,
    onAdjust: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.remote_label_mf),
                style = MaterialTheme.typography.bodyMedium,
            )
            val position = status?.mfPosition
            Text(
                text = if (position != null) {
                    stringResource(R.string.remote_label_mf) + " · " + position
                } else {
                    stringResource(R.string.remote_settings_hint)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        OutlinedButton(
            onClick = { onAdjust(-1) },
            enabled = status != null,
        ) {
            Text(stringResource(R.string.mf_minus_label))
        }
        OutlinedButton(
            onClick = { onAdjust(1) },
            enabled = status != null,
        ) {
            Text(stringResource(R.string.mf_plus_label))
        }
    }
}
