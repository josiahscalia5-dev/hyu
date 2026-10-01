package com.islandblast.game.level6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.islandblast.game.LevelSelectView
import com.islandblast.game.Png
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Storm Dodge on real phone shapes: painted edge to edge, HUD and arrows inside the safe area. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StormFullScreenTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level6-shots/phones").apply { mkdirs() }
    private val assets by lazy { StormAssets(RuntimeEnvironment.getApplication().assets) }

    private class Phone(val name: String, val w: Int, val h: Int, val top: Int, val bottom: Int)

    private val phones = listOf(
        Phone("pixel7_20x9_punchhole", 1080, 2400, 118, 0),
        Phone("galaxy_19.5x9_notch", 1080, 2340, 110, 63),
        Phone("classic_16x9", 1080, 1920, 0, 0),
        Phone("tall_21x9_cutout", 1080, 2520, 130, 0),
        Phone("small_720x1600", 720, 1600, 72, 0),
    )

    private fun render(v: View, name: String): IntArray {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        Png.write(File(out, "$name.png"), v.width, v.height, px)
        return px
    }

    @Test
    fun fillsEveryPhoneWithHudInsideTheSafeArea() {
        for (p in phones) {
            val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            val v = StormView(activity, assets)
            v.measure(View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, p.w, p.h)
            v.setSafeInsetsForTest(0, p.top, 0, p.bottom)
            repeat(90) { v.step(1f / 60f) }
            val px = render(v, p.name)
            // Edge to edge: no black or flat rows/columns at any edge.
            val rows = mapOf(
                "top" to (0 until p.w).map { px[it] }, "bottom" to (0 until p.w).map { px[(p.h - 1) * p.w + it] },
                "left" to (0 until p.h).map { px[it * p.w] }, "right" to (0 until p.h).map { px[it * p.w + p.w - 1] },
            )
            for ((edge, line) in rows) {
                val black = line.count { Color.red(it) + Color.green(it) + Color.blue(it) < 24 }
                assertTrue("${p.name}: $edge edge has black pixels", black < line.size / 10)
            }
            // Top band must be real scenery, not a flat fill: rows near the top differ from each other.
            val r0 = (0 until p.w step 9).map { px[it] }
            val r1 = (0 until p.w step 9).map { px[(p.top / 2 + 20) * p.w + it] }
            assertTrue("${p.name}: flat band at the top", r0.zip(r1).count { (a, b) -> a != b } > r0.size / 4)
            // HUD and arrows inside the safe area.
            val safe = v.safeRect
            val s = assets.spec
            val hud = s.hudExtent
            val tl = v.stageToView(hud.l, hud.t)
            val br = v.stageToView(hud.r, hud.b)
            assertTrue("${p.name}: HUD $tl..$br outside safe $safe",
                tl.x >= safe.left - 0.5f && tl.y >= safe.top - 0.5f && br.x <= safe.right + 0.5f && br.y <= safe.bottom + 0.5f)
            assertTrue("${p.name}: game spans ${v.stageRect.width() / p.w} of the width", v.stageRect.width() >= 0.9f * p.w)
        }
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val sel = LevelSelectView(activity, RuntimeEnvironment.getApplication().assets) {}
        sel.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        sel.layout(0, 0, 1080, 2400)
        render(sel, "level_select")
    }
}
