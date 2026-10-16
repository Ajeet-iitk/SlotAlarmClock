package com.ajeet.slotalarmclock.ui.metal

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs

/**
 * The single source of truth for the metallic material.
 *
 * Every routine here paints onto a plain [android.graphics.Canvas] instead of a Compose
 * `DrawScope`, for two reasons:
 *
 *  1. the home-screen widget has to render the identical look into a [Bitmap];
 *  2. keeping the material in one place means the app and the widget can never drift apart.
 *
 * The material is a multi-stop chrome ramp — two blown-out highlights split by a dark band —
 * with a fine anisotropic brushing pass laid over it, all baked into a texture [Bitmap] and
 * wrapped in a [BitmapShader].
 *
 * That baking step is the reason there is no `RuntimeShader` here any more, and it is worth
 * spelling out because the code spent a while trying: a `RuntimeShader` may only be drawn to a
 * **hardware** canvas, and a bitmap's canvas is always a **software** one. Baking is not
 * optional — it is what lets one renderer serve the widget — so an AGSL tier could never have
 * worked; on Android 13+ it threw `IllegalArgumentException: Software rendering doesn't support
 * RuntimeShader` on the very first frame. The grain is drawn with real 1px strokes instead,
 * which costs nothing, works on every API level, and looks the same in the widget.
 */
object Metal {

    // ------------------------------------------------------------------------------ palette

    private val CHROME_OFFSETS = floatArrayOf(
        0.00f, 0.07f, 0.17f, 0.27f, 0.38f, 0.47f,
        0.53f, 0.62f, 0.72f, 0.81f, 0.90f, 1.00f,
    )

    private val CHROME_COLOURS = intArrayOf(
        0xFF14171C.toInt(), 0xFF3B424A.toInt(), 0xFF8E96A1.toInt(), 0xFFF3F6FA.toInt(),
        0xFFB4BCC6.toInt(), 0xFF4E555F.toInt(), 0xFF2E343B.toInt(), 0xFF7F8792.toInt(),
        0xFFE9EEF4.toInt(), 0xFF97A0AB.toInt(), 0xFF40464F.toInt(), 0xFF12151A.toInt(),
    )

    private val BRASS_COLOURS = intArrayOf(
        0xFF1A1710.toInt(), 0xFF4A4232.toInt(), 0xFF9C8F72.toInt(), 0xFFF9F3E4.toInt(),
        0xFFC6B896.toInt(), 0xFF5C5340.toInt(), 0xFF3A3527.toInt(), 0xFF94876A.toInt(),
        0xFFF4EDDA.toInt(), 0xFFA79A7C.toInt(), 0xFF4B4335.toInt(), 0xFF181510.toInt(),
    )

    /** Warm accent used when the alarm is armed. */
    const val AMBER: Int = 0xFFFFB74D.toInt()
    const val AMBER_DEEP: Int = 0xFFE07A16.toInt()

    /** A heavy condensed face reads like a stamped steel counter. */
    val digitTypeface: Typeface =
        Typeface.create("sans-serif-condensed", Typeface.BOLD) ?: Typeface.DEFAULT_BOLD

    val labelTypeface: Typeface =
        Typeface.create("sans-serif-medium", Typeface.NORMAL) ?: Typeface.DEFAULT_BOLD

    // ------------------------------------------------------------------------------- brushing

