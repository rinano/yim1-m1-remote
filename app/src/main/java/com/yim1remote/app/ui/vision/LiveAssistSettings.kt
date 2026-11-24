package com.yim1remote.app.ui.vision

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.ui.theme.CameraTextMuted

/**
 * Which live-view assist overlays are enabled.
 *
 * All helpers are **client-side only** — the YI M1's M-mode live view does not
 * simulate exposure (verified on hardware), so the phone draws focus peaking,
 * zebra, grid, level, histogram and an exposure simulation on top of the received
 * JPEG without touching camera state.
 */
data class LiveAssistSettings(
    val focusPeaking: Boolean = false,
    val zebra: Boolean = false,
    val grid: Boolean = false,
    val level: Boolean = false,
    val histogram: Boolean = false,
    val exposureSimulation: Boolean = false,
) {
    val anyEnabled: Boolean
        get() = focusPeaking || zebra || grid || level || histogram || exposureSimulation

    /** True when the per-frame pixel analysis pass is needed. */
    val wantsPixelAnalysis: Boolean
        get() = focusPeaking || zebra || histogram

    companion object {
        /** Saves the six switches across rotation without a new dependency. */
        val Saver = listSaver<LiveAssistSettings, Boolean>(
            save = {
                listOf(
                    it.focusPeaking,
                    it.zebra,
                    it.grid,
                    it.level,
                    it.histogram,
                    it.exposureSimulation,
                )
            },
            restore = {
                LiveAssistSettings(
                    focusPeaking = it[0],
                    zebra = it[1],
                    grid = it[2],
                    level = it[3],
                    histogram = it[4],
                    exposureSimulation = it[5],
                )
            },
        )
    }
}

/** Session-remembered assist settings (defaults all off). */
@Composable
fun rememberLiveAssistSettings(): MutableState<LiveAssistSettings> =
    rememberSaveable(stateSaver = LiveAssistSettings.Saver) {
        mutableStateOf(LiveAssistSettings())
    }

/** 取景辅助 settings dialog (all switches, plus the exposure-simulation disclaimer). */
@Composable
fun LiveAssistSettingsDialog(
    settings: LiveAssistSettings,
    onSettingsChange: (LiveAssistSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assist_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AssistSwitchRow(
                    label = stringResource(R.string.assist_peaking),
                    checked = settings.focusPeaking,
                ) { onSettingsChange(settings.copy(focusPeaking = it)) }
                AssistSwitchRow(
                    label = stringResource(R.string.assist_zebra),
                    checked = settings.zebra,
                ) { onSettingsChange(settings.copy(zebra = it)) }
                AssistSwitchRow(
                    label = stringResource(R.string.assist_grid),
                    checked = settings.grid,
                ) { onSettingsChange(settings.copy(grid = it)) }
                AssistSwitchRow(
                    label = stringResource(R.string.assist_level),
                    checked = settings.level,
                ) { onSettingsChange(settings.copy(level = it)) }
                AssistSwitchRow(
                    label = stringResource(R.string.assist_histogram),
                    checked = settings.histogram,
                ) { onSettingsChange(settings.copy(histogram = it)) }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                AssistSwitchRow(
                    label = stringResource(R.string.assist_exposure_sim),
                    checked = settings.exposureSimulation,
                ) { onSettingsChange(settings.copy(exposureSimulation = it)) }
                Text(
                    text = stringResource(R.string.assist_exposure_sim_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraTextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.assist_close))
            }
        },
    )
}

@Composable
private fun AssistSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
