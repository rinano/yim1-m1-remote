package com.yim1remote.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.ui.components.ConfirmDeleteDialog
import com.yim1remote.app.ui.components.EmptyState
import com.yim1remote.app.ui.components.ErrorCard
import com.yim1remote.app.ui.media.LocalMediaError
import com.yim1remote.app.ui.media.LocalMediaItem
import com.yim1remote.app.ui.media.LocalMediaResult
import com.yim1remote.app.ui.media.LocalMediaStore
import com.yim1remote.app.ui.media.LocalMediaViewer
import com.yim1remote.app.ui.theme.CameraBlack
import com.yim1remote.app.ui.theme.CameraTextMuted
import kotlinx.coroutines.launch

/**
 * 本机 tab — browse the photos/videos already downloaded to the phone.
 *
 * The data source is [LocalMediaStore] (MediaStore owner access on Android 10+,
 * app-private directory on Android 8/9). The screen shows a 3-column thumbnail
 * grid, long-press multi-select with batch share/delete, and opens the
 * full-screen [LocalMediaViewer] for zoom, share, rotate-edit and delete.
 *
 * The tab has no ViewModel on purpose: the list is re-read whenever the tab is
 * entered or the refresh button is pressed, and all MediaStore I/O happens in
 * the suspend functions of the store.
 */
@Composable
fun LocalAlbumScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<LocalMediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<LocalMediaError?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewerItem by remember { mutableStateOf<LocalMediaItem?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    fun reload() {
        refreshTick++
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(item: LocalMediaItem) {
        val next = selectedIds.toMutableSet()
        if (!next.add(item.id)) next.remove(item.id)
        selectedIds = next
        if (next.isEmpty()) selectionMode = false
    }

    fun shareItems(targets: List<LocalMediaItem>) {
        val intent = LocalMediaStore.shareIntent(context, targets)
        val launched = intent != null &&
            LocalMediaStore.launchIntent(context, Intent.createChooser(intent, null))
        if (!launched) {
            Toast.makeText(context, R.string.local_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteItems(targets: List<LocalMediaItem>) {
        scope.launch {
            var deleted = 0
            for (target in targets) {
                if (LocalMediaStore.delete(context, target)) deleted++
            }
            exitSelection()
            reload()
            Toast.makeText(
                context,
                if (deleted > 0) R.string.delete_done else R.string.delete_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // System back leaves multi-select before the app-level handler switches tabs.
    BackHandler(enabled = selectionMode) { exitSelection() }

    LaunchedEffect(refreshTick) {
        loading = true
        when (val result = LocalMediaStore.load(context)) {
            is LocalMediaResult.Success -> {
                items = result.items
                error = null
            }

            is LocalMediaResult.Failure -> {
                items = emptyList()
                error = result.error
            }
        }
        loading = false
        loadedOnce = true
    }

    val loadError = error
    val selectedItems = items.filter { it.id in selectedIds }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraBlack),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LocalAlbumHeader(
                count = items.size,
                selectionMode = selectionMode,
                selectedCount = selectedItems.size,
                refreshing = loading && loadedOnce,
                onRefresh = ::reload,
                onShare = { shareItems(selectedItems) },
                onDelete = { showBatchDeleteConfirm = true },
                onExitSelection = ::exitSelection,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when {
                    loading && !loadedOnce -> LocalLoadingState(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    loadError != null -> LocalErrorState(
                        error = loadError,
                        onRetry = ::reload,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    items.isEmpty() -> EmptyState(
                        icon = Icons.Outlined.Folder,
                        title = stringResource(R.string.local_empty),
                        body = stringResource(R.string.local_empty_hint),
                        actionLabel = stringResource(R.string.action_refresh),
                        onAction = ::reload,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LocalMediaGrid(
                        items = items,
                        version = refreshTick,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onOpen = { viewerItem = it },
                        onToggle = ::toggleSelection,
                        onEnterSelection = { item ->
                            selectionMode = true
                            selectedIds = setOf(item.id)
                        },
                    )
                }

                if (loading && loadedOnce) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }

    viewerItem?.let { item ->
        LocalMediaViewer(
            item = item,
            onDismiss = { viewerItem = null },
            onDeleted = {
                viewerItem = null
                reload()
            },
            onEdited = { reload() },
        )
    }

    if (showBatchDeleteConfirm) {
        ConfirmDeleteDialog(
            message = stringResource(R.string.local_delete_confirm_fmt, selectedItems.size),
            onConfirm = {
                showBatchDeleteConfirm = false
                deleteItems(selectedItems)
            },
            onDismiss = { showBatchDeleteConfirm = false },
        )
    }
}

/** Title / count header, plus the refresh or multi-select action set. */
@Composable
private fun LocalAlbumHeader(
    count: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onExitSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (selectionMode) {
                    stringResource(R.string.local_selected_fmt, selectedCount)
                } else {
                    stringResource(R.string.local_title)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!selectionMode) {
                Text(
                    text = stringResource(R.string.local_count_fmt, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = CameraTextMuted,
                )
            }
        }
        if (selectionMode) {
            TextButton(
                onClick = onShare,
                enabled = selectedCount > 0,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(stringResource(R.string.local_share))
            }
            TextButton(
                onClick = onDelete,
                enabled = selectedCount > 0,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.local_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onExitSelection) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_exit_selection),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    tint = if (refreshing) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp,
        )
        Text(
            text = stringResource(R.string.local_loading),
            style = MaterialTheme.typography.bodySmall,
            color = CameraTextMuted,
        )
    }
}

@Composable
private fun LocalErrorState(
    error: LocalMediaError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ErrorCard(
            title = stringResource(R.string.local_error),
            body = if (error == LocalMediaError.PermissionDenied) {
                stringResource(R.string.local_permission_hint)
            } else {
                null
            },
            onRetry = onRetry,
        )
    }
}

/** 3-column thumbnail grid; multi-select aware. */
@Composable
private fun LocalMediaGrid(
    items: List<LocalMediaItem>,
    version: Int,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onOpen: (LocalMediaItem) -> Unit,
    onToggle: (LocalMediaItem) -> Unit,
    onEnterSelection: (LocalMediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items = items, key = { it.id }) { item ->
            LocalMediaCell(
                item = item,
                version = version,
                selectionMode = selectionMode,
                selected = item.id in selectedIds,
                onClick = {
                    if (selectionMode) onToggle(item) else onOpen(item)
                },
                onLongClick = {
                    if (selectionMode) onToggle(item) else onEnterSelection(item)
                },
            )
        }
    }
}

/** One 1:1 cell: lazy thumbnail, video/RAW badges, date label, selection check. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalMediaCell(
    item: LocalMediaItem,
    version: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(
        initialValue = LocalMediaStore.cachedThumbnail(item),
        item.id,
        version,
    ) {
        value = LocalMediaStore.loadThumbnail(context, item)
    }
    val image = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (item.isVideo) Icons.Outlined.PlayArrow else Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
            )
        }

        // Bottom gradient keeps the date legible over bright photos.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.6f),
                    ),
                ),
        )
        LocalMediaStore.formatShortDate(item.dateAddedMillis)?.let { date ->
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 4.dp, end = 20.dp),
            )
        }

        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (item.isRaw) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = stringResource(R.string.gallery_badge_raw),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        if (selectionMode) {
            LocalSelectionCheck(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp),
            )
        }
    }
}

@Composable
private fun LocalSelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
