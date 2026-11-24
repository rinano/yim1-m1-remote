package com.yim1remote.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.ConnectionState
import com.yim1remote.app.data.LiveFrame
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.FocusVisualState
import com.yim1remote.app.ui.FocusUiState
import com.yim1remote.app.ui.FocusAreaMode
import com.yim1remote.app.ui.LiveViewUiState
import com.yim1remote.app.ui.components.OverlayChip
import com.yim1remote.app.ui.nav.HomeTab
import com.yim1remote.app.ui.theme.CameraBlack
import com.yim1remote.app.ui.theme.CameraFocusRing
import com.yim1remote.app.ui.theme.CameraGreen
import com.yim1remote.app.ui.theme.CameraRed
import com.yim1remote.app.ui.theme.CameraTextMuted
import com.yim1remote.app.ui.util.CameraFormat
import com.yim1remote.app.ui.vision.ExposureCheckDialog
import com.yim1remote.app.ui.vision.ExposureSimulationBadge
import com.yim1remote.app.ui.vision.LiveAssistEntryButton
import com.yim1remote.app.ui.vision.LiveAssistSettings
import com.yim1remote.app.ui.vision.LiveAssistSettingsDialog
import com.yim1remote.app.ui.vision.LiveHistogramOverlay
import com.yim1remote.app.ui.vision.LiveLevelOverlay
import com.yim1remote.app.ui.vision.drawAssistGrid
import com.yim1remote.app.ui.vision.drawAssistOverlay
import com.yim1remote.app.ui.vision.exposureSimulationGain
import com.yim1remote.app.ui.vision.rememberLiveAssistAnalysis
import com.yim1remote.app.ui.vision.rememberLiveAssistSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 遥控 tab — the main screen: live view, tap-to-focus, exposure readouts, the
 * shutter and the full camera-settings sheet.
 *
 * Rendering contract: the JPEG/Bitmap arrives on [CameraAppViewModel.liveFrame]
 * (decoded off the UI thread by the protocol layer) and is painted with
 * `ContentScale.Fit` inside the black viewfinder area; when frames stop arriving
 * the last frame is kept and the ViewModel's stall flag adds a warning overlay.
 */
