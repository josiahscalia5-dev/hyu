package com.islandblast.game.temple

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
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
 * Plays Temple Chase through the real TempleView on a 1080x2400 phone, with real touches:
 * the bot taps the arrow buttons, and taps the power-up buttons. Saves the opening frame,
 * every section, a hit, each power-up, and the end cards. Output: app/build/temple-shots/run
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TempleScreenshotTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/temple-shots/run").apply {
        deleteRecursively(); mkdirs()
    }
    private val assets by lazy { TempleAssets(RuntimeEnvironment.getApplication().assets) }

    private fun view(w: Int, h: Int, top: Int = 0): TempleView {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = TempleView(activity, assets)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        v.setSafeInsetsForTest(0, top, 0, 0)
        return v
    }

    private fun save(v: TempleView, name: String, dir: File = out) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        Png.write(File(dir, "$name.png"), v.width, v.height, px)
    }

    private fun tap(v: TempleView, sx: Float, sy: Float) {
        val p = v.stageToView(sx, sy)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, p.x, p.y, 0)
        v.dispatchTouchEvent(down); down.recycle()
        val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, p.x, p.y, 0)
        v.dispatchTouchEvent(up); up.recycle()
    }

    private fun press(v: TempleView, dir: Int) {
        val c = if (dir < 0) assets.spec.arrowLeft else assets.spec.arrowRight
        tap(v, c[0], c[1])
    }

    private fun usePower(v: TempleView, p: Power) {
        val d = assets.spec.powerDiscs[p.ordinal]
        tap(v, d[0], d[1])
    }

    private fun steps(v: TempleView, n: Int) = repeat(n) { v.step(1f / 60f) }

    @Test
    fun playTempleChaseToTheEnd() {
        val v = view(1080, 2400, top = 110)
        save(v, "00_opening")
        steps(v, 100)
        save(v, "01_go")
        var t = 0f
        var lastSection = 0
        var crashed = false
        var hitShot = false
        val powerShots = HashSet<Power>()
        while (!v.game.over && t < 150f) {
            val g = v.game
            // One deliberate mistake in section 2, to show a hit.
            if (!crashed && g.section == 1 && g.dist > 200f) {
                val target = g.objects.firstOrNull { it.kind.hazard && !it.decor && it.z > g.dist + 4f && it.z < g.dist + 10f }
                if (target != null) {
                    g.steerTo(target.x); crashed = true
                    while (g.hits == 0 && !g.over) { v.step(1f / 60f); t += 1f / 60f }
                    steps(v, 6)
                    save(v, "hit"); hitShot = true
                }
            }
            // Use each power-up once, with a real tap on its button.
            val want = when {
                g.section == 1 && g.dist > 160f -> Power.MAGNET
                g.section == 2 && g.dist > 330f -> Power.SHIELD
                g.section == 3 && g.dist > 500f -> Power.LIGHTNING
                else -> null
            }
            if (want != null && want !in powerShots && !g.active(want)) {
                val before = g.powers[want.ordinal]
                usePower(v, want)
                assertEquals("tap on the $want button used it", before - 1, g.powers[want.ordinal])
                steps(v, 24)
                save(v, "power_${want.name.lowercase()}")
                powerShots += want
            }
            val dir = TempleBot.decide(g)
            if (dir != 0) press(v, dir)
            v.step(1f / 60f)
            t += 1f / 60f
            if (v.game.section != lastSection) {
                lastSection = v.game.section
                steps(v, 30)
                save(v, "%02d_section%d".format(lastSection + 1, lastSection + 1))
            }
        }
        steps(v, 150)
        save(v, "99_complete")
        val g = v.game
        println("temple: phase=${g.phase} time=${g.time} left=${g.timeLeft} coins=${g.coinsTaken}/${g.totalCoins} gems=${g.gemsTaken} hits=${g.hits} hearts=${g.hearts} score=${g.score} stars=${g.stars}")
        assertEquals(TemplePhase.COMPLETE, g.phase)
        assertTrue("the deliberate crash registered", hitShot && g.hits == 1)
        assertEquals(setOf(Power.LIGHTNING, Power.SHIELD, Power.MAGNET), powerShots)
        // Tap on the end card runs again.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1000))
        tap(v, 470f, 900f)
        assertEquals(TemplePhase.RUNNING, v.game.phase)
        assertEquals(0f, v.game.dist, 0f)
    }

    @Test
    fun caughtShowsTheTryAgainCard() {
        val v = view(1080, 2400, top = 110)
        steps(v, 90)
        var t = 0f
        while (!v.game.over && t < 60f) {
            val g = v.game
            val next = g.objects.firstOrNull { it.kind.hazard && !it.decor && !g.isTaken(it) && it.z > g.dist + 0.6f }
            if (next != null) g.steerTo(next.x)
            v.step(1f / 60f); t += 1f / 60f
        }
        steps(v, 150)
        save(v, "caught", File(out.parentFile, "caught").apply { mkdirs() })
        assertEquals(TemplePhase.CAUGHT, v.game.phase)
    }
}
