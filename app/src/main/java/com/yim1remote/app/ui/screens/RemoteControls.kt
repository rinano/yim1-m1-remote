package com.yim1remote.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.components.CameraThumbnail
import com.yim1remote.app.ui.theme.CameraHairline
import com.yim1remote.app.ui.theme.CameraPanel
import com.yim1remote.app.ui.theme.CameraTextMuted

/**
 * The bottom control deck of the 遥控 tab: exposure-dial chips, the big shutter,
 * auto-focus, the gallery shortcut and the entry point to the full settings
 * sheet. Kept separate from [RemoteScreen] so the live-view composable stays
 * focused on rendering/focus gestures.
 */
@Composable
fun RemoteControlPanel(
    viewModel: CameraAppViewModel,
    status: CameraStatus?,
    connected: Boolean,
    capturing: Boolean,
    lastCapture: GalleryItem?,
    onShutter: () -> Unit,
    onCancelCapture: () -> Unit,
    onAutoFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onSelectMode: (ExposureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CameraPanel,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Exposure dial + entry to the full settings sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExposureMode.entries.forEach { mode ->
                        ExposureModeChip(
                            label = mode.label,
                            selected = status?.exposureMode == mode,
                            enabled = connected,
                            onClick = { onSelectMode(mode) },
                        )
                    }
                }
                IconButton(onClick = onOpenSettings, enabled = connected) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = stringResource(R.string.cd_camera_settings),
                        tint = if (connected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                }
            }

            // Gallery shortcut — shutter — auto focus.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryShortcutButton(
                    viewModel = viewModel,
                    item = lastCapture,
                    enabled = connected,
                    onClick = onOpenGallery,
                )
                ShutterButton(
                    capturing = capturing,
                    enabled = connected,
                    onClick = onShutter,
                )
                RoundControlButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CenterFocusStrong,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    label = stringResource(R.string.remote_action_auto_focus),
                    contentDescription = stringResource(R.string.cd_auto_focus),
                    enabled = connected && !capturing,
                    onClick = onAutoFocus,
                )
            }

            if (capturing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.remote_capture_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancelCapture) {
                        Text(stringResource(R.string.remote_capture_cancel))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.remote_live_tap_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraTextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Square-ish dial-position chip (自动 / P / A / S / M / C). */
@Composable
private fun ExposureModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = if (selected) null else BorderStroke(1.dp, CameraHairline),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** Large circular shutter: amber ring + filled centre, spinner while capturing. */
@Composable
private fun ShutterButton(
    capturing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.cd_shutter)
    val ringColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    }
    val fillColor = if (enabled) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(
                enabled = enabled && !capturing,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringStroke = 3.dp.toPx()
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - ringStroke / 2f - 2.dp.toPx(),
                style = Stroke(width = ringStroke),
            )
            drawCircle(
                color = fillColor,
                radius = size.minDimension / 2f - 12.dp.toPx(),
            )
        }
        if (capturing) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Circular secondary control with a label below (auto focus / gallery). */
@Composable
private fun RoundControlButton(
    icon: @Composable () -> Unit,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            border = BorderStroke(1.dp, CameraHairline),
            modifier = Modifier.size(54.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            maxLines = 1,
        )
    }
}

/** Gallery tab shortcut showing the newest captured item's thumbnail when known. */
@Composable
private fun GalleryShortcutButton(
    viewModel: CameraAppViewModel,
    item: GalleryItem?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.cd_gallery)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, CameraHairline),
            modifier = Modifier.size(54.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (item != null) {
                    CameraThumbnail(
                        viewModel = viewModel,
                        item = item,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        fallbackIcon = if (item.isVideo) Icons.Outlined.PlayArrow else Icons.Outlined.PhotoLibrary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tab_gallery),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
