package com.ajeet.slotalarmclock.ui

import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.ajeet.slotalarmclock.ui.metal.Metal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The ordered set of faces on one reel.
 *
 * Modelling the faces explicitly, rather than assuming 0-9 everywhere, is what lets the
 * 24-hour readout behave: the hour-tens reel genuinely has only three faces, so it can never
 * spin past 2 into nonsense.
 */
data class ReelSpec(val faces: List<Int>) {

    init {
        require(faces.isNotEmpty()) { "A reel needs at least one face" }
    }

    val count: Int = faces.size

    /** Pre-rendered glyphs — drawing happens every frame, so avoid per-frame String churn. */
    val labels: List<String> = faces.map(Int::toString)

    fun indexOf(value: Int): Int = faces.indexOf(value).coerceAtLeast(0)

    /** Wraps an unbounded reel index back onto the face list. */
    fun faceAt(index: Int): Int = faces[((index % count) + count) % count]

    fun labelAt(index: Int): String = labels[((index % count) + count) % count]
}

/** 24-hour readout, split so every digit is its own dial. */
val HourTensSpec = ReelSpec(listOf(0, 1, 2))
val HourOnesSpec = ReelSpec((0..9).toList())
val MinuteTensSpec = ReelSpec((0..5).toList())
val MinuteOnesSpec = ReelSpec((0..9).toList())

/**
 * Heavy launch, long decelerating tail, then the small mechanical overshoot of a real sprung
 * reel. `easeOutBack` with a modest `s` overshoots by roughly 5% of one slot, which reads as a
 * satisfying clunk without ever exposing the neighbouring digit.
 */
private object ReelEase : Easing {
    override fun transform(fraction: Float): Float {
        val p = fraction - 1f
        return p * p * (2.2f * p + 1.2f) + 1f
    }
}

/** Shortest forward distance from [from] to face [to] on a reel of [size] faces. */
private fun forwardDelta(from: Float, to: Int, size: Int): Float {
    val raw = to.toFloat() - from
    return ((raw % size) + size) % size
}

// --------------------------------------------------------------------------- shared renderer

/**
 * Draws one reel into a plain [NativeCanvas]. Shared verbatim by the live UI and the widget
 * bitmap, so the two can never disagree about what the clock looks like.
 *
 * @param position continuous reel index; the visible face is `faceAt(round(position))`.
 * @param blur     0..1 motion blur, driven by the spin animation.
 */
internal fun drawReelStrip(
    canvas: NativeCanvas,
    rect: RectF,
    spec: ReelSpec,
    position: Float,
    blur: Float,
    warm: Boolean,
    accentArgb: Int = 0,
    accentAlpha: Float = 0f,
) {
    val radius = rect.height() * 0.16f
    Metal.drawReelBase(canvas, rect)

    val checkpoint = canvas.save()
    canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })

    val digitHeight = rect.height()
    val textSize = digitHeight * 0.82f
    val centreY = rect.centerY()
    val ramp = Metal.chromeRamp(rect.top, rect.bottom, warm)

    fun facePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Metal.digitTypeface
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        shader = ramp
    }

    // Motion blur: several additive copies of the strip smeared along the direction of travel.
    // Additive blending is what stops overlapping copies muddying into grey mush.
    val samples = if (blur > 0.04f) (2f + blur * 5f).roundToInt() else 0
    if (samples > 1) {
        val streak = facePaint().apply {
            alpha = (225f / samples).roundToInt().coerceIn(16, 225)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
        val smear = blur * 0.22f * digitHeight
        for (s in 0 until samples) {
            val t = s.toFloat() / (samples - 1).toFloat()
            drawFaces(canvas, spec, position + (t - 0.5f) * smear, digitHeight, centreY, rect, streak)
        }
    }

    // The crisp pass fades as blur ramps up, so a fast spin reads as pure motion.
    val sharp = facePaint().apply {
        alpha = (255f * (1f - 0.80f * blur)).roundToInt().coerceIn(0, 255)
    }
    drawFaces(canvas, spec, position, digitHeight, centreY, rect, sharp)

    Metal.drawReelShade(canvas, rect)

    if (accentAlpha > 0f) {
        val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        tint.color = accentArgb
        tint.alpha = (accentAlpha.coerceIn(0f, 1f) * 48f).roundToInt()
        canvas.drawRect(rect, tint)
    }

    canvas.restoreToCount(checkpoint)

    // Outside the clip so the lip stays crisp all the way round.
    Metal.drawReelLip(canvas, rect, radius)
}

