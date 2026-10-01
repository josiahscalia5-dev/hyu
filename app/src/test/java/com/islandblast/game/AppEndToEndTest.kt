package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import com.islandblast.game.e2e.Driver
import com.islandblast.game.e2e.Level5Checklist
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
 * Launches the real MainActivity on a 1080x2400 phone profile and plays Level 5
 * through its GameView with real MotionEvents and the real Choreographer frame loop
 * (on a simulated clock). Screens are rendered with Android's native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class AppEndToEndTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level5-e2e/robolectric").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun playLevel5ThroughTheUi() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val intent = android.content.Intent(org.robolectric.RuntimeEnvironment.getApplication(), MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LEVEL, 5)
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val gameView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as GameView
        if (gameView.width == 0) {
            gameView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
            )
            gameView.layout(0, 0, 1080, 2400)
        }
        assertTrue("view laid out", gameView.width > 0)

        val driver = object : Driver {
            override val view = gameView
            override val background: Bitmap = TestLevel.assets.background
            private var downTime = 0L

            override fun <T> onMain(block: () -> T): T = block()

            override fun touch(action: Int, x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
                view.dispatchTouchEvent(e)
                e.recycle()
                ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            }

            override fun waitFor(what: String, timeoutS: Float, cond: () -> Boolean) {
                var t = 0f
                while (!cond()) {
                    if (t > timeoutS) throw AssertionError("timed out waiting for $what")
                    ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
                    t += 0.016f
                }
            }

            override fun screenshot(): Bitmap {
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                return bmp
            }

            override fun save(bmp: Bitmap, name: String) {
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                Png.write(File(out, "$name.png"), bmp.width, bmp.height, px)
            }

            override fun log(line: String) = println(line)
        }
        val report = Level5Checklist(driver).run()
        File(out, "report.txt").writeText(report)
    }
}
