package com.yim1remote.app.ui.media

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yim1remote.app.R
import com.yim1remote.app.ui.components.ConfirmDeleteDialog
import com.yim1remote.app.ui.components.PreviewLoadingIndicator
import com.yim1remote.app.ui.theme.CameraBlack
import com.yim1remote.app.ui.theme.CameraTextMuted
import kotlinx.coroutines.launch

/**
 * Full-screen viewer for one downloaded (local) photo/video.
 *
 * Photos: pinch-zoom/pan via Compose `transformable` with double-tap reset, a
 * close button, and share / delete / rotate actions. Rotation re-encodes the
 * bitmap and writes it back into the same MediaStore row (or the app-private
 * file on Android 8/9), then the album is told to reload.
 *
 * Videos: no player dependency. The file is handed to the system through
 * `ACTION_VIEW`, with a play placeholder (and video frame, when one can be
 * decoded) shown in the viewer.
 */
@Composable
fun LocalMediaViewer(
    item: LocalMediaItem,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    onEdited: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var preview by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(item.id) { mutableStateOf(true) }
    var previewVersion by remember(item.id) { mutableStateOf(0) }
    var exifDate by remember(item.id) { mutableStateOf<String?>(null) }
    var dimensions by remember(item.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var sizeBytes by remember(item.id) { mutableStateOf(item.sizeBytes) }
    var saving by remember(item.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Explicit image height + weighted, scrollable info panel so nothing clips.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    LaunchedEffect(item.id, previewVersion) {
        loading = true
        preview = LocalMediaStore.loadPreview(context, item)
        loading = false
    }
    LaunchedEffect(item.id) {
        exifDate = LocalMediaStore.exifDateTaken(context, item)
        dimensions = LocalMediaStore.imageDimensions(context, item)
    }

    fun share() {
        val intent = LocalMediaStore.shareIntent(context, listOf(item)) ?: run {
            Toast.makeText(context, R.string.local_share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!LocalMediaStore.launchIntent(context, Intent.createChooser(intent, null))) {
            Toast.makeText(context, R.string.local_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun openWithSystem() {
        if (!LocalMediaStore.launchIntent(context, LocalMediaStore.viewIntent(item))) {
            Toast.makeText(context, R.string.local_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun rotate(clockwise: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val edited = LocalMediaStore.rotateAndSave(context, item, clockwise)
            if (edited) {
                LocalMediaStore.currentSize(context, item)?.let { sizeBytes = it }
                exifDate = LocalMediaStore.exifDateTaken(context, item)
                dimensions = LocalMediaStore.imageDimensions(context, item)
                previewVersion++
                onEdited()
                Toast.makeText(context, R.string.local_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.local_error, Toast.LENGTH_SHORT).show()
            }
            saving = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CameraBlack),
        ) {
            // Top bar: close, filename, share/delete.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (item.isVideo) {
                            stringResource(R.string.viewer_type_video)
                        } else {
                            stringResource(R.string.viewer_type_photo)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = CameraTextMuted,
                    )
                }
                IconButton(onClick = ::share) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.local_share),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.local_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // Preview surface: fills the remaining height, black background.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((screenHeight * 0.55f).coerceAtLeast(220.dp))
                    .background(Color.Black)
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val loadedPreview = preview
                when {
                    item.isVideo -> VideoPreview(
                        frame = loadedPreview,
                        loading = loading && loadedPreview == null,
                        onOpen = ::openWithSystem,
                    )

                    loadedPreview != null -> ZoomableImage(
                        bitmap = loadedPreview,
                        contentDescription = item.displayName,
                    )

                    loading -> PreviewLoadingIndicator()

                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = CameraTextMuted,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = "无法读取图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = CameraTextMuted,
                        )
                    }
                }

                if (saving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp,
                        )
                    }
                }
            }

            // Info + actions.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!item.isVideo) {
                    Text(
                        text = stringResource(R.string.local_image_info),
                        style = MaterialTheme.typography.labelMedium,
                        color = CameraTextMuted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                if (item.isVideo) {
                    Text(
                        text = stringResource(R.string.local_video_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = CameraTextMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = ::openWithSystem,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cd_play))
                    }
                } else {
                    if (!item.canEdit) {
                        Text(
                            text = "当前文件格式不支持旋转编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = CameraTextMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { rotate(clockwise = false) },
                            enabled = item.canEdit && !saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.RotateLeft,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.local_rotate_left))
                        }
                        OutlinedButton(
                            onClick = { rotate(clockwise = true) },
                            enabled = item.canEdit && !saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.RotateRight,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.local_rotate_right))
                        }
                    }
                }

                // Filename is already shown in the viewer title bar; keeping a
                // duplicate row pushed the last metadata row past the screen edge.
                InfoLine(
                    label = stringResource(R.string.viewer_label_date),
                    value = exifDate
                        ?: LocalMediaStore.formatLocalDate(item.dateAddedMillis)
                        ?: stringResource(R.string.value_unknown_time),
                )
                // One compact row instead of three: the bottom info panel must fit
                // above the navigation inset (previously the last row was clipped).
                val metaParts = mutableListOf<String>()
                LocalMediaStore.formatBytes(sizeBytes)?.let { metaParts.add(it) }
                metaParts.add(
                    stringResource(
                        if (item.isVideo) R.string.viewer_type_video else R.string.viewer_type_photo,
                    ),
                )
                dimensions?.let { (width, height) -> metaParts.add("$width x $height") }
                InfoLine(
                    label = "信息",
                    value = metaParts.joinToString(" · "),
                )

                Spacer(Modifier.height(6.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            message = stringResource(R.string.local_delete_single_fmt, item.displayName),
            onConfirm = {
                showDeleteConfirm = false
                scope.launch {
                    if (LocalMediaStore.delete(context, item)) {
                        Toast.makeText(context, R.string.delete_done, Toast.LENGTH_SHORT).show()
                        onDeleted()
                    } else {
                        Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Pinch-zoom/pan image. Double-tap resets the transform; panning only engages
 * while zoomed in.
 */
@Composable
private fun ZoomableImage(bitmap: Bitmap, contentDescription: String?) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
        scale = nextScale
        offset = if (nextScale > 1.001f) offset + panChange else Offset.Zero
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transformableState)
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                },
        )
    }
}

/** Video placeholder: decoded frame (when available), dimmed, with a play button. */
@Composable
private fun VideoPreview(frame: Bitmap?, loading: Boolean, onOpen: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (frame != null) {
            val image = remember(frame) { frame.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.5f },
            )
        } else if (loading) {
            PreviewLoadingIndicator()
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                text = stringResource(R.string.local_video_hint),
                style = MaterialTheme.typography.bodySmall,
                color = CameraTextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}
