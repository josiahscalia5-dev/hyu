package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.islandblast.game.model.Box
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Level 5 on real phone shapes, through the real GameView. For each screen it checks:
 *  - full screen: every edge row/column is painted scenery, no black or flat bars;
 *  - safe area: every HUD element sits inside the display minus the cutout/bars;
 *  - no overlap: Goal board and combo stay clear of the block formation;
 *  - legibility: blocks and ball are big enough on the phone to see and hit.
 * Frames are saved to app/build/level5-shots/phones/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FullScreenTest {
    private val assets get() = TestLevel.assets
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level5-shots/phones").apply { mkdirs() }

    /** name, width, height, safe insets (left, top, right, bottom) in px, density. */
    private data class Phone(val name: String, val w: Int, val h: Int, val inset: IntArray, val density: Float)

    private val phones = listOf(
        Phone("pixel7_20x9_punchhole", 1080, 2400, intArrayOf(0, 118, 0, 0), 2.625f),
        Phone("galaxy_19.5x9_notch", 1080, 2340, intArrayOf(0, 110, 0, 63), 2.75f),
        Phone("classic_16x9", 1080, 1920, intArrayOf(0, 0, 0, 0), 2.625f),
        Phone("tall_21x9_cutout", 1080, 2520, intArrayOf(0, 130, 0, 0), 2.625f),
        Phone("small_720x1600", 720, 1600, intArrayOf(0, 72, 0, 0), 2f),
    )

    @Test
    fun fillsEveryPhoneShapeWithHudInsideTheSafeArea() {
        val report = StringBuilder()
        for (p in phones) {
            val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
            activity.resources.displayMetrics.density = p.density
            val view = GameView(activity, assets)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(p.w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(p.h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, p.w, p.h)
            view.setSafeInsetsForTest(p.inset[0], p.inset[1], p.inset[2], p.inset[3])
            val bmp = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            val px = IntArray(p.w * p.h)
            bmp.getPixels(px, 0, p.w, 0, 0, p.w, p.h)
            Png.write(File(out, "${p.name}.png"), p.w, p.h, px)

            // Full screen: edges carry real scenery (varied colour), never a flat/black bar.
            for ((edge, pixels) in edges(px, p.w, p.h)) {
                val dark = pixels.count { Color.red(it) + Color.green(it) + Color.blue(it) < 24 }
                assertTrue("${p.name}: $edge edge has ${dark * 100 / pixels.size}% black pixels",
                    dark < pixels.size / 10)
                assertTrue("${p.name}: $edge edge looks like a flat bar", spread(pixels) > 40)
            }

            // Safe area: every HUD element inside it.
            val safe = view.safeRect
            val hud = assets.spec.hud
            val items = mapOf(
                "pause" to hud.pause, "sign+stars" to Box(290f, 138f, 549f, 300f), "timer" to Box(637f, 150f, 828f, 238f),
                "goal" to Box(15f, 297f, 235f, 560f), "combo" to hud.comboBox,
                "score" to Box(20f, 1602f, 232f, 1740f), "coins" to Box(606f, 1618f, 818f, 1708f),
            )
            for ((name, b) in items) {
                val tl = view.stageToView(b.l, b.t)
                val br = view.stageToView(b.r, b.b)
                assertTrue("${p.name}: $name ($tl..$br) outside safe area $safe",
                    tl.x >= safe.left - 0.5f && tl.y >= safe.top - 0.5f && br.x <= safe.right + 0.5f && br.y <= safe.bottom + 0.5f)
            }

            // No overlap between HUD panels and the block formation.
            val blocks = assets.spec.blocks.map { it.rect }
            for (name in listOf("goal", "combo")) {
                val b = items.getValue(name)
                for (r in blocks) {
                    val ox = min(b.r, r.r) - max(b.l, r.l)
                    val oy = min(b.b, r.b) - max(b.t, r.t)
                    assertTrue("${p.name}: $name overlaps a block", ox <= 0f || oy <= 0f)
                }
            }

            // Legible and touchable: blocks at least ~7 mm, ball at least ~11 mm wide.
            val scale = view.stageRect.width() / assets.spec.stageW
            val blockMm = blocks.minOf { it.w } * scale / (p.density * 160f) * 25.4f
            val ballMm = 2 * assets.spec.ballRadius * scale / (p.density * 160f) * 25.4f
            val cover = view.stageRect.let { it.left <= 0.5f && it.top <= 0.5f && it.right >= p.w - 0.5f && it.bottom >= p.h - 0.5f }
            report.appendLine("%-22s scale %.3f  stage %s  smallest block %.1f mm  ball %.1f mm  stage covers screen: %s"
                .format(p.name, scale, view.stageRect.toShortString(), blockMm, ballMm, cover))
            assertTrue("${p.name}: blocks too small ($blockMm mm)", blockMm >= 6.5f)
            assertTrue("${p.name}: ball too small ($ballMm mm)", ballMm >= 10f)
        }
        println(report)
        File(out, "report.txt").writeText(report.toString())
    }

    private fun edges(px: IntArray, w: Int, h: Int): Map<String, List<Int>> = mapOf(
        "top" to (0 until w).map { px[it] },
        "bottom" to (0 until w).map { px[(h - 1) * w + it] },
        "left" to (0 until h).map { px[it * w] },
        "right" to (0 until h).map { px[it * w + w - 1] },
    )

    private fun spread(p: List<Int>): Int {
        val lum = p.map { (Color.red(it) * 299 + Color.green(it) * 587 + Color.blue(it) * 114) / 1000 }.sorted()
        return lum[lum.size * 95 / 100] - lum[lum.size * 5 / 100]
    }
}
