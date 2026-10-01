package com.islandblast.game.level6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.islandblast.game.Png
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Plays Storm Dodge through the real StormView with the bot pressing the arrow
 * buttons, saving a frame in every section, on a hit, a shield, and the finish card.
 * Output: app/build/level6-shots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StormScreenshotTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level6-shots/run").apply {
        deleteRecursively(); mkdirs()
    }
    private val assets by lazy { StormAssets(RuntimeEnvironment.getApplication().assets) }

    private fun view(w: Int, h: Int, top: Int = 0): StormView {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = StormView(activity, assets)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.setSafeInsetsForTest(0, top, 0, 0)
        return v
    }

    private fun save(v: StormView, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        Png.write(File(out, "$name.png"), v.width, v.height, px)
    }

    private fun press(v: StormView, dir: Int) {
        val c = if (dir < 0) assets.spec.arrowLeft else assets.spec.arrowRight
        val p = v.stageToView(c[0], c[1])
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, p.x, p.y, 0)
        v.dispatchTouchEvent(down); down.recycle()
        val up = android.view.MotionEvent.obtain(0, 16, android.view.MotionEvent.ACTION_UP, p.x, p.y, 0)
        v.dispatchTouchEvent(up); up.recycle()
    }

    @Test
    fun playStormDodgeToTheFinish() {
        val v = view(1080, 2400, top = 110)
        save(v, "00_start")
        val shotsAt = mutableMapOf<String, Boolean>()
        var t = 0f
        var hitShot = false
        var shieldShot = false
        var lastSection = 0
        var crashed = false
        while (!v.game.over && t < 120f) {
            // One deliberate mistake in section 2, to show the collision.
            val g = v.game
            val target = if (!crashed && g.section == 1 && g.z > 230f)
                g.objects.firstOrNull { it.kind.hazard && !it.drift && it.z > g.z + 5f && it.z < g.z + 12f } else null
            if (target != null) {
                g.steerTo(target.x); crashed = true
                while (g.hits == 0 && !g.over) { v.step(1f / 60f); t += 1f / 60f }
                repeat(5) { v.step(1f / 60f) }
                save(v, "hit"); hitShot = true
            }
            // Go for the section 1 shield, to show it.
            val shield = g.objects.firstOrNull { it.kind == com.islandblast.game.level6.Kind.SHIELD && !g.isTaken(it) && it.section == 0 }
            val dir = if (shield != null && shield.z - g.z in 0f..14f && g.targetX != shield.x)
                (if (shield.x > g.targetX) 1 else -1) else StormBot.decide(v.game)
            if (dir != 0) press(v, dir)
            val hitsBefore = v.game.hits
            val shieldBefore = v.game.shielded
            v.step(1f / 60f)
            t += 1f / 60f
            if (v.game.section != lastSection) {
                lastSection = v.game.section
                repeat(30) { v.step(1f / 60f) }
                save(v, "%02d_section%d".format(lastSection, lastSection + 1))
            }
            if (!hitShot && v.game.hits > hitsBefore) {
                repeat(6) { v.step(1f / 60f) }
                save(v, "hit"); hitShot = true
            }
            if (!shieldShot && v.game.shielded && !shieldBefore) {
                repeat(10) { v.step(1f / 60f) }
                save(v, "shield"); shieldShot = true
            }
            if (t > 6f && "mid" !in shotsAt) { save(v, "01_section1_play"); shotsAt["mid"] = true }
        }
        repeat(120) { v.step(1f / 60f) }
        save(v, "99_finish")
        val g = v.game
        println("finished=${g.phase} time=${g.time} left=${g.timeLeft} coins=${g.coins}/${g.totalCoins} hits=${g.hits} shields=${g.shieldsUsed} score=${g.score} stars=${g.stars}")
        assertEquals(StormPhase.FINISHED, g.phase)
        assertTrue(g.coins > 0)
        assertTrue("the deliberate crash registered", g.hits >= 1)
        assertTrue("shield was collected", shieldShot)
    }
}
