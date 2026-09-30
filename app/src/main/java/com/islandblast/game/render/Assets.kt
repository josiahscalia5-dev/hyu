package com.islandblast.game.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.islandblast.game.model.GameColor
import com.islandblast.game.model.LevelSpec

/** Bitmaps cut from the approved Level 5 reference (see tools/assets). */
class Assets(private val am: AssetManager) {
    val spec: LevelSpec = LevelSpec.parse(am.open("$DIR/level5.json").bufferedReader().use { it.readText() })

    private val opts = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /**
     * Art is authored in stage pixels and always drawn through the stage transform,
     * so it is marked density-free: Android must never rescale it for the screen.
     */
    private fun bmp(name: String): Bitmap =
        (am.open("$DIR/$name").use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Missing asset $name"))
            .apply { density = Bitmap.DENSITY_NONE }

    private fun perColor(prefix: String) = GameColor.entries.associateWith { bmp("${prefix}_${it.key}.png") }

    /** The temple with every moving part removed, at stage size. */
    val background = bmp("background.png")

    /** The same scene painted past every edge (spec.extMarginX/Y), so any phone shape is filled. */
    val backgroundExt = bmp("background_ext.png")

    /** Tiny copy of the extended scene: drawn stretched it is a soft blur for extreme aspect ratios. */
    val backgroundBlur: Bitmap = Bitmap.createScaledBitmap(
        Bitmap.createScaledBitmap(backgroundExt, 60, 106, true), 20, 35, true,
    )
    val blocksRef = bmp("blocks_ref.png")
    val blocks = perColor("blocks")
    val fxIntro = bmp("fx_intro.png")
    val fxIntroBurst = bmp("fx_intro_burst.png")
    val fxIntroBg = bmp("fx_intro_bg.png")
    val burst = perColor("burst")
    val shards: Map<GameColor, List<Bitmap>> = GameColor.entries.associateWith { c ->
        spec.shardSizes.indices.map { bmp("shard${it}_${c.key}.png") }
    }
    val ball = perColor("ball")
    val ballHalo = bmp("ball_halo.png")
    val chevron = perColor("chevron")
    val starGold = bmp("star_gold.png")
    val starEmpty = bmp("star_empty.png")

    val fredoka: Typeface = Typeface.createFromAsset(am, "fonts/Fredoka-Bold.ttf")
    val nunito: Typeface = Typeface.createFromAsset(am, "fonts/Nunito-Black.ttf")

    companion object {
        const val DIR = "level5"
    }
}
