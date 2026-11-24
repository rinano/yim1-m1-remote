package com.yim1remote.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders for every JSON command the YI M1 camera accepts.
 *
 * Protocol shape (recovered from the official app,
 * `analysis/jadx-out/sources/com/xiaoyi/mirrorlesscamera/http/camera/b.java`):
 *
 * ```
 * GET http://192.168.0.10/?data=<url-encoded compact JSON>
 * {"command":"<Name>", "<Param>": <value>, ...}
 * ```
 *
 * Every payload is modern-camera firmware compatible compact JSON with no
 * whitespace; the official app serialized `LinkedHashMap`s with Gson, so key
 * order below matches the official request order (some cameras log/sign the
 * raw parameter string, but order is not semantically significant).
 *
 * Value types matter and follow the APK exactly:
 *  * integers are bare JSON numbers (`range_start`, `range_end`);
 *  * string setters keep the camera's own wire strings, e.g. `Fnumber: "4.5"`,
 *    `EV: "0.3"`, `ShutterSpeed: "1/125"`-style values such as `"125"`;
 *  * `RCDoFocus` sends `Mode` as a string (`"Auto"`/`"Manual"`) and `Posx`/`Posy`
 *    as strings (`String.valueOf(int)` in the official app);
 *  * `RCDShootCntSet` nests the value under a key named exactly like the
 *    command (`RCDShootCntSet`), as the official app did.
 *
 * Pure Kotlin + `org.json`, no Android dependencies: unit-test friendly.
 */
object CameraJson {

