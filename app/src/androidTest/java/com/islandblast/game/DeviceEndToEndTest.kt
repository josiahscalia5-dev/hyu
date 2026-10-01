package com.islandblast.game

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.islandblast.game.e2e.Driver
import com.islandblast.game.e2e.Level5Checklist
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plays Level 5 on a real device/emulator: real frame loop and real time, touches
 * injected through the input system, and every screen check done on actual
 * screenshots from the display. Screenshots are saved to the app's external files
 * dir under e2e/ (adb pull /sdcard/Android/data/com.islandblast.game/files/e2e).
 *
 * The game redraws every frame, so its main thread is never idle: nothing here may
 * wait for idle (no ActivityScenario, no sendPointerSync).
 */
@RunWith(AndroidJUnit4::class)
class DeviceEndToEndTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var activity: Activity? = null

    @After
    fun finish() {
        activity?.let { a -> inst.runOnMainSync { a.finish() } }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun playLevel5OnDevice() {
        val target = inst.targetContext
        target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_LEVEL, MainActivity.LEVEL_COLOR_SHIFT))
        val deadline = SystemClock.uptimeMillis() + 120_000
        while (activity == null) {
            check(SystemClock.uptimeMillis() < deadline) { "MainActivity did not resume" }
            SystemClock.sleep(200)
            activity = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is MainActivity }
            }
        }
        // Level art loads on a background thread behind a loading screen; wait for the game.
        var gameView: GameView? = null
        while (gameView == null) {
            check(SystemClock.uptimeMillis() < deadline) { "level 5 did not finish loading" }
            SystemClock.sleep(200)
            gameView = onMain { activity!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? GameView }
        }
        val outDir = File(target.getExternalFilesDir(null), "e2e").apply {
            deleteRecursively()
            mkdirs()
        }
        val plate = target.assets.open("level5/background.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })!!
        }
        val slow = (InstrumentationRegistry.getArguments().getString("slow") ?: "20").toFloat()

        val driver = object : Driver {
            override val view = gameView!!
            override val background: Bitmap = plate
            private var downTime = 0L

            override fun <T> onMain(block: () -> T): T = this@DeviceEndToEndTest.onMain(block)

            override fun touch(action: Int, x: Float, y: Float) {
                val loc = onMain { IntArray(2).also { view.getLocationOnScreen(it) } }
                val now = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) downTime = now
                val e = MotionEvent.obtain(downTime, now, action, loc[0] + x, loc[1] + y, 0)
                e.source = InputDevice.SOURCE_TOUCHSCREEN
                check(inst.uiAutomation.injectInputEvent(e, true)) { "touch was not injected" }
                e.recycle()
            }

            override fun waitFor(what: String, timeoutS: Float, cond: () -> Boolean) {
                val until = SystemClock.uptimeMillis() + (timeoutS * slow * 1000).toLong()
                while (!onMain(cond)) {
                    if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
                    SystemClock.sleep(40)
                }
            }

            override fun screenshot(): Bitmap {
                var full = inst.uiAutomation.takeScreenshot()
                if (full.config != Bitmap.Config.ARGB_8888) full = full.copy(Bitmap.Config.ARGB_8888, false)
                val loc = onMain { IntArray(2).also { view.getLocationOnScreen(it) } }
                return Bitmap.createBitmap(full, loc[0], loc[1], view.width, view.height)
            }

            override fun save(bmp: Bitmap, name: String) {
                File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            override fun log(line: String) {
                Log.i("Level5E2E", line)
            }
        }
        val report = Level5Checklist(driver).run()
        File(outDir, "report.txt").writeText(report)
    }
}
