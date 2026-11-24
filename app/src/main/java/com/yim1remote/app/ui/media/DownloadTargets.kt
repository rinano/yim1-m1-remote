package com.yim1remote.app.ui.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yim1remote.app.R
import com.yim1remote.app.data.model.GalleryItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * One destination stream for a camera download, created before the bytes start
 * flowing and finalised after the last byte.
 *
 * On Android 10+ this is a pending [MediaStore] row under
 * `Pictures/YI M1 Remote` (published by [commit], deleted by [abort]); on
 * Android 8/9 — where the manifest intentionally has no
 * `WRITE_EXTERNAL_STORAGE` — it is a plain file in the app-specific pictures
 * directory, which needs no permission at all.
 *
 * The UI owns this helper (not `data/`) because it is pure Android storage
 * plumbing around [com.yim1remote.app.data.CameraRepository.download].
 */
class DownloadTarget internal constructor(
    val displayName: String,
    val sink: OutputStream,
    /** Absolute-ish description of where the file lands, for the result snackbar. */
    val locationLabel: String,
    private val onCommit: () -> Unit,
    private val onAbort: () -> Unit,
) {
    private var finished = false

    /** Publishes a fully written file (MediaStore pending flag cleared). */
    fun commit() {
        if (finished) return
        finished = true
        runCatching { sink.flush() }
        runCatching { sink.close() }
        runCatching { onCommit() }
    }

    /** Drops a partially written file (MediaStore row deleted, temp file removed). */
    fun abort() {
        if (finished) return
        finished = true
        runCatching { sink.close() }
        runCatching { onAbort() }
    }
}

/**
 * Opens download destinations for camera gallery items. All methods are safe to
 * call from a background dispatcher; callers own the returned [DownloadTarget].
 */
object DownloadTargets {

    /** Relative MediaStore path used for every download (photos and videos alike). */
    const val RELATIVE_DIR = "Pictures/YI M1 Remote"

    /** Android 8/9 fallback directory below the app-specific external files dir. */
    private const val LEGACY_SUBDIR = "YI M1 Remote"

    /** True when downloads land in the app-specific directory instead of MediaStore. */
    val usesAppPrivateStorage: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun open(context: Context, item: GalleryItem): DownloadTarget {
        val displayName = sanitizeFileName(item.fileName)
        val mime = mimeTypeOf(displayName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openMediaStore(context, item, displayName, mime)
        } else {
            openLegacyFile(context, displayName)
        }
    }

    /** MIME type used to file the download; the camera's own extension decides. */
    fun mimeTypeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "dng" -> "image/x-adobe-dng"
        "raw" -> "image/x-adobe-dng"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }

    /** Strips path separators/control characters so a camera filename cannot escape its folder. */
    fun sanitizeFileName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (character in name) {
                when {
                    character == '/' || character == '\\' -> append('_')
                    character.code < 0x20 -> Unit
                    else -> append(character)
                }
            }
        }.trim().trimStart('.')
        return cleaned.ifBlank { "YI_capture.jpg" }
    }

    // ------------------------------------------------------------------ Android 10+

    private fun openMediaStore(
        context: Context,
        item: GalleryItem,
        displayName: String,
        mime: String,
    ): DownloadTarget {
        val resolver = context.contentResolver
        val isVideo = item.isVideo || mime.startsWith("video/")
        val collection: Uri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uniqueName = uniqueMediaStoreName(context, collection, displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException(context.getString(R.string.error_create_gallery_entry))
        val stream = try {
            resolver.openOutputStream(uri)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        } ?: run {
            resolver.delete(uri, null, null)
            throw IOException(context.getString(R.string.error_open_gallery_stream))
        }
        return DownloadTarget(
            displayName = uniqueName,
            sink = stream,
            locationLabel = RELATIVE_DIR,
            onCommit = {
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, publish, null, null)
            },
            onAbort = { resolver.delete(uri, null, null) },
        )
    }

    /**
     * Adds the conventional ` (1)`, ` (2)` suffix before inserting, so a second
     * download of the same camera file never silently overwrites the first.
     * MediaStore would normally dedupe too, but doing it explicitly keeps the
     * displayed result name deterministic.
     */
    private fun uniqueMediaStoreName(context: Context, collection: Uri, fileName: String): String {
        val (base, extension) = splitName(fileName)
        var candidate = fileName
        var suffix = 1
        while (suffix <= 99 && mediaStoreNameExists(context, collection, candidate)) {
            candidate = if (extension.isEmpty()) "$base ($suffix)" else "$base ($suffix).$extension"
            suffix++
        }
        return candidate
    }

    private fun mediaStoreNameExists(context: Context, collection: Uri, displayName: String): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? " +
            "AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        return try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                arrayOf(displayName, "$RELATIVE_DIR/"),
                null,
            )?.use { cursor -> cursor.count > 0 } ?: false
        } catch (t: Throwable) {
            false
        }
    }

    // -------------------------------------------------------------------- Android 8/9

    private fun openLegacyFile(context: Context, displayName: String): DownloadTarget {
        val picturesRoot: File = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "pictures")
        val directory = File(picturesRoot, LEGACY_SUBDIR)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException(context.getString(R.string.error_create_picture_dir))
        }
        val file = uniqueFile(directory, displayName)
        val stream = FileOutputStream(file)
        return DownloadTarget(
            displayName = file.name,
            sink = stream,
            locationLabel = file.parent ?: LEGACY_SUBDIR,
            onCommit = { },
            onAbort = { file.delete() },
        )
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val (base, extension) = splitName(fileName)
        var candidate = File(directory, fileName)
        var suffix = 1
        while (candidate.exists() && suffix <= 99) {
            val name = if (extension.isEmpty()) "$base ($suffix)" else "$base ($suffix).$extension"
            candidate = File(directory, name)
            suffix++
        }
        return candidate
    }

    // ------------------------------------------------------------------------ utils

    private fun splitName(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) {
            fileName to ""
        } else {
            fileName.substring(0, dot) to fileName.substring(dot + 1)
        }
    }
}
