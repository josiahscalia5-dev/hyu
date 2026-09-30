package com.islandblast.game

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.islandblast.game.model.Level4Event
import com.islandblast.game.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Launches the real app on a 1080x2400 phone, which opens on Level 4, and plays it
 * through Level4View with real MotionEvents and the real Choreographer frame loop:
 * drag to aim, release to fire, the pause button, Level Complete, then the tap that
 * moves on to Level 5. After every shot the HUD, the time bar and the screen are
 * checked against the rules.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class Level4AppTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level4-e2e").apply {
        deleteRecursively()
        mkdirs()
    }
    private var downTime = 0L

    private fun frames(n: Int) = repeat(n) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
        frames(1)
    }

    private fun shot(view: View, name: String): IntArray {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val px = IntArray(view.width * view.height)
        bmp.getPixels(px, 0, view.width, 0, 0, view.width, view.height)
        Png.write(File(out, "$name.png"), view.width, view.height, px)
        return px
    }

    @Test
    fun playLevel4ThroughTheUiThenMoveOnToLevel5() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val intent = Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val view = content.getChildAt(0) as Level4View
        if (view.width == 0) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 2400)
        }
        val game0 = view.game
        assertEquals("the app opens on Level 4 as painted", 28, game0.timerSeconds)
        assertEquals(1240, game0.score)
        shot(view, "00_open")
        frames(80)

        // Pause and resume with the pause button.
        val pause = view.game.spec.hud.pause
        val pp = view.stageToView(pause.cx, pause.cy)
        touch(view, MotionEvent.ACTION_DOWN, pp.x, pp.y)
        touch(view, MotionEvent.ACTION_UP, pp.x, pp.y)
        assertTrue("pause button pauses", view.game.paused)
        val tPaused = view.game.time
        frames(60)
        assertEquals("time stands still while paused", tPaused, view.game.time, 0f)
        shot(view, "01_paused")
        touch(view, MotionEvent.ACTION_DOWN, 540f, 1200f)
        touch(view, MotionEvent.ACTION_UP, 540f, 1200f)
        assertTrue("a tap resumes", !view.game.paused)

        // Play: drag to aim where the guide shows the most treasure, release to fire.
        var shots = 0
        while (!view.game.over && shots < 40) {
            val g = view.game
            view.clockHeld = true
            val aim = TestLevel4.bestAim(g) ?: error("nothing reachable, ${g.remaining} left")
            val predicted = g.aimPreview().hits
            view.clockHeld = false
            val p = view.stageToView(aim.x, aim.y)
            val start = view.stageToView(aim.x + 60f, aim.y + 80f)
            val before = g.score
            val comboBefore = g.combo
            val timeBefore = g.timeLeft
            touch(view, MotionEvent.ACTION_DOWN, start.x, start.y)
            for (k in 1..6) touch(view, MotionEvent.ACTION_MOVE, start.x + (p.x - start.x) * k / 6f, start.y + (p.y - start.y) * k / 6f)
            // The drag re-aims each frame; re-read what the guide promises at release.
            val promised = g.aimPreview().hits
            val hits = ArrayList<Int>()
            val events = ArrayList<Level4Event>()
            touch(view, MotionEvent.ACTION_UP, p.x, p.y)
            shots++
            var waited = 0
            while (g.phase != Phase.READY && !g.over && waited++ < 400) frames(1)
            // Level4View's effects consumed the events; check the outcome instead.
            val harvested = promised.filter { !g.treasures[it].alive }
            assertEquals("shot $shots harvests what the guide promised", promised, harvested)
            val expectedCombo = comboBefore + promised.size
            if (promised.isNotEmpty()) assertEquals("combo", expectedCombo, g.combo)
            var pts = 0
            var c = comboBefore
            for (i in promised) { c++; pts += g.rules.basePoints(g.treasures[i].spec) * c }
            val bonus = if (g.phase == Phase.WON) g.clearBonus else 0
            assertEquals("score after shot $shots", before + pts + bonus, g.score)
            assertTrue("time bar follows the timer", kotlin.math.abs(g.timeFraction - g.timeLeft / g.rules.barSeconds) < 1e-4f)
            println("shot %2d: harvested %d (%s), left %2d | combo x%d score %,d stars %d time %.1fs"
                .format(shots, promised.size, promised.joinToString { g.treasures[it].spec.id }, g.remaining, g.combo,
                    g.score, g.stars, g.timeLeft))
            frames(20)
            shot(view, "shot%02d".format(shots))
            // Harvested treasure is gone from the screen: its centre shows the lagoon, not its art.
            if (!g.over) {
                for (i in promised) {
                    val t = g.treasures[i].spec
                    val q = view.stageToView(t.cx, t.cy)
                    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bmp))
                    val onScreen = bmp.getPixel(q.x.toInt().coerceIn(0, view.width - 1), q.y.toInt().coerceIn(0, view.height - 1))
                    val art = TestLevel4.assets.targets[i]
                    val artPx = art.getPixel((t.cx - t.box.l).toInt().coerceIn(0, art.width - 1),
                        (t.cy - t.box.t).toInt().coerceIn(0, art.height - 1))
                    if (Color.alpha(artPx) > 200) {
                        val d = kotlin.math.abs(Color.red(onScreen) - Color.red(artPx)) +
                            kotlin.math.abs(Color.green(onScreen) - Color.green(artPx)) +
                            kotlin.math.abs(Color.blue(onScreen) - Color.blue(artPx))
                        assertTrue("${t.id} still drawn after its harvest", d > 12)
                    }
                }
            }
        }
        val g = view.game
        println("result: %s after %d shots, score %,d, %d stars, %.1fs left".format(g.phase, shots, g.score, g.stars, g.timeLeft))
        assertEquals("Level 4 complete", Phase.WON, g.phase)
        frames(60)
        shot(view, "zz_complete")

        // The Level Complete card moves on to Level 5.
        touch(view, MotionEvent.ACTION_DOWN, 540f, 1200f)
        touch(view, MotionEvent.ACTION_UP, 540f, 1200f)
        frames(3)
        val next = content.getChildAt(0)
        assertTrue("Level Complete leads on to Level 5, got $next", next is GameView)
        val l5 = next as GameView
        if (l5.width == 0) {
            l5.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            l5.layout(0, 0, 1080, 2400)
        }
        frames(10)
        assertEquals(31, l5.game.board.blocks.count { it.alive })
        shot(l5, "zz_level5")
    }
}
