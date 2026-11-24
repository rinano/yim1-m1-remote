package com.yim1remote.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yim1remote.app.R
import com.yim1remote.app.data.model.CameraFileInfo
import com.yim1remote.app.data.model.DownloadResolution
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.components.ConfirmDeleteDialog
import com.yim1remote.app.ui.components.PreviewLoadingIndicator
import com.yim1remote.app.ui.theme.CameraBlack
import com.yim1remote.app.ui.theme.CameraTextMuted
import com.yim1remote.app.ui.util.CameraFormat

/** Lowest/highest pinch-zoom factor of the preview surface. */
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

/**
 * Full-screen viewer for one camera item.
 *
 * Preview loading is progressive and failure-tolerant: thumbnail first (fast),
 * then `MidThumb`, then the original JPEG for pictures only. RAW is served by
 * the camera as a JPEG rendition (`Thumbnail`/`MidThumb`) and never as its
 * 30 MB original, while videos have no preview rendition at all (the firmware
 * streams the whole MP4) — in those cases the viewer keeps a type placeholder
 * and download still works. The original is attempted for pictures only
 * because a 50MP decode can exceed the heap.
 *
 * The image area supports pinch-to-zoom and pan (double-tap resets), and below
 * it a panel shows the camera `GetFileInfo` metadata once that second HTTP
 * round-trip completes; when the request fails the listing date/type rows stay
 * as fallback.
 */
@Composable
fun GalleryViewerDialog(
    viewModel: CameraAppViewModel,
    item: GalleryItem,
    onDismiss: () -> Unit,
) {
    var preview by remember(item.path) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(item.path) { mutableStateOf(true) }
    var fileInfo by remember(item.path) { mutableStateOf<CameraFileInfo?>(null) }
    var fileInfoLoading by remember(item.path) { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // Explicit image height + a weighted scrollable metadata panel: the previous
    // weight-based image greedily took space, so the panel overflowed the screen
    // and both metadata rows and buttons were clipped on tall phones.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // Progressive preview: THUMBNAIL, then MID_THUMB for every file type (RAW
    // included — the camera answers with a small JPEG), then ORIGINAL only for
    // non-RAW/non-video pictures. The spinner stays until the first bitmap.
    LaunchedEffect(item.path) {
        loading = true
        val thumb = viewModel.loadViewerPreview(item, DownloadResolution.THUMBNAIL)
        if (thumb != null) {
            preview = thumb
            loading = false
        }
        val mid = viewModel.loadViewerPreview(item, DownloadResolution.MID_THUMB)
        if (mid != null) {
            preview = mid
            loading = false
        }
        if (!item.isVideo && !item.isRaw) {
            val original = viewModel.loadViewerPreview(item, DownloadResolution.ORIGINAL)
            if (original != null) preview = original
        }
        loading = false
    }

    // EXIF/metadata panel: `GetFileInfo` is a separate HTTP round-trip, loaded
    // in parallel with the preview; failures keep the listing fallback rows.
    LaunchedEffect(item.path) {
        fileInfoLoading = true
        fileInfo = viewModel.loadFileInfo(item)
        fileInfoLoading = false
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
            // Top bar: close, filename, download/delete.
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
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = CameraFormat.fullDate(item.capturedAtEpochMillis)
                            ?: stringResource(R.string.value_unknown_time),
                        style = MaterialTheme.typography.labelSmall,
                        color = CameraTextMuted,
                    )
                }
                IconButton(onClick = { viewModel.downloadItem(item) }) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.cd_download),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !item.isProtected,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.cd_delete),
                        tint = if (item.isProtected) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // Preview surface: fills the remaining screen, letterboxed at scale
            // 1 and pinch-zoomable in place.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((screenHeight * 0.46f).coerceAtLeast(220.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = preview
                val image = remember(bitmap) { bitmap?.asImageBitmap() }
                when {
                    image != null -> ZoomableImage(image)

                    loading -> PreviewLoadingIndicator()

                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = when {
                                item.isVideo -> Icons.Outlined.PlayArrow
                                item.isRaw -> Icons.Outlined.PhotoLibrary
                                else -> Icons.Outlined.PhotoLibrary
                            },
                            contentDescription = null,
                            tint = CameraTextMuted,
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = when {
                                item.isVideo -> stringResource(R.string.viewer_video_hint)
                                item.isRaw -> stringResource(R.string.viewer_raw_hint)
                                else -> stringResource(R.string.viewer_preview_unavailable)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = CameraTextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            }

            // Bottom panel: compact metadata grid + always-visible action bar.
            // The previous version put the buttons inside a scrolling column, so on
            // tall screens the action row was clipped by the bottom edge.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (item.isProtected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.viewer_protected_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ViewerMetadataPanel(
                    item = item,
                    fileInfo = fileInfo,
                    loading = fileInfoLoading,
                )

            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            message = stringResource(R.string.delete_confirm_single, item.fileName),
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteItems(listOf(item))
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Camera `GetFileInfo` panel under the preview.
 *
 * Only fields the firmware actually reported are shown; the date and type rows
 * always exist because the listing carries a usable fallback. While the request
 * is in flight a small loading line is shown instead of an error state.
 */
