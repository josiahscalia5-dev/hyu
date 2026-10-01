package com.islandblast.game

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import com.islandblast.game.app.GameFlow
import com.islandblast.game.app.Navigator

/**
 * The one activity: Home → World 1 map → a level → back to the map, all as screens
 * inside it (see [GameFlow]). Start with [EXTRA_LEVEL] (a World 1 level number) to
 * open that level directly.
 */
class MainActivity : Activity() {
    lateinit var flow: GameFlow
        private set
    private lateinit var navigator: Navigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            // Draw behind the camera cutout too; every screen keeps its UI clear of it.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        val root = FrameLayout(this)
        setContentView(root)
        navigator = Navigator(root)
        flow = GameFlow(this, navigator)
        val direct = intent.getIntExtra(EXTRA_LEVEL, 0)
        val level = flow.catalog.worlds.first().level(direct)
        if (level != null && level.playable) flow.openLevel(level) else flow.openHome()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        hideSystemBars()
    }

    /** The Android back button: each screen decides (level → pause, map → home, home → exit). */
    fun back() {
        if (navigator.current?.onBack() != true) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        navigator.current?.start()
    }

    override fun onPause() {
        navigator.current?.let {
            it.onAppPause()
            it.stop()
        }
        super.onPause()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
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
        /** Open this World 1 level directly instead of the home screen (testing). */
        const val EXTRA_LEVEL = "level"
    }
}
