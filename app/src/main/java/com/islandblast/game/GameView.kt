package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import com.islandblast.game.levels.LevelPlay
import com.islandblast.game.levels.PlayState
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.Phase
import com.islandblast.game.render.Assets
import com.islandblast.game.render.Effects
import com.islandblast.game.render.Renderer
import kotlin.math.hypot

/**
 * Hosts a colour-shift level (Level 5) full screen, edge to edge (see [StageView]):
 * whatever the stage does not cover is filled by the temple painted past its edges
 * (background_ext). The level host draws the pause menu and result card over it.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val assets: Assets) : StageView(context), LevelPlay {
    var game = Level5Game(assets.spec)
        private set
    private var effects = Effects(assets)
    private val renderer = Renderer(assets).apply { overlays = false }

    override val stageW get() = assets.spec.stageW
    override val stageH get() = assets.spec.stageH
    override val hudExtent get() = assets.spec.hud.extent
    override val extMarginX get() = assets.spec.extMarginX
    override val extMarginY get() = assets.spec.extMarginY
    override val backgroundBlur get() = assets.backgroundBlur

    /** True while hit flashes, shards or sparks are still on screen. */
    val effectsBusy: Boolean get() = effects.busy

    // Touch state: a short tap on the ball switches colour, any drag aims, release shoots.
    private var downX = 0f
    private var downY = 0f
    private var downOnBall = false
    private var aiming = false

    // ---- LevelPlay ----------------------------------------------------------------

    override val view: StageView get() = this
    override val state: PlayState
        get() = when (game.phase) {
            Phase.WON -> PlayState.WON
            Phase.LOST -> PlayState.LOST
            else -> PlayState.PLAYING
        }
    override val score: Int get() = game.score
    override val stars: Int get() = game.stars
    override val goal: String get() = "clear every block"
    override var paused: Boolean
        get() = game.paused
        set(v) {
            if (v && game.over) return
            if (v) aiming = false
            game.paused = v
            invalidate()
        }
    override val settling: Boolean get() = effects.busy

    override fun onFrame(dt: Float, held: Boolean) {
        if (!held) game.update(dt)
        effects.consume(game)
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
                if (game.over || game.paused) return true
                val p = s.hud.pause
                if (x in p.l - 10f..p.r + 10f && y in p.t - 10f..p.b + 10f) {
                    paused = true
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

    override fun restart() {
        game = Level5Game(assets.spec)
        effects.clear()
        effects = Effects(assets)
        renderer.reset()
        downOnBall = false
        aiming = false
    }
}
