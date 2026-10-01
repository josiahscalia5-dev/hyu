package com.islandblast.game.temple

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.islandblast.game.LevelSelectView
import com.islandblast.game.MainActivity
import com.islandblast.game.Png
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
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The real app on a 1080x2400 phone: the level select's Level 5 card opens Temple Chase,
 * and it plays with real taps on the arrow buttons and the real frame loop.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class TempleAppTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/temple-shots/app").apply { deleteRecursively(); mkdirs() }

    private fun frame(ms: Long = 16) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    private fun layout(v: View) {
        if (v.width == 0) {
            v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1080, 2400)
        }
    }

    private fun save(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        Png.write(File(out, "$name.png"), v.width, v.height, px)
    }

    private fun tap(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        v.dispatchTouchEvent(down); down.recycle()
        frame()
        val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        v.dispatchTouchEvent(up); up.recycle()
        frame()
    }

    @Test
    fun levelFiveCardOpensTempleChase() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val select = content.getChildAt(0)
        assertTrue("opens on the level select", select is LevelSelectView)
        layout(select)
        select.invalidate(); frame()
        save(select, "1_level_select")
        // The first card is Level 5. Tap it.
        tap(select, 540f, 2400 * 0.34f + 2400 * 0.12f)
        val deadline = System.currentTimeMillis() + 180_000
        while (content.getChildAt(0) !is TempleView) {
            check(System.currentTimeMillis() < deadline) { "Temple Chase did not open" }
            Thread.sleep(20)
            frame()
        }
        val v = content.getChildAt(0) as TempleView
        layout(v)
        save(v, "2_temple_chase_opening")
        assertEquals(0f, v.game.dist, 0f)
        // Run: the real frame loop moves the runner forward; the right arrow moves a lane.
        repeat(120) { frame() }
        assertTrue("running forward", v.game.dist > 3f)
        val c = v.stageToView(797f, 1514f) // the right arrow button in the approved screen
        tap(v, c.x, c.y)
        repeat(30) { frame() }
        assertTrue("right arrow moved the runner a lane", v.game.x > 0.9f)
        save(v, "3_playing")
        // Back returns to the level select.
        @Suppress("DEPRECATION")
        activity.onBackPressed()
        frame()
        assertTrue(content.getChildAt(0) is LevelSelectView)
    }
}