    private fun command(name: String, vararg fields: Pair<String, Any>): String =
        JSONObject().apply {
            put("command", name)
            for ((key, value) in fields) {
                when (value) {
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    is Boolean -> put(key, value)
                    is JSONArray -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }.toString()

    // ------------------------------------------------------------------ status

    /** `{"command":"GetCameraStatus"}` — battery, remaining shots, lens info. */
    fun getCameraStatus(): String = command(CMD_GET_CAMERA_STATUS)

    /** `{"command":"CheckPreUpdate"}` — asks the camera for firmware update info. */
    fun checkPreUpdate(): String = command(CMD_CHECK_PRE_UPDATE)

    // ------------------------------------------------------------------- files

    /** `{"command":"GetFileList","range_start":1,"range_end":60,"filetype":"all"}` (1-based). */
    fun getFileList(rangeStart: Int, rangeEnd: Int): String =
        command(
            CMD_GET_FILE_LIST,
            "range_start" to rangeStart,
            "range_end" to rangeEnd,
            "filetype" to "all",
        )

    /** `{"command":"GetFileInfo","path":"/DCIM/100YIM1/YI0001.JPG"}`. */
    fun getFileInfo(path: String): String = command(CMD_GET_FILE_INFO, "path" to path)

    /**
     * `{"command":"GetFile","path":"...","date":"<epoch ms as string>","resulotion":"Thumbnail"}`
     *
     * Note the official (misspelled) key `resulotion`; [resolution] is one of
     * `Thumbnail` / `MidThumb` / `Original`.
     */
    fun getFile(path: String, date: String, resolution: String): String =
        command(
            CMD_GET_FILE,
            "path" to path,
            "date" to date,
            "resulotion" to resolution,
        )

    /** `{"command":"DeleteFile","file_list":["/DCIM/..."]}`. */
    fun deleteFile(paths: List<String>): String =
        command(CMD_DELETE_FILE, "file_list" to JSONArray(paths))

    // -------------------------------------------------------------- live view

    /** `{"command":"StartLiveview"}` — enables the UDP live-view stream. */
    fun startLiveview(): String = command(CMD_START_LIVEVIEW)

    /** `{"command":"RCGetCameraCfg"}` — camera configuration read. */
    fun rcGetCameraCfg(): String = command(CMD_RC_GET_CAMERA_CFG)

    /** `{"command":"RCStartRemoteCtl"}` — enters remote-control mode (official app always sent it). */
    fun rcStartRemoteCtl(): String = command(CMD_RC_START_REMOTE_CTL)

    /** `{"command":"RCStopRemoteCtl"}` — leaves remote-control mode. */
    fun rcStopRemoteCtl(): String = command(CMD_RC_STOP_REMOTE_CTL)

    // --------------------------------------------------------------- settings

    /** `{"command":"RCSwitchDialMode","DialMode":"Auto"|"P"|"A"|"S"|"M"|"C"}`. */
    fun rcSwitchDialMode(dialMode: String): String =
        command(CMD_RC_SWITCH_DIAL_MODE, "DialMode" to dialMode)

    /** `{"command":"RCMeteringModeSet","MeteringMode":"Multi"|"Spot"|"CenterWeighted"}`. */
    fun rcMeteringModeSet(value: String): String =
        command(CMD_RC_METERING_MODE_SET, "MeteringMode" to value)

    /** `{"command":"RCFocusModeSet","FocusMode":"C-AF"|"S-AF"|"MF"}`. */
    fun rcFocusModeSet(value: String): String =
        command(CMD_RC_FOCUS_MODE_SET, "FocusMode" to value)

    /** `{"command":"RCImageQualitySet","ImageQuality":"50"|"20"|"16"|"8"|"3"|"VGA"}`. */
    fun rcImageQualitySet(value: String): String =
        command(CMD_RC_IMAGE_QUALITY_SET, "ImageQuality" to value)

    /** `{"command":"RCImageAspect","ImageAspect":"4:3"|"3:2"|"16:9"|"1:1"}`. */
    fun rcImageAspect(value: String): String =
        command(CMD_RC_IMAGE_ASPECT, "ImageAspect" to value)

    /** `{"command":"RCFileFormatSet","FileFormat":"JPG-S"|"JPG-M"|"JPG-L"|"RAW"|"RAWJ-S"|"RAWJ-M"|"RAWJ-L"}`. */
    fun rcFileFormatSet(value: String): String =
        command(CMD_RC_FILE_FORMAT_SET, "FileFormat" to value)

    /** `{"command":"RCDriveModeSet","DriveMode":"Single"|"Continuous"|"2SDelay"|"10SDelay"}`. */
    fun rcDriveModeSet(value: String): String =
        command(CMD_RC_DRIVE_MODE_SET, "DriveMode" to value)

    /** `{"command":"RCFNSet","Fnumber":"4.5"}` (wire strings from `CameraOptions.aperture`). */
    fun rcFnSet(value: String): String = command(CMD_RC_FN_SET, "Fnumber" to value)

    /** `{"command":"RCShutterSpeedSet","ShutterSpeed":"125"|"2\""|"TIME"|...}`. */
    fun rcShutterSpeedSet(value: String): String =
        command(CMD_RC_SHUTTER_SPEED_SET, "ShutterSpeed" to value)

    /** `{"command":"RCEVSet","EV":"0.3"}` — signed decimal, one place, no leading `+`. */
    fun rcEvSet(value: String): String = command(CMD_RC_EV_SET, "EV" to normalizeEv(value))

    /** `{"command":"RCISOSet","ISO":"Auto"|"100"|...}`. */
    fun rcIsoSet(value: String): String = command(CMD_RC_ISO_SET, "ISO" to value)

    /** `{"command":"RCWBSet","WB":"Auto"|"Sunny"|...|"5000"}`. */
    fun rcWbSet(value: String): String = command(CMD_RC_WB_SET, "WB" to value)

    /** `{"command":"RCChooseColorMode","ColorMode":"Standard"|...}`. */
    fun rcChooseColorMode(value: String): String =
        command(CMD_RC_CHOOSE_COLOR_MODE, "ColorMode" to value)

    /** `{"command":"RCDShootCntSet","RCDShootCntSet":"0"}` — delay/interval shot count. */
    fun rcDShootCntSet(count: String): String =
        command(CMD_RC_D_SHOOT_CNT_SET, "RCDShootCntSet" to count)

    /** Convenience overload for the numeric delay count 0..20. */
    fun rcDShootCntSet(count: Int): String = rcDShootCntSet(count.toString())

    // ------------------------------------------------------------------ focus

    /**
     * `{"command":"RCDoFocus","Mode":"Manual","Posx":"<x>","Posy":"<y>"}`.
     *
     * Coordinates are the camera's own pixel space (see
     * [CameraProtocol.focusPoint]); `Mode` is `Auto` for auto-area focus or
     * `Manual` for a tapped focus point.
     */
    fun rcDoFocus(mode: String, posX: Int, posY: Int): String =
        command(
            CMD_RC_DO_FOCUS,
            "Mode" to mode,
            "Posx" to posX.toString(),
            "Posy" to posY.toString(),
        )

    /** `{"command":"RCMFAdjust","Operation":"FarF"|"FarS"|"NearF"|"NearS"}`. */
    fun rcMfAdjust(operation: String): String =
        command(CMD_RC_MF_ADJUST, "Operation" to operation)

    /**
     * Maps a signed manual-focus step count onto the four wire operations the
     * official adjust-MF view used:
     * `FarF`/`NearF` = far/near fast (long press), `FarS`/`NearS` = far/near
     * single step. `|steps| >= 2` means fast, otherwise single step.
     * `0` is a no-op and returns null so callers can skip the request.
     */
    fun rcMfAdjust(steps: Int): String? = when {
        steps >= 2 -> rcMfAdjust("FarF")
        steps == 1 -> rcMfAdjust("FarS")
        steps == -1 -> rcMfAdjust("NearS")
        steps <= -2 -> rcMfAdjust("NearF")
        else -> null
    }

    // ----------------------------------------------------------------- action

    /** `{"command":"RCDoShooting"}` — shutter release; response arrives when the capture finished. */
    fun rcDoShooting(): String = command(CMD_RC_DO_SHOOTING)

    /** `{"command":"RCCancelShooting"}` — cancels a running capture (10 s timeout in the official app). */
    fun rcCancelShooting(): String = command(CMD_RC_CANCEL_SHOOTING)

    /** `{"command":"RCCancelShooting1"}` — fallback cancel (official app used a 60 s timeout). */
    fun rcCancelShooting1(): String = command(CMD_RC_CANCEL_SHOOTING_1)

    // ------------------------------------------------------------------ helper

    /**
     * The camera's EV wire values never carry a leading `+` (`"+0.3"` UI value is
     * sent as `"0.3"`); keep numeric equality even if a caller passes a `+`.
     */
    internal fun normalizeEv(value: String): String =
        if (value.startsWith("+")) value.substring(1) else value

    // --------------------------------------------------------------- constants

    const val CMD_GET_CAMERA_STATUS = "GetCameraStatus"
    const val CMD_CHECK_PRE_UPDATE = "CheckPreUpdate"
    const val CMD_GET_FILE_LIST = "GetFileList"
    const val CMD_GET_FILE_INFO = "GetFileInfo"
    const val CMD_GET_FILE = "GetFile"
    const val CMD_DELETE_FILE = "DeleteFile"
    const val CMD_START_LIVEVIEW = "StartLiveview"
    const val CMD_RC_GET_CAMERA_CFG = "RCGetCameraCfg"
    const val CMD_RC_START_REMOTE_CTL = "RCStartRemoteCtl"
    const val CMD_RC_STOP_REMOTE_CTL = "RCStopRemoteCtl"
    const val CMD_RC_SWITCH_DIAL_MODE = "RCSwitchDialMode"
    const val CMD_RC_METERING_MODE_SET = "RCMeteringModeSet"
    const val CMD_RC_FOCUS_MODE_SET = "RCFocusModeSet"
    const val CMD_RC_IMAGE_QUALITY_SET = "RCImageQualitySet"
    const val CMD_RC_IMAGE_ASPECT = "RCImageAspect"
    const val CMD_RC_FILE_FORMAT_SET = "RCFileFormatSet"
    const val CMD_RC_DRIVE_MODE_SET = "RCDriveModeSet"
    const val CMD_RC_FN_SET = "RCFNSet"
    const val CMD_RC_SHUTTER_SPEED_SET = "RCShutterSpeedSet"
    const val CMD_RC_EV_SET = "RCEVSet"
    const val CMD_RC_ISO_SET = "RCISOSet"
    const val CMD_RC_WB_SET = "RCWBSet"
    const val CMD_RC_DO_FOCUS = "RCDoFocus"
    const val CMD_RC_MF_ADJUST = "RCMFAdjust"
    const val CMD_RC_DO_SHOOTING = "RCDoShooting"
    const val CMD_RC_CANCEL_SHOOTING = "RCCancelShooting"
    const val CMD_RC_CANCEL_SHOOTING_1 = "RCCancelShooting1"
    const val CMD_RC_CHOOSE_COLOR_MODE = "RCChooseColorMode"
    const val CMD_RC_D_SHOOT_CNT_SET = "RCDShootCntSet"

    /** Resolution wire strings for [getFile]. */
    object Resolution {
        const val THUMBNAIL = "Thumbnail"
        const val MID_THUMB = "MidThumb"
        const val ORIGINAL = "Original"
    }
}
