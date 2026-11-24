package com.yim1remote.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.ConnectionState
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.components.CameraThumbnail
import com.yim1remote.app.ui.components.EmptyState
import com.yim1remote.app.ui.components.ErrorCard
import com.yim1remote.app.ui.nav.HomeTab
import com.yim1remote.app.ui.theme.CameraBlack
import com.yim1remote.app.ui.theme.CameraTextMuted
import com.yim1remote.app.ui.util.CameraFormat

/**
 * 相册 tab — browse the camera SD card as a thumbnail grid, load more pages
 * while scrolling, multi-select for batch download/delete, and open the
 * full-screen viewer.
 *
 * Thumbnails are decoded by the protocol layer and cached in the ViewModel's L1
 * LRU, so scrolling back over a page does not re-download previews. The grid
 * loads pages of 60 until the camera returns a short page (end of album).
 */
@Composable
fun GalleryScreen(
    viewModel: CameraAppViewModel,
    modifier: Modifier = Modifier,
) {
    val connection by viewModel.connectionState.collectAsState()
    val gallery by viewModel.gallery.collectAsState()
    val connected = connection is ConnectionState.Connected

    var viewerItem by remember { mutableStateOf<GalleryItem?>(null) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { viewModel.ensureGalleryLoaded() }

    GalleryLoadMoreEffect(
        gridState = gridState,
        itemCount = gallery.items.size,
        onLoadMore = viewModel::loadMoreGallery,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraBlack),
    ) {
        when {
            !connected -> EmptyState(
                icon = Icons.Outlined.Wifi,
                title = stringResource(R.string.gallery_not_connected),
                body = stringResource(R.string.gallery_not_connected_hint),
                actionLabel = stringResource(R.string.action_go_connect),
                onAction = { viewModel.selectTab(HomeTab.CONNECT) },
                modifier = Modifier.align(Alignment.Center),
            )

            gallery.error != null && gallery.items.isEmpty() -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ErrorCard(
                    title = stringResource(R.string.gallery_error),
                    body = gallery.error,
                    onRetry = { viewModel.refreshGallery() },
                )
            }

            gallery.refreshing && gallery.items.isEmpty() -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
            )

            gallery.loadedOnce && gallery.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.gallery_empty),
                body = stringResource(R.string.gallery_empty_hint),
                actionLabel = stringResource(R.string.action_refresh),
                onAction = { viewModel.refreshGallery() },
                modifier = Modifier.align(Alignment.Center),
            )

            else -> GalleryGrid(
                viewModel = viewModel,
                items = gallery.items,
                gridState = gridState,
                selectionMode = gallery.selectionMode,
                selectedPaths = gallery.selectedPaths,
                refreshing = gallery.refreshing,
                loadingMore = gallery.loadingMore,
                endReached = gallery.endReached,
                onOpenItem = { viewerItem = it },
                onToggleSelection = viewModel::toggleSelection,
                onEnterSelection = viewModel::enterSelection,
            )
        }

        if (gallery.selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(R.string.gallery_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }

    viewerItem?.let { item ->
        GalleryViewerDialog(
            viewModel = viewModel,
            item = item,
            onDismiss = { viewerItem = null },
        )
    }
}

/** Loads the next page when the user is within ~8 cells of the end of the grid. */
@Composable
private fun GalleryLoadMoreEffect(
    gridState: LazyGridState,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    val nearEnd by remember {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            itemCount > 0 && lastVisible >= layout.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, itemCount) {
        if (nearEnd) onLoadMore()
    }
}

/** Thumbnail grid with the camera's filtered file types; multi-select aware. */
@Composable
private fun GalleryGrid(
    viewModel: CameraAppViewModel,
    items: List<GalleryItem>,
    gridState: LazyGridState,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    refreshing: Boolean,
    loadingMore: Boolean,
    endReached: Boolean,
    onOpenItem: (GalleryItem) -> Unit,
    onToggleSelection: (GalleryItem) -> Unit,
    onEnterSelection: (GalleryItem) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items = items, key = { it.path }) { item ->
                GalleryCell(
                    viewModel = viewModel,
                    item = item,
                    selectionMode = selectionMode,
                    selected = item.path in selectedPaths,
                    onClick = {
                        if (selectionMode) onToggleSelection(item) else onOpenItem(item)
                    },
                    onLongClick = {
                        if (selectionMode) onToggleSelection(item) else onEnterSelection(item)
                    },
                )
            }

            if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.gallery_loading_more),
                            style = MaterialTheme.typography.labelSmall,
                            color = CameraTextMuted,
                        )
                    }
                }
            } else if (endReached && items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.gallery_end_reached),
                        style = MaterialTheme.typography.labelSmall,
                        color = CameraTextMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (refreshing) {
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

/** One 4:5 grid cell: thumbnail, type/protection badges, date, selection check. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    viewModel: CameraAppViewModel,
    item: GalleryItem,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .aspectRatio(4f / 5f)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CameraThumbnail(
            viewModel = viewModel,
            item = item,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            fallbackIcon = when {
                item.isVideo -> Icons.Outlined.PlayArrow
                item.isRaw -> Icons.Outlined.PhotoLibrary
                else -> Icons.Outlined.PhotoLibrary
            },
        )

        // Bottom gradient keeps the date legible over bright photos.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(30.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )

        if (item.isRaw) {
            TypeBadge(
                text = stringResource(R.string.gallery_badge_raw),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
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
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        CameraFormat.shortDate(item.capturedAtEpochMillis)?.let { date ->
            Text(
                text = date,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, bottom = 4.dp, end = 22.dp),
            )
        }

        if (item.isProtected) {
            BadgeSurface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = stringResource(R.string.gallery_badge_protected),
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        if (selectionMode) {
            SelectionCheck(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp),
            )
        }
    }
}

@Composable
private fun TypeBadge(text: String, modifier: Modifier = Modifier) {
    BadgeSurface(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
private fun BadgeSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Box(modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) {
            content()
        }
    }
}

@Composable
private fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
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
