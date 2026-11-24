package com.yim1remote.app.ui.vision

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.LiveFrame
import com.yim1remote.app.ui.theme.CameraAmber
import com.yim1remote.app.ui.theme.CameraGreen
import com.yim1remote.app.ui.theme.CameraTextMuted
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Client-side live-view assist overlays for the 遥控 tab.
 *
 * The YI M1's M-mode live view does not simulate exposure (verified on the
 * hardware unit), and it has no focus-peaking/zebra/level helpers at all, so the
 * phone derives them from the received 800x600 JPEG and the device sensors:
 *
 *  * **Focus peaking / zebra / histogram** need per-pixel work, throttled to
 *    ~7 fps on [Dispatchers.Default] — the UI thread only draws the cached result.
 *  * **Grid / level / exposure simulation** are cheap draw-time helpers.
 *
 * Nothing here talks to the camera; switching an overlay off or on never changes
 * camera state.
 */

/** Cached per-frame analysis result handed from the analysis coroutine to the draw pass. */
data class LiveAssistFrame(
    /** Half-resolution ARGB overlay with peaking edges (red) and zebra pixels (magenta). */
    val overlay: ImageBitmap? = null,
    /** 64-bin luma histogram (0 = black, 63 = white), or null when disabled. */
    val histogram: IntArray? = null,
)

/** Approximate horizontal gradient magnitude that counts as a focus-peaking edge. */
private const val PEAKING_THRESHOLD = 90
private const val PEAKING_COLOR = 0xE6FF3D00.toInt()
private const val ZEBRA_LUMA = 235
private const val ZEBRA_COLOR = 0xCCFF00E5.toInt()
private const val HISTOGRAM_BINS = 64
private const val ANALYSIS_INTERVAL_MS = 140L

/**
 * Pixel analysis with reusable scratch buffers (single instance per Remote screen).
 * Only meant to be called from one coroutine at a time, which the produceState loop
 * guarantees.
 */
class LiveAssistAnalyzer {

    private var fullPixels = IntArray(0)
    private var luma = IntArray(0)
    private var overlayPixels = IntArray(0)

    fun analyze(
        bitmap: Bitmap,
        peaking: Boolean,
        zebra: Boolean,
        histogram: Boolean,
    ): LiveAssistFrame {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 2 || height < 2) return LiveAssistFrame()

        val halfWidth = (width + 1) / 2
        val halfHeight = (height + 1) / 2
        val lumaSize = halfWidth * halfHeight

        if (fullPixels.size < width * height) fullPixels = IntArray(width * height)
        bitmap.getPixels(fullPixels, 0, width, 0, 0, width, height)

        if (luma.size < lumaSize) luma = IntArray(lumaSize)
        var index = 0
        for (y in 0 until halfHeight) {
            val sourceRow = (y * 2).coerceAtMost(height - 1) * width
            for (x in 0 until halfWidth) {
                val sourceX = (x * 2).coerceAtMost(width - 1)
                val color = fullPixels[sourceRow + sourceX]
                luma[index++] = (
                    ((color shr 16) and 0xFF) * 77 +
                        ((color shr 8) and 0xFF) * 150 +
                        (color and 0xFF) * 29
                    ) shr 8
            }
        }

        val histogramBins = if (histogram) {
            val bins = IntArray(HISTOGRAM_BINS)
            for (i in 0 until lumaSize) {
                bins[(luma[i] * HISTOGRAM_BINS shr 8).coerceIn(0, HISTOGRAM_BINS - 1)]++
            }
            bins
        } else {
            null
        }

        var overlay: ImageBitmap? = null
        if (peaking || zebra) {
            if (overlayPixels.size < lumaSize) overlayPixels = IntArray(lumaSize)
            java.util.Arrays.fill(overlayPixels, 0, lumaSize, 0)

            if (zebra) {
                for (y in 0 until halfHeight) {
                    val rowStart = y * halfWidth
                    for (x in 0 until halfWidth) {
                        // Diagonal 4-px-wide (after 2x upscale) stripes so clipping
                        // reads as "zebra" rather than a flat magenta block.
                        if (luma[rowStart + x] >= ZEBRA_LUMA && ((x / 2 + y / 2) and 1) == 0) {
                            overlayPixels[rowStart + x] = ZEBRA_COLOR
                        }
                    }
                }
            }

            if (peaking) {
                for (y in 1 until halfHeight - 1) {
                    val rowStart = y * halfWidth
                    for (x in 1 until halfWidth - 1) {
                        val i = rowStart + x
                        val dx = abs(luma[i + 1] - luma[i - 1])
                        val dy = abs(luma[i + halfWidth] - luma[i - halfWidth])
                        if (dx + dy >= PEAKING_THRESHOLD) {
                            overlayPixels[i] = PEAKING_COLOR
                        }
                    }
                }
            }

            overlay = Bitmap.createBitmap(
                overlayPixels,
                0,
                halfWidth,
                halfWidth,
                halfHeight,
                Bitmap.Config.ARGB_8888,
            ).asImageBitmap()
        }

