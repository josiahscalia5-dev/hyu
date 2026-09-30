package com.islandblast.game.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import com.islandblast.game.model.Level4Spec

/** Bitmaps cut from the approved Level 4 screen (see tools/level4). */
class Level4Assets(private val am: AssetManager) {
    val spec: Level4Spec = Level4Spec.parse(am.open("$DIR/level4.json").bufferedReader().use { it.readText() })

    private val opts = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /** Art is authored in stage pixels and drawn through the stage transform: never rescaled by Android. */
    private fun bmp(name: String): Bitmap =
        (am.open("$DIR/$name").use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Missing asset $name"))
            .apply { density = Bitmap.DENSITY_NONE }

    /** The lagoon with every moving part removed, painted past each edge (spec.extMarginX/Y). */
    val backgroundExt = bmp("background_ext.png")

    /** Tiny copy of the extended scene: drawn stretched it is a soft blur for extreme aspect ratios. */
    val backgroundBlur: Bitmap = Bitmap.createScaledBitmap(
        Bitmap.createScaledBitmap(backgroundExt, 60, 115, true), 20, 38, true,
    )

    /** One sprite per target, in spec order. */
    val targets: List<Bitmap> = spec.targets.map { bmp("t_${it.id}.png") }

    /** Each target's own vivid colour, for its flash and sparks. */
    val glow: List<Int> = targets.map { dominantColor(it) }

    val launcher = bmp("launcher.png")
    val fxGlow = bmp("fx_glow.png")
    val fxDebris = bmp("fx_debris.png")
    val starGold = bmp("star_gold.png")
    val barFill = bmp("bar_fill.png")

    /** The HUD values exactly as painted, shown while the live values still equal them. */
    val painted: Map<String, Bitmap> = spec.hud.painted.keys.associateWith { bmp("painted_$it.png") }

    val fredoka: Typeface = Typeface.createFromAsset(am, "fonts/Fredoka-Bold.ttf")

    private fun dominantColor(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        val hsv = FloatArray(3)
        for (c in px) {
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.45f || hsv[2] < 0.45f) continue
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
        }
        if (n == 0L) return Color.WHITE
        val c = Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    companion object {
        const val DIR = "level4"
    }
}
