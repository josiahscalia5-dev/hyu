package com.islandblast.game

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.islandblast.game.level6.StormAssets
import com.islandblast.game.level6.StormView
import com.islandblast.game.render.Assets
import com.islandblast.game.temple.TempleAssets
import com.islandblast.game.temple.TempleView

/**
 * Opens on the level select screen: Level 5 (Temple Chase) and Level 6 (Storm Dodge).
 * Back from a level returns to it. Launch straight into a level with the intent extra
 * "level" (5 or 6; [LEVEL_COLOR_SHIFT] opens the earlier Color Shift prototype, which is
 * no longer in the menu).
 */
class MainActivity : Activity() {
    private var level5: TempleView? = null
    private var level6: StormView? = null
    private var colorShift: GameView? = null
    private var current: View? = null
    private var loadRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Draw behind the camera cutout too; the game views keep the HUD clear of it.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        when (val level = intent?.getIntExtra(EXTRA_LEVEL, 0)) {
            5, 6, LEVEL_COLOR_SHIFT -> openLevel(level)
            else -> showLevelSelect()
        }
        hideSystemBars()
    }

    private fun showLevelSelect() {
        stopCurrent()
        loadRequest++
        show(LevelSelectView(this, assets) { openLevel(it) })
    }

    /**
     * Shows a loading screen, decodes the level's artwork on a background thread (never on
     * the UI thread: that froze the app long enough for an ANR), then opens the level.
     */
    private fun openLevel(level: Int) {
        stopCurrent()
        show(LoadingView(this, assets, level))
        val request = ++loadRequest
        Thread {
            val loaded: Any = try {
                when (level) {
                    5 -> TempleAssets(assets)
                    6 -> StormAssets(assets)
                    else -> Assets(assets)
                }
            } catch (e: Throwable) {
                // Never leave the player stuck on the loading screen: report and go back.
                android.util.Log.e("IslandBlast", "Loading level $level failed", e)
                runOnUiThread { if (request == loadRequest) showLevelSelect() }
                return@Thread
            }
            runOnUiThread {
                if (request != loadRequest || isFinishing) return@runOnUiThread
                val v: View = when (loaded) {
                    is TempleAssets -> TempleView(this, loaded).also { level5 = it; it.start() }
                    is StormAssets -> StormView(this, loaded).also { level6 = it; it.start() }
                    else -> GameView(this, loaded as Assets).also { colorShift = it; it.start() }
                }
                show(v)
            }
        }.apply { name = "load-level-$level" }.start()
    }

    private fun show(v: View) {
        current = v
        setContentView(v)
        v.requestApplyInsets()
    }

    private fun stopCurrent() {
        level5?.stop(); level5 = null
        level6?.stop(); level6 = null
        colorShift?.stop(); colorShift = null
    }

    @Deprecated("Back returns from a level to the level select screen")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (current is LevelSelectView) super.onBackPressed() else showLevelSelect()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        level5?.start()
        level6?.start()
        colorShift?.start()
    }

    override fun onPause() {
        level5?.apply { pauseGame(); stop() }
        level6?.apply { pauseGame(); stop() }
        colorShift?.apply { pauseGame(); stop() }
        super.onPause()
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    companion object {
        const val EXTRA_LEVEL = "level"
        /** The earlier Level 5 prototype (Color Shift), kept for its tests; not in the menu. */
        const val LEVEL_COLOR_SHIFT = 50
    }
}
