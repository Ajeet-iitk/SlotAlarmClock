package com.ajeet.slotalarmclock.ui

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ajeet.slotalarmclock.R
import com.ajeet.slotalarmclock.ui.metal.Metal

/**
 * The alarm's ringing face — deliberately the same machine as the clock, lit amber.
 *
 * It shows the alarm time on inert dials rather than a live clock, because at 6am the only
 * useful information is which alarm is going off.
 */
@Composable
fun RingingScreen(
    hour: Int,
    minute: Int,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.18f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(modifier.fillMaxSize()) {
        MetalBackdrop(Modifier.matchParentSize())

        Canvas(Modifier.matchParentSize()) {
            drawIntoCanvas { canvas ->
                Metal.drawGlow(
                    canvas = canvas.nativeCanvas,
                    cx = size.width / 2f,
                    cy = size.height * 0.36f,
                    radius = size.width * 0.85f,
                    colour = Metal.AMBER,
                    strength = pulse.value * 0.55f,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EngravedLabel(stringResource(R.string.alarm_ringing_title).uppercase(), 13.dp, letterSpacing = 0.4f)
            Spacer(Modifier.height(20.dp))

            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val colonWidth = 16.dp
                val available = maxWidth - colonWidth - 32.dp
                val digitHeight = minOf(104.dp, available / (4f * ReelDefaults.Aspect))
                val reelWidth = digitHeight * ReelDefaults.Aspect

                Box(Modifier.fillMaxWidth().height(digitHeight + 40.dp)) {
                    Canvas(Modifier.matchParentSize()) {
                        drawIntoCanvas { canvas ->
                            Metal.drawPlate(
                                canvas = canvas.nativeCanvas,
                                rect = RectF(0f, 0f, size.width, size.height),
                                radius = 30.dp.toPx(),
                                sweep = 0.42f,
                                warm = true,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InertReel(HourTensSpec, hour / 10, reelWidth, digitHeight)
                        InertReel(HourOnesSpec, hour % 10, reelWidth, digitHeight)
                        Spacer(Modifier.width(colonWidth))
                        InertReel(MinuteTensSpec, minute / 10, reelWidth, digitHeight)
                        InertReel(MinuteOnesSpec, minute % 10, reelWidth, digitHeight)
                    }
                }
            }

            Spacer(Modifier.height(36.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetalChip(stringResource(R.string.action_snooze).uppercase(), onSnooze, { 0.35f })
                MetalChip(stringResource(R.string.action_dismiss).uppercase(), onDismiss, { 0.62f }, warm = true)
            }
        }
    }
}

@Composable
private fun InertReel(spec: ReelSpec, value: Int, width: Dp, height: Dp) {
    SlotReel(
        spec = spec,
        value = value,
        modifier = Modifier.width(width).height(height),
        accentArgb = Metal.AMBER,
        accentAlpha = 1f,
    )
}
