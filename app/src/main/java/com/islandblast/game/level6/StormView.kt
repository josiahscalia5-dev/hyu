package com.islandblast.game.level6

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.islandblast.game.StageFit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts Level 6 (Storm Dodge) full screen. Controls:
 *  - the left/right arrow buttons move the jet ski one lane;
 *  - dragging anywhere else steers the jet ski under the finger;
 *  - the pause button pauses; a tap resumes; a tap on the end card rides again.
 */
@SuppressLint("ViewConstructor")
class StormView(context: Context, private val assets: StormAssets) : View(context), Choreographer.FrameCallback {
    var game = StormGame()
        private set
    private val renderer = StormRenderer(assets)
    private val spec = assets.spec
    private val fit = StageFit(spec.stageW, spec.stageH, spec.hudExtent)
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val safe = IntArray(4)

    private var lastNanos = 0L
    private var running = false
    private var overSince = 0L

    /** Test hook: while true, frames draw but game time stands still. */
    var clockHeld = false

    val stageRect: RectF get() = RectF(fit.stage)
    val safeRect: RectF
        get() = RectF(safe[0] + pad(), safe[1] + pad(), width - safe[2] - pad(), height - safe[3] - pad())
    val canRestart get() = game.over && overSince != 0L && SystemClock.uptimeMillis() - overSince >= 900L

    fun stageToView(x: Float, y: Float) = android.graphics.PointF(fit.offX + x * fit.scale, fit.offY + y * fit.scale)

    fun setSafeInsetsForTest(l: Int, t: Int, r: Int, b: Int) {
        safe[0] = l; safe[1] = t; safe[2] = r; safe[3] = b
        refit()
    }

    private fun pad() = 6f * resources.displayMetrics.density

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
        val dt = if (lastNanos == 0L) 0f else (frameTimeNanos - lastNanos) / 1e9f
        lastNanos = frameTimeNanos
        if (!clockHeld) step(min(dt, 1f / 30f))
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Advances the game and its effects (also used by tests). */
    fun step(dt: Float) {
        game.update(dt)
        renderer.consume(game)
        if (game.over && overSince == 0L) overSince = SystemClock.uptimeMillis()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        StageFit.safeInsets(insets).copyInto(safe)
        refit()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = refit()

    private fun refit() {
        fit.fit(width.toFloat(), height.toFloat(), safeRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val ext = RectF(fit.stage.left - spec.skyMarginX * fit.scale, fit.stage.top - spec.skyMarginY * fit.scale,
            fit.stage.right + spec.skyMarginX * fit.scale, fit.stage.bottom)
        if (ext.left > 0f || ext.top > 0f || ext.right < width) {
            val cover = max(width / ext.width(), height / ext.height())
            val cw = ext.width() * cover
            val ch = ext.height() * cover
            canvas.drawBitmap(assets.skyBlur, null, RectF((width - cw) / 2f, 0f, (width + cw) / 2f, ch), bgPaint)
        }
        canvas.save()
        canvas.translate(fit.offX, fit.offY)
        canvas.scale(fit.scale, fit.scale)
        renderer.draw(canvas, game)
        canvas.restore()
    }

    // ---- touch -----------------------------------------------------------------------

    private var dragging = false
    private var arrowPointer = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = fit.toStageX(e.getX(i))
        val y = fit.toStageY(e.getY(i))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (game.over) {
                    if (canRestart) restart()
                    return true
                }
                if (game.paused) {
                    game.paused = false
                    return true
                }
                val p = spec.pause
                if (x in p.l - 12f..p.r + 12f && y in p.t - 12f..p.b + 12f) {
                    game.paused = true
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
                        game.steerTo((x - spec.vanishX) / spec.laneW)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                for (k in 0 until e.pointerCount) {
                    if (e.getPointerId(k) == arrowPointer) continue
                    game.steerTo((fit.toStageX(e.getX(k)) - spec.vanishX) / spec.laneW)
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

    fun restart() {
        game = StormGame()
        renderer.reset()
        overSince = 0L
    }
}