@Composable
fun RemoteScreen(
    viewModel: CameraAppViewModel,
    onOpenGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.cameraStatus.collectAsState()
    val frame by viewModel.liveFrame.collectAsState()
    val liveView by viewModel.liveView.collectAsState()
    val capture by viewModel.capture.collectAsState()
    val lastCapture by viewModel.lastCapture.collectAsState()
    val focus by viewModel.focus.collectAsState()

    val connected = connection is ConnectionState.Connected
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var modePickerOpen by rememberSaveable { mutableStateOf(false) }
    var quickPickerSpec by remember { mutableStateOf<CameraSettingSpec?>(null) }
    var autoFocusPulse by remember { mutableIntStateOf(0) }

    // A2: client-side assist overlays (remembered per session, all off by default).
    val assist = rememberLiveAssistSettings()
    var assistSettingsOpen by rememberSaveable { mutableStateOf(false) }
    // A1: M-mode exposure check (example shot) dialog.
    var exposureCheckOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CameraBlack),
    ) {
        LiveViewArea(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            connected = connected,
            frame = frame,
            status = status,
            liveView = liveView,
            capturing = capture.capturing,
            autoFocusPulse = autoFocusPulse,
            focus = focus,
            assist = assist.value,
            onOpenAssistSettings = { assistSettingsOpen = true },
            onTapFocus = { nx, ny ->
                if (focus.areaMode == FocusAreaMode.AUTO) {
                    viewModel.autoFocus()
                } else {
                    viewModel.focusAt(nx, ny)
                }
            },
            onRetryLiveView = viewModel::retryLiveView,
            onGoConnect = { viewModel.selectTab(HomeTab.CONNECT) },
            onEditSpec = { quickPickerSpec = it },
            onEditMode = { modePickerOpen = true },
        )

        // A2/A4 action strip between the viewfinder and the shutter deck:
        // exposure check (test shot) + app-side interval/bracket control.
        LiveAssistActionRow(
            viewModel = viewModel,
            connected = connected,
            capturing = capture.capturing,
            onExposureCheck = { if (!exposureCheckOpen) exposureCheckOpen = true },
        )

        RemoteControlPanel(
            viewModel = viewModel,
            status = status,
            connected = connected,
            capturing = capture.capturing,
            lastCapture = lastCapture,
            onShutter = viewModel::capture,
            onCancelCapture = viewModel::cancelCapture,
            onAutoFocus = {
                autoFocusPulse += 1
                viewModel.autoFocus()
            },
            onOpenSettings = { settingsOpen = true },
            onOpenGallery = onOpenGallery,
            onSelectMode = viewModel::setExposureMode,
        )
    }

    if (settingsOpen) {
        RemoteSettingsSheet(
            status = status,
            viewModel = viewModel,
            onDismiss = { settingsOpen = false },
        )
    }

    if (modePickerOpen) {
        com.yim1remote.app.ui.components.OptionPickerDialog(
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

    quickPickerSpec?.let { spec ->
        com.yim1remote.app.ui.components.OptionPickerDialog(
            title = stringResource(R.string.remote_picker_title, stringResource(spec.labelRes)),
            options = spec.options(status),
            selectedWire = spec.currentWire(status),
            onSelect = { option ->
                viewModel.applySetting(spec.setting, option.wire)
                quickPickerSpec = null
            },
            onDismiss = { quickPickerSpec = null },
        )
    }

    if (assistSettingsOpen) {
        LiveAssistSettingsDialog(
            settings = assist.value,
            onSettingsChange = { assist.value = it },
            onDismiss = { assistSettingsOpen = false },
        )
    }

    if (exposureCheckOpen) {
        ExposureCheckDialog(
            viewModel = viewModel,
            onDismiss = { exposureCheckOpen = false },
        )
    }
}

// =====================================================================
// Live view area
// =====================================================================

@Composable
private fun LiveViewArea(
    modifier: Modifier,
    connected: Boolean,
    frame: LiveFrame?,
    status: CameraStatus?,
    liveView: LiveViewUiState,
    capturing: Boolean,
    autoFocusPulse: Int,
    focus: FocusUiState,
    assist: LiveAssistSettings,
    onOpenAssistSettings: () -> Unit,
    onTapFocus: (Float, Float) -> Unit,
    onRetryLiveView: () -> Unit,
    onGoConnect: () -> Unit,
    onEditSpec: (CameraSettingSpec) -> Unit,
    onEditMode: () -> Unit,
) {
    // Throttled background pixel analysis for peaking / zebra / histogram; cheap
    // helpers (grid, level, exposure simulation) are drawn directly.
    val assistFrame = rememberLiveAssistAnalysis(frame = frame, settings = assist)

    Box(modifier = modifier.background(Color.Black)) {
        when {
            !connected -> DisconnectedPlaceholder(onGoConnect = onGoConnect)
            frame == null -> WaitingForFramesPlaceholder(
                liveView = liveView,
                onRetry = onRetryLiveView,
            )

            else -> LiveFrameSurface(
                frame = frame,
                autoFocusPulse = autoFocusPulse,
                focus = focus,
                onTapFocus = onTapFocus,
                assistGrid = assist.grid,
                assistOverlay = assistFrame.overlay,
                exposureGain = if (assist.exposureSimulation) {
                    exposureSimulationGain(status?.ev)
                } else {
                    null
                },
            )
        }

        if (connected) {
            LiveStatusBar(
                status = status,
                capturing = capturing,
                modifier = Modifier.align(Alignment.TopStart),
            )
            // Unobtrusive 取景辅助 entry, top-right under the status bar.
            LiveAssistEntryButton(
                active = assist.anyEnabled,
                onClick = onOpenAssistSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 44.dp, end = 10.dp),
            )
        }

        if (connected && frame != null) {
            if (assist.exposureSimulation) {
                ExposureSimulationBadge(
                    evWire = status?.ev,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 44.dp),
                )
            }
            if (assist.level) {
                LiveLevelOverlay(modifier = Modifier.fillMaxSize())
            }
            if (assist.histogram) {
                // Bottom-right, above the exposure quick chips.
                LiveHistogramOverlay(
                    histogram = assistFrame.histogram,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 58.dp),
                )
            }
            QuickChipRow(
                status = status,
                modifier = Modifier.align(Alignment.BottomStart),
                onEditMode = onEditMode,
                onEditSpec = onEditSpec,
            )
            if (liveView.stalled) {
                StreamWarningOverlay(
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = onRetryLiveView,
                )
            }
        }
    }
}

