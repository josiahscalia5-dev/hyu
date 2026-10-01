package com.islandblast.game.app

import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** One full-screen page of the game: home, a world map, or a level. */
interface Screen {
    val view: View

    /** Shown (or the app came back): start animating. */
    fun start()

    /** Hidden (or the app went to the background): stop animating. */
    fun stop()

    /** The app is going to the background: pause gameplay. */
    fun onAppPause() = Unit

    /** The Android back button. Return false to let the app close. */
    fun onBack(): Boolean
}

/** Shows one [Screen] at a time inside [root]. */
class Navigator(private val root: FrameLayout) {
    var current: Screen? = null
        private set

    /** Listeners told about every screen change (tests use this). */
    val listeners = ArrayList<(Screen) -> Unit>()

    fun show(screen: Screen) {
        current?.let {
            it.stop()
            root.removeView(it.view)
        }
        current = screen
        root.addView(screen.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        screen.view.requestApplyInsets()
        screen.start()
        listeners.forEach { it(screen) }
    }
}

/** Calls [onFrame] once per display frame with the seconds since the last one. */
class FrameLoop(private val onFrame: (dt: Float) -> Unit) : Choreographer.FrameCallback {
    private var running = false
    private var last = 0L

    fun start() {
        if (running) return
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (last == 0L) 0f else ((frameTimeNanos - last) / 1e9f).coerceAtMost(1f / 30f)
        last = frameTimeNanos
        onFrame(dt)
        Choreographer.getInstance().postFrameCallback(this)
    }
}
