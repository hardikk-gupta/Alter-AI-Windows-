package com.example.ui

import alter.graphics.Bitmap
import alter.graphics.Canvas
import alter.graphics.DashPathEffect
import alter.graphics.Paint
import alter.graphics.Path
import alter.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.example.ui.theme.*
import kotlin.math.*

/** Continuous species geometry first; a fixed, screen-aligned ASCII sampling layer second. */
internal class KoiAsciiRenderer : AutoCloseable {
    private var scene: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val body = Path()
    private val part = Path()
    private val point = FloatArray(3)
    private val glyphs = "o:+tx8%$#"
    private val pearl = ShoalPearl.toArgb()
    private val coral = ShoalCoral.toArgb()
    private val gold = ShoalGold.toArgb()
    private val ink = ShoalInk.toArgb()

    override fun close() { scene?.recycle(); scene = null; sceneCanvas = null }

    fun draw(canvas: Canvas, pond: ShoalSimulation, width: Float, height: Float,
        density: Float, opacity: Float, dark: Boolean) {
        if (width <= 0f || height <= 0f) return
        val cellDp = 7.5f * pond.sizeScale
        val cell = cellDp * density
        val cols = ceil(width / cell).toInt(); val rows = ceil(height / cell).toInt()
        // Four subpixel samples per cell keep edge coverage soft without a full-screen bitmap.
        if (scene?.width != cols * 2 || scene?.height != rows * 2) {
            close()
            scene = Bitmap.createBitmap(cols * 2, rows * 2, Bitmap.Config.ARGB_8888)
            sceneCanvas = Canvas(scene!!)
            pixels = IntArray(cols * rows * 4)
        }
        val bitmap = scene!!
        bitmap.eraseColor(alter.graphics.Colors.TRANSPARENT)
        val source = sceneCanvas!!
        source.save(); source.scale(2f / cellDp, 2f / cellDp)
        pond.fish.forEach { fish -> if (pond.isVisible(fish)) {
            val length = pond.visibleLength(fish)
            fish.poseSpine(length)
            source.save(); source.translate(fish.x, fish.y)
            source.rotate(fish.heading * 180f / PI.toFloat()); source.scale(length, length)
            when (fish.species) {
                RiverSpecies.KOI -> drawFish(source, fish)
                RiverSpecies.HAMMERHEAD -> drawHammerhead(source, fish)
                RiverSpecies.DOLPHIN -> drawDolphin(source, fish)
            }
            source.restore()
        } }
        source.restore()
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        paint.color = if (dark) pearl else ink; paint.alpha = (12 * opacity).toInt()
        paint.strokeWidth = .4f * density
        for (x in 0..cols) canvas.drawLine(x * cell, 0f, x * cell, height, paint)
        for (y in 0..rows) canvas.drawLine(0f, y * cell, width, y * cell, paint)
        text.textSize = cell * .64f
        val baseline = (cell - text.fontMetrics.ascent - text.fontMetrics.descent) * .5f
        for (row in 0 until rows) for (col in 0 until cols) {
            var alpha = 0; var red = 0; var green = 0; var blue = 0
            for (dy in 0..1) for (dx in 0..1) {
                val color = pixels[(row * 2 + dy) * bitmap.width + col * 2 + dx]
                val a = color ushr 24
                alpha += a; red += (color shr 16 and 255) * a
                green += (color shr 8 and 255) * a; blue += (color and 255) * a
            }
            val x = col * cell; val y = row * cell
            if (alpha > 32) {
                val r = red / alpha; val g = green / alpha; val b = blue / alpha
                paint.color = alter.graphics.Colors.rgb(r, g, b)
                paint.alpha = (alpha / 4f * .90f * opacity).toInt()
                canvas.drawRect(x + density * .45f, y + density * .45f,
                    x + cell - density * .45f, y + cell - density * .45f, paint)
                text.color = if (r * .2126f + g * .7152f + b * .0722f > 140f) ShoalNight.toArgb() else pearl
                text.alpha = ((alpha / 4f + 60f).coerceAtMost(255f) * opacity).toInt()
                val symbol = ((r + g + b) / 64 + col * 3 + row * 7) % glyphs.length
                canvas.drawText(glyphs, symbol, symbol + 1, x + cell * .5f, y + baseline, text)
            } else if ((col * 37 + row * 19) % 113 == 0) {
                text.color = if (dark) pearl else ink; text.alpha = (23 * opacity).toInt()
                canvas.drawText(".", x + cell * .5f, y + baseline, text)
            }
        }
        if (ripplePaint.strokeWidth != density * .6f) {
            ripplePaint.strokeWidth = density * .6f
            ripplePaint.pathEffect = DashPathEffect(floatArrayOf(density, density * 4f), 0f)
        }
        pond.food.forEach { pellet ->
            val x = (floor(pellet.x * density / cell) + .5f) * cell
            val y = (floor(pellet.y * density / cell) + .5f) * cell
            if (pellet.age < .7f) {
                ripplePaint.color = pearl; ripplePaint.alpha = ((1f - pellet.age / .7f) * 90 * opacity).toInt()
                canvas.drawCircle(x, y, (8f + pellet.age * 30f) * density * pond.sizeScale, ripplePaint)
            }
            if (!pellet.consumed) {
                paint.color = ShoalFoodColor.toArgb()
                paint.alpha = (255 * ((16f - pellet.age) / 3f).coerceIn(0f, 1f) * opacity).toInt()
                canvas.drawRect(x - cell * .25f, y - cell * .25f, x + cell * .25f, y + cell * .25f, paint)
            }
        }
    }

