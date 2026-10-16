package com.ajeet.slotalarmclock.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ajeet.slotalarmclock.R
import com.ajeet.slotalarmclock.alarm.AlarmScheduler
import com.ajeet.slotalarmclock.data.AlarmState
import com.ajeet.slotalarmclock.data.AlarmStore
import com.ajeet.slotalarmclock.ui.metal.Metal
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * The clock face.
 *
 * Two modes share one set of dials. Normally the reels show the live 24-hour time and are
 * inert; tapping the alarm strip hands the same dials over to alarm editing, where they become
 * draggable. Reusing the dials means the app has exactly one clock metaphor instead of a
 * beautiful display plus a grey system picker.
 */
@Composable
fun SlotAlarmClockScreen(
    store: AlarmStore,
    onAlarmArmed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val alarm by store.state.collectAsState()
    val now by rememberNow()
    val context = LocalContext.current

    var editing by remember { mutableStateOf(false) }
    var draftHour by remember { mutableIntStateOf(alarm.hour) }
    var draftMinute by remember { mutableIntStateOf(alarm.minute) }

    // One slow sheen travelling across every plate, so the whole face feels lit by a single
    // light source rather than assembled from separate bitmaps.
    //
    // Held as a State rather than read into a local Float on purpose: this animates forever, so
    // reading it here would recompose the entire screen 60 times a second. Passing it down as a
    // lambda means the value is only ever read inside a draw scope, which invalidates the draw
    // phase and nothing else.
    val sweep: State<Float> = rememberInfiniteTransition(label = "sheen").animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    val sheen: () -> Float = { sweep.value }

    val shownHour = if (editing) draftHour else now.hour
    val shownMinute = if (editing) draftMinute else now.minute
    val accent = if (editing) 1f else 0f

    Box(modifier.fillMaxSize()) {
        MetalBackdrop(Modifier.matchParentSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(22.dp))
            EngravedLabel(stringResource(R.string.app_name).uppercase(), 13.dp, letterSpacing = 0.34f)
            Spacer(Modifier.height(4.dp))
            EngravedLabel(
                text = if (editing) stringResource(R.string.alarm_set_title).uppercase() else "24-HOUR",
                sizeDp = 10.dp,
                letterSpacing = 0.28f,
            )

            Spacer(Modifier.height(24.dp))

            ReelPanel(
                hourTens = shownHour / 10,
                hourOnes = shownHour % 10,
                minuteTens = shownMinute / 10,
                minuteOnes = shownMinute % 10,
                interactive = editing,
                sweep = sheen,
                onHourTens = { draftHour = (it * 10 + draftHour % 10).coerceAtMost(23) },
                onHourOnes = { draftHour = ((draftHour / 10) * 10 + it).coerceAtMost(23) },
                onMinuteTens = { draftMinute = (it * 10 + draftMinute % 10).coerceIn(0, 59) },
                onMinuteOnes = { draftMinute = ((draftMinute / 10) * 10 + it).coerceIn(0, 59) },
            )

            Spacer(Modifier.height(18.dp))

            AlarmRow(
                alarm = alarm,
                editing = editing,
                sweep = sheen,
                onEdit = {
                    draftHour = alarm.hour
                    draftMinute = alarm.minute
                    editing = true
                },
                onSet = {
                    store.setTime(draftHour, draftMinute)
                    AlarmScheduler.apply(context)
                    editing = false
                },
                onCancel = { editing = false },
            )

            Spacer(Modifier.weight(1f))

            MetalSwitch(
                checked = alarm.enabled,
                onCheckedChange = { enabled ->
                    store.setEnabled(enabled)
                    AlarmScheduler.apply(context)
                    if (enabled) onAlarmArmed()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                sweep = sheen,
            )

            Spacer(Modifier.height(16.dp))
            EngravedLabel(
                text = if (Metal.usingProceduralMaterial) "AGSL BRUSHED METAL" else "CHROME GRADIENT",
                sizeDp = 9.dp,
                letterSpacing = 0.26f,
                accent = accent,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ------------------------------------------------------------------------------ clock ticking

/** Emits the current wall-clock time once per second, aligned to the second boundary. */
@Composable
private fun rememberNow(): State<LocalTime> = produceState(initialValue = LocalTime.now()) {
    while (true) {
        value = LocalTime.now()
        // Land just after each second ticks over so the minute roll never lags.
        delay(1000L - (System.currentTimeMillis() % 1000L) + 8L)
    }
}

// ------------------------------------------------------------------------------- dial assembly

@Composable
private fun ReelPanel(
    hourTens: Int,
    hourOnes: Int,
    minuteTens: Int,
    minuteOnes: Int,
    interactive: Boolean,
    sweep: () -> Float,
    modifier: Modifier = Modifier,
    onHourTens: (Int) -> Unit = {},
    onHourOnes: (Int) -> Unit = {},
    onMinuteTens: (Int) -> Unit = {},
    onMinuteOnes: (Int) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Size the dials to whatever room the device actually has, so a narrow phone shrinks
        // the reels instead of clipping them.
        val colonWidth = 16.dp
        val panelPadding = 32.dp
        val available = maxWidth - colonWidth - panelPadding
        val digitHeight = minOf(
            ReelDefaults.DigitHeight.dp,
            available / (4f * ReelDefaults.Aspect),
        )
        val reelWidth = digitHeight * ReelDefaults.Aspect
        val accent = if (interactive) 1f else 0f

        Box(Modifier.fillMaxWidth().height(digitHeight + 40.dp)) {
            Canvas(Modifier.matchParentSize()) {
                drawIntoCanvas { canvas ->
                    Metal.drawPlate(
                        canvas = canvas.nativeCanvas,
                        rect = RectF(0f, 0f, size.width, size.height),
                        radius = 30.dp.toPx(),
                        sweep = sweep(),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReelCell(HourTensSpec, hourTens, reelWidth, digitHeight, interactive, accent, onHourTens)
                ReelCell(HourOnesSpec, hourOnes, reelWidth, digitHeight, interactive, accent, onHourOnes)
                ColonDots(accent, digitHeight)
                ReelCell(MinuteTensSpec, minuteTens, reelWidth, digitHeight, interactive, accent, onMinuteTens)
                ReelCell(MinuteOnesSpec, minuteOnes, reelWidth, digitHeight, interactive, accent, onMinuteOnes)
            }
        }
    }
}

@Composable
private fun ReelCell(
    spec: ReelSpec,
    value: Int,
    width: Dp,
    height: Dp,
    interactive: Boolean,
    accent: Float,
    onSettled: (Int) -> Unit,
) {
    SlotReel(
        spec = spec,
        value = value,
        modifier = Modifier.width(width).height(height),
        interactive = interactive,
        accentArgb = Metal.AMBER,
        accentAlpha = accent,
        onValueSettled = onSettled,
    )
}

@Composable
private fun ColonDots(accent: Float, height: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.width(16.dp).height(height)) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val radius = size.width * 0.21f
            val cx = size.width / 2f
            val top = size.height * 0.37f
            val bottom = size.height * 0.63f

            val chrome = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = Metal.chromeRamp(size.height * 0.25f, size.height * 0.75f)
            }
            native.drawCircle(cx, top, radius, chrome)
            native.drawCircle(cx, bottom, radius, chrome)

            if (accent > 0.01f) {
                val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Metal.AMBER
                    alpha = (accent * 210f).toInt()
                }
                native.drawCircle(cx, top, radius, hot)
                native.drawCircle(cx, bottom, radius, hot)
            }
        }
    }
}

