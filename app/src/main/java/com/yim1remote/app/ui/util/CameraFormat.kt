package com.yim1remote.app.ui.util

import com.yim1remote.app.data.model.CameraOptions
import com.yim1remote.app.data.model.CameraStatus
import com.yim1remote.app.data.model.ExposureMode
import com.yim1remote.app.data.model.GalleryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Presentation helpers for camera values.
 *
 * Wire values are never translated (the camera rejects unknown values and the
 * official app showed the same tables), but the readouts are: shutter speed as
 * `1/250s`, aperture as `f/4.5`, EV with sign, etc. Everything returns null when
 * the camera has not reported the field yet so callers can show the generic "—"
 * placeholder from `strings.xml` instead of inventing data.
 */
object CameraFormat {

    /**
     * Camera timestamps are **wall-clock-as-epoch**: the camera has no RTC and the
     * official app syncs it with `(now + zoneOffset)` (see BLE `41106dac`), then
     * displays photo dates in **GMT/UTC** (`CameraAlbumsFragment`,
     * `PhotoDetailActivity`). Formatting those values in the device timezone would
     * shift every photo by the UTC offset (+8 h in China, observed on hardware).
     */
    private val fullDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val shortDate = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ---------------------------------------------------------------- status

    /** Battery percentage, or null when unknown/on external power (see [CameraStatus.isExternalPower]). */
    fun battery(status: CameraStatus?): String? {
        val percent = status?.batteryPercent ?: return null
        if (status.isExternalPower) return null
        return "$percent%"
    }

    fun remainingShots(status: CameraStatus?): String? =
        status?.remainingShots?.toString()

    fun firmware(status: CameraStatus?): String? =
        status?.firmwareDisplay?.takeIf { it.isNotBlank() }
            ?: status?.firmwareVersion?.takeIf { it.isNotBlank() }

    fun lens(status: CameraStatus?): String? {
        val type = status?.lensType?.takeIf { it.isNotBlank() }
        if (type != null) {
            val version = status.lensVersion?.takeIf { it.isNotBlank() }
            return if (version != null) "$type ($version)" else type
        }
        val statusLabel = status?.lensStatus?.takeIf { it.isNotBlank() }
        return statusLabel
    }

    // -------------------------------------------------------------- settings

    fun exposureModeLabel(status: CameraStatus?): String? =
        status?.exposureMode?.label

    fun shutterLabel(status: CameraStatus?): String? =
        status?.shutterSpeed?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.shutterSpeed, wire)
        }

    fun isoLabel(status: CameraStatus?): String? {
        val wire = status?.iso?.takeIf { it.isNotBlank() } ?: return null
        if (wire.equals("Auto", ignoreCase = true)) {
            val autoLabel = CameraOptions.labelOf(CameraOptions.iso, wire)
            val autoValue = status.isoAutoValue.takeIf { it.isNotBlank() }
            return if (autoValue != null) "$autoLabel ×$autoValue" else autoLabel
        }
        return wire
    }

    fun apertureLabel(status: CameraStatus?): String? =
        status?.fnumber?.takeIf { it.isNotBlank() }?.let { "f/$it" }

    fun evLabel(status: CameraStatus?): String? =
        status?.ev?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.ev, wire)
        }

    fun wbLabel(status: CameraStatus?): String? =
        status?.whiteBalance?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.whiteBalance, wire)
        }

    fun focusModeLabel(status: CameraStatus?): String? =
        status?.focusMode?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.focusMode, wire)
        }

    fun driveModeLabel(status: CameraStatus?): String? =
        status?.driveMode?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.driveMode, wire)
        }

    fun meteringLabel(status: CameraStatus?): String? =
        status?.meteringMode?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.metering, wire)
        }

    fun qualityLabel(status: CameraStatus?): String? =
        status?.imageQuality?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.imageQuality, wire)
        }

    fun fileFormatLabel(status: CameraStatus?): String? =
        status?.fileFormat?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.fileFormat, wire)
        }

    fun colorModeLabel(status: CameraStatus?): String? =
        status?.colorMode?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.colorMode, wire)
        }

    fun aspectLabel(status: CameraStatus?): String? =
        status?.imageAspect?.takeIf { it.isNotBlank() }?.let { wire ->
            CameraOptions.labelOf(CameraOptions.imageAspect, wire)
        }

    fun delayLabel(status: CameraStatus?): String? =
        status?.let { CameraOptions.labelOf(CameraOptions.delayShootCount, it.delayShootCount.toString()) }

    fun exposureModeOptions(): List<ExposureMode> = ExposureMode.entries

    // ------------------------------------------------------------------ gallery

    /** Camera date in `dateWire` is Unix seconds; 0 / malformed means the camera has no RTC value. */
    fun fullDate(epochMillis: Long): String? =
        if (epochMillis <= 0L) null else fullDate.format(Date(epochMillis))

    fun shortDate(epochMillis: Long): String? =
        if (epochMillis <= 0L) null else shortDate.format(Date(epochMillis))

    /** Human-readable file size, e.g. `31.9 MB`; null for unknown/zero. */
    fun fileSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
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

    /** `1:23` / `1:02:03` for a duration in seconds. */
    fun duration(seconds: Long?): String? {
        if (seconds == null || seconds < 0L) return null
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }

    /** Formats a camera clock value given in seconds (wall-clock-as-epoch). */
    fun cameraDate(epochSeconds: Long?): String? =
        cameraDateFromMillis(epochSeconds?.let { if (it >= 1_000_000_000_000L) it else it * 1000L } ?: 0L)

    /** Formats a camera clock value given in epoch milliseconds. */
    fun cameraDateFromMillis(epochMillis: Long?): String? =
        if (epochMillis == null || epochMillis <= 0L) null else fullDate.format(Date(epochMillis))

    /** Aperture options limited to the range the mounted lens reported. */
    fun apertureOptions(status: CameraStatus?): List<com.yim1remote.app.data.model.CameraOption> {
        val min = status?.fnumberMin?.toFloatOrNull()
        val max = status?.fnumberMax?.toFloatOrNull()
        if (min == null || max == null || min <= 0f || max < min) {
            return CameraOptions.aperture
        }
        return CameraOptions.aperture.filter { option ->
            val value = option.wire.toFloatOrNull()
            value == null || (value >= min - 0.001f && value <= max + 0.001f)
        }
    }

    /** Type label key used by the gallery/viewer; UI maps it to a localized string. */
    fun fileTypeKey(item: GalleryItem): String = when {
        item.isVideo -> "video"
        item.isRaw -> "raw"
        else -> "photo"
    }
}
