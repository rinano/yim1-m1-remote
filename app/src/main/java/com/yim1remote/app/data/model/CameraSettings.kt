package com.yim1remote.app.data.model

/**
 * Every setting the YI M1 exposes over its local HTTP/JSON API, including the
 * exact wire strings recovered from the official app
 * (`com.xiaoyi.mirrorlesscamera.common.CameraSettingParams`).
 *
 * The UI must show these option lists verbatim — the camera rejects values that
 * are not in its own table, and the official app never translated them.
 */

/** Exposure dial modes (`RCSwitchDialMode` -> `DialMode`). */
enum class ExposureMode(val wire: String, val label: String) {
    AUTO("Auto", "自动"),
    P("P", "P"),
    A("A", "A"),
    S("S", "S"),
    M("M", "M"),
    C("C", "C");

    companion object {
        /**
         * The dial mode is reported as `Scene` by some firmware and as `C` by the
         * official 3.1.6 app for the "custom/scene" position. Unknown values fall
         * back to [AUTO] so a status parse can never fail.
         */
        fun fromWire(value: String?): ExposureMode = when (value) {
            "Scene" -> C
            else -> entries.firstOrNull { it.wire == value } ?: AUTO
        }
    }
}

/** Which manual controls are available for a given [ExposureMode]. */
data class ExposureCapabilities(
    val canSetAperture: Boolean,
    val canSetShutter: Boolean,
    val canSetIso: Boolean,
    val canSetEv: Boolean,
) {
    companion object {
        /**
         * From `CameraSettingParams$ExposureModeEnableBean` constructor values:
         * Auto (no aperture, no shutter, no ISO, EV), P (all), A (aperture+ISO+EV),
         * S (shutter+ISO+EV), M (aperture+shutter+EV, ISO locked), C (all).
         */
        fun forMode(mode: ExposureMode): ExposureCapabilities = when (mode) {
            ExposureMode.AUTO -> ExposureCapabilities(false, false, false, true)
            ExposureMode.P -> ExposureCapabilities(true, true, true, true)
            ExposureMode.A -> ExposureCapabilities(true, false, true, true)
            ExposureMode.S -> ExposureCapabilities(false, true, true, true)
            ExposureMode.M -> ExposureCapabilities(true, true, false, true)
            ExposureMode.C -> ExposureCapabilities(true, true, true, true)
        }
    }
}

/** One selectable value of a camera setting: [wire] is sent to the camera, [label] is shown. */
data class CameraOption(val wire: String, val label: String = wire)

/** All enumerated option tables, in the order the camera/official app lists them. */
object CameraOptions {
    val metering: List<CameraOption> = listOf(
        CameraOption("Multi", "多重测光"),
        CameraOption("Spot", "点测光"),
        CameraOption("CenterWeighted", "中央重点测光"),
    )

    val focusMode: List<CameraOption> = listOf(
        CameraOption("C-AF", "连续自动对焦"),
        CameraOption("S-AF", "单次自动对焦"),
        CameraOption("MF", "手动对焦"),
    )

    val imageQuality: List<CameraOption> = listOf(
        CameraOption("50", "50MP"),
        CameraOption("20", "20MP"),
        CameraOption("16", "16MP"),
        CameraOption("8", "8MP"),
        CameraOption("3", "3MP"),
        CameraOption("VGA", "VGA"),
    )

    val imageAspect: List<CameraOption> = listOf(
        CameraOption("4:3", "4:3"),
        CameraOption("3:2", "3:2"),
        CameraOption("16:9", "16:9"),
        CameraOption("1:1", "1:1"),
    )

    val fileFormat: List<CameraOption> = listOf(
        CameraOption("JPG-S", "JPG 小"),
        CameraOption("JPG-M", "JPG 中"),
        CameraOption("JPG-L", "JPG 大"),
        CameraOption("RAW", "RAW"),
        CameraOption("RAWJ-S", "RAW + JPG 小"),
        CameraOption("RAWJ-M", "RAW + JPG 中"),
        CameraOption("RAWJ-L", "RAW + JPG 大"),
    )

    val driveMode: List<CameraOption> = listOf(
        CameraOption("Single", "单张拍摄"),
        CameraOption("Continuous", "连拍"),
        CameraOption("2SDelay", "2 秒延时"),
        CameraOption("10SDelay", "10 秒延时"),
    )

    val colorMode: List<CameraOption> = listOf(
        CameraOption("Standard", "标准"),
        CameraOption("Portrait", "人像"),
        CameraOption("Vivid", "鲜艳"),
        CameraOption("NaturalBW", "自然黑白"),
        CameraOption("HContrastBW", "高对比黑白"),
    )

    val iso: List<CameraOption> = listOf(
        CameraOption("Auto", "自动"),
        CameraOption("100", "100"),
        CameraOption("200", "200"),
        CameraOption("400", "400"),
        CameraOption("800", "800"),
        CameraOption("1600", "1600"),
        CameraOption("3200", "3200"),
        CameraOption("6400", "6400"),
        CameraOption("12800", "12800"),
        CameraOption("25600", "25600"),
    )

    /** Aperture wire strings (camera accepts `1.0` .. `32`). */
    val aperture: List<CameraOption> = listOf(
        "1.0", "1.2", "1.4", "1.7", "1.8", "2.0", "2.2", "2.5", "2.8", "3.2",
        "3.5", "4.0", "4.5", "5.0", "5.6", "6.3", "7.1", "8.0", "9.0", "10",
        "11", "13", "14", "16", "18", "20", "22", "25", "29", "32",
    ).map { CameraOption(it, "f/$it") }

