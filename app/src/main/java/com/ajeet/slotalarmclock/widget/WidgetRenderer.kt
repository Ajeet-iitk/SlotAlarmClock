package com.ajeet.slotalarmclock.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.ajeet.slotalarmclock.data.AlarmState
import com.ajeet.slotalarmclock.ui.HourOnesSpec
import com.ajeet.slotalarmclock.ui.HourTensSpec
import com.ajeet.slotalarmclock.ui.MinuteOnesSpec
import com.ajeet.slotalarmclock.ui.MinuteTensSpec
import com.ajeet.slotalarmclock.ui.ReelDefaults
import com.ajeet.slotalarmclock.ui.ReelSpec
import com.ajeet.slotalarmclock.ui.drawMetalSwitch
import com.ajeet.slotalarmclock.ui.drawReelStrip
import com.ajeet.slotalarmclock.ui.metal.Metal
import java.util.Locale
import kotlin.math.sqrt

/**
 * Renders the widget's two bitmaps.
 *
 * A home-screen widget cannot animate or run a custom shader — RemoteViews is a static view
 * description, not a canvas. So instead of trying to fake the animation, the widget draws the
 * *exact same* metal and the *exact same* reel renderer into an offscreen bitmap and hands that
 * to an ImageView. The result is a genuine still frame of the app's clock.
 */
object WidgetRenderer {

    /**
     * Pixel budgets for the bitmaps that get pushed into a RemoteViews.
     *
     * The bitmap is not drawn on-screen by this process: it is packed into a Parcel and sent
     * across Binder to the launcher. That buffer is small (about 1 MB for the whole
     * transaction), and when a payload exceeds it the failure is invisible from the user's
     * side — the update is simply dropped and the widget sits there blank, which is exactly the
     * bug these budgets exist to prevent. They are deliberately well under the ceiling rather
     * than near it. The artwork is smooth gradient and text, which upscales far better than a
     * photograph, so a modest bitmap still looks right at full widget size.
     */
    private const val MAX_PLATE_PIXELS = 60_000
    private const val MAX_SWITCH_PIXELS = 24_000
    private const val MAX_PLAIN_PIXELS = 12_000

    /** Renders the plate: case, reels and status line. Archived as a still frame of the clock. */
    fun renderPlate(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        hour: Int,
        minute: Int,
        alarm: AlarmState,
    ): Bitmap {
        val (w, h) = fit(widthPx, heightPx, MAX_PLATE_PIXELS)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density

        // Everything is expressed in dp off the live density so the widget looks identical on a
        // 1x tablet and a 3.5x phone.
        val pad = 5f * density
        val plate = RectF(pad, pad, w - pad, h - pad)
        if (plate.width() <= 8f || plate.height() <= 8f) return bitmap

        Metal.drawPlate(canvas, plate, 26f * density, sweep = 0.36f, warm = alarm.enabled)

        val titleHeight = 16f * density
        val switchBand = 54f * density
        val hPad = 14f * density
        val vPad = 6f * density
        val colonWidth = 14f * density

        val innerWidth = plate.width() - hPad * 2f
        val innerHeight = plate.height() - titleHeight - switchBand - vPad
        val digitHeight = minOf(
            innerHeight.coerceAtLeast(20f * density),
            ((innerWidth - colonWidth) / (4f * ReelDefaults.Aspect)).coerceAtLeast(20f * density),
        )
        val reelWidth = digitHeight * ReelDefaults.Aspect

        drawStatusLine(canvas, plate, titleHeight, alarm, density)
        drawReels(
            canvas = canvas,
            plate = plate,
            titleHeight = titleHeight,
            innerHeight = innerHeight,
            colonWidth = colonWidth,
            reelWidth = reelWidth,
            digitHeight = digitHeight,
            hour = hour,
            minute = minute,
        )
        return bitmap
    }

    /** Renders the alarm switch on its own so the widget can give it a separate tap target. */
    fun renderSwitch(context: Context, widthPx: Int, heightPx: Int, enabled: Boolean): Bitmap {
        val (w, h) = fit(widthPx, heightPx, MAX_SWITCH_PIXELS)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        // Inset so the armed-alarm bloom has somewhere to spill.
        val inset = 3f * density
        drawMetalSwitch(
            canvas = canvas,
            rect = RectF(inset, inset, w - inset, h - inset),
            progress = if (enabled) 1f else 0f,
            label = if (enabled) "ON" else "OFF",
            bloom = if (enabled) 1f else 0f,
            sweep = 0.42f,
        )
        return bitmap
    }

