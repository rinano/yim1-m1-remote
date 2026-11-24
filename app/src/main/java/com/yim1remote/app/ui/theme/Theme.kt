package com.yim1remote.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val AmberContainer = Color(0xFF3A2C10)
private val Scrim = Color(0xCC000000)

private val CameraDarkColors = darkColorScheme(
    primary = CameraAmber,
    onPrimary = CameraOnAmber,
    primaryContainer = AmberContainer,
    onPrimaryContainer = CameraText,
    secondary = CameraGreen,
    onSecondary = CameraBlack,
    secondaryContainer = CameraPanelRaised,
    onSecondaryContainer = CameraText,
    tertiary = CameraAmber,
    onTertiary = CameraOnAmber,
    background = CameraBlack,
    onBackground = CameraText,
    surface = CameraPanel,
    onSurface = CameraText,
    surfaceVariant = CameraPanelRaised,
    onSurfaceVariant = CameraTextMuted,
    surfaceTint = CameraAmber,
    outline = CameraHairline,
    outlineVariant = CameraHairline,
    error = CameraRed,
    onError = CameraBlack,
    errorContainer = CameraRedDim,
    onErrorContainer = CameraText,
    scrim = Scrim,
)

/** Slightly rounder than stock Material: camera apps read as hardware, not forms. */
private val CameraShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * App theme. The camera viewfinder UI is dark-only by design (like the camera's
 * own screen): a light chrome would wash out live-view exposure judgement, so
 * the system light setting is intentionally ignored.
 */
@Composable
fun Yim1RemoteTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CameraDarkColors,
        typography = CameraTypography,
        shapes = CameraShapes,
        content = content,
    )
}
