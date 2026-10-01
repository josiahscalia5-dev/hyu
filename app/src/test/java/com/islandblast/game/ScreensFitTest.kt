package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.islandblast.game.home.HomeAssets
import com.islandblast.game.home.HomeView
import com.islandblast.game.levels.Catalog
import com.islandblast.game.levels.Progress
import com.islandblast.game.map.MapArt
import com.islandblast.game.map.WorldCompleteView
import com.islandblast.game.map.WorldMapView
import com.islandblast.game.ui.UiKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File

/**
 * The home screen, the World 1 map and World 1 Complete on real phone shapes.
 * Everything that matters must be fully on screen and inside the safe area (camera
 * cutout, bars): on Home the logo, PLAY, the top bar and the bottom nav; on the map
 * every level button, the World 2 gate and the header; on World Complete both cards
 * and Continue. Edges must be painted scenery, not bars. Frames go to app/build/screens/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34])
class ScreensFitTest {
    init {
        // Screens animate every frame: frames must only come when a test asks for them.
        ShadowChoreographer.setPaused(true)
    }

    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/screens").apply { mkdirs() }

    private data class Phone(val name: String, val w: Int, val h: Int, val inset: IntArray, val density: Float)

    private val phones = listOf(
        Phone("pixel7_20x9_punchhole", 1080, 2400, intArrayOf(0, 118, 0, 0), 2.625f),
        Phone("galaxy_19.5x9_notch", 1080, 2340, intArrayOf(0, 110, 0, 63), 2.75f),
        Phone("classic_16x9", 1080, 1920, intArrayOf(0, 0, 0, 0), 2.625f),
        Phone("tall_21x9_cutout", 1080, 2520, intArrayOf(0, 130, 0, 0), 2.625f),
        Phone("small_720x1600", 720, 1600, intArrayOf(0, 72, 0, 0), 2f),
    )

    private fun render(v: View, p: Phone, name: String): IntArray {
        v.measure(View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, p.w, p.h)
        return draw(v, p, name)
    }

    private fun draw(v: View, p: Phone, name: String): IntArray {
        val bmp = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(p.w * p.h)
        bmp.getPixels(px, 0, p.w, 0, 0, p.w, p.h)
        Png.write(File(out, "$name.png"), p.w, p.h, px)
        return px
    }

    private fun inside(what: String, r: android.graphics.RectF, l: Float, t: Float, rr: Float, b: Float) =
        assertTrue("$what $r outside ($l, $t, $rr, $b)", r.left >= l - 0.5f && r.top >= t - 0.5f && r.right <= rr + 0.5f &&
            r.bottom <= b + 0.5f)

    @Test
    fun homeFitsEveryPhoneWithNothingCropped() {
        val report = StringBuilder()
        for (p in phones) {
            val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            activity.resources.displayMetrics.density = p.density
            val v = HomeView(activity, HomeAssets(activity.assets), UiKit(activity.assets)) {}
            v.measure(View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, p.w, p.h)
            v.setSafeInsetsForTest(p.inset[0], p.inset[1], p.inset[2], p.inset[3])
            v.loop.start()
            repeat(40) { v.loop.doFrame(1_000_000_000L + it * 16_000_000L) }
            v.loop.stop()
            val px = draw(v, p, "home_${p.name}")
            val l = p.inset[0].toFloat()
            val t = p.inset[1].toFloat()
            val r = (p.w - p.inset[2]).toFloat()
            val b = (p.h - p.inset[3]).toFloat()
            for ((name, box) in v.mustShow()) inside("${p.name}: home $name", box, l, t, r, b)
            edges(px, p.w, p.h, p.name)
            val must = v.mustShow()
            assertTrue("${p.name}: PLAY sits above the nav", must.getValue("play").bottom <= must.getValue("nav").top + 1f)
            assertTrue("${p.name}: the logo sits below the top bar", must.getValue("logo").top >= must.getValue("topBar").bottom - 40f)
            report.appendLine("%-22s bars x%.3f  logo/character/PLAY x%.3f  PLAY %s".format(p.name, v.barScale, v.heroScale,
                must.getValue("play").toShortString()))
        }
        println(report)
    }

    @Test
    fun worldMapFitsEveryPhoneWithEveryLevelOnScreen() {
        for (p in phones) {
            val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            activity.resources.displayMetrics.density = p.density
            val world = Catalog.load(activity.assets).worlds.first()
            val v = WorldMapView(activity, world, MapArt(activity.assets, world.id), UiKit(activity.assets),
                Progress(activity), null) {}
            v.measure(View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, p.w, p.h)
            v.setSafeInsetsForTest(p.inset[0], p.inset[1], p.inset[2], p.inset[3])
            v.loop.start()
            repeat(40) { v.loop.doFrame(1_000_000_000L + it * 16_000_000L) }
            v.loop.stop()
            val px = draw(v, p, "map_${p.name}")
            val r = v.nodeRadius()
            for (lv in world.levels) {
                val c = v.nodeCenter(lv)
                inside("${p.name}: level ${lv.number}", android.graphics.RectF(c.x - r * 1.2f, c.y - r * 1.7f, c.x + r * 1.2f,
                    c.y + r * 1.9f), p.inset[0].toFloat(), p.inset[1].toFloat(), (p.w - p.inset[2]).toFloat(),
                    (p.h - p.inset[3]).toFloat())
            }
            val g = v.gateCenter()!!
            inside("${p.name}: World 2 gate", android.graphics.RectF(g.x - r * 1.3f, g.y - r * 1.3f, g.x + r * 1.3f,
                g.y + r * 2.1f), p.inset[0].toFloat(), p.inset[1].toFloat(), (p.w - p.inset[2]).toFloat(),
                (p.h - p.inset[3]).toFloat())
            val back = v.backCenter()
            assertTrue("${p.name}: back button below the cutout", back.y - 60f >= p.inset[1])
            edges(px, p.w, p.h, p.name)
        }
    }

    @Test
    fun worldOneHasLevelsOneToSixEndingWithStormDodge() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val world = Catalog.load(activity.assets).worlds.first()
        assertEquals((1..6).toList(), world.levels.map { it.number })
        assertEquals(listOf("Relic Ricochet", "Totem Sequence", "Coral Current", "Mystic Harvest", "Temple Chase", "Storm Dodge"),
            world.levels.map { it.name })
        assertEquals(listOf(null, null, null, "treasure-slice", "color-shift", "storm-dodge"), world.levels.map { it.kind })
        assertEquals(listOf(4, 5, 6), world.levels.filter { it.playable }.map { it.number })
        assertEquals(world.level(6), world.lastLevel)
        assertEquals(2, world.gate!!.to)
        val progress = Progress(activity).apply { clear() }
        assertTrue("World 2 starts locked", !progress.worldUnlocked(2) && !progress.worldComplete(world))
        progress.completeWorld(world)
        assertTrue("completing World 1 unlocks World 2", progress.worldUnlocked(2) && progress.worldComplete(world))
        progress.clear()
    }

    @Test
    fun worldCompleteFitsEveryPhone() {
        for (p in phones) {
            val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            activity.resources.displayMetrics.density = p.density
            val world = Catalog.load(activity.assets).worlds.first()
            val progress = Progress(activity)
            val v = WorldCompleteView(activity, world, UiKit(activity.assets), progress, MapArt(activity.assets, world.id)) {}
            v.measure(View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, p.w, p.h)
            v.setSafeInsetsForTest(p.inset[0], p.inset[1], p.inset[2], p.inset[3])
            v.loop.start()
            repeat(200) { v.loop.doFrame(1_000_000_000L + it * 16_000_000L) }
            v.loop.stop()
            assertTrue("${p.name}: World 2 card and Continue are up", v.unlockShown && v.ready)
            val px = draw(v, p, "world_complete_${p.name}")
            for ((name, box) in v.mustShow()) {
                inside("${p.name}: World Complete $name", box, p.inset[0].toFloat(), p.inset[1].toFloat(),
                    (p.w - p.inset[2]).toFloat(), (p.h - p.inset[3]).toFloat())
            }
            edges(px, p.w, p.h, p.name)
        }
    }

    private fun edges(px: IntArray, w: Int, h: Int, name: String) {
        val sides = mapOf(
            "top" to (0 until w).map { px[it] }, "bottom" to (0 until w).map { px[(h - 1) * w + it] },
            "left" to (0 until h).map { px[it * w] }, "right" to (0 until h).map { px[it * w + w - 1] },
        )
        for ((edge, pixels) in sides) {
            val dark = pixels.count { Color.red(it) + Color.green(it) + Color.blue(it) < 24 }
            assertTrue("$name: $edge edge has ${dark * 100 / pixels.size}% black pixels", dark < pixels.size / 3)
        }
    }
}