    /**
     * Shutter speed wire strings.
     *
     * NOTE: the camera accepts the *value/display* form here, e.g. `1/125s`, `2s`,
     * `BULB` (the official app sends `ShutterSpeedValue.getValue()`), not the
     * enum-name form. The live-view state JSON reports the same value form, so
     * status values can be matched against these entries directly.
     */
    val shutterSpeed: List<CameraOption> = listOf(
        "TIME", "BULB", "60s", "50s", "40s", "30s", "25s", "20s", "15s",
        "13s", "10s", "8s", "6s", "5s", "4s", "3.2s", "2.5s", "2s",
        "1.6s", "1.3s", "1s", "1/1.3s", "1/1.6s", "1/2s", "1/2.5s", "1/3s",
        "1/4s", "1/5s", "1/6s", "1/8s", "1/10s", "1/13s", "1/15s", "1/20s",
        "1/25s", "1/30s", "1/40s", "1/50s", "1/60s", "1/80s", "1/100s",
        "1/125s", "1/160s", "1/200s", "1/250s", "1/320s", "1/400s", "1/500s",
        "1/640s", "1/800s", "1/1000s", "1/1250s", "1/1600s", "1/2000s",
        "1/2500s", "1/3200s", "1/4000s",
    ).map { CameraOption(it, it) }

    /** EV wire strings are plain signed decimals with one decimal place. */
    val ev: List<CameraOption> = listOf(
        "-5.0", "-4.7", "-4.3", "-4.0", "-3.7", "-3.3", "-3.0", "-2.7", "-2.3",
        "-2.0", "-1.7", "-1.3", "-1.0", "-0.7", "-0.3", "0.0", "0.3", "0.7",
        "1.0", "1.3", "1.7", "2.0", "2.3", "2.7", "3.0", "3.3", "3.7", "4.0",
        "4.3", "4.7", "5.0",
    ).map { CameraOption(it, if (it.startsWith("-")) "EV $it" else "EV +$it") }

    val whiteBalance: List<CameraOption> = buildList {
        add(CameraOption("Auto", "自动"))
        add(CameraOption("Sunny", "晴天"))
        add(CameraOption("Shadow", "阴影"))
        add(CameraOption("Cloudy", "阴天"))
        add(CameraOption("Incandescent", "白炽灯"))
        var k = 2000
        while (k <= 11500) {
            add(CameraOption(k.toString(), "${k}K"))
            k += if (k < 3000) 50 else if (k < 4000) 100 else if (k < 5000) 200 else if (k < 6000) 200 else if (k < 7000) 200 else 500
        }
    }

    /** Delay count values accepted by `RCDShootCntSet` (RCDShootCntSet key). */
    val delayShootCount: List<CameraOption> = (0..20).map { CameraOption(it.toString(), if (it == 0) "关闭" else "$it 张") }

    /** Find an option by wire value (defensive: unknown wire strings fall back to the first entry). */
    fun labelOf(options: List<CameraOption>, wire: String?): String =
        options.firstOrNull { it.wire == wire }?.label ?: (wire ?: "—")
}

/**
 * Full camera state as reported by `GetCameraStatus` / the live-view parameter
 * block. The camera reports settings and status in one JSON object, so this
 * single snapshot feeds both the status bar and the settings sheets.
 */
data class CameraStatus(
    // --- status ---
    val connected: Boolean = false,
    val batteryPercent: Int? = null,
    val remainingShots: Int? = null,
    val firmwareVersion: String? = null,
    val firmwareDisplay: String? = null,
    val lensType: String? = null,
    val lensVersion: String? = null,
    val lensStatus: String? = null,
    val focusSupport: Boolean = false,
    val shooting: Boolean = false,
    val recording: Boolean = false,
    // --- settings ---
    val exposureMode: ExposureMode = ExposureMode.AUTO,
    val meteringMode: String = "Multi",
    val focusMode: String = "S-AF",
    val imageQuality: String = "16",
    val imageAspect: String = "4:3",
    val fileFormat: String = "JPG-M",
    val driveMode: String = "Single",
    val fnumber: String = "4.5",
    val fnumberMin: String = "1.0",
    val fnumberMax: String = "32",
    val shutterSpeed: String = "1/15s",
    val ev: String = "0.0",
    val iso: String = "Auto",
    val isoAutoValue: String = "",
    val whiteBalance: String = "Auto",
    val colorMode: String = "Standard",
    val delayShootCount: Int = 0,
    /** Manual-focus position hint reported by the camera, if any. */
    val mfPosition: Int? = null,
) {
    val capabilities: ExposureCapabilities get() = ExposureCapabilities.forMode(exposureMode)

    /** The camera reports `101` on external power instead of a percentage. */
    val isExternalPower: Boolean get() = batteryPercent == 101

    /** `LensStatus`: `0` = lens disconnected, `1` = auto, `2` = manual, `3` = unknown/error. */
    val lensReady: Boolean get() = lensStatus != "0" && lensStatus != "3"

    companion object {
        fun disconnected(): CameraStatus = CameraStatus(connected = false)
    }
}

/** A writable setting, mapped by [com.yim1remote.app.data.CameraRepository]. */
enum class CameraSetting {
    EXPOSURE_MODE,
    METERING_MODE,
    FOCUS_MODE,
    IMAGE_QUALITY,
    IMAGE_ASPECT,
    FILE_FORMAT,
    DRIVE_MODE,
    FNUMBER,
    SHUTTER_SPEED,
    EV,
    ISO,
    WHITE_BALANCE,
    COLOR_MODE,
    DELAY_SHOOT_COUNT,
    MF_ADJUST,
}
