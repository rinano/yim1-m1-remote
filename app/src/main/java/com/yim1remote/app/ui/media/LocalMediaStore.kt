package com.yim1remote.app.ui.media

import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One photo/video that already lives on this phone because it was downloaded
 * from the camera by [DownloadTargets].
 *
 * `filePath` is non-null only on the Android 8/9 fallback, where downloads are
 * plain files in the app-specific external pictures directory; on Android 10+
 * the item is addressed through its MediaStore [uri] (owner access, no READ
 * permission needed). [sizeBytes] / [dateAddedMillis] are best-effort values
 * read from MediaStore and are re-read after an in-place edit.
 */
data class LocalMediaItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val dateAddedMillis: Long,
    val filePath: String? = null,
) {
    /** Camera RAW files are listed but cannot be decoded/rotated by BitmapFactory. */
    val isRaw: Boolean
        get() = mimeType == "image/x-adobe-dng"

    /** Rotation editing is only offered for bitmap formats we can re-encode losslessly-ish. */
    val canEdit: Boolean
        get() = !isVideo && (mimeType == "image/jpeg" || mimeType == "image/png")
}

/** Why the local album could not be read; the UI maps these to strings.xml copy. */
enum class LocalMediaError {
    /** MediaStore threw SecurityException (no ownership / read access to the folder). */
    PermissionDenied,

    /** Anything else: provider unavailable, I/O error, … */
    ReadFailed,
}

sealed interface LocalMediaResult {
    data class Success(val items: List<LocalMediaItem>) : LocalMediaResult
    data class Failure(val error: LocalMediaError) : LocalMediaResult
}

/**
 * Local (already downloaded) media source for the 本机 tab.
 *
 * Android 10+ reads the MediaStore rows this app owns under
 * `Pictures/YI M1 Remote` — ownership means no READ permission is required and
 * `openInputStream` / `openOutputStream` / `delete` work on the row directly.
 * Android 8/9 scans the app-specific external pictures directory the legacy
 * [DownloadTargets] fallback writes to; those files need no permission at all.
 *
 * All I/O runs on [Dispatchers.IO]. Decoded thumbnails are cached in a
 * heap-bounded [LruCache] so scrolling the grid never re-decodes a visible cell.
 */
object LocalMediaStore {

    /** MediaStore folder every download lands in; mirrors [DownloadTargets]. */
    val relativeDir: String
        get() = DownloadTargets.RELATIVE_DIR

    /** Android 8/9 fallback directory below the app-specific external files dir. */
    private const val LEGACY_SUBDIR = "YI M1 Remote"

    private const val THUMBNAIL_MAX_PX = 512
    private const val PREVIEW_MAX_PX = 2560
    private const val JPEG_QUALITY = 95

    /**
     * Bounded bitmap cache (sized in KiB, about 1/8 of the heap capped at 48 MiB).
     * Entries are never recycled explicitly: a bitmap can still be drawn by a
     * cell that is being disposed, the GC handles the bytes after eviction.
     */
    private val thumbnailCache = object : LruCache<String, Bitmap>(thumbnailCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun thumbnailCacheSizeKb(): Int {
        val heapKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
        return (heapKb / 8).coerceIn(4 * 1024, 48 * 1024)
    }

    // ------------------------------------------------------------------ listing

    /** Reads every downloaded photo/video, newest first. */
    suspend fun load(context: Context): LocalMediaResult = withContext(Dispatchers.IO) {
        try {
            val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryMediaStore(context)
            } else {
                scanLegacyFiles(context)
            }
            LocalMediaResult.Success(items.sortedByDescending { it.dateAddedMillis })
        } catch (security: SecurityException) {
            LocalMediaResult.Failure(LocalMediaError.PermissionDenied)
        } catch (throwable: Throwable) {
            LocalMediaResult.Failure(LocalMediaError.ReadFailed)
        }
    }

