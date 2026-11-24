package com.yim1remote.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.yim1remote.app.R

/**
 * The three top-level destinations, shown in the bottom navigation bar.
 *
 * Order is the user journey: join the camera hotspot ([CONNECT]), shoot
 * ([REMOTE]), then pull the results off the card ([GALLERY]).
 */
enum class HomeTab(
    val labelRes: Int,
    val icon: ImageVector,
) {
    CONNECT(R.string.tab_connect, Icons.Outlined.Wifi),
    REMOTE(R.string.tab_remote, Icons.Outlined.CameraAlt),
    GALLERY(R.string.tab_gallery, Icons.Outlined.PhotoLibrary),
    LOCAL(R.string.tab_local, Icons.Outlined.Folder),
}