private fun drawFaces(
    canvas: NativeCanvas,
    spec: ReelSpec,
    position: Float,
    digitHeight: Float,
    centreY: Float,
    rect: RectF,
    paint: Paint,
) {
    // Hoisted: fontMetrics allocates and this runs inside the frame loop.
    val metrics = paint.fontMetrics
    val opticalOffset = (metrics.ascent + metrics.descent) / 2f
    val x = rect.centerX()
    val base = floor(position).toInt()

    for (k in -2..2) {
        val index = base + k
        val faceY = centreY + (index - position) * digitHeight
        if (faceY < rect.top - digitHeight || faceY > rect.bottom + digitHeight) continue
        canvas.drawText(spec.labelAt(index), x, faceY - opticalOffset, paint)
    }
}

// -------------------------------------------------------------------------------- component

/**
 * A single spinning dial.
 *
 * When [interactive] the reel can be flicked vertically; on release it settles onto the
 * nearest face using the same easing a spin uses, then reports the landing value.
 */
@Composable
fun SlotReel(
    spec: ReelSpec,
    value: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    accentArgb: Int = 0,
    accentAlpha: Float = 0f,
    onValueSettled: (Int) -> Unit = {},
) {
    val position = remember(spec) { Animatable(spec.indexOf(value).toFloat()) }
    val spinBlur = remember(spec) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var dragging by remember { mutableStateOf(false) }
    var lastTick by remember { mutableIntStateOf(spec.indexOf(value)) }

    // External changes — the clock ticking, entering/leaving edit mode, a clamp after a drag —
    // spin the reel. The alignment guard is what stops it re-spinning after its own settle.
    LaunchedEffect(spec, value) {
        if (dragging) return@LaunchedEffect
        val current = position.value
        val nearest = current.roundToInt()
        if (abs(current - nearest) < 0.01f && spec.faceAt(nearest) == value) return@LaunchedEffect

        var distance = forwardDelta(current, spec.indexOf(value), spec.count)
        if (distance < 0.35f) distance += spec.count

        val duration = (430f + distance * 105f).roundToInt().coerceAtMost(1400)
        coroutineScope {
            launch { position.animateTo(current + distance, tween(duration, easing = ReelEase)) }
            launch {
                spinBlur.snapTo(1f)
                spinBlur.animateTo(0f, tween((duration * 0.8f).roundToInt(), easing = FastOutSlowInEasing))
            }
        }
    }

    Canvas(
        modifier = modifier.pointerInput(spec, interactive) {
            if (!interactive) return@pointerInput

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val digitHeight = size.height.toFloat().coerceAtLeast(1f)

                dragging = true
                lastTick = floor(position.value).toInt()

                var lastY = down.position.y
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    // Dragging up rolls the reel forward, like pulling a lever.
                    val dy = change.position.y - lastY
                    lastY = change.position.y
                    change.consume()
                    // snapTo is a suspending call, and this gesture block runs inside a
                    // restricted suspension scope (AwaitPointerEventScope), where only that
                    // scope's own suspend functions may be called. Handing the work to the
                    // composition's scope keeps the drag identical while compiling legally.
                    val delta = dy / digitHeight
                    scope.launch { position.snapTo(position.value - delta) }

                    val tick = floor(position.value).toInt()
                    if (tick != lastTick) {
                        lastTick = tick
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                dragging = false
                val target = position.value.roundToInt()

                // Launched rather than awaited so the gesture ends immediately and the user can
                // grab the reel again mid-settle; snapTo cancels this animation cleanly.
                scope.launch {
                    position.animateTo(target.toFloat(), tween(280, easing = ReelEase))
                    spinBlur.snapTo(0f)
                    if (position.value.roundToInt() == target && abs(position.value - target) < 0.05f) {
                        onValueSettled(spec.faceAt(target))
                    }
                }
            }
        },
    ) {
        drawIntoCanvas { canvas ->
            drawReelStrip(
                canvas = canvas.nativeCanvas,
                rect = RectF(0f, 0f, size.width, size.height),
                spec = spec,
                position = position.value,
                blur = spinBlur.value,
                warm = false,
                accentArgb = accentArgb,
                accentAlpha = accentAlpha,
            )
        }
    }
}

/** Reel geometry, exposed so the widget renderer can lay out an identical face. */
object ReelDefaults {
    /** Height of one reel window, in dp. */
    const val DigitHeight: Float = 96f

    /** Reel width as a fraction of its height. */
    const val Aspect: Float = 0.66f
}
