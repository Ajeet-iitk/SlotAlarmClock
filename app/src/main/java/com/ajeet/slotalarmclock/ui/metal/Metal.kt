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
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
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
 * The material is built in two tiers:
 *
 *  * **Android 13+** — a procedural AGSL shader bakes anisotropic brushed steel, complete
 *    with a specular sheen, into a texture bitmap.
 *  * **Android 8–12** — a hand-tuned multi-stop chrome ramp. Same silhouette, softer grain.
 *
 * Either way the result is wrapped in a [BitmapShader]. That detail matters: it means the
 * mutable [RuntimeShader] is *never* handed to a recording canvas, so per-frame uniform
 * writes can't leak between draws. All animation (the specular sweep) is a cheap,
 * immutable gradient overlay painted on top of the baked material.
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

    // ------------------------------------------------------------------- procedural material

    private const val AGSL_BRUSHED = """
        uniform float2 uSize;
        uniform float  uTime;
        uniform float  uGrain;
        uniform float  uWarm;

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / max(uSize, float2(1.0));
            float  y  = clamp(uv.y, 0.0, 1.0);

            float3 dark = float3(0.078, 0.088, 0.103);
            float3 mid  = float3(0.400, 0.428, 0.470);
            float3 lite = float3(0.930, 0.950, 0.972);

            // Chrome ramp: two blown-out highlights split by a dark band.
            float3 col = dark;
            col = mix(col, mid,  smoothstep(0.00, 0.16, y));
            col = mix(col, lite, smoothstep(0.16, 0.27, y));
            col = mix(col, mid,  smoothstep(0.27, 0.44, y));
            col = mix(col, dark, smoothstep(0.44, 0.52, y));
            col = mix(col, mid,  smoothstep(0.52, 0.62, y));
            col = mix(col, lite, smoothstep(0.62, 0.72, y));
            col = mix(col, mid,  smoothstep(0.72, 0.87, y));
            col = mix(col, dark, smoothstep(0.87, 1.00, y));

            // Anisotropic brushing: two octaves of fine, near-horizontal micro-facets.
            float n = fract(sin(fragCoord.y * 127.1 + fragCoord.x * 0.37) * 43758.5453);
            float m = fract(sin(fragCoord.y * 311.7 + fragCoord.x * 0.11 + 17.0) * 24634.6345);
            col *= 1.0 + uGrain * ((n - 0.5) * 0.100 + (m - 0.5) * 0.050);

            // Broad sheen so the plate never reads as a flat ramp.
            float d = y - (0.5 + 0.34 * sin(uTime * 0.55));
            col += float3(0.80, 0.86, 0.98) * exp(-d * d * 46.0) * 0.085;

            col = mix(col, col * float3(1.14, 1.00, 0.80), uWarm);
            return half4(clamp(col, float3(0.0), float3(1.0)), 1.0);
        }
    """

    /**
     * Every Android 13+ reference lives in here — including the *signatures*.
     *
     * That is the whole point of the object. On Android 8–12 `android.graphics.RuntimeShader`
     * does not exist, and if any field or method of [Metal] named the type, loading [Metal]
     * could fail with NoClassDefFoundError on exactly the devices that are supposed to fall
     * back to the gradient ramp. Nothing outside this object ever mentions it: the shader is
     * handed around as a plain [Shader], and its uniforms are written in here.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Brushed {
        fun create(): Shader = RuntimeShader(AGSL_BRUSHED)

        fun configure(shader: Shader, w: Int, h: Int, warm: Boolean) {
            val runtime = shader as RuntimeShader
            runtime.setFloatUniform("uSize", w.toFloat(), h.toFloat())
            runtime.setFloatUniform("uTime", 0f)
            runtime.setFloatUniform("uGrain", 1f)
            runtime.setFloatUniform("uWarm", if (warm) 1f else 0f)
        }
    }

    private var brushed: Shader? = null
    private var brushedFailed = false

    private fun brushedShader(): Shader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (brushedFailed) return null
        brushed?.let { return it }
        return try {
            Brushed.create().also { brushed = it }
        } catch (_: Throwable) {
            // A driver that rejects AGSL falls back to the gradient ramp forever.
            brushedFailed = true
            null
        }
    }

    /** True when the procedural shader is driving the material. Surfaced in the UI footer. */
    val usingProceduralMaterial: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !brushedFailed

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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = brushedShader()
        if (shader != null) {
            Brushed.configure(shader, key.w, key.h, warm)
            paint.shader = shader
        } else {
            paint.shader = ramp(
                colours = if (warm) BRASS_COLOURS else CHROME_COLOURS,
                offsets = CHROME_OFFSETS,
                top = 0f,
                bottom = key.h.toFloat(),
            )
        }
        canvas.drawRect(0f, 0f, key.w.toFloat(), key.h.toFloat(), paint)

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