/** The actual frame + tap-to-focus gesture + focus-ring animation. */
@Composable
private fun LiveFrameSurface(
    frame: LiveFrame,
    autoFocusPulse: Int,
    focus: FocusUiState,
    onTapFocus: (Float, Float) -> Unit,
    assistGrid: Boolean = false,
    assistOverlay: ImageBitmap? = null,
    exposureGain: Float? = null,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val imageRect: Rect? = remember(containerSize, frame.width, frame.height) {
        fitRect(
            containerWidth = containerSize.width.toFloat(),
            containerHeight = containerSize.height.toFloat(),
            frameWidth = frame.width.toFloat(),
            frameHeight = frame.height.toFloat(),
        )
    }
    // Keep the tap callback fresh without restarting the gesture detector on
    // every recomposition. The detector must NOT be keyed on `frame`: a new live
    // frame arrives ~30x/s and restarting pointerInput cancels an in-flight tap
    // (this made tap-to-focus appear completely unresponsive).
    val latestTapFocus by rememberUpdatedState(onTapFocus)
    var ringCenter by remember { mutableStateOf<Offset?>(null) }
    var ringPulse by remember { mutableIntStateOf(0) }
    val ringAlpha = remember { Animatable(0f) }
    val ringScale = remember { Animatable(1f) }

    LaunchedEffect(ringPulse) {
        if (ringPulse == 0) return@LaunchedEffect
        ringAlpha.snapTo(1f)
        ringScale.snapTo(1.45f)
        launch { ringScale.animateTo(1f, tween(durationMillis = 180)) }
        delay(1_200)
        ringAlpha.animateTo(0f, tween(durationMillis = 260))
        ringCenter = null
    }

    LaunchedEffect(autoFocusPulse) {
        if (autoFocusPulse == 0) return@LaunchedEffect
        val rect = imageRect ?: return@LaunchedEffect
        ringCenter = Offset(rect.center.x, rect.center.y)
        ringPulse += 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(imageRect) {
                detectTapGestures { offset ->
                    val rect = imageRect ?: return@detectTapGestures
                    if (rect.width <= 0f || rect.height <= 0f) return@detectTapGestures
                    val x = offset.x.coerceIn(rect.left, rect.right)
                    val y = offset.y.coerceIn(rect.top, rect.bottom)
                    ringCenter = Offset(x, y)
                    ringPulse += 1
                    latestTapFocus(
                        ((x - rect.left) / rect.width).coerceIn(0f, 1f),
                        ((y - rect.top) / rect.height).coerceIn(0f, 1f),
                    )
                }
            },
    ) {
        // Client-side exposure simulation: brightness gain only, never sent to the camera.
        val colorFilter = remember(exposureGain) {
            exposureGain?.let { gain ->
                ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(gain, gain, gain, 1f) })
            }
        }
        Image(
            bitmap = remember(frame.bitmap) { frame.bitmap.asImageBitmap() },
            contentDescription = stringResource(R.string.cd_live_view),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = colorFilter,
        )
        // A2 assist overlays clipped to the letterboxed image rectangle: grid plus
        // the throttled peaking/zebra bitmap from the background analyzer.
        if (assistGrid || assistOverlay != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rect = imageRect ?: return@Canvas
                if (assistGrid) drawAssistGrid(rect)
                if (assistOverlay != null) drawAssistOverlay(assistOverlay, rect)
            }
        }
        // Persistent result reticle: amber while focusing, green when the camera
        // reports the focus request finished, red on failure. It marks the exact
        // chosen focus point/area (auto-area uses the position the camera returned).
        val resultCenter = focus.point?.let { (nx, ny) ->
            imageRect?.let { rect ->
                Offset(
                    rect.left + nx * rect.width,
                    rect.top + ny * rect.height,
                )
            }
        }
        if (resultCenter != null && focus.state != FocusVisualState.IDLE) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val color = when (focus.state) {
                    FocusVisualState.FOCUSING -> CameraFocusRing
                    FocusVisualState.FOCUSED -> CameraGreen
                    FocusVisualState.FAILED -> Color(0xFFFF5252)
                    FocusVisualState.IDLE -> CameraFocusRing
                }
                val half = 22.dp.toPx()
                val arm = 10.dp.toPx()
                val stroke = 2.5.dp.toPx()
                val x = resultCenter.x
                val y = resultCenter.y
                // four corner brackets around the selected focus box
                drawLine(color, Offset(x - half, y - half), Offset(x - half + arm, y - half), stroke)
                drawLine(color, Offset(x - half, y - half), Offset(x - half, y - half + arm), stroke)
                drawLine(color, Offset(x + half, y - half), Offset(x + half - arm, y - half), stroke)
                drawLine(color, Offset(x + half, y - half), Offset(x + half, y - half + arm), stroke)
                drawLine(color, Offset(x - half, y + half), Offset(x - half + arm, y + half), stroke)
                drawLine(color, Offset(x - half, y + half), Offset(x - half, y + half - arm), stroke)
                drawLine(color, Offset(x + half, y + half), Offset(x + half - arm, y + half), stroke)
                drawLine(color, Offset(x + half, y + half), Offset(x + half, y + half - arm), stroke)
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(x, y))
            }
        }
        val center = ringCenter
        if (center != null && ringAlpha.value > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val alpha = ringAlpha.value
                val radius = 26.dp.toPx() * ringScale.value
                val color = CameraFocusRing.copy(alpha = alpha)
                drawCircle(color = color, radius = radius, center = center, style = Stroke(2.dp.toPx()))
                drawCircle(color = color, radius = 2.5.dp.toPx(), center = center)
                val gap = radius + 4.dp.toPx()
                val tick = 7.dp.toPx()
                drawLine(color, Offset(center.x - gap - tick, center.y), Offset(center.x - gap, center.y), 2.dp.toPx())
                drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + gap + tick, center.y), 2.dp.toPx())
                drawLine(color, Offset(center.x, center.y - gap - tick), Offset(center.x, center.y - gap), 2.dp.toPx())
                drawLine(color, Offset(center.x, center.y + gap), Offset(center.x, center.y + gap + tick), 2.dp.toPx())
            }
        }
    }
}

