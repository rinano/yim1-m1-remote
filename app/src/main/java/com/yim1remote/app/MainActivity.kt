package com.yim1remote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.Yim1RemoteApp
import com.yim1remote.app.ui.theme.Yim1RemoteTheme

/**
 * Single activity host; all screens are Compose destinations inside
 * [Yim1RemoteApp].
 *
 * The app talks to the camera over the camera's own WiFi hotspot, so there is no
 * deep-link/`ACTION_VIEW` surface to handle and no second activity. This class
 * only owns the process-level lifecycle signal that stops the live-view UDP
 * receiver while the app is backgrounded (the camera keeps streaming otherwise,
 * and keeping the socket open drains both batteries).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CameraAppViewModel by viewModels()

    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            viewModel.onAppForegroundChanged(true)
        }

        override fun onStop(owner: LifecycleOwner) {
            viewModel.onAppForegroundChanged(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // targetSdk 35 enforces edge-to-edge on Android 15; opting in explicitly
        // keeps behavior consistent on older releases. Insets are consumed by the
        // Material3 Scaffold/TopAppBar/NavigationBar in Yim1RemoteApp.
        enableEdgeToEdge()
        lifecycle.addObserver(foregroundObserver)

        setContent {
            Yim1RemoteTheme {
                Yim1RemoteApp(viewModel)
            }
        }
    }
}
