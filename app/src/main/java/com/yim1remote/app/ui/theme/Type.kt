package com.yim1remote.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Defaults = Typography()

/**
 * Denser than the Material defaults: the Remote screen is a control surface
 * (exposure chips, status rows), not a reading surface.
 */
val CameraTypography = Typography(
    titleLarge = Defaults.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Defaults.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = Defaults.titleSmall.copy(fontWeight = FontWeight.Medium),
    labelSmall = Defaults.labelSmall.copy(letterSpacing = 0.4.sp),
    labelMedium = Defaults.labelMedium.copy(letterSpacing = 0.2.sp),
)

/**
 * Tabular readout style for exposure values (shutter/ISO/aperture/EV): a
 * monospaced face keeps numbers from jittering while the camera updates them
 * every couple of seconds.
 */
val ExposureReadoutStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    letterSpacing = 0.sp,
)

/** Slightly larger monospaced variant used for overlay readouts on live view. */
val ExposureReadoutLargeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    letterSpacing = 0.sp,
)
