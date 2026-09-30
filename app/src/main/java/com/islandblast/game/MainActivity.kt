package com.islandblast.game

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.islandblast.game.render.Assets
import com.islandblast.game.render.Level4Assets

/**
 * Opens Level 4 "Mystic Harvest"; its Level Complete card leads on to Level 5.
 * Start with the extra [EXTRA_LEVEL] = 5 to open Level 5 directly.
 */
class MainActivity : Activity() {
    private lateinit var view: StageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Draw behind the camera cutout too; GameView keeps the HUD clear of it.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        showLevel(intent.getIntExtra(EXTRA_LEVEL, 4))
        hideSystemBars()
    }

    private fun showLevel(level: Int) {
        if (::view.isInitialized) view.stop()
        view = if (level == 5) {
            GameView(this, Assets(assets))
        } else {
            Level4View(this, Level4Assets(assets)).apply { onNextLevel = { showLevel(5) } }
        }
        setContentView(view)
        view.requestApplyInsets()
        view.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        view.start()
    }

    override fun onPause() {
        when (val v = view) {
            is GameView -> v.pauseGame()
            is Level4View -> v.pauseGame()
        }
        view.stop()
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
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    companion object {
        /** Which level to open (4 or 5); Level 4 by default. */
        const val EXTRA_LEVEL = "level"
    }
}
