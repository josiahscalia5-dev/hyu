package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import com.islandblast.game.levels.LevelPlay
import com.islandblast.game.levels.PlayState
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.SlicePhase
import com.islandblast.game.model.SliceRules
import com.islandblast.game.render.SliceAssets
import com.islandblast.game.render.SliceEffects
import com.islandblast.game.render.SliceRenderer

/**
 * A treasure-slicing level full screen (see [StageView]): swipe anywhere to slice,
 * the painted pause button pauses. The level host draws the menus over it.
 */
@SuppressLint("ViewConstructor")
class SliceView(
    context: Context,
    private val assets: SliceAssets,
    private val rules: SliceRules,
    levelNumber: Int,
    levelName: String,
) : StageView(context), LevelPlay {
    var game = SliceGame(assets.spec, rules)
        private set
    private var effects = SliceEffects(assets)
    private val renderer = SliceRenderer(assets, levelNumber, levelName)

    override val stageW get() = assets.spec.stageW
    override val stageH get() = assets.spec.stageH
    override val hudExtent get() = assets.spec.hud.extent
    override val extMarginX get() = assets.spec.extMarginX
    override val extMarginY get() = assets.spec.extMarginY
    override val backgroundBlur get() = assets.backgroundBlur

    // ---- LevelPlay ----------------------------------------------------------------

    override val view: StageView get() = this
    override val state: PlayState
        get() = when (game.phase) {
            SlicePhase.WON -> PlayState.WON
            SlicePhase.LOST -> PlayState.LOST
            else -> PlayState.PLAYING
        }
    override val score: Int get() = game.score
    override val stars: Int get() = game.stars
    override val goal: String get() = "%,d points".format(rules.goal)
    override var paused: Boolean
        get() = game.paused
        set(v) {
            if (v && game.over) return
            if (v) game.touchUp()
            game.paused = v
            invalidate()
        }
    override val settling: Boolean get() = effects.busy

    /** Halves of cut treasure still flying (tests check cuts are drawn). */
    val halvesInFlight: Int get() = effects.halvesInFlight

    override fun restart() {
        game = SliceGame(assets.spec, rules)
        effects.clear()
        effects = SliceEffects(assets)
        renderer.reset()
    }

    // ---- frame loop ---------------------------------------------------------------

    override fun onFrame(dt: Float, held: Boolean) {
        if (!held) game.update(dt)
        effects.consume(game)
    }

    override fun drawStage(canvas: Canvas) = renderer.draw(canvas, game, effects)

    /** Test hook: one frame of [dt] seconds without the display's frame loop. */
    fun stepForTest(dt: Float) = onFrame(dt, false)

    // ---- input --------------------------------------------------------------------

    private fun sx(x: Float) = (x - offX) / scale
    private fun sy(y: Float) = (y - offY) / scale

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = sx(e.x)
        val y = sy(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (game.over || game.paused) return true
                val p = assets.spec.hud.pause
                if (x in p.l - 10f..p.r + 10f && y in p.t - 10f..p.b + 10f) {
                    paused = true
                    return true
                }
                game.touchDown(x, y, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                // Every sample since the last event, so fast swipes cut exactly where they went.
                for (h in 0 until e.historySize) {
                    game.touchMove(sx(e.getHistoricalX(h)), sy(e.getHistoricalY(h)), e.getHistoricalEventTime(h))
                }
                game.touchMove(x, y, e.eventTime)
            }
            MotionEvent.ACTION_UP -> {
                game.touchMove(x, y, e.eventTime)
                game.touchUp()
            }
            MotionEvent.ACTION_CANCEL -> game.touchUp()
        }
        return true
    }
}
