package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.islandblast.game.e2e.FlowChecklist
import com.islandblast.game.e2e.FlowDriver
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
 * The whole app on a 1080x2400 phone, through the real MainActivity, real
 * MotionEvents and the real Choreographer frame loop (on a simulated clock):
 * Home → PLAY → World 1 → Level 4 → Level Complete → World 1 → Level 5 →
 * Level Complete → World 1 → Back → Home. Screens are saved to app/build/flow/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class GameFlowTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/flow").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun homeToWorldMapToLevel4ThenLevel5AndBack() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.flow.progress.clear()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        fun frame() = ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
        fun layout() {
            if (content.width == 0 || content.getChildAt(0).let { it.width == 0 }) {
                content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, 1080, 2400)
            }
        }
        layout()
        activity.flow.navigator.listeners += { frame(); layout() }

        val driver = object : FlowDriver {
            override val activity = activity
            override val level5Background: Bitmap = TestLevel.assets.background
            private var downTime = 0L

            override fun <T> onMain(block: () -> T): T = block()

            override fun touch(action: Int, x: Float, y: Float) {
                layout()
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
                content.dispatchTouchEvent(e)
                e.recycle()
                frame()
            }

            override fun waitFor(what: String, timeoutS: Float, cond: () -> Boolean) {
                var t = 0f
                while (!cond()) {
                    if (t > timeoutS) throw AssertionError("timed out waiting for $what")
                    frame()
                    layout()
                    t += 0.016f
                }
            }

            override fun idle(seconds: Float) {
                var t = 0f
                while (t < seconds) {
                    frame()
                    t += 0.016f
                }
            }

            override fun screenshot(): Bitmap {
                layout()
                val bmp = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                content.draw(Canvas(bmp))
                return bmp
            }

            override fun save(bmp: Bitmap, name: String) {
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                Png.write(File(out, "$name.png"), bmp.width, bmp.height, px)
            }

            override fun log(line: String) = println(line)
            override fun pressBack() {
                activity.back()
                frame()
            }
        }
        val report = FlowChecklist(driver).run()
        File(out, "report.txt").writeText(report)
    }
}
