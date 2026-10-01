package com.islandblast.game.home

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.islandblast.game.model.Box
import org.json.JSONArray
import org.json.JSONObject

/** Where the home screen's parts sit in the approved art (tools/home writes home.json). */
class HomeSpec(
    val artW: Float,
    val artH: Float,
    val extLeft: Float,
    val extTop: Float,
    val extRight: Float,
    val extBottom: Float,
    val topBar: Box,
    val topItems: Map<String, Box>,
    val play: Box,
    /** The button's own outline inside [play]: the part that takes taps. */
    val playShape: Box,
    val nav: Box,
    val navTiles: Map<String, Box>,
    val logo: Box,
    /** The logo, character and PLAY: always on screen, uncropped. */
    val hero: Box,
) {
    /** The top bar's visible items (excluding the transparent margin of its sprite). */
    val topItemsExtent: Box = topItems.values.reduce { a, b ->
        Box(minOf(a.l, b.l), minOf(a.t, b.t), maxOf(a.r, b.r), maxOf(a.b, b.b))
    }

    /** Top of the nav tiles. */
    val navTilesTop: Float = navTiles.values.minOf { it.t }

    companion object {
        fun parse(json: String): HomeSpec {
            val o = JSONObject(json)
            val art = o.getJSONArray("art")
            val ext = o.getJSONObject("backgroundExt")
            val top = o.getJSONObject("topBar")
            val play = o.getJSONObject("play")
            val nav = o.getJSONObject("nav")
            return HomeSpec(
                artW = art.f(0), artH = art.f(1),
                extLeft = ext.f("left"), extTop = ext.f("top"), extRight = ext.f("right"), extBottom = ext.f("bottom"),
                topBar = top.getJSONArray("box").box(),
                topItems = top.getJSONObject("items").boxes(),
                play = play.getJSONArray("box").box(),
                playShape = play.getJSONArray("shape").box(),
                nav = nav.getJSONArray("box").box(),
                navTiles = nav.getJSONObject("tiles").boxes(),
                logo = o.getJSONObject("logo").getJSONArray("box").box(),
                hero = o.getJSONArray("hero").box(),
            )
        }

        private fun JSONArray.f(i: Int) = getDouble(i).toFloat()
        private fun JSONObject.f(k: String) = getDouble(k).toFloat()
        private fun JSONArray.box() = Box(f(0), f(1), f(2), f(3))
        private fun JSONObject.boxes() = keys().asSequence().associateWith { getJSONArray(it).box() }
    }
}

/** The home screen's art, cut from the approved home screen (see tools/home). */
class HomeAssets(am: AssetManager) {
    val spec = HomeSpec.parse(am.open("$DIR/home.json").bufferedReader().use { it.readText() })

    private val opts = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private fun bmp(am: AssetManager, name: String): Bitmap =
        (am.open("$DIR/$name").use { BitmapFactory.decodeStream(it, null, opts) } ?: error("Missing asset $name"))
            .apply { density = Bitmap.DENSITY_NONE }

    /** The scene behind everything, painted past each edge (spec.ext*). Logo and character are in it. */
    val background = bmp(am, "background_ext.png")
    val backgroundBlur: Bitmap = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(background, 60, 120, true), 20, 40, true)
    val topBar = bmp(am, "topbar.png")
    val play = bmp(am, "play.png")
    val nav = bmp(am, "nav.png")

    /** The logo's shape (white, alpha = coverage), for the light sweeping across it. */
    val logoMask = bmp(am, "logo_mask.png")

    companion object {
        const val DIR = "home"
    }
}
