package com.yim1remote.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yim1remote.app.BuildConfig
import com.yim1remote.app.R
import com.yim1remote.app.data.CameraConfig
import com.yim1remote.app.data.ConnectionState
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.ui.components.AboutDialog
import com.yim1remote.app.ui.components.AddressEditDialog
import com.yim1remote.app.ui.components.ConfirmDeleteDialog
import com.yim1remote.app.ui.components.ConnectionPill
import com.yim1remote.app.ui.components.DownloadProgressDialog
import com.yim1remote.app.ui.nav.HomeTab
import com.yim1remote.app.ui.screens.LocalAlbumScreen
import com.yim1remote.app.ui.screens.ConnectScreen
import com.yim1remote.app.ui.screens.GalleryScreen
import com.yim1remote.app.ui.screens.RemoteScreen
import com.yim1remote.app.ui.theme.CameraBlack
import com.yim1remote.app.ui.theme.CameraGreen
import com.yim1remote.app.ui.theme.CameraPanel
import com.yim1remote.app.ui.theme.CameraRed
import com.yim1remote.app.ui.theme.CameraTextMuted

/**
 * Root composable: persistent top bar, bottom navigation and one of the three
 * screens (连接 / 遥控 / 相册). Also hosts the app-level dialogs (about, camera
 * address, download progress, batch-delete confirm) and maps ViewModel events to
 * snackbars.
 *
 * No navigation-compose dependency on purpose (not in the offline Gradle cache):
 * with three flat, always-alive destinations plain state is sufficient, and it
 * lets the 遥控 tab own the live-view lifecycle directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Yim1RemoteApp(viewModel: CameraAppViewModel) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val config by viewModel.config.collectAsState()
    val gallery by viewModel.gallery.collectAsState()
    val downloads by viewModel.downloads.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showAddressDialog by rememberSaveable { mutableStateOf(false) }
    var showBatchDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    val connected = connection is ConnectionState.Connected
    val selectedItems: List<GalleryItem> = gallery.items.filter { it.path in gallery.selectedPaths }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val text = event.toSnackbarText(context) ?: return@collect
            snackbarHostState.showSnackbar(text)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Back: leave multi-select, then leave the current tab. On the root tab the
    // handler is disabled so the system back gesture still exits the app.
    BackHandler(
        enabled = gallery.selectionMode || selectedTab != HomeTab.CONNECT,
    ) {
        if (gallery.selectionMode) {
            viewModel.exitSelection()
        } else {
            viewModel.selectTab(HomeTab.CONNECT)
        }
    }

    Scaffold(
        containerColor = CameraBlack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectedTab == HomeTab.GALLERY && gallery.selectionMode) {
                SelectionTopBar(
                    selectedCount = selectedItems.size,
                    allSelected = gallery.items.isNotEmpty() &&
                        gallery.selectedPaths.size == gallery.items.size,
                    onExitSelection = viewModel::exitSelection,
                    onSelectAll = viewModel::selectAll,
                    onClearSelection = viewModel::clearSelection,
                    onDownload = { viewModel.downloadSelection() },
                    onDelete = { showBatchDeleteConfirm = true },
                )
            } else {
                AppTopBar(
                    connection = connection,
                    showRefresh = selectedTab == HomeTab.GALLERY,
                    refreshEnabled = connected,
                    onRefresh = { viewModel.refreshGallery() },
                    onAbout = { showAbout = true },
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = CameraPanel,
                tonalElevation = 0.dp,
            ) {
                HomeTab.entries.forEach { tab ->
                    val label = stringResource(tab.labelRes)
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(imageVector = tab.icon, contentDescription = label) },
                        label = { Text(label) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = CameraTextMuted,
                            unselectedTextColor = CameraTextMuted,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                HomeTab.CONNECT -> ConnectScreen(viewModel = viewModel)
                HomeTab.REMOTE -> RemoteScreen(
                    viewModel = viewModel,
                    onOpenGallery = { viewModel.selectTab(HomeTab.GALLERY) },
                )

                HomeTab.GALLERY -> GalleryScreen(viewModel = viewModel)
                HomeTab.LOCAL -> LocalAlbumScreen()
            }
        }
    }

    if (downloads.active) {
        DownloadProgressDialog(
            state = downloads,
            onCancel = { viewModel.cancelDownload() },
        )
    }

    if (showAbout) {
        AboutDialog(
            versionName = BuildConfig.VERSION_NAME,
            baseUrl = config.baseUrl,
            onEditAddress = {
                showAbout = false
                showAddressDialog = true
            },
            onDismiss = { showAbout = false },
        )
    }

    if (showAddressDialog) {
        AddressEditDialog(
            currentBaseUrl = config.baseUrl,
            onDismiss = { showAddressDialog = false },
            onSave = { raw ->
                viewModel.updateBaseUrl(raw)
                showAddressDialog = false
            },
            onReset = { viewModel.resetBaseUrlToDefault() },
        )
    }

    if (showBatchDeleteConfirm) {
        ConfirmDeleteDialog(
            message = stringResource(R.string.delete_confirm_batch, selectedItems.size),
            note = stringResource(R.string.delete_confirm_protected_note),
            onConfirm = {
                showBatchDeleteConfirm = false
                viewModel.deleteItems(selectedItems)
            },
            onDismiss = { showBatchDeleteConfirm = false },
        )
    }
}

/** Default top bar: app name, link state pill and the 关于/设置 entry point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    connection: ConnectionState,
    showRefresh: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                ConnectionPill(state = connection)
            }
        },
        actions = {
            if (showRefresh) {
                IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        tint = if (refreshEnabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    )
                }
            }
            IconButton(onClick = onAbout) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.cd_about_settings),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = CameraPanel,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Replaces the top bar while the gallery is in multi-select mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExitSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(text = stringResource(R.string.gallery_selection_count_fmt, selectedCount))
        },
        navigationIcon = {
            IconButton(onClick = onExitSelection) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_exit_selection),
                )
            }
        },
        actions = {
            IconButton(
                onClick = if (allSelected) onClearSelection else onSelectAll,
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Outlined.ClearAll else Icons.Outlined.SelectAll,
                    contentDescription = stringResource(
                        if (allSelected) R.string.action_clear_selection else R.string.action_select_all,
                    ),
                )
            }
            IconButton(onClick = onDownload, enabled = selectedCount > 0) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(R.string.action_download),
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = if (selectedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = CameraPanel,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// =====================================================================
// UiEvent -> snackbar text
// =====================================================================

/**
 * Maps a ViewModel event to localized snackbar copy. Repository-provided error
 * messages are appended in parentheses when present (they carry camera error
 * codes such as `1506` that are useful for bug reports).
 */