@Composable
private fun ViewerMetadataPanel(
    item: GalleryItem,
    fileInfo: CameraFileInfo?,
    loading: Boolean,
) {
    val info = fileInfo
    // Two-column, one-line-per-field layout: keeps the whole panel above the fixed
    // action bar so nothing is clipped (previously 12 full-width rows + buttons
    // exceeded the screen height).
    val entries = mutableListOf<Pair<String, String>>()
    entries += stringResource(R.string.viewer_label_date) to (
        info?.captureDateSeconds
            ?.let { CameraFormat.cameraDateFromMillis(it * 1000L) }
            ?: CameraFormat.fullDate(item.capturedAtEpochMillis)
            ?: stringResource(R.string.value_unknown_time)
        )
    entries += stringResource(R.string.viewer_label_type) to
        stringResource(viewerTypeLabelRes(info?.type, item))
    if (info != null && !info.isEmpty) {
        CameraFormat.fileSize(info.sizeBytes)?.let {
            entries += stringResource(R.string.viewer_label_size) to it
        }
        info.dimensions?.let {
            entries += stringResource(R.string.viewer_label_dimensions) to it
        }
        info.aperture.nonBlank()?.let { aperture ->
            entries += stringResource(R.string.viewer_label_aperture) to
                if (aperture.startsWith("f/", ignoreCase = true)) aperture else "f/$aperture"
        }
        info.shutterSpeed.nonBlank()?.let {
            entries += stringResource(R.string.viewer_label_shutter) to it
        }
        info.iso.nonBlank()?.let {
            entries += stringResource(R.string.viewer_label_iso) to it
        }
        info.focusMode.nonBlank()?.let {
            entries += stringResource(R.string.viewer_label_focus_mode) to it
        }
        info.whiteBalance.nonBlank()?.let {
            entries += stringResource(R.string.viewer_label_wb) to it
        }
        info.focalLengthMm.nonBlank()?.let { focal ->
            entries += stringResource(R.string.viewer_label_focal) to
                if (focal.endsWith("mm", ignoreCase = true)) focal else "${focal}mm"
        }
        info.make.nonBlank()?.let {
            entries += stringResource(R.string.viewer_label_make) to it
        }
        info.model.nonBlank()?.let {
            entries += stringResource(R.string.viewer_label_model) to it
        }
        CameraFormat.duration(info.durationSeconds)?.let {
            entries += stringResource(R.string.viewer_label_duration) to it
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = stringResource(R.string.viewer_exif_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (loading) {
            Text(
                text = stringResource(R.string.viewer_exif_loading),
                style = MaterialTheme.typography.labelSmall,
                color = CameraTextMuted,
            )
        }
        entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MetadataCell(row[0], Modifier.weight(1f))
                if (row.size > 1) {
                    MetadataCell(row[1], Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            text = stringResource(R.string.viewer_camera_clock_note),
            style = MaterialTheme.typography.labelSmall,
            color = CameraTextMuted,
        )
    }
}

/** One compact label/value cell of the two-column metadata grid. */
@Composable
private fun MetadataCell(entry: Pair<String, String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.first,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = entry.second,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** Localized type row: camera `GetFileInfo.type` first, listing type as fallback. */
private fun viewerTypeLabelRes(infoType: String?, item: GalleryItem): Int = when {
    infoType.equals("PICRAW", ignoreCase = true) -> R.string.viewer_type_raw
    infoType.equals("PICNORMAL", ignoreCase = true) -> R.string.viewer_type_photo
    infoType.equals("MOVIE", ignoreCase = true) -> R.string.viewer_type_video
    CameraFormat.fileTypeKey(item) == "video" -> R.string.viewer_type_video
    CameraFormat.fileTypeKey(item) == "raw" -> R.string.viewer_type_raw
    else -> R.string.viewer_type_photo
}

/** `null` for blank camera strings so the row is omitted instead of showing "". */
private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Full-size preview with pinch-to-zoom/pan. Scale 1 is the plain `Fit` image;
 * double-tap resets zoom and pan.
 */
@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember(image) { mutableStateOf(MIN_ZOOM) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = newScale
        offset = if (newScale <= MIN_ZOOM) {
            Offset.Zero
        } else {
            clampPan(offset + panChange, newScale, containerSize)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { containerSize = it }
            .pointerInput(image) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = MIN_ZOOM
                        offset = Offset.Zero
                    },
                )
            }
            .transformable(
                state = transformState,
                lockRotationOnZoomPan = true,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/** Keeps the zoomed image covering its surface: no panning past the edges. */
private fun clampPan(offset: Offset, scale: Float, size: IntSize): Offset {
    if (scale <= MIN_ZOOM || size.width <= 0 || size.height <= 0) return Offset.Zero
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
