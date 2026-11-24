package com.yim1remote.app.data.model

/**
 * Metadata for one camera file, parsed from the camera's `GetFileInfo` reply.
 *
 * Hardware-verified reply (YI M1, 2026-11-24):
 * `{"type":"PICRAW","size":"31932416","width":"5200","height":"3902",
 *   "capture_date":"1795476953","shutter_speed":"1/25s","FNumber":"2.0",
 *   "WB":"Auto","ISO":"1600","FocalLength":"43","Make":"YI TECHNOLOGY",
 *   "focus_mode":"S-AF","model":"M1"}`
 *
 * Every field is optional: the camera returns only the fields it knows for the
 * given file type, and videos may use different keys. The class is display-only
 * (no protocol decisions depend on it).
 */
data class CameraFileInfo(
    /** Camera file type token, e.g. `PICRAW`, `PICNORMAL`, `MOVIE`. */
    val type: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Camera clock value (wall-clock-as-epoch seconds, same convention as `GetFileList.date`). */
    val captureDateSeconds: Long? = null,
    val shutterSpeed: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val whiteBalance: String? = null,
    /** Focal length in millimetres, as reported (the camera says `"43"`). */
    val focalLengthMm: String? = null,
    val make: String? = null,
    val model: String? = null,
    val focusMode: String? = null,
    /** Video duration when the firmware reports one; seconds. */
    val durationSeconds: Long? = null,
) {
    /** `5200x3902` or null when either dimension is missing. */
    val dimensions: String?
        get() {
            val w = width ?: return null
            val h = height ?: return null
            return "${w}x${h}"
        }

    val isEmpty: Boolean
        get() = type == null && sizeBytes == null && width == null && height == null &&
            captureDateSeconds == null && shutterSpeed == null && aperture == null &&
            iso == null && whiteBalance == null && focalLengthMm == null && make == null &&
            model == null && focusMode == null && durationSeconds == null
}
