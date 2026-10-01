package com.islandblast.game

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.islandblast.game.e2e.FlowChecklist
import com.islandblast.game.e2e.FlowDriver
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole game on a real device/emulator: launched like a player launches it, real
 * frame loop and real time, touches injected through the input system, every screen
 * checked on real screenshots of the display:
 * Home → PLAY → World 1 → Level 5 → Level Complete → World 1 → Level 6 →
 * Level Complete → World 1 Complete → World 2 Unlocked → World 1 → Back → Home.
 * Screenshots and the report go to the app's external files dir under e2e/
 * (adb pull /sdcard/Android/data/com.islandblast.game/files/e2e).
 *
 * The game redraws every frame, so its main thread is never idle: nothing here may
 * wait for idle (no ActivityScenario, no sendPointerSync).
 */
@RunWith(AndroidJUnit4::class)
class DeviceEndToEndTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var launched: MainActivity? = null

    @After
    fun finish() {
        launched?.let { a -> inst.runOnMainSync { a.finish() } }
    }

    private fun <T> onMain(block: () -> T): T {
        // Re-entrant: checks run on the main thread may read state through onMain too.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun playTheGameOnDevice() {
        val target = inst.targetContext
        target.getSharedPreferences(com.islandblast.game.levels.Progress.PREFS, 0).edit().clear().commit()
        target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = SystemClock.uptimeMillis() + 180_000
        while (launched == null) {
            check(SystemClock.uptimeMillis() < deadline) { "MainActivity did not resume" }
            SystemClock.sleep(200)
            launched = onMain {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is MainActivity } as MainActivity?
            }
        }
        val app = launched!!
        val outDir = File(target.getExternalFilesDir(null), "e2e").apply {
            deleteRecursively()
            mkdirs()
        }
        val plate = target.assets.open("level5/background.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })!!
        }
        val slow = (InstrumentationRegistry.getArguments().getString("slow") ?: "20").toFloat()
        val content: View = onMain { app.findViewById<ViewGroup>(android.R.id.content) }

        val driver = object : FlowDriver {
            override val activity = app
            override val level5Background: Bitmap = plate
            private var downTime = 0L

            override fun <T> onMain(block: () -> T): T = this@DeviceEndToEndTest.onMain(block)

            override fun touch(action: Int, x: Float, y: Float) {
                val loc = onMain { IntArray(2).also { content.getLocationOnScreen(it) } }
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

            override fun idle(seconds: Float) = SystemClock.sleep((seconds * 1000).toLong())

            override fun screenshot(): Bitmap {
                var full = inst.uiAutomation.takeScreenshot()
                if (full.config != Bitmap.Config.ARGB_8888) full = full.copy(Bitmap.Config.ARGB_8888, false)
                val loc = onMain { IntArray(2).also { content.getLocationOnScreen(it) } }
                return Bitmap.createBitmap(full, loc[0], loc[1], content.width, content.height)
            }

            override fun save(bmp: Bitmap, name: String) {
                File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            override fun log(line: String) {
                Log.i("IslandBlastE2E", line)
            }

            override fun pressBack() = onMain { app.back() }
        }
        val report = FlowChecklist(driver).run()
        File(outDir, "report.txt").writeText(report)
    }
}