    /**
     * Brushed anisotropic grain, painted straight onto the baked ramp.
     *
     * One hairline per pixel row, each with a deterministic alpha, so the surface reads as
     * machined rather than printed. The seed is fixed rather than random because the same region
     * is re-baked whenever a plate resizes, and the grain must not crawl when it does.
     */
    private fun drawBrushing(canvas: Canvas, w: Float, h: Float, warm: Boolean) {
        val stroke = Paint()
        stroke.strokeWidth = 1f
        stroke.color = if (warm) 0xFFF4EDDA.toInt() else 0xFFF2F6FB.toInt()

        var seed = BRUSH_SEED
        var y = 0f
        while (y < h) {
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed ushr 16) and 0x7FFF) / 32_767f
            // Fine enough to be felt rather than seen: 2-12 out of 255.
            stroke.alpha = (2f + noise * 10f).toInt().coerceIn(2, 12)
            canvas.drawLine(0f, y, w, y, stroke)
            y += 1f
        }
    }

    /** Arbitrary but fixed, so re-baking a size reproduces the same grain. */
    private const val BRUSH_SEED = 0x5F3A7C19

    // ------------------------------------------------------------------------- texture cache

    private data class TextureKey(val w: Int, val h: Int, val warm: Boolean, val dx: Int, val dy: Int)

    private val textures = HashMap<TextureKey, BitmapShader>()
    private const val TEXTURE_LIMIT = 16

    /**
     * Baked brushed-metal material for a region of [w]x[h] pixels.
     *
     * Pass [dx]/[dy] when the region does not start at the canvas origin so the grain stays
     * locked to the machine rather than sliding with the shape.
     */
    @Synchronized
    fun plateShader(w: Int, h: Int, warm: Boolean = false, dx: Float = 0f, dy: Float = 0f): BitmapShader {
        val key = TextureKey(
            w.coerceAtLeast(1),
            h.coerceAtLeast(1),
            warm,
            dx.toInt(),
            dy.toInt(),
        )
        textures[key]?.let { return it }
        if (textures.size >= TEXTURE_LIMIT) textures.clear()

        val bitmap = Bitmap.createBitmap(key.w, key.h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = ramp(
                colours = if (warm) BRASS_COLOURS else CHROME_COLOURS,
                offsets = CHROME_OFFSETS,
                top = 0f,
                bottom = key.h.toFloat(),
            )
        }
        canvas.drawRect(0f, 0f, key.w.toFloat(), key.h.toFloat(), paint)

        // The ramp gives the shape of the light; the brushing gives it a direction.
        drawBrushing(canvas, key.w.toFloat(), key.h.toFloat(), warm)

        val wrapped = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (key.dx != 0 || key.dy != 0) {
            wrapped.setLocalMatrix(Matrix().apply { setTranslate(key.dx.toFloat(), key.dy.toFloat()) })
        }
        textures[key] = wrapped
        return wrapped
    }

    // ----------------------------------------------------------------------------- gradients

    private fun ramp(colours: IntArray, offsets: FloatArray, top: Float, bottom: Float): Shader =
        LinearGradient(0f, top, 0f, bottom, colours, offsets, Shader.TileMode.CLAMP)

    /**
     * Vertical chrome ramp. Called per digit per frame with absolute canvas coordinates —
     * that keeps the highlight anchored to the reel window instead of scrolling with the
     * digit, which is what sells the "polished drum" illusion.
     */
    fun chromeRamp(top: Float, bottom: Float, warm: Boolean = false): Shader =
        ramp(if (warm) BRASS_COLOURS else CHROME_COLOURS, CHROME_OFFSETS, top, bottom)

    // --------------------------------------------------------------------------- primitives

    private fun rrect(rect: RectF, radius: Float): Path = Path().apply {
        addRoundRect(rect, radius, radius, Path.Direction.CW)
    }

    /**
     * Paints a machined metal panel: baked grain, a travelling specular sweep, convex
     * top/bottom shading and a beveled edge.
     *
     * @param sweep 0..1 position of the specular highlight, or a negative value to omit it.
     */
    fun drawPlate(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        sweep: Float = -1f,
        warm: Boolean = false,
        sweepStrength: Float = 1f,
    ) {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) return

        val path = rrect(rect, radius)
        val checkpoint = canvas.save()
        canvas.clipPath(path)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fill.shader = plateShader(w.toInt(), h.toInt(), warm, rect.left, rect.top)
        canvas.drawRect(rect, fill)

        if (sweep >= 0f) drawSweep(canvas, rect, sweep, sweepStrength, warm)

        // Convex shading: pinch the extremes of the plate.
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(0x9E000000.toInt(), 0x00000000, 0x00000000, 0x66000000.toInt()),
            floatArrayOf(0f, 0.14f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, shade)
        canvas.restoreToCount(checkpoint)

        // Machined outer edge.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = 2f
        edge.color = 0xFF03050A.toInt()
        canvas.drawRoundRect(rect, radius, radius, edge)

        // Rim light: bright along the top lip, fading to shadow at the bottom.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG)
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = 2f
        rim.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(0xCCFFFFFF.toInt(), 0x2AFFFFFF, 0x00000000, 0x99000000.toInt()),
            floatArrayOf(0f, 0.18f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        val inset = RectF(rect)
        inset.inset(1f, 1f)
        canvas.drawRoundRect(inset, radius - 1f, radius - 1f, rim)
    }

    /** A soft, travelling specular band. Cheap enough to run every frame. */
    fun drawSweep(canvas: Canvas, rect: RectF, phase: Float, strength: Float, warm: Boolean) {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) return

        val centre = phase.coerceIn(0.04f, 0.96f)
        val lead = (centre - 0.20f).coerceAtLeast(0.001f)
        val trail = (centre + 0.20f).coerceAtMost(0.999f)
        val a = (0.30f * strength * 255f).toInt().coerceIn(0, 255)
        val core = if (warm) 0xFFFFF3DC.toInt() else 0xFFF2F7FF.toInt()

        val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
        sweep.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0x00FFFFFF, (a / 4) shl 24 or (core and 0x00FFFFFF), a shl 24 or (core and 0x00FFFFFF), 0x00FFFFFF),
            floatArrayOf(0f, lead, centre, trail),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, sweep)
    }

    /** The dark floor of a reel well. Painted before the digits. */
    fun drawReelBase(canvas: Canvas, rect: RectF) {
        val well = Paint(Paint.ANTI_ALIAS_FLAG)
        well.shader = RadialGradient(
            rect.centerX(), rect.centerY(), rect.height() * 0.95f,
            intArrayOf(0xFF12161B.toInt(), 0xFF080A0D.toInt(), 0xFF030406.toInt()),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, well)
    }

    /**
     * Cylinder shading for a reel well. Painted *after* the digits, which is what makes the
     * faces darken as they leave the centre of the drum.
     */
    fun drawReelShade(canvas: Canvas, rect: RectF) {
        val drum = Paint(Paint.ANTI_ALIAS_FLAG)
        drum.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(
                0xFF000000.toInt(), 0xC4000000.toInt(), 0x00000000,
                0xD2000000.toInt(), 0xFF000000.toInt(),
            ),
            floatArrayOf(0f, 0.14f, 0.5f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, drum)
    }

    /** The machined lip where the well meets the case. Painted outside the clip. */
    fun drawReelLip(canvas: Canvas, rect: RectF, radius: Float) {
        val lip = Paint(Paint.ANTI_ALIAS_FLAG)
        lip.style = Paint.Style.STROKE
        lip.strokeWidth = 3f
        lip.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(0xFF000000.toInt(), 0xFF20262E.toInt(), 0xFF000000.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, radius, radius, lip)
    }

    /**
     * A soft warm bloom, used behind an armed alarm switch.
     * Drawn as concentric translucent rings so no blur filter is required on either tier.
     */
    fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, colour: Int, strength: Float) {
        if (radius <= 0f || strength <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val alpha = (strength.coerceIn(0f, 1f) * 255f).toInt()
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf((alpha / 3) shl 24 or (colour and 0x00FFFFFF), (alpha / 9) shl 24 or (colour and 0x00FFFFFF), 0x00000000),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }

    /**
     * Letterpress text: a bright copy offset downwards underneath a dark cut, which reads as
     * a groove stamped into the plate.
     */
    fun drawEngraved(
        canvas: Canvas,
        text: String,
        centreX: Float,
        baselineY: Float,
        paint: Paint,
        cut: Int = 0xF20A0C10.toInt(),
        lip: Int = 0x66FFFFFF,
    ) {
        val originalShader = paint.shader
        val originalColour = paint.color
        val originalAlpha = paint.alpha

        paint.shader = null
        paint.alpha = 255

        paint.color = lip
        canvas.drawText(text, centreX, baselineY + 1.2f, paint)

        paint.color = cut
        canvas.drawText(text, centreX, baselineY, paint)

        paint.color = originalColour
        paint.alpha = originalAlpha
        paint.shader = originalShader
    }

    /** Flat, brushed backdrop for a full-screen surface. */
    fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG)
        base.shader = LinearGradient(
            0f, 0f, w * 0.35f, h,
            intArrayOf(0xFF161B21.toInt(), 0xFF0D1014.toInt(), 0xFF05070A.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, base)

        val bloom = Paint(Paint.ANTI_ALIAS_FLAG)
        bloom.shader = RadialGradient(
            w * 0.5f, h * 0.24f, maxOf(w, h) * 0.62f,
            intArrayOf(0x2E333B44, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, bloom)

        // Faint horizontal brushing to tie the backdrop to the metal.
        val grain = Paint(Paint.ANTI_ALIAS_FLAG)
        grain.strokeWidth = 1f
        grain.color = 0x0DFFFFFF
        var y = 0f
        while (y < h) {
            grain.alpha = (2 + (abs(y * 7f) % 5f)).toInt().coerceIn(1, 10)
            canvas.drawLine(0f, y, w, y, grain)
            y += 3f
        }
    }

    /** Baseline offset that vertically centres [paint]'s text on [centreY]. */
    fun centredBaseline(paint: Paint, centreY: Float): Float {
        val fm = paint.fontMetrics
        return centreY - (fm.ascent + fm.descent) / 2f
    }
}
