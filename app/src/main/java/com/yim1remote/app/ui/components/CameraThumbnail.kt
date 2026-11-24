package com.yim1remote.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.ui.CameraAppViewModel

/**
 * Camera thumbnail for one gallery item with the ViewModel's in-memory LRU
 * cache behind it.
 *
 * The first composition paints whatever is already cached synchronously (no
 * flicker when scrolling back), then loads the thumbnail off the main thread.
 * Decoded bitmaps are cached in the ViewModel so a 60-cell page never issues the
 * same `GetFile Thumbnail` twice.
 */
@Composable
fun CameraThumbnail(
    viewModel: CameraAppViewModel,
    item: GalleryItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /** Icon shown when the camera has no preview rendition (RAW/video often have none). */
    fallbackIcon: ImageVector = Icons.Outlined.Image,
) {
    val bitmapState = produceState<Bitmap?>(
        initialValue = viewModel.cachedThumbnail(item),
        key1 = item.path,
        key2 = item.dateWire,
    ) {
        if (value == null) {
            value = viewModel.loadThumbnail(item)
        }
    }
    val bitmap = bitmapState.value
    val image: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/** Small amber spinner used inside full-screen preview surfaces. */
@Composable
fun PreviewLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
    }
}