    private fun locate(f: ShoalFish, u: Float, v: Float) {
        val segment = ((.48f - u) / 1.38f * f.spineX.lastIndex).coerceIn(0f, f.spineX.lastIndex.toFloat())
        val i = segment.toInt().coerceAtMost(f.spineX.lastIndex - 1); val mix = segment - i
        val angle = f.spineAngle[i] + (f.spineAngle[i + 1] - f.spineAngle[i]) * mix
        point[0] = f.spineX[i] + (f.spineX[i + 1] - f.spineX[i]) * mix - sin(angle) * v
        point[1] = f.spineY[i] + (f.spineY[i + 1] - f.spineY[i]) * mix + cos(angle) * v
        point[2] = angle
    }

    private fun vertex(path: Path, f: ShoalFish, u: Float, v: Float, first: Boolean = false) {
        locate(f, u, v)
        if (first) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
    }

    private fun halfWidth(u: Float): Float {
        val t = ((.48f - u) / 1.1f).coerceIn(0f, 1f)
        return .23f * sin(t * PI.toFloat()).coerceAtLeast(0f).pow(.65f) * (1f - .65f * t)
    }

    private fun polygon(canvas: Canvas, f: ShoalFish, color: Int, vararg coordinates: Float) {
        part.reset()
        for (i in coordinates.indices step 2) vertex(part, f, coordinates[i], coordinates[i + 1], i == 0)
        part.close(); paint.color = color; paint.alpha = 255
        canvas.drawPath(part, paint)
    }

    private fun outline(canvas: Canvas, f: ShoalFish, color: Int, width: (Float) -> Float) {
        body.reset()
        for (i in 0..48) {
            val u = .48f - i * 1.1f / 48
            vertex(body, f, u, width(u), i == 0)
        }
        for (i in 48 downTo 0) {
            val u = .48f - i * 1.1f / 48
            vertex(body, f, u, -width(u))
        }
        body.close(); paint.color = color; paint.alpha = 255; canvas.drawPath(body, paint)
    }

    private fun drawHammerhead(canvas: Canvas, f: ShoalFish) {
        // Top-down cephalofoil: broad transverse head, swept fins and narrow tail stalk.
        for (side in -1..1 step 2) polygon(canvas, f, ink,
            .16f, side * .09f, -.19f, side * .34f, -.10f, side * .09f)
        polygon(canvas, f, ink, -.57f, .025f, -.90f, .25f, -.78f, 0f, -.90f, -.25f, -.57f, -.025f)
        outline(canvas, f, ink) { u -> halfWidth(u) * .78f }
        polygon(canvas, f, pearl, .46f, -.36f, .48f, -.28f, .39f, -.11f,
            .39f, .11f, .48f, .28f, .46f, .36f, .33f, .38f,
            .29f, .29f, .31f, .10f, .31f, -.10f, .29f, -.29f, .33f, -.38f)
        polygon(canvas, f, pearl, .18f, 0f, -.22f, .055f, -.10f, -.055f)
        paint.color = ShoalNight.toArgb()
        for (side in -1..1 step 2) {
            locate(f, .39f, side * .33f)
            canvas.drawCircle(point[0], point[1], .024f, paint)
        }
    }

