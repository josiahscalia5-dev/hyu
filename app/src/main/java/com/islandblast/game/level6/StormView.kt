package com.islandblast.game.level6

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.islandblast.game.StageView
import com.islandblast.game.levels.LevelPlay
import com.islandblast.game.levels.PlayState
import kotlin.math.hypot
import kotlin.math.max

/**
 * Hosts Level 6 (Storm Dodge) full screen, edge to edge (see [StageView]). Controls:
 *  - the left/right arrow buttons move the jet ski one lane;
 *  - dragging anywhere else steers the jet ski under the finger;
 *  - the pause button pauses.
 * The level host draws the pause menu and result card over it.
 */
@SuppressLint("ViewConstructor")
class StormView(context: Context, private val assets: StormAssets) : StageView(context), LevelPlay {
    var game = StormGame()
        private set
    val renderer = StormRenderer(assets).apply { overlays = false }
    val spec: StormSpec get() = assets.spec
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val stageW get() = spec.stageW
    override val stageH get() = spec.stageH
    override val hudExtent get() = spec.hudExtent
    override val extMarginX get() = spec.skyMarginX
    override val extMarginY get() = spec.skyMarginY
    override val backgroundBlur get() = assets.skyBlur

    // ---- LevelPlay ----------------------------------------------------------------

    override val view: StageView get() = this
    override val state: PlayState
        get() = when (game.phase) {
            StormPhase.FINISHED -> PlayState.WON
            StormPhase.OUT_OF_TIME -> PlayState.LOST
            StormPhase.RACING -> PlayState.PLAYING
        }
    override val score: Int get() = game.score
    override val stars: Int get() = game.stars
    override val goal: String get() = "reach the temple"
    override var paused: Boolean
        get() = game.paused
        set(v) {
            if (v && game.over) return
            if (v) releaseControls()
            game.paused = v
            invalidate()
        }

    /** The finish line's confetti is still flying; the result card waits for it. */
    override val settling: Boolean get() = game.over && game.time - game.finishedAt < FINISH_SETTLE

    override fun onFrame(dt: Float, held: Boolean) {
        if (!held) step(dt)
    }

    /** Advances the game and its effects (also used by tests). */
    fun step(dt: Float) {
        game.update(dt)
        renderer.consume(game)
    }

    override fun drawStage(canvas: Canvas) = renderer.draw(canvas, game)

    override fun onDraw(canvas: Canvas) {
        // The sky is painted past the stage's top and sides (the river runs off the bottom).
        val stage = stageRect
        val ext = RectF(stage.left - spec.skyMarginX * scale, stage.top - spec.skyMarginY * scale,
            stage.right + spec.skyMarginX * scale, stage.bottom)
        if (ext.left > 0f || ext.top > 0f || ext.right < width) {
            val cover = max(width / ext.width(), height / ext.height())
            val cw = ext.width() * cover
            val ch = ext.height() * cover
            canvas.drawBitmap(assets.skyBlur, null, RectF((width - cw) / 2f, 0f, (width + cw) / 2f, ch), bgPaint)
        }
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        drawStage(canvas)
        canvas.restore()
    }

    // ---- touch -----------------------------------------------------------------------

    private var dragging = false
    private var arrowPointer = -1

    private fun toStageX(viewX: Float) = (viewX - offX) / scale
    private fun toStageY(viewY: Float) = (viewY - offY) / scale

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = toStageX(e.getX(i))
        val y = toStageY(e.getY(i))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // The host's pause menu and result card take the taps then.
                if (game.over || game.paused) return true
                val p = spec.pause
                if (x in p.l - 12f..p.r + 12f && y in p.t - 12f..p.b + 12f) {
                    paused = true
                    return true
                }
                when {
                    near(x, y, spec.arrowLeft) -> {
                        game.steer(-1); renderer.leftPressed = true; arrowPointer = e.getPointerId(i)
                    }
                    near(x, y, spec.arrowRight) -> {
                        game.steer(1); renderer.rightPressed = true; arrowPointer = e.getPointerId(i)
                    }
                    else -> {
                        dragging = true
                        game.steerTo(spec.lanesAtSki(x, renderer.camLanes(game)))
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                for (k in 0 until e.pointerCount) {
                    if (e.getPointerId(k) == arrowPointer) continue
                    game.steerTo(spec.lanesAtSki(toStageX(e.getX(k)), renderer.camLanes(game)))
                    break
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.getPointerId(i) == arrowPointer || e.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    renderer.leftPressed = false
                    renderer.rightPressed = false
                    arrowPointer = -1
                }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) dragging = false
            }
        }
        return true
    }

    private fun near(x: Float, y: Float, c: FloatArray) = hypot(x - c[0], y - c[1]) <= c[2] * 1.15f

    private fun releaseControls() {
        dragging = false
        arrowPointer = -1
        renderer.leftPressed = false
        renderer.rightPressed = false
    }

    /** For the visual preview: show a given game (e.g. a composed layout). */
    fun replaceGameForPreview(g: StormGame) {
        game = g
        renderer.reset()
    }

    override fun restart() {
        releaseControls()
        game = StormGame()
        renderer.reset()
    }

    companion object {
        /** Seconds the finish celebration plays before the result card. */
        const val FINISH_SETTLE = 1.6f
    }
}
