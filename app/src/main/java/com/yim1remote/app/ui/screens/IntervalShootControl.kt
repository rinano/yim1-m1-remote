package com.yim1remote.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.IntervalShootConfig
import com.yim1remote.app.ui.IntervalShootMode
import com.yim1remote.app.ui.IntervalShootUiState
import com.yim1remote.app.ui.theme.CameraGreen
import com.yim1remote.app.ui.theme.CameraPanel
import com.yim1remote.app.ui.theme.CameraTextMuted
import kotlin.math.roundToInt

/**
 * "间隔拍摄 / 曝光包围" (interval shooting / EV bracketing) entry + configuration
 * dialog for the 遥控 tab.
 *
 * The camera's own shot-count/interval command is rejected by 3.1 firmware
 * (`RCDShootCntSet` answers HTTP 404, hardware-verified), so the sequence is
 * driven entirely by [CameraAppViewModel]: this composable only collects
 * [CameraAppViewModel.interval], shows the config dialog and the inline
 * progress/result line, and calls `startIntervalShoot` / `stopIntervalShoot`.
 *
 * Compact by design: idle it is one outlined button, active it expands into a
 * thin status strip with the progress and a 停止 action. The caller decides the
 * width via [modifier] (e.g. `Modifier.fillMaxWidth()` inside the control deck).
 */