// --------------------------------------------------------------------------------- alarm strip

@Composable
private fun AlarmRow(
    alarm: AlarmState,
    editing: Boolean,
    sweep: () -> Float,
    onEdit: () -> Unit,
    onSet: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (editing) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EngravedLabel(stringResource(R.string.hint_drag_dials).uppercase(), 10.dp, letterSpacing = 0.22f)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MetalChip(stringResource(R.string.action_cancel).uppercase(), onCancel, sweep)
                MetalChip(stringResource(R.string.action_done).uppercase(), onSet, sweep, warm = true)
            }
        }
        return
    }

    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onEdit,
            ),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                Metal.drawPlate(
                    canvas = native,
                    rect = RectF(0f, 0f, size.width, size.height),
                    radius = 22.dp.toPx(),
                    sweep = sweep(),
                    warm = alarm.enabled,
                )

                val left = 26.dp.toPx()
                val right = size.width - 26.dp.toPx()
                val midY = size.height / 2f

                // LED: dull red when disarmed, amber when armed.
                val ledRadius = 5.dp.toPx()
                val ledX = left + ledRadius
                Metal.drawGlow(
                    canvas = native,
                    cx = ledX,
                    cy = midY,
                    radius = ledRadius * 3.4f,
                    colour = if (alarm.enabled) Metal.AMBER else 0xFFE4695B.toInt(),
                    strength = if (alarm.enabled) 1f else 0.28f,
                )
                val led = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (alarm.enabled) Metal.AMBER else 0xFF6E2A22.toInt()
                }
                native.drawCircle(ledX, midY, ledRadius, led)

                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Metal.labelTypeface
                    textSize = 12.dp.toPx() * fontScale
                    textAlign = Paint.Align.LEFT
                    letterSpacing = 0.18f
                }
                Metal.drawEngraved(
                    canvas = native,
                    text = "ALARM",
                    centreX = ledX + ledRadius + 14.dp.toPx(),
                    baselineY = Metal.centredBaseline(label, midY),
                    paint = label,
                )

                val time = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Metal.digitTypeface
                    textSize = 30.dp.toPx() * fontScale
                    textAlign = Paint.Align.RIGHT
                    shader = Metal.chromeRamp(
                        midY - 18.dp.toPx(),
                        midY + 18.dp.toPx(),
                        warm = alarm.enabled,
                    )
                }
                native.drawText(alarm.label, right, Metal.centredBaseline(time, midY), time)
            }
        }
    }
}

