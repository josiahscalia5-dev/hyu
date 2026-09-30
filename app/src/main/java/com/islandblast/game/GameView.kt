package com.islandblast.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.islandblast.game.model.Level5Game
import com.islandblast.game.render.Assets
import com.islandblast.game.render.Effects
import com.islandblast.game.render.Renderer
import kotlin.math.hypot
import kotlin.math.min

/**
 * Hosts Level 5. The reference's 1024x1536 frame is scaled uniformly to fit the
 * screen (layout never stretches); extra height on tall phones shows a soft,
 * blurred continuation of the temple instead of black bars.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val assets: Assets) : View(context), Choreographer.FrameCallback {
    var game = Level5Game(assets.spec)
        private set
    private var effects = Effects(assets)
    private val renderer = Renderer(assets)

    private var scale = 1f
    private var offX = 0f
    private var offY = 0f
    private val stage = RectF()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bandPaint = Paint().apply { color = 0xFF000000.toInt() }
    private val bandShade = Paint()

    private var lastNanos = 0L
    private var running = false

    /** When the win/lose card appeared (uptime ms); taps restart only after a short beat. */
    private var overSince = 0L

    /** The win/lose card is up and a tap will start the level again. */
    val canRestart: Boolean
        get() = game.over && overSince != 0L && SystemClock.uptimeMillis() - overSince >= RESTART_GUARD_MS

    /** True while hit flashes, shards or sparks are still on screen. */
    val effectsBusy: Boolean get() = effects.busy

    /**
     * Test hook: while true, frames keep drawing but game time stands still, so a
     * test can capture and inspect the screen without the clock running on.
     */
    var clockHeld = false

    /** Maps a stage point (reference pixels) to view pixels. */
    fun stageToView(x: Float, y: Float) = PointF(offX + x * scale, offY + y * scale)

    // Touch state: a short tap on the ball switches colour, any drag aims, release shoots.
    private var downX = 0f
    private var downY = 0f
    private var downOnBall = false
    private var aiming = false

    fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun pauseGame() {
        if (!game.over) game.paused = true
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f)
        lastNanos = frameTimeNanos
        if (!clockHeld) game.update(min(dt, 1f / 30f))
        effects.consume(game)
        if (game.over && overSince == 0L) overSince = SystemClock.uptimeMillis()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = assets.spec
        scale = min(w / s.stageW, h / s.stageH)
        offX = (w - s.stageW * scale) / 2f
        offY = (h - s.stageH * scale) / 2f
        stage.set(offX, offY, offX + s.stageW * scale, offY + s.stageH * scale)
    }

    override fun onDraw(canvas: Canvas) {
        if (stage.top > 0.5f) drawBands(canvas)
        if (stage.left > 0.5f) {
            canvas.drawRect(0f, 0f, stage.left, height.toFloat(), bandPaint)
            canvas.drawRect(stage.right, 0f, width.toFloat(), height.toFloat(), bandPaint)
        }
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, assets.spec.stageW, assets.spec.stageH)
        renderer.draw(canvas, game, effects)
        canvas.restore()
    }

    /** Tall screens: mirror a blur of the scene's top/bottom edge into the spare height. */
    private fun drawBands(canvas: Canvas) {
        val topH = stage.top
        val bottomH = height - stage.bottom
        canvas.save()
        canvas.scale(1f, -1f, 0f, stage.top)
        canvas.drawBitmap(assets.bandTop, null, RectF(stage.left, stage.top, stage.right, stage.top + topH), bgPaint)
        canvas.restore()
        canvas.save()
        canvas.scale(1f, -1f, 0f, stage.bottom)
        canvas.drawBitmap(assets.bandBottom, null, RectF(stage.left, stage.bottom - bottomH, stage.right, stage.bottom), bgPaint)
        canvas.restore()
        bandShade.shader = LinearGradient(0f, 0f, 0f, stage.top, 0xAA000000.toInt(), 0x22000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), stage.top, bandShade)
        bandShade.shader = LinearGradient(0f, stage.bottom, 0f, height.toFloat(), 0x22000000, 0xAA000000.toInt(),
            Shader.TileMode.CLAMP)
        canvas.drawRect(0f, stage.bottom, width.toFloat(), height.toFloat(), bandShade)
    }

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
                if (PAUSE.contains(x, y)) {
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
        /** The painted pause button, in stage units. */
        private val PAUSE = RectF(24f, 18f, 172f, 156f)
        private const val RESTART_GUARD_MS = 700L
    }
}