/** Top overlay: battery, remaining shots, lens warning, shooting indicator. */
@Composable
private fun LiveStatusBar(
    status: CameraStatus?,
    capturing: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.72f),
                    1f to Color.Transparent,
                ),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val batteryText = when {
            status?.isExternalPower == true -> stringResource(R.string.value_external_power)
            else -> CameraFormat.battery(status) ?: stringResource(R.string.value_unknown)
        }
        InfoPill(icon = Icons.Outlined.BatteryFull, text = batteryText)
        val shots = CameraFormat.remainingShots(status)
        InfoPill(
            icon = Icons.Outlined.PhotoLibrary,
            text = if (shots != null) {
                stringResource(R.string.remote_shots_left_fmt, shots)
            } else {
                stringResource(R.string.value_unknown)
            },
        )
        if (status != null && !status.lensReady) {
            Surface(
                shape = CircleShape,
                color = CameraRed.copy(alpha = 0.22f),
                contentColor = CameraRed,
            ) {
                Text(
                    text = stringResource(R.string.value_lens_warning),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (capturing || status?.shooting == true || status?.recording == true) {
            RecordingPill(
                text = stringResource(
                    if (status?.recording == true) R.string.remote_recording else R.string.remote_shooting,
                ),
            )
        }
    }
}

@Composable
private fun InfoPill(icon: ImageVector, text: String) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.5f),
        contentColor = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = Color.White.copy(alpha = 0.75f),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RecordingPill(text: String) {
    Surface(
        shape = CircleShape,
        color = CameraRed.copy(alpha = 0.85f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(Color.White, CircleShape),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Bottom overlay: tap-to-edit exposure readouts (each opens the option picker). */
@Composable
private fun QuickChipRow(
    status: CameraStatus?,
    modifier: Modifier = Modifier,
    onEditMode: () -> Unit,
    onEditSpec: (CameraSettingSpec) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.78f),
                ),
            )
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayChip(
            label = stringResource(R.string.remote_label_exposure_mode),
            value = CameraFormat.exposureModeLabel(status) ?: stringResource(R.string.value_unknown),
            enabled = status != null,
            onClick = onEditMode,
        )
        CameraSettingSpecs.quickChips.forEach { spec ->
            OverlayChip(
                label = stringResource(spec.labelRes),
                value = spec.currentLabel(status) ?: stringResource(R.string.value_unknown),
                enabled = spec.isEnabled(status),
                onClick = { onEditSpec(spec) },
            )
        }
    }
}

