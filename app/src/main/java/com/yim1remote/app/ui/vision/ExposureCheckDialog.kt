package com.yim1remote.app.ui.vision

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yim1remote.app.R
import com.yim1remote.app.data.model.GalleryItem
import com.yim1remote.app.ui.CameraAppViewModel
import com.yim1remote.app.ui.theme.CameraTextMuted
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exposure statistics of one real test shot, measured on the camera's MidThumb preview. */
data class ExposureCheckStats(
    /** Mean luma over the whole frame, 0..100 %. */
    val meanPercent: Int,
    /** Share of pixels at/above the highlight-clipping threshold (luma >= 235), 0..100 %. */
    val highlightPercent: Int,
    /** Share of pixels at/below the shadow-clipping threshold (luma <= 16), 0..100 %. */
    val shadowPercent: Int,
    /** 64-bin luma histogram (0 = black, 63 = white). */
    val histogram: IntArray,
)

/**
 * Pure exposure analysis for the M-mode 曝光检查 (试拍) panel.
 *
 * The camera's live view does not simulate exposure in M mode, so the only real
 * feedback is an actual captured photo: the caller captures, fetches the MidThumb
 * preview and hands the bitmap here (runs on [Dispatchers.Default]).
 */
object ExposureCheckAnalyzer {

    fun analyze(bitmap: Bitmap): ExposureCheckStats {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return ExposureCheckStats(0, 0, 0, IntArray(64))
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(64)
        var sum = 0L
        var count = 0
        var highlights = 0
        var shadows = 0
        // Every second pixel is plenty for statistics and keeps large previews fast.
        for (y in 0 until height step 2) {
            val row = y * width
            for (x in 0 until width step 2) {
                val color = pixels[row + x]
                val luma = (((color shr 16) and 0xFF) * 77 +
                    ((color shr 8) and 0xFF) * 150 +
                    (color and 0xFF) * 29) shr 8
                sum += luma
                count++
                if (luma >= 235) highlights++
                if (luma <= 16) shadows++
                histogram[(luma * 64 shr 8).coerceIn(0, 63)]++
            }
        }
        if (count == 0) return ExposureCheckStats(0, 0, 0, histogram)
        return ExposureCheckStats(
            meanPercent = ((sum.toDouble() / count) / 255.0 * 100.0).roundToInt(),
            highlightPercent = (highlights * 100.0 / count).roundToInt(),
            shadowPercent = (shadows * 100.0 / count).roundToInt(),
            histogram = histogram,
        )
    }
}

private sealed interface ExposureCheckPhase {
    data object Loading : ExposureCheckPhase
    data class Done(val item: GalleryItem, val stats: ExposureCheckStats) : ExposureCheckPhase
    data class Failed(val message: String) : ExposureCheckPhase
}

/**
 * M-mode 曝光检查 (试拍) dialog: captures one real photo, analyses its preview and
 * gives mean brightness / highlight / shadow clipping with an actionable verdict.
 *
 * The test shot is NOT auto-deleted (the user chooses 删除试拍) because it is also a
 * legitimate capture; keeping it makes the flow non-destructive.
 */
@Composable
fun ExposureCheckDialog(
    viewModel: CameraAppViewModel,
    onDismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf<ExposureCheckPhase>(ExposureCheckPhase.Loading) }
    val noItemMessage = stringResource(R.string.exposure_check_no_item)
    val noPreviewMessage = "无法读取试拍预览，请重试。"

    LaunchedEffect(Unit) {
        val captured = try {
            viewModel.captureForExposureCheck()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val newest = captured.getOrNull()
        if (newest == null) {
            phase = ExposureCheckPhase.Failed(noItemMessage)
            return@LaunchedEffect
        }
        val bitmap = try {
            viewModel.previewForExposureCheck(newest)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            null
        }
        if (bitmap == null) {
            phase = ExposureCheckPhase.Failed(noPreviewMessage)
            return@LaunchedEffect
        }
        val stats = try {
            withContext(Dispatchers.Default) { ExposureCheckAnalyzer.analyze(bitmap) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            phase = ExposureCheckPhase.Failed(noPreviewMessage)
            return@LaunchedEffect
        }
        phase = ExposureCheckPhase.Done(item = newest, stats = stats)
    }

    val donePhase = phase as? ExposureCheckPhase.Done
    AlertDialog(
        onDismissRequest = { if (phase !is ExposureCheckPhase.Loading) onDismiss() },
        title = { Text(stringResource(R.string.exposure_check_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.exposure_check_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = CameraTextMuted,
                )
                when (val current = phase) {
                    ExposureCheckPhase.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.exposure_check_loading),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    is ExposureCheckPhase.Failed -> Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is ExposureCheckPhase.Done -> ExposureCheckResult(stats = current.stats)
                }
            }
        },
        confirmButton = {
            if (donePhase != null) {
                TextButton(
                    onClick = {
                        viewModel.deleteItems(listOf(donePhase.item))
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.exposure_check_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = phase !is ExposureCheckPhase.Loading,
            ) {
                Text(stringResource(R.string.exposure_check_close))
            }
        },
    )
}

@Composable
private fun ExposureCheckResult(stats: ExposureCheckStats) {
    AssistHistogram(
        histogram = stats.histogram,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        height = 56.dp,
    )
    Text(
        text = stringResource(R.string.exposure_check_mean_fmt, stats.meanPercent),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.exposure_check_highlight_fmt, stats.highlightPercent),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(R.string.exposure_check_shadow_fmt, stats.shadowPercent),
        style = MaterialTheme.typography.bodyMedium,
    )

    val (verdictRes, adviceRes) = when {
        stats.highlightPercent >= 10 ->
            R.string.exposure_check_bright to R.string.exposure_check_advice_clipped

        stats.meanPercent < 35 ->
            R.string.exposure_check_dark to R.string.exposure_check_advice_dark

        stats.meanPercent > 65 ->
            R.string.exposure_check_bright to R.string.exposure_check_advice_bright

        else -> R.string.exposure_check_ok to R.string.exposure_check_advice_ok
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(verdictRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(adviceRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
