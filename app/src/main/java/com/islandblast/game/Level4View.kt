package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import com.islandblast.game.model.Level4Game
import com.islandblast.game.model.Phase
import com.islandblast.game.render.Level4Assets
import com.islandblast.game.render.Level4Effects
import com.islandblast.game.render.Level4Renderer

/**
 * Hosts Level 4 "Mystic Harvest" full screen (see [StageView]). Drag anywhere to aim,
 * release to fire; the pause button pauses. On the end card a tap moves on to Level 5
 * after a win, or starts Level 4 again after time runs out.
 */
@SuppressLint("ViewConstructor")
class Level4View(context: Context, private val assets: Level4Assets) : StageView(context) {
    var game = Level4Game(assets.spec)
        private set
    private var effects = Level4Effects(assets)
    private val renderer = Level4Renderer(assets)

    /** Called when the player taps "Tap for Level 5" on the Level Complete card. */
    var onNextLevel: (() -> Unit)? = null

    override val stageW get() = assets.spec.stageW
    override val stageH get() = assets.spec.stageH
    override val hudExtent get() = assets.spec.hud.extent
    override val extMarginX get() = assets.spec.extMarginX
    override val extMarginY get() = assets.spec.extMarginY
    override val backgroundBlur get() = assets.backgroundBlur

    /** When the win/lose card appeared (uptime ms); taps act on it only after a short beat. */
    private var overSince = 0L

    /** The win/lose card is up and a tap will act on it. */
    val canContinue: Boolean
        get() = game.over && overSince != 0L && SystemClock.uptimeMillis() - overSince >= GUARD_MS

    /** True while harvest effects are still on screen. */
    val effectsBusy: Boolean get() = effects.busy

    private var aiming = false

    fun pauseGame() {
        if (!game.over) game.paused = true
        invalidate()
    }

    override fun onFrame(dt: Float, held: Boolean) {
        if (!held) game.update(dt)
        effects.consume(game)
        if (game.over && overSince == 0L) overSince = SystemClock.uptimeMillis()
    }

    override fun drawStage(canvas: Canvas) = renderer.draw(canvas, game, effects)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x - offX) / scale
        val y = (e.y - offY) / scale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                aiming = false
                if (game.over) {
                    if (canContinue) {
                        if (game.phase == Phase.WON && onNextLevel != null) onNextLevel?.invoke() else restart()
                    }
                    return true
                }
                if (game.paused) {
                    game.paused = false
                    return true
                }
                val p = assets.spec.hud.pause
                if (x in p.l - 10f..p.r + 10f && y in p.t - 10f..p.b + 10f) {
                    game.paused = true
                    return true
                }
                aiming = true
                game.aimAt(x, y)
            }
            MotionEvent.ACTION_MOVE -> if (aiming) game.aimAt(x, y)
            MotionEvent.ACTION_UP -> {
                if (aiming) game.shoot()
                aiming = false
            }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
        return true
    }

    private fun restart() {
        game = Level4Game(assets.spec)
        effects.clear()
        effects = Level4Effects(assets)
        renderer.reset()
        overSince = 0L
    }

    companion object {
        private const val GUARD_MS = 700L
    }
}
