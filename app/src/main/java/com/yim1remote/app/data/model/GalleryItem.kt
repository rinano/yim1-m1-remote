package com.yim1remote.app.data.model

/**
 * One photo/video entry from the camera's SD card (`GetFileList`).
 *
 * Verified response item: `{path, date, filetype, protectStatus}` where
 * `date` is a Unix **seconds** string, `protectStatus` is a JSON boolean and
 * `filetype` is one of `picture|video|raw|rawJpeg|panorama` (only the response
 * uses these names; `GetFile` re-uses `path` + the raw `date` string).
 *
 * A RAW+JPEG shot is a single `rawJpeg` entry (the `.JPG` path; the `.DNG`
 * sibling is derived by extension).
 */
data class GalleryItem(
    val path: String,
    /** `date` exactly as the camera sent it — echoed back in `GetFile`. */
    val dateWire: String,
    val isProtected: Boolean,
    val fileType: CameraFileType,
    /** Convenience: last path segment, e.g. `YI000001.JPG`. */
    val fileName: String = path.substringAfterLast('/'),
) {
    /**
     * `date` interpreted as an epoch timestamp (the camera has no RTC; may be wrong).
     *
     * Real cameras report Unix **seconds** (10 digits). Some emulators/mocks report
     * milliseconds (13 digits), so both are accepted: values >= 1e12 are already
     * milliseconds, smaller values are seconds.
     */
    val capturedAtEpochMillis: Long = dateWire.toLongOrNull()?.let { raw ->
        if (raw >= 1_000_000_000_000L) raw else raw * 1000L
    } ?: 0L

    val isVideo: Boolean get() = fileType == CameraFileType.VIDEO

    val isRaw: Boolean
        get() = fileType == CameraFileType.RAW || fileType == CameraFileType.RAW_JPEG

    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
}

/** `filetype` field of a `GetFileList` entry (verified wire values). */
enum class CameraFileType(val wire: String) {
    /** Ordinary JPEG photo. */
    PICTURE("picture"),
    VIDEO("video"),
    RAW("raw"),
    RAW_JPEG("rawJpeg"),
    PANORAMA("panorama"),

    /** Tolerated legacy/unknown spellings. */
    UNKNOWN("");

    companion object {
        fun fromWire(value: String?): CameraFileType = when (value?.trim()?.lowercase()) {
            "picture", "jpeg", "jpg", "photo" -> PICTURE
            "video", "movie", "mp4" -> VIDEO
            "raw", "dng" -> RAW
            "rawjpeg", "raw+jpeg", "raw_jpeg" -> RAW_JPEG
            "panorama" -> PANORAMA
            else -> UNKNOWN
        }
    }
}

/** Resolution of one `GetFile` request. Note the camera's firmware spelling `resulotion`. */
enum class DownloadResolution(val wire: String) {
    /** Small preview used for grid cells. */
    THUMBNAIL("Thumbnail"),

    /** Medium preview used for full-screen preview (not needed to save originals). */
    MID_THUMB("MidThumb"),

    /** Original file bytes — what "download" saves. */
    ORIGINAL("Original"),
}

/** Pagination of the camera listing. */
object GalleryPaging {
    const val PAGE_SIZE = 60

    /**
     * 1-based range for a 0-based page index, matching `range_start`/`range_end`.
     * The official app used `(page*60)+1 .. (page+1)*60`; a non-zero range is
     * required by the firmware (0..0 returns code 1502).
     */
    fun rangeForPage(page: Int): IntRange {
        val safePage = page.coerceAtLeast(0)
        val start = safePage * PAGE_SIZE + 1
        return start..(start + PAGE_SIZE - 1)
    }
}