// ------------------------------------------------------------------------------------ typography

/** Brushed graphite backdrop, shared by every full-screen surface. */
@Composable
internal fun MetalBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { Metal.drawBackdrop(it.nativeCanvas, size.width, size.height) }
    }
}

/** Letterpress label: a bright under-lip beneath a dark cut. */
@Composable
internal fun EngravedLabel(
    text: String,
    sizeDp: Dp,
    modifier: Modifier = Modifier,
    letterSpacing: Float = 0.2f,
    accent: Float = 0f,
) {
    val fontScale = LocalDensity.current.fontScale
    Canvas(modifier.fillMaxWidth().height(sizeDp * 1.6f)) {
        drawIntoCanvas { canvas ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Metal.labelTypeface
                textSize = sizeDp.toPx() * fontScale
                textAlign = Paint.Align.CENTER
                this.letterSpacing = letterSpacing
            }
            Metal.drawEngraved(
                canvas = canvas.nativeCanvas,
                text = text,
                centreX = size.width / 2f,
                baselineY = Metal.centredBaseline(paint, size.height / 2f),
                paint = paint,
                cut = if (accent > 0.5f) 0xF23A2A08.toInt() else 0xF20A0C10.toInt(),
            )
        }
    }
}

/** A small machined button. */
@Composable
internal fun MetalChip(
    text: String,
    onClick: () -> Unit,
    sweep: () -> Float,
    modifier: Modifier = Modifier,
    warm: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .width(118.dp)
            .height(46.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                Metal.drawPlate(
                    canvas = native,
                    rect = RectF(0f, 0f, size.width, size.height),
                    radius = size.height / 2f,
                    sweep = sweep(),
                    warm = warm,
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = Metal.labelTypeface
                    textSize = 12.dp.toPx() * fontScale
                    textAlign = Paint.Align.CENTER
                    letterSpacing = 0.16f
                }
                Metal.drawEngraved(
                    canvas = native,
                    text = text,
                    centreX = size.width / 2f,
                    baselineY = Metal.centredBaseline(paint, size.height / 2f),
                    paint = paint,
                    cut = if (warm) 0xF23A2A08.toInt() else 0xF20A0C10.toInt(),
                    lip = if (warm) 0x99FFE9C4.toInt() else 0x66FFFFFF,
                )
            }
        }
    }
}