@Composable
fun IntervalShootControl(
    viewModel: CameraAppViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.interval.collectAsState()

    var showDialog by rememberSaveable { mutableStateOf(false) }
    // Last chosen configuration: kept outside the dialog so re-opening it (after
    // a finished/stopped run) starts from the same values.
    var modeIndex by rememberSaveable { mutableStateOf(0) }
    var shots by rememberSaveable { mutableStateOf(3) }
    var intervalSeconds by rememberSaveable { mutableStateOf(3) }
    var evStepTenths by rememberSaveable { mutableStateOf(10) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (state.active) {
            IntervalProgressStrip(
                state = state,
                onStop = viewModel::stopIntervalShoot,
            )
        } else {
            OutlinedButton(
                onClick = { showDialog = true },
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.interval_action),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IntervalResultLine(
                state = state,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }

    if (showDialog) {
        IntervalShootDialog(
            active = state.active,
            modeIndex = modeIndex,
            shots = shots,
            intervalSeconds = intervalSeconds,
            evStepTenths = evStepTenths,
            onModeChange = { modeIndex = it },
            onShotsChange = { shots = it },
            onIntervalChange = { intervalSeconds = it },
            onEvStepChange = { evStepTenths = it },
            onStart = { config ->
                viewModel.startIntervalShoot(config)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/** Live progress while a sequence is running: shots done, countdown, 停止. */
@Composable
private fun IntervalProgressStrip(
    state: IntervalShootUiState,
    onStop: () -> Unit,
) {
    val total = state.total.coerceAtLeast(state.shot).coerceAtLeast(1)
    val displayShot = state.shot.coerceIn(1, total)
    val progress = stringResource(R.string.interval_progress_fmt, displayShot, total)
    val waiting = state.waitingSeconds
    val progressLine = if (waiting > 0) {
        progress + " · " + stringResource(R.string.interval_waiting_fmt, waiting)
    } else {
        progress
    }
    val headline = if (state.mode == IntervalShootMode.EV_BRACKET) {
        stringResource(R.string.interval_active_label) +
            " · " +
            stringResource(R.string.interval_mode_bracket) +
            " " +
            evStepLabel(state.evStepTenths)
    } else {
        stringResource(R.string.interval_active_label)
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CameraPanel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = progressLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onStop,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.interval_stop),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** One muted line after a run: 完成 N 张 / 已停止 / 失败：…; hidden initially. */
@Composable
private fun IntervalResultLine(
    state: IntervalShootUiState,
    modifier: Modifier = Modifier,
) {
    val error = state.error
    val stopped = error == STOPPED_MESSAGE
    val text = when {
        stopped -> stringResource(R.string.interval_stopped)
        error != null -> stringResource(R.string.interval_failed_fmt, error)
        state.finished -> stringResource(R.string.interval_done_fmt, state.shot)
        else -> null
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                stopped -> CameraTextMuted
                error != null -> MaterialTheme.colorScheme.error
                else -> CameraGreen
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}

/**
 * Configuration dialog. The 开始 button is disabled while a sequence is already
 * active so a second run cannot be queued from a stale dialog instance.
 */
@Composable
private fun IntervalShootDialog(
    active: Boolean,
    modeIndex: Int,
    shots: Int,
    intervalSeconds: Int,
    evStepTenths: Int,
    onModeChange: (Int) -> Unit,
    onShotsChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onEvStepChange: (Int) -> Unit,
    onStart: (IntervalShootConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val editingEnabled = !active
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.interval_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Mode: 间隔拍摄 / 曝光包围（EV）.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = modeIndex == 0,
                        onClick = { onModeChange(0) },
                        enabled = editingEnabled,
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = stringResource(R.string.interval_mode_interval),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                    FilterChip(
                        selected = modeIndex == 1,
                        onClick = { onModeChange(1) },
                        enabled = editingEnabled,
                        modifier = Modifier.weight(1f),
                        label = {
                            Text(
                                text = stringResource(R.string.interval_mode_bracket),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }

                // 张数 2–30, 间隔 1–60 s (both modes wait between shots).
                IntervalSliderRow(
                    label = stringResource(R.string.interval_shots),
                    value = shots,
                    range = 2..30,
                    enabled = editingEnabled,
                    onValueChange = onShotsChange,
                )
                IntervalSliderRow(
                    label = stringResource(R.string.interval_seconds),
                    value = intervalSeconds,
                    range = 1..60,
                    enabled = editingEnabled,
                    onValueChange = onIntervalChange,
                )

                // BKT only: 0.3 / 0.7 / 1.0 EV around the current exposure bias.
                if (modeIndex == 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.interval_ev_step),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EvStepChip(
                                label = "0.3EV",
                                selected = evStepTenths == 3,
                                enabled = editingEnabled,
                                onClick = { onEvStepChange(3) },
                                modifier = Modifier.weight(1f),
                            )
                            EvStepChip(
                                label = "0.7EV",
                                selected = evStepTenths == 7,
                                enabled = editingEnabled,
                                onClick = { onEvStepChange(7) },
                                modifier = Modifier.weight(1f),
                            )
                            EvStepChip(
                                label = "1.0EV",
                                selected = evStepTenths == 10,
                                enabled = editingEnabled,
                                onClick = { onEvStepChange(10) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.interval_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraTextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStart(
                        IntervalShootConfig(
                            mode = if (modeIndex == 1) {
                                IntervalShootMode.EV_BRACKET
                            } else {
                                IntervalShootMode.INTERVAL
                            },
                            shots = shots,
                            intervalSeconds = intervalSeconds,
                            evStepTenths = evStepTenths,
                        ),
                    )
                },
                enabled = editingEnabled,
            ) {
                Text(stringResource(R.string.interval_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Label + current value + discrete slider (integer values only). */
@Composable
private fun IntervalSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // Every integer in the range is a stop; endpoints are excluded from
            // `steps`, hence `count - 2` intermediate positions.
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            enabled = enabled,
        )
    }
}

/** One 0.3 / 0.7 / 1.0 EV choice chip for the bracket step. */
@Composable
private fun EvStepChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
        },
    )
}

/** "0.3EV" / "0.7EV" / "1.0EV" readout for the running bracket. */
private fun evStepLabel(tenths: Int): String = when (tenths) {
    3 -> "0.3EV"
    7 -> "0.7EV"
    else -> "1.0EV"
}

/** Cancellation marker that [CameraAppViewModel.runIntervalShoot] reports on stop. */
private const val STOPPED_MESSAGE = "已停止"