private fun UiEvent.toSnackbarText(context: Context): String? = when (this) {
    UiEvent.Connected -> context.getString(R.string.snackbar_connected)
    UiEvent.Disconnected -> context.getString(R.string.snackbar_disconnected)
    UiEvent.AddressSaved -> context.getString(R.string.snackbar_address_saved)
    UiEvent.CaptureSucceeded -> context.getString(R.string.snackbar_capture_done)
    is UiEvent.CaptureFailed -> context.getString(R.string.snackbar_capture_failed).withDetail(message)
    is UiEvent.SettingFailed -> context.getString(R.string.snackbar_setting_failed).withDetail(message)
    is UiEvent.FocusFailed -> context.getString(R.string.snackbar_focus_failed).withDetail(message)
    is UiEvent.GalleryRefreshFailed -> context.getString(R.string.snackbar_gallery_failed).withDetail(message)
    is UiEvent.DownloadSaved -> context.getString(R.string.download_saved_fmt, name)
    is UiEvent.DownloadFinished -> when {
        saved == 0 -> context.getString(R.string.download_failed)
        failed > 0 -> context.getString(R.string.download_partial_fmt, saved, failed)
        saved == 1 -> context.getString(R.string.download_saved_count_fmt, saved)
        else -> context.getString(R.string.download_saved_count_fmt, saved)
    }

    UiEvent.DownloadCancelled -> context.getString(R.string.download_cancelled)
    is UiEvent.DeleteFinished -> buildString {
        if (deleted > 0) append(context.getString(R.string.delete_done))
        if (skippedProtected > 0) {
            if (isNotEmpty()) append(context.getString(R.string.snackbar_separator))
            append(context.getString(R.string.gallery_delete_protected_skip, skippedProtected))
        }
        if (isEmpty()) {
            append(context.getString(R.string.gallery_delete_protected_skip, skippedProtected))
        }
    }

    is UiEvent.DeleteFailed -> context.getString(R.string.delete_failed).withDetail(message)

    is UiEvent.BleWifiReady -> context.getString(R.string.snackbar_ble_wifi_ready_fmt, ssid)
    UiEvent.BleCopied -> context.getString(R.string.snackbar_ble_copied)
    UiEvent.CameraTimeSynced -> context.getString(R.string.snackbar_camera_time_synced)
    is UiEvent.CameraTimeSyncFailed ->
        context.getString(R.string.snackbar_camera_time_failed).withDetail(message)
}

/** Appends "（原因）" only when the repository supplied a message. */
private fun String.withDetail(detail: String?): String =
    if (detail.isNullOrBlank()) this else "$this（$detail）"
