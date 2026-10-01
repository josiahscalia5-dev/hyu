package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.islandblast.game.e2e.SliceBot
import com.islandblast.game.model.TargetKind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Level 4 as it plays on a 1080x2400 phone, through the real SliceView: the
 * opening (approved composition, start banner), a cut mid-air (two halves of the
 * treasure flying apart along the blade), treasure tossed out of the lagoon with the
 * blade's trail, a cracked chest, and time up. Frames go to app/build/level4-shots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SliceScreenshotTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level4-shots").apply { mkdirs() }

    private fun save(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        Png.write(File(out, "$name.png"), v.width, v.height, px)
    }

    @Test
    fun playsAndSlices() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = SliceView(activity, TestSlice.assets, TestSlice.rules, 4, "Mystic Harvest")
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2400)
        v.setSafeInsetsForTest(0, 118, 0, 0)
        save(v, "01_open")
        repeat(30) { v.stepForTest(1f / 60f) }
        save(v, "02_intro_banner")

        var ms = 1000L
        fun swipe(s: SliceBot.Swipe, stopAt: Int = 4) {
            val g = v.game
            g.touchDown(s.x0, s.y0, ms)
            for (i in 1..stopAt) {
                ms += 16
                g.touchMove(s.x0 + (s.x1 - s.x0) * i / 4f, s.y0 + (s.y1 - s.y0) * i / 4f, ms)
                v.stepForTest(1f / 60f)
            }
            if (stopAt == 4) g.touchUp()
        }

        // The first cut: treasure splits along the blade and flies apart.
        swipe(SliceBot.plan(v.game)!!)
        repeat(3) { v.stepForTest(1f / 60f) }
        assertTrue("cut treasure is drawn as flying halves", v.halvesInFlight >= 2)
        save(v, "03_first_cut")
        repeat(10) { v.stepForTest(1f / 60f) }
        save(v, "04_halves_apart")

        // Play on: a bot slices; capture tossed treasure with the blade mid-swipe.
        var shotToss = false
        var shotChest = false
        var t = 0f
        while (t < 30f) {
            val plan = SliceBot.plan(v.game)
            val g = v.game
            if (plan != null && !shotToss && g.clock > 6f && g.alive.count { !it.hovering } >= 3) {
                swipe(plan, stopAt = 3)
                save(v, "05_tossed_mid_swipe")
                ms += 16
                g.touchMove(plan.x1, plan.y1, ms)
                g.touchUp()
                shotToss = true
            } else if (plan != null) {
                swipe(plan)
            }
            if (!shotChest && g.alive.any { it.kind == TargetKind.CHEST && it.hp in 1 until g.rules.chestHits }) {
                v.stepForTest(1f / 60f)
                save(v, "06_chest_cracked")
                shotChest = true
            }
            repeat(12) { v.stepForTest(1f / 60f) }
            t += 16f / 60f
        }
        save(v, "07_mid_level")
        while (!v.game.over) v.stepForTest(1f / 60f)
        repeat(30) { v.stepForTest(1f / 60f) }
        save(v, "08_time_up")
        println("score %,d stars %d phase %s".format(v.game.score, v.game.stars, v.game.phase))
    }
}