    /**
     * A deliberately plain face: a dark rounded panel with the time as flat text.
     *
     * Used only when the full material render throws. It exists so that a renderer fault
     * degrades into "the widget still tells you the time" rather than "the widget is a blank
     * box", and it doubles as a diagnostic — if this is what shows up, the bitmaps *are*
     * reaching the launcher and the fault is inside the material renderer.
     */
    fun renderPlain(context: Context, widthPx: Int, heightPx: Int, hour: Int, minute: Int): Bitmap {
        val (w, h) = fit(widthPx, heightPx, MAX_PLAIN_PIXELS)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14181E.toInt() }
        val radius = 18f * density
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, background)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Metal.AMBER
            textSize = h * 0.36f
            textAlign = Paint.Align.CENTER
            typeface = Metal.digitTypeface
        }
        val label = String.format(Locale.US, "%02d:%02d", hour, minute)
        canvas.drawText(label, w / 2f, Metal.centredBaseline(text, h / 2f), text)
        return bitmap
    }

    // ------------------------------------------------------------------------------- internals

    private fun drawStatusLine(
        canvas: Canvas,
        plate: RectF,
        titleHeight: Float,
        alarm: AlarmState,
        density: Float,
    ) {
        val centreY = plate.top + titleHeight / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Metal.labelTypeface
            textSize = 10f * density
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.18f
        }
        val text = if (alarm.enabled) "ALARM ${alarm.label}" else "SLOT ALARM"
        Metal.drawEngraved(
            canvas = canvas,
            text = text,
            centreX = plate.centerX(),
            baselineY = Metal.centredBaseline(paint, centreY),
            paint = paint,
        )

        if (alarm.enabled) {
            val ledX = plate.left + 14f * density
            Metal.drawGlow(canvas, ledX, centreY, 9f * density, Metal.AMBER, 0.9f)
            val led = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Metal.AMBER }
            canvas.drawCircle(ledX, centreY, 3f * density, led)
        }
    }

    private fun drawReels(
        canvas: Canvas,
        plate: RectF,
        titleHeight: Float,
        innerHeight: Float,
        colonWidth: Float,
        reelWidth: Float,
        digitHeight: Float,
        hour: Int,
        minute: Int,
    ) {
        val totalWidth = reelWidth * 4f + colonWidth
        var x = plate.centerX() - totalWidth / 2f
        val bandTop = plate.top + titleHeight
        val centreY = bandTop + innerHeight / 2f
        val top = centreY - digitHeight / 2f

        val specs = listOf(
            HourTensSpec to hour / 10,
            HourOnesSpec to hour % 10,
            MinuteTensSpec to minute / 10,
            MinuteOnesSpec to minute % 10,
        )

        specs.forEachIndexed { index, (spec, value) ->
            drawReel(
                canvas = canvas,
                spec = spec,
                value = value,
                rect = RectF(x, top, x + reelWidth, top + digitHeight),
            )
            x += reelWidth
            // Park a gap where the colon sits.
            if (index == 1) x += colonWidth
        }
    }

    private fun drawReel(canvas: Canvas, spec: ReelSpec, value: Int, rect: RectF) {
        drawReelStrip(
            canvas = canvas,
            rect = rect,
            spec = spec,
            // An integral position means the face sits dead centre — no motion, no blur.
            position = spec.indexOf(value).toFloat(),
            blur = 0f,
            warm = false,
        )
    }

    /** Scales a requested size down until it fits [maxPixels]. */
    private fun fit(widthPx: Int, heightPx: Int, maxPixels: Int): Pair<Int, Int> {
        val w = widthPx.coerceIn(64, 1600)
        val h = heightPx.coerceIn(64, 1000)
        val pixels = w.toLong() * h.toLong()
        if (pixels <= maxPixels) return w to h
        val scale = sqrt(maxPixels.toDouble() / pixels.toDouble())
        return (w * scale).toInt().coerceAtLeast(64) to (h * scale).toInt().coerceAtLeast(48)
    }
}
