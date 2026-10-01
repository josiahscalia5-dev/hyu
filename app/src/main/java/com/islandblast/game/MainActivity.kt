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

/**
 * Opens on the level select screen. Back from a level returns to it.
 * Launch straight into a level with the intent extra "level" (5 or 6).
 */
class MainActivity : Activity() {
    private var level5: GameView? = null
    private var level6: StormView? = null
    private var current: View? = null

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
        when (intent?.getIntExtra(EXTRA_LEVEL, 0)) {
            5 -> openLevel(5)
            6 -> openLevel(6)
            else -> showLevelSelect()
        }
        hideSystemBars()
    }

    private fun showLevelSelect() {
        stopCurrent()
        show(LevelSelectView(this, assets) { openLevel(it) })
    }

    private fun openLevel(level: Int) {
        stopCurrent()
        val v: View = if (level == 6) {
            StormView(this, StormAssets(assets)).also { level6 = it; it.start() }
        } else {
            GameView(this, Assets(assets)).also { level5 = it; it.start() }
        }
        show(v)
    }

    private fun show(v: View) {
        current = v
        setContentView(v)
        v.requestApplyInsets()
    }

    private fun stopCurrent() {
        level5?.stop(); level5 = null
        level6?.stop(); level6 = null
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
    }

    override fun onPause() {
        level5?.apply { pauseGame(); stop() }
        level6?.apply { pauseGame(); stop() }
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
    }
}
