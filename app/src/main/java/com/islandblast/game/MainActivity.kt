package com.islandblast.game

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.islandblast.game.render.Assets

class MainActivity : Activity() {
    private lateinit var view: GameView

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
        view = GameView(this, Assets(assets))
        setContentView(view)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        view.start()
    }

    override fun onPause() {
        view.pauseGame()
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
}
