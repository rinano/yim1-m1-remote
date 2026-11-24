package com.yim1remote.app.ui

import android.content.Context
import com.yim1remote.app.data.CameraRepository
import com.yim1remote.app.data.CameraRepositoryImpl

/**
 * Single construction point of the camera data layer for the UI.
 *
 * The concrete [CameraRepository] implementation lives in `data/` (owned by the
 * protocol stream); this object keeps transport details out of the ViewModel.
 * The protocol team's factory is the only supported entry point:
 * `CameraRepositoryImpl.create(context)`.
 */
object CameraRepositoryProvider {

    fun create(context: Context): CameraRepository = CameraRepositoryImpl.create(context)

    /** Releases the repository's IO scope/coroutines when the ViewModel is cleared. */
    fun close(repository: CameraRepository) {
        (repository as? CameraRepositoryImpl)?.close()
    }
}