        return LiveAssistFrame(overlay = overlay, histogram = histogramBins)
    }
}

private data class PixelAnalysisKey(
    val peaking: Boolean,
    val zebra: Boolean,
    val histogram: Boolean,
)

/**
 * Throttled background analysis of the current live frame.
 *
 * Recomposes as frames arrive but only re-runs the pixel pass every
 * [ANALYSIS_INTERVAL_MS]; the last result is kept while the stream is stalled so
 * overlays freeze with the last frame instead of disappearing.
 */
@Composable
fun rememberLiveAssistAnalysis(
    frame: LiveFrame?,
    settings: LiveAssistSettings,
): LiveAssistFrame {
    val analyzer = remember { LiveAssistAnalyzer() }
    val latestFrame = rememberUpdatedState(frame)
    val key = PixelAnalysisKey(
        peaking = settings.focusPeaking,
        zebra = settings.zebra,
        histogram = settings.histogram,
    )
    val state = produceState(initialValue = LiveAssistFrame(), key1 = key) {
        if (!settings.wantsPixelAnalysis) {
            value = LiveAssistFrame()
            return@produceState
        }
        var lastAnalyzedBitmap: Bitmap? = null
        while (isActive) {
            val current = latestFrame.value
            if (current != null && current.bitmap !== lastAnalyzedBitmap) {
                lastAnalyzedBitmap = current.bitmap
                val result = try {
                    withContext(Dispatchers.Default) {
                        analyzer.analyze(
                            bitmap = current.bitmap,
                            peaking = settings.focusPeaking,
                            zebra = settings.zebra,
                            histogram = settings.histogram,
                        )
                    }
                } catch (t: Throwable) {
                    // A recycled/invalid frame must never crash the viewfinder.
                    LiveAssistFrame()
                }
                value = result
            }
            delay(ANALYSIS_INTERVAL_MS)
        }
    }
    return state.value
}

// =====================================================================
// Draw-time overlays (called from the live-frame canvas)
// =====================================================================

/** Rule-of-thirds grid drawn inside the letterboxed image rectangle. */
fun DrawScope.drawAssistGrid(imageRect: Rect) {
    val color = Color.White.copy(alpha = 0.30f)
    val stroke = 1.dp.toPx()
    for (i in 1..2) {
        val x = imageRect.left + imageRect.width * i / 3f
        val y = imageRect.top + imageRect.height * i / 3f
        drawLine(color, Offset(x, imageRect.top), Offset(x, imageRect.bottom), stroke)
        drawLine(color, Offset(imageRect.left, y), Offset(imageRect.right, y), stroke)
    }
}

/** Peaking/zebra overlay bitmap stretched exactly onto the displayed image rect. */
fun DrawScope.drawAssistOverlay(overlay: ImageBitmap, imageRect: Rect) {
    if (imageRect.width <= 1f || imageRect.height <= 1f) return
    drawImage(
        image = overlay,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(overlay.width, overlay.height),
        dstOffset = IntOffset(imageRect.left.roundToInt(), imageRect.top.roundToInt()),
        dstSize = IntSize(
            imageRect.width.roundToInt().coerceAtLeast(1),
            imageRect.height.roundToInt().coerceAtLeast(1),
        ),
        filterQuality = FilterQuality.Low,
    )
}