    private fun queryMediaStore(context: Context): List<LocalMediaItem> {
        val images = queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false)
        val videos = queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true)
        return images + videos
    }

    /**
     * One MediaStore collection query. MediaStore normalizes `RELATIVE_PATH` to a
     * trailing slash, but some OEM providers store the raw value, so both spellings
     * are matched (still an exact match — no `LIKE`).
     */
    private fun queryCollection(context: Context, collection: Uri, isVideo: Boolean): List<LocalMediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH}=? " +
            "OR ${MediaStore.MediaColumns.RELATIVE_PATH}=?) " +
            "AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        val selectionArgs = arrayOf("$relativeDir/", relativeDir)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val result = ArrayList<LocalMediaItem>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                if (idIndex < 0) return@use
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(idIndex)
                    if (rowId <= 0L) continue
                    val rawName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                    val displayName = rawName?.takeIf { it.isNotBlank() } ?: "YI_media_$rowId"
                    val rawMime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
                        cursor.getString(mimeIndex)
                    } else {
                        null
                    }
                    val mime = rawMime?.takeIf { it.isNotBlank() } ?: DownloadTargets.mimeTypeOf(displayName)
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    val addedSeconds = if (dateIndex >= 0 && !cursor.isNull(dateIndex)) {
                        cursor.getLong(dateIndex)
                    } else {
                        0L
                    }
                    val uri = ContentUris.withAppendedId(collection, rowId)
                    result += LocalMediaItem(
                        id = uri.toString(),
                        uri = uri,
                        displayName = displayName,
                        mimeType = mime,
                        isVideo = isVideo || mime.startsWith("video/"),
                        sizeBytes = size.coerceAtLeast(0L),
                        dateAddedMillis = addedSeconds.coerceAtLeast(0L) * 1000L,
                    )
                }
            }
        return result
    }

    private fun scanLegacyFiles(context: Context): List<LocalMediaItem> {
        val directory = legacyDirectory(context)
        val files = directory.listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            if (!file.isFile || file.length() <= 0L || file.name.startsWith(".")) return@mapNotNull null
            val mime = DownloadTargets.mimeTypeOf(file.name)
            LocalMediaItem(
                id = file.absolutePath,
                uri = Uri.fromFile(file),
                displayName = file.name,
                mimeType = mime,
                isVideo = mime.startsWith("video/"),
                sizeBytes = file.length(),
                dateAddedMillis = file.lastModified(),
                filePath = file.absolutePath,
            )
        }
    }

    /** Mirrors the legacy directory [DownloadTargets] writes to on Android 8/9. */
    fun legacyDirectory(context: Context): File {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "pictures")
        return File(pictures, LEGACY_SUBDIR)
    }

    // --------------------------------------------------------------- thumbnails

    /** Synchronous cache peek; used to paint an already decoded cell without flicker. */
    fun cachedThumbnail(item: LocalMediaItem): Bitmap? = thumbnailCache.get(item.id)

    /** Drops the cached bitmap for one item (after rotate/delete). */
    fun invalidate(item: LocalMediaItem) {
        thumbnailCache.remove(item.id)
    }

    suspend fun loadThumbnail(context: Context, item: LocalMediaItem): Bitmap? = withContext(Dispatchers.IO) {
        thumbnailCache.get(item.id)?.let { return@withContext it }
        val decoded = runCatching { decodeThumbnail(context, item) }.getOrNull()
        if (decoded != null) thumbnailCache.put(item.id, decoded)
        decoded
    }

    private fun decodeThumbnail(context: Context, item: LocalMediaItem): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.filePath == null) {
            val thumbnail = runCatching {
                context.contentResolver.loadThumbnail(
                    item.uri,
                    Size(THUMBNAIL_MAX_PX, THUMBNAIL_MAX_PX),
                    null,
                )
            }.getOrNull()
            if (thumbnail != null) return thumbnail
        }
        if (item.isVideo) return videoFrame(context, item, THUMBNAIL_MAX_PX)
        val decoded = decodeSampled(context, item, THUMBNAIL_MAX_PX) ?: return null
        return applyOrientation(context, item, decoded)
    }

    // ------------------------------------------------------------------ viewer

    /**
     * Larger bitmap for the full-screen viewer. Images are sampled to at most
     * [PREVIEW_MAX_PX] on the long edge and corrected for their EXIF orientation;
     * videos get a representative frame (possibly null when the codec refuses).
     */
    suspend fun loadPreview(context: Context, item: LocalMediaItem): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (item.isVideo) {
                videoFrame(context, item, PREVIEW_MAX_PX)
            } else {
                val decoded = decodeSampled(context, item, PREVIEW_MAX_PX) ?: return@runCatching null
                applyOrientation(context, item, decoded)
            }
        }.getOrNull()
    }

    /** Original pixel dimensions (EXIF rotation applied); null for videos/undecodable files. */
    suspend fun imageDimensions(context: Context, item: LocalMediaItem): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            if (item.isVideo) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openInput(context, item)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val orientation = readOrientation(context, item)
                val quarterTurn = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270
                if (quarterTurn) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
            }.getOrNull()
        }

    /** EXIF `DateTimeOriginal` (camera wall-clock, e.g. `2024-05-01 12:34`), only for images. */
    suspend fun exifDateTaken(context: Context, item: LocalMediaItem): String? =
        withContext(Dispatchers.IO) {
            if (item.isVideo) return@withContext null
            runCatching {
                openInput(context, item)?.let { input ->
                    BufferedInputStream(input).use { buffered ->
                        val exif = ExifInterface(buffered)
                        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            ?.takeIf { it.isNotBlank() }
                            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)?.takeIf { it.isNotBlank() }
                    }
                }
            }.getOrNull()?.let(::formatExifDate)
        }

    // ------------------------------------------------------------- edit / delete

    /**
     * Rotates an image 90° clockwise/counter-clockwise and writes it back into the
     * same MediaStore row (`openOutputStream(uri, "w")`, JPEG quality 95; PNG stays
     * PNG so a `.png` row is never silently turned into JPEG bytes).
     *
     * The existing EXIF rotation is baked into the pixels first (the re-encode does
     * not keep the EXIF tag), then the requested quarter turn is applied. Very large
     * photos are decoded with an `inSampleSize` that keeps two ARGB bitmaps (source
     * + rotated) comfortably inside the heap.
     */
    suspend fun rotateAndSave(context: Context, item: LocalMediaItem, clockwise: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!item.canEdit) return@withContext false
            var current: Bitmap? = null
            try {
                current = decodeForEdit(context, item) ?: return@withContext false
                current = applyOrientation(context, item, current)
                current = rotate(current, if (clockwise) 90f else -90f)
                val bitmap = current
                val output = openOutput(context, item) ?: return@withContext false
                val format = if (item.mimeType == "image/png") {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                val written = output.use { stream -> bitmap.compress(format, JPEG_QUALITY, stream) }
                if (written) {
                    thumbnailCache.remove(item.id)
                    touchMediaStoreRow(context, item)
                }
                written
            } catch (throwable: Throwable) {
                false
            } finally {
                current?.recycle()
            }
        }

    /**
     * Nudges the MediaStore row after an in-place rewrite so its derived-thumbnail
     * cache is regenerated on the next `loadThumbnail` call.
     */
    private fun touchMediaStoreRow(context: Context, item: LocalMediaItem) {
        if (item.filePath != null) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            context.contentResolver.update(item.uri, values, null, null)
        }
    }

    /** Re-reads the byte size after an in-place edit (MediaStore may keep reporting stale SIZE). */
    suspend fun currentSize(context: Context, item: LocalMediaItem): Long? = withContext(Dispatchers.IO) {
        if (item.filePath != null) {
            File(item.filePath).length().takeIf { it > 0L }
        } else {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(item.uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L }
                }
            }.getOrNull()
        }
    }

    /** Deletes one item (own MediaStore row on 10+, plain file on 8/9). */
    suspend fun delete(context: Context, item: LocalMediaItem): Boolean = withContext(Dispatchers.IO) {
        thumbnailCache.remove(item.id)
        try {
            if (item.filePath != null) {
                val file = File(item.filePath)
                !file.exists() || file.delete()
            } else {
                context.contentResolver.delete(item.uri, null, null) > 0
            }
        } catch (throwable: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- intents

    /**
     * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent for one or more items.
     *
     * Returns null for the Android 8/9 fallback: those items only have a `file://`
     * URI and exposing one across apps throws `FileUriExposedException` on
     * targetSdk >= 24 (the manifest deliberately has no FileProvider).
     */
    fun shareIntent(context: Context, items: List<LocalMediaItem>): Intent? {
        if (items.isEmpty()) return null
        if (items.any { it.filePath != null }) return null
        val uris = ArrayList<Uri>(items.size)
        for (item in items) uris += item.uri
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items[0].mimeType
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = sharedMimeType(items)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clipData = ClipData.newUri(context.contentResolver, items[0].displayName, uris[0])
        for (index in 1 until uris.size) clipData.addItem(ClipData.Item(uris[index]))
        intent.clipData = clipData
        return intent
    }

    /** Widest MIME type the receiving app can use for a mixed selection. */
    private fun sharedMimeType(items: List<LocalMediaItem>): String {
        val families = items.map { it.mimeType.substringBefore('/') }.distinct()
        return if (families.size == 1) {
            when (families[0]) {
                "image" -> "image/*"
                "video" -> "video/*"
                else -> "*/*"
            }
        } else {
            "*/*"
        }
    }

    /** `ACTION_VIEW` intent for a video (or any item the system should render). */
    fun viewIntent(item: LocalMediaItem): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Starts an intent without taking the app down when no activity can handle it. */
    fun launchIntent(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (throwable: Throwable) {
        false
    }

    // ------------------------------------------------------------- formatting

    /** `2026-11-24 09:30` in the device timezone (MediaStore DATE_ADDED is a real instant). */
    fun formatLocalDate(epochMillis: Long): String? =
        if (epochMillis <= 0L) null else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))

    /** `11-24 09:30` for the grid cell label. */
    fun formatShortDate(epochMillis: Long): String? =
        if (epochMillis <= 0L) null else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochMillis))

    /** EXIF writes camera wall-clock time without a timezone; display it verbatim. */
    fun formatExifDate(raw: String): String? {
        val match = exifDatePattern.find(raw.trim()) ?: return null
        val (year, month, day, hour, minute) = match.destructured
        return "$year-$month-$day $hour:$minute"
    }

    /** Human-readable size (`912 KB`, `31.9 MB`); null when unknown/zero. */
    fun formatBytes(bytes: Long): String? {
        if (bytes <= 0L) return null
        if (bytes < 1024L) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = "B"
        for (next in units) {
            if (value < 1024.0) break
            value /= 1024.0
            unit = next
        }
        return if (value >= 100.0 || value == value.toLong().toDouble()) {
            "${value.toLong()} $unit"
        } else {
            String.format(Locale.US, "%.1f %s", value, unit)
        }
    }

    private val exifDatePattern = Regex("""(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2})""")

    // ---------------------------------------------------------------- internals

    private fun openInput(context: Context, item: LocalMediaItem): InputStream? {
        val path = item.filePath
        return runCatching {
            if (path != null) FileInputStream(File(path)) else context.contentResolver.openInputStream(item.uri)
        }.getOrNull()
    }

    private fun openOutput(context: Context, item: LocalMediaItem): OutputStream? {
        val path = item.filePath
        return runCatching {
            if (path != null) {
                FileOutputStream(File(path))
            } else {
                context.contentResolver.openOutputStream(item.uri, "w")
            }
        }.getOrNull()
    }

    private fun decodeSampled(context: Context, item: LocalMediaItem, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(context, item)?.use { input ->
            runCatching { BitmapFactory.decodeStream(input, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        }
        return openInput(context, item)?.use { input ->
            runCatching { BitmapFactory.decodeStream(input, null, options) }.getOrNull()
        }
    }

    /** Decode for an in-place edit: full resolution when two ARGB bitmaps fit in a quarter heap. */
    private fun decodeForEdit(context: Context, item: LocalMediaItem): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openInput(context, item)?.use { input ->
            runCatching { BitmapFactory.decodeStream(input, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxPixels = (Runtime.getRuntime().maxMemory() / 32L).coerceAtLeast(1L)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeForPixels(bounds.outWidth, bounds.outHeight, maxPixels)
        }
        return openInput(context, item)?.use { input ->
            runCatching { BitmapFactory.decodeStream(input, null, options) }.getOrNull()
        }
    }

    /** Power-of-two sample size that keeps the long edge within ~2x of [maxDimension]. */
    private fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxDimension * 2) sample *= 2
        return sample
    }

    /** Power-of-two sample size so the decoded bitmap stays below [maxPixels]. */
    private fun sampleSizeForPixels(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
        return sample
    }

    private fun videoFrame(context: Context, item: LocalMediaItem, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val path = item.filePath
            if (path != null) {
                retriever.setDataSource(path)
            } else {
                retriever.setDataSource(context, item.uri)
            }
            retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx)
        } catch (throwable: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readOrientation(context: Context, item: LocalMediaItem): Int? = try {
        openInput(context, item)?.let { input ->
            BufferedInputStream(input).use { buffered ->
                ExifInterface(buffered).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }
    } catch (throwable: Throwable) {
        null
    }

    private fun applyOrientation(context: Context, item: LocalMediaItem, bitmap: Bitmap): Bitmap =
        when (readOrientation(context, item)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bitmap, 270f)
            else -> bitmap
        }

    private fun rotate(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }
}