/** "画面已中断" warning over the (kept) last frame, with a retry action. */
@Composable
private fun StreamWarningOverlay(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Color.Black.copy(alpha = 0.82f),
        border = androidx.compose.foundation.BorderStroke(1.dp, CameraFocusRing.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = CameraFocusRing,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    text = stringResource(R.string.remote_live_stalled),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.remote_live_error_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.remote_live_retry))
            }
        }
    }
}

// =====================================================================
// Placeholders
// =====================================================================

@Composable
private fun WaitingForFramesPlaceholder(
    liveView: LiveViewUiState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            liveView.error != null -> {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = CameraFocusRing,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.remote_live_no_frames),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.remote_live_error_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = CameraTextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_live_retry))
                }
            }

            liveView.stalled -> {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = CameraFocusRing,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.remote_live_stalled),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.remote_live_error_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = CameraTextMuted,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_live_retry))
                }
            }

            else -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = CameraGreen,
                    strokeWidth = 2.5.dp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.remote_live_starting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CameraTextMuted,
                )
            }
        }
    }
}

@Composable
private fun DisconnectedPlaceholder(onGoConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CameraAlt,
            contentDescription = null,
            tint = CameraTextMuted,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.remote_not_connected),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_not_connected_hint),
            style = MaterialTheme.typography.bodySmall,
            color = CameraTextMuted,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onGoConnect) {
            Text(stringResource(R.string.action_go_connect))
        }
    }
}

// =====================================================================
// Helpers
// =====================================================================

/**
 * Visible rectangle of an image drawn with `ContentScale.Fit` inside a container,
 * used to map a tap to the camera's normalized focus coordinates.
 */
private fun fitRect(
    containerWidth: Float,
    containerHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
): Rect? {
    if (containerWidth <= 0f || containerHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) {
        return null
    }
    val scale = minOf(containerWidth / frameWidth, containerHeight / frameHeight)
    val width = frameWidth * scale
    val height = frameHeight * scale
    val left = (containerWidth - width) / 2f
    val top = (containerHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}

// =====================================================================
// Assist / interval action strip
// =====================================================================

/**
 * Slim action strip between the viewfinder and the shutter deck.
 *
 * Left: 曝光检查 (A1) — takes a real test shot and analyses its preview because the
 * M-mode live view does not reflect exposure. Right: the app-side interval /
 * exposure-bracket control (A4, owned by another module) which gets the remaining
 * width for its active progress strip. Kept above the control deck so it can never
 * cover the shutter button.
 */
@Composable
private fun LiveAssistActionRow(
    viewModel: CameraAppViewModel,
    connected: Boolean,
    capturing: Boolean,
    onExposureCheck: () -> Unit,
) {
    val intervalState by viewModel.interval.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CameraBlack)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onExposureCheck,
            enabled = connected && !capturing && !intervalState.active,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp),
        ) {
            Text(
                text = stringResource(R.string.exposure_check_action),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        val focusState by viewModel.focus.collectAsState()
        OutlinedButton(
            onClick = { viewModel.toggleFocusAreaMode() },
            enabled = connected,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(34.dp),
        ) {
            Text(
                text = stringResource(
                    if (focusState.areaMode == FocusAreaMode.POINT) {
                        R.string.focus_area_point
                    } else {
                        R.string.focus_area_auto
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        IntervalShootControl(
            viewModel = viewModel,
            modifier = Modifier.weight(1f),
        )
    }
}