/** Small badge telling the user the viewfinder brightness is simulated. */
@Composable
fun ExposureSimulationBadge(evWire: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.5f),
        contentColor = CameraAmber,
    ) {
        Text(
            text = "模拟 " + formatEv(evWire),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** Entry point that opens the 取景辅助 settings dialog (top-right of the live view). */
@Composable
fun LiveAssistEntryButton(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.5f),
        contentColor = if (active) CameraAmber else Color.White.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.cd_assist),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Chroma/luma gain for the client-side exposure simulation (2^EV, clamped). */
fun exposureSimulationGain(evWire: String?): Float {
    val ev = (evWire?.toFloatOrNull() ?: 0f).coerceIn(-3f, 3f)
    return 2f.pow(ev).coerceIn(0.25f, 4f)
}

/** Signed EV label, e.g. `+1.0 EV` / `-0.3 EV`. */
fun formatEv(evWire: String?): String {
    val ev = evWire?.toFloatOrNull() ?: 0f
    return if (ev > 0.05f) "+%.1f EV".format(ev) else "%.1f EV".format(ev)
}

// =====================================================================
// Histogram
// =====================================================================

/** Bare 64-bin luma histogram (used both on the live view and in the exposure check). */
@Composable
fun AssistHistogram(
    histogram: IntArray,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 40.dp,
) {
    val maxBin = remember(histogram) { histogram.maxOrNull()?.coerceAtLeast(1) ?: 1 }
    Canvas(
        modifier = modifier.then(
            if (width != null) {
                Modifier.size(width = width, height = height)
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(height)
            },
        ),
    ) {
        val barWidth = size.width / histogram.size
        histogram.forEachIndexed { index, value ->
            val fraction = (value.toFloat() / maxBin).coerceIn(0f, 1f)
            val barHeight = fraction * size.height
            if (barHeight > 0.5f) {
                drawRect(
                    color = if (index > histogram.size * 3 / 4) CameraAmber else Color.White.copy(alpha = 0.82f),
                    topLeft = Offset(index * barWidth, size.height - barHeight),
                    size = Size((barWidth - 0.4f).coerceAtLeast(0.4f), barHeight),
                )
            }
        }
    }
}

/** Bottom-right histogram widget on the live view (above the quick chips). */
@Composable
fun LiveHistogramOverlay(
    histogram: IntArray?,
    modifier: Modifier = Modifier,
) {
    if (histogram == null) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.assist_histogram),
                style = MaterialTheme.typography.labelSmall,
                color = CameraTextMuted,
            )
            AssistHistogram(
                histogram = histogram,
                modifier = Modifier.padding(top = 2.dp),
                width = 84.dp,
                height = 30.dp,
            )
        }
    }
}

// =====================================================================
// Level (accelerometer)
// =====================================================================

/**
 * Horizon/roll overlay from the accelerometer, degrades to "no overlay" on
 * devices without a usable sensor (e.g. emulators) instead of crashing.
 */
@Composable
fun LiveLevelOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var rollDegrees by remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (manager == null || sensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val gx = event.values.getOrNull(0) ?: return
                    val gy = event.values.getOrNull(1) ?: return
                    if (abs(gx) < 0.15f && abs(gy) < 0.15f) return
                    rollDegrees = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            runCatching {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
            onDispose { runCatching { manager.unregisterListener(listener) } }
        }
    }

    val roll = rollDegrees
    if (roll.isNaN()) return
    val leveled = abs(roll) <= 1.0f
    val tint = if (leveled) CameraGreen else CameraAmber
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val half = minOf(size.width, size.height) * 0.30f
            val radians = Math.toRadians((-roll).toDouble())
            val dx = (cos(radians) * half).toFloat()
            val dy = (sin(radians) * half).toFloat()
            drawLine(
                color = tint.copy(alpha = 0.9f),
                start = Offset(centerX - dx, centerY - dy),
                end = Offset(centerX + dx, centerY + dy),
                strokeWidth = 2.dp.toPx(),
            )
            // End ticks perpendicular to the horizon line.
            val tickX = (-sin(radians)).toFloat()
            val tickY = cos(radians).toFloat()
            val tick = 8.dp.toPx()
            drawLine(
                color = tint,
                start = Offset(centerX - dx, centerY - dy),
                end = Offset(centerX - dx + tickX * tick, centerY - dy + tickY * tick),
                strokeWidth = 2.dp.toPx(),
            )
            drawLine(
                color = tint,
                start = Offset(centerX + dx, centerY + dy),
                end = Offset(centerX + dx - tickX * tick, centerY + dy - tickY * tick),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(color = tint, radius = 2.5.dp.toPx(), center = Offset(centerX, centerY))
        }
        Text(
            text = stringResource(R.string.assist_level) + " " + "%.1f".format(roll) + "°",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 26.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