    private fun drawDolphin(canvas: Canvas, f: ShoalFish) {
        // Rounded melon + long narrow rostrum, swept flippers and broad horizontal flukes.
        for (side in -1..1 step 2) polygon(canvas, f, ink,
            .14f, side * .09f, -.14f, side * .28f, -.08f, side * .10f)
        polygon(canvas, f, pearl, -.57f, .025f, -.77f, .29f, -.90f, .26f,
            -.78f, 0f, -.90f, -.26f, -.77f, -.29f, -.57f, -.025f)
        outline(canvas, f, pearl) { u ->
            if (u > .29f) .036f * ((.48f - u) / .06f).coerceIn(0f, 1f)
            else halfWidth(u) * .88f
        }
        polygon(canvas, f, ink, .05f, 0f, -.24f, .075f, -.13f, -.05f)
        paint.color = ink; paint.alpha = 255
        for (side in -1..1 step 2) {
            locate(f, .24f, side * .09f)
            canvas.drawCircle(point[0], point[1], .018f, paint)
        }
    }

    private fun drawFish(canvas: Canvas, f: ShoalFish) {
        val base = if (f.id % 4 == 2) gold else pearl
        paint.color = base; paint.alpha = 160
        // Pectoral fins pivot at the shoulders; the narrow peduncle joins the forked tail.
        for (side in -1..1 step 2) {
            part.reset(); vertex(part, f, .18f, side * .10f, true)
            vertex(part, f, -.02f, side * .29f)
            vertex(part, f, -.16f, side * .16f); vertex(part, f, .02f, side * .10f)
            part.close(); canvas.drawPath(part, paint)
        }
        part.reset(); vertex(part, f, -.55f, .025f, true)
        vertex(part, f, -.9f, .17f); vertex(part, f, -.81f, 0f)
        vertex(part, f, -.9f, -.17f); vertex(part, f, -.55f, -.025f)
        part.close(); canvas.drawPath(part, paint)
        body.reset()
        for (i in 0..36) {
            val u = .48f - i * 1.1f / 36
            vertex(body, f, u, halfWidth(u), i == 0)
        }
        for (i in 36 downTo 0) {
            val u = .48f - i * 1.1f / 36
            vertex(body, f, u, -halfWidth(u))
        }
        body.close(); paint.alpha = 255; canvas.drawPath(body, paint)
        canvas.save(); canvas.clipPath(body)
        if (f.id % 4 != 2) {
            val patches = if (f.id % 4 == 1) 4 else 3
            for (i in 0 until patches) {
                val u = if (patches == 4) .30f - i * .22f else .27f - i * .30f
                locate(f, u, sin(f.phase + i * 1.9f) * .055f)
                canvas.save(); canvas.translate(point[0], point[1]); canvas.rotate(point[2] * 180f / PI.toFloat())
                paint.color = if (f.id % 4 == 3 || (f.id % 4 == 1 && i % 2 == 1)) ink else coral
                paint.alpha = 255
                canvas.drawOval(-.10f, -.14f, .11f, .13f, paint); canvas.restore()
            }
        } else {
            paint.color = pearl; paint.alpha = 60
            for (i in 0..20) { locate(f, .32f - i * .038f, 0f); canvas.drawCircle(point[0], point[1], .025f, paint) }
        }
        canvas.restore()
        paint.color = ShoalNight.toArgb(); paint.alpha = 255
        for (side in -1..1 step 2) {
            locate(f, .32f, side * .075f)
            canvas.drawCircle(point[0], point[1], .016f, paint)
        }
    }
}
