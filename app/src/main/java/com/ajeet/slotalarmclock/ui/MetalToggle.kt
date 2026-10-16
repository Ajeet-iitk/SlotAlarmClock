package com.ajeet.slotalarmclock.ui

import android.graphics.Canvas as NativeCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import com.ajeet.slotalarmclock.ui.metal.Metal

/**
 * A chunky metal rocker switch.
 *
 * Drawn by hand rather than assembled from Material components so it shares the exact same
 * material as the reels — and so the widget can render an identical switch into its bitmap.
 *
 * @param progress 0 = OFF (thumb left), 1 = ON (thumb right).
 */
internal fun drawMetalSwitch(
    canvas: NativeCanvas,
    rect: RectF,
    progress: Float,
    label: String,
    bloom: Float,
    sweep: Float = 0.5f,
) {
    val height = rect.height()
    val width = rect.width()
    if (width <= 0f || height <= 0f) return

    val t = progress.coerceIn(0f, 1f)
    val radius = height / 2f

    // Warm bloom spilling out behind an armed alarm.
    if (bloom > 0.01f) {
        Metal.drawGlow(canvas, rect.centerX(), rect.centerY(), width * 0.55f, Metal.AMBER, bloom * 0.9f)
    }

    // The case.
    Metal.drawPlate(canvas, rect, radius, sweep = sweep, warm = t > 0.5f)

    // Recessed channel the thumb travels along.
    val channel = RectF(rect)
    val margin = height * 0.12f
    channel.inset(margin, margin)
    val channelRadius = channel.height() / 2f
    val channelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    channelPaint.shader = LinearGradient(
        0f, channel.top, 0f, channel.bottom,
        intArrayOf(0xFF000000.toInt(), 0xFF10141A.toInt(), 0xFF000000.toInt()),
        floatArrayOf(0f, 0.42f, 1f),
        Shader.TileMode.CLAMP,
    )
    canvas.drawRoundRect(channel, channelRadius, channelRadius, channelPaint)

    // Amber wash over the channel once armed.
    if (t > 0.01f) {
        val wash = Paint(Paint.ANTI_ALIAS_FLAG)
        wash.color = Metal.AMBER_DEEP
        wash.alpha = (t * 64f).toInt().coerceIn(0, 64)
        canvas.drawRoundRect(channel, channelRadius, channelRadius, wash)
    }

    // The sliding thumb.
    val pad = height * 0.16f
    val thumbHeight = height - pad * 2f
    val thumbWidth = width * 0.44f
    val travel = width - thumbWidth - pad * 2f
    val left = rect.left + pad + travel * t
    val thumb = RectF(left, rect.top + pad, left + thumbWidth, rect.bottom - pad)
    val thumbRadius = thumbHeight / 2f

    if (bloom > 0.01f) {
        Metal.drawGlow(canvas, thumb.centerX(), thumb.centerY(), thumbWidth * 0.9f, Metal.AMBER, bloom)
    }

    Metal.drawPlate(canvas, thumb, thumbRadius, sweep = sweep, warm = t > 0.5f)

    // Engraved state label, moving with the thumb.
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Metal.labelTypeface
        textSize = thumbHeight * 0.54f
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    Metal.drawEngraved(
        canvas = canvas,
        text = label,
        centreX = thumb.centerX(),
        baselineY = Metal.centredBaseline(labelPaint, thumb.centerY()),
        paint = labelPaint,
        cut = if (t > 0.5f) 0xF23A2A08.toInt() else 0xF20A0C10.toInt(),
        lip = if (t > 0.5f) 0x99FFE9C4.toInt() else 0x66FFFFFF,
    )
}

/**
 * The interactive switch. Springs between states and squashes slightly while held, which is
 * what makes a hand-drawn toggle feel physical.
 */
@Composable
fun MetalSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLabel: String = "ON",
    offLabel: String = "OFF",
    sweep: () -> Float = { 0.5f },
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "switch",
    )
    val bloom by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "bloom",
    )
    val squash by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "squash",
    )

    Canvas(
        modifier = modifier
            .graphicsLayer {
                scaleX = squash
                scaleY = squash
            }
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            ),
    ) {
        drawIntoCanvas { canvas ->
            drawMetalSwitch(
                canvas = canvas.nativeCanvas,
                rect = RectF(0f, 0f, size.width, size.height),
                progress = progress,
                label = if (progress >= 0.5f) onLabel else offLabel,
                bloom = bloom,
                sweep = sweep(),
            )
        }
    }
}
