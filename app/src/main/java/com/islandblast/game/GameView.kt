package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import com.islandblast.game.model.Level5Game
import com.islandblast.game.render.Assets
import com.islandblast.game.render.Effects
import com.islandblast.game.render.Renderer
import kotlin.math.hypot

/**
 * Hosts Level 5 full screen, edge to edge (see [StageView]): whatever the stage does
 * not cover is filled by the temple painted past its edges (background_ext).
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val assets: Assets) : StageView(context) {
    var game = Level5Game(assets.spec)
        private set
    private var effects = Effects(assets)
    private val renderer = Renderer(assets)

    override val stageW get() = assets.spec.stageW
    override val stageH get() = assets.spec.stageH
    override val hudExtent get() = assets.spec.hud.extent
    override val extMarginX get() = assets.spec.extMarginX
    override val extMarginY get() = assets.spec.extMarginY
    override val backgroundBlur get() = assets.backgroundBlur

    /** When the win/lose card appeared (uptime ms); taps restart only after a short beat. */
    private var overSince = 0L

    /** The win/lose card is up and a tap will start the level again. */
    val canRestart: Boolean
        get() = game.over && overSince != 0L && SystemClock.uptimeMillis() - overSince >= RESTART_GUARD_MS

    /** True while hit flashes, shards or sparks are still on screen. */
    val effectsBusy: Boolean get() = effects.busy

    // Touch state: a short tap on the ball switches colour, any drag aims, release shoots.
    private var downX = 0f
    private var downY = 0f
    private var downOnBall = false
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
        val s = assets.spec
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                aiming = false
                if (game.over) {
                    if (canRestart) restart()
                    return true
                }
                if (game.paused) {
                    game.paused = false
                    return true
                }
                val p = s.hud.pause
                if (x in p.l - 10f..p.r + 10f && y in p.t - 10f..p.b + 10f) {
                    game.paused = true
                    return true
                }
                downOnBall = hypot(x - s.ballX, y - s.ballY) < s.ballRadius * 1.15f
                if (!downOnBall) {
                    aiming = true
                    game.aimAt(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (downOnBall && hypot(x - downX, y - downY) > 28f) {
                    downOnBall = false
                    aiming = true
                }
                if (aiming) game.aimAt(x, y)
            }
            MotionEvent.ACTION_UP -> {
                if (downOnBall) game.switchColor()
                else if (aiming) game.shoot()
                downOnBall = false
                aiming = false
            }
            MotionEvent.ACTION_CANCEL -> {
                downOnBall = false
                aiming = false
            }
        }
        return true
    }

    private fun restart() {
        game = Level5Game(assets.spec)
        effects.clear()
        effects = Effects(assets)
        renderer.reset()
        overSince = 0L
    }

    companion object {
        private const val RESTART_GUARD_MS = 700L
    }
}
