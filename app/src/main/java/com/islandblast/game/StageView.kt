package com.islandblast.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import com.islandblast.game.model.Box
import kotlin.math.max
import kotlin.math.min

/**
 * Hosts a level full screen, edge to edge.
 *
 * The approved screen (the "stage") is scaled uniformly, never stretched:
 *  - as large as possible so the scene fills the whole display, but
 *  - never so large that any HUD element leaves the safe area (camera cutout,
 *    status/navigation bars, rounded corners).
 * Whatever the stage does not cover is filled by the same scene painted past its
 * edges, so there are no bars or borders on any phone shape.
 */
abstract class StageView(context: Context) : View(context), Choreographer.FrameCallback {
    protected abstract val stageW: Float
    protected abstract val stageH: Float
    /** Stage-space box that must stay inside the safe area. */
    protected abstract val hudExtent: Box
    /** How far the painted margins reach past each stage edge. */
    protected abstract val extMarginX: Float
    protected abstract val extMarginY: Float
    /** Tiny copy of the extended scene, drawn stretched on extreme screen shapes. */
    protected abstract val backgroundBlur: Bitmap

    /** One display frame: [dt] seconds passed; while [held] the game clock must not move. */
    protected abstract fun onFrame(dt: Float, held: Boolean)

    /** Draws the level in stage units. */
    protected abstract fun drawStage(canvas: Canvas)

    protected var scale = 1f
        private set
    protected var offX = 0f
        private set
    protected var offY = 0f
        private set
    private val stage = RectF()
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Safe-area insets in view pixels (left, top, right, bottom). */
    private val safe = IntArray(4)

    private var lastNanos = 0L
    private var running = false

    /**
     * Test hook: while true, frames keep drawing but game time stands still, so a
     * test can capture and inspect the screen without the clock running on.
     */
    var clockHeld = false

    /** Maps a stage point (reference pixels) to view pixels. */
    fun stageToView(x: Float, y: Float) = PointF(offX + x * scale, offY + y * scale)

    /** The stage's rectangle in view pixels (may extend past the view). */
    val stageRect: RectF get() = RectF(stage)

    /** The area the HUD must stay inside, in view pixels. */
    val safeRect: RectF
        get() = RectF(safe[0] + pad(), safe[1] + pad(), width - safe[2] - pad(), height - safe[3] - pad())

    /** Test hook: pretend the device reports these insets (e.g. a camera cutout). */
    fun setSafeInsetsForTest(l: Int, t: Int, r: Int, b: Int) {
        safe[0] = l; safe[1] = t; safe[2] = r; safe[3] = b
        fitStage()
        invalidate()
    }

    private fun pad() = 6f * resources.displayMetrics.density

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var l = 0; var t = 0; var r = 0; var b = 0
        insets.displayCutout?.let {
            l = it.safeInsetLeft; t = it.safeInsetTop; r = it.safeInsetRight; b = it.safeInsetBottom
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // Bars are hidden while playing; if the user swipes them in, keep clear of them.
            val vis = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            l = maxOf(l, vis.left); t = maxOf(t, vis.top); r = maxOf(r, vis.right); b = maxOf(b, vis.bottom)
            // Rounded corners: keep the HUD a little further in on phones that report them.
            if (Build.VERSION.SDK_INT >= 31) {
                val tl = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                val bl = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                val corner = (maxOf(tl, bl) * 0.3f).toInt()
                l = maxOf(l, corner); r = maxOf(r, corner)
                t = maxOf(t, corner); b = maxOf(b, corner)
            }
        }
        safe[0] = l; safe[1] = t; safe[2] = r; safe[3] = b
        fitStage()
        invalidate()
        return insets
    }

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

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0f else ((frameTimeNanos - lastNanos) / 1e9f)
        lastNanos = frameTimeNanos
        onFrame(min(dt, 1f / 30f), clockHeld)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fitStage()

    private fun fitStage() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val hud = hudExtent
        val safeArea = safeRect
        // Cover the screen, unless that would push the HUD out of the safe area.
        val cover = max(w / stageW, h / stageH)
        val fitHud = min(safeArea.width() / hud.w, safeArea.height() / hud.h)
        scale = min(cover, fitHud)
        offX = place((w - stageW * scale) / 2f, hud.l, hud.r, safeArea.left, safeArea.right)
        offY = place((h - stageH * scale) / 2f, hud.t, hud.b, safeArea.top, safeArea.bottom)
        stage.set(offX, offY, offX + stageW * scale, offY + stageH * scale)
    }

    /** Offset nearest [centred] that keeps stage span [a, b] inside screen span [lo, hi]. */
    private fun place(centred: Float, a: Float, b: Float, lo: Float, hi: Float): Float {
        val min = lo - a * scale
        val max = hi - b * scale
        return if (min <= max) centred.coerceIn(min, max) else (min + max) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val ext = RectF(
            stage.left - extMarginX * scale, stage.top - extMarginY * scale,
            stage.right + extMarginX * scale, stage.bottom + extMarginY * scale,
        )
        if (ext.left > 0f || ext.top > 0f || ext.right < width || ext.bottom < height) {
            // Only on extreme shapes: a soft blur of the scene behind the painted margins.
            val cover = max(width / ext.width(), height / ext.height())
            val cw = ext.width() * cover
            val ch = ext.height() * cover
            canvas.drawBitmap(backgroundBlur, null,
                RectF((width - cw) / 2f, (height - ch) / 2f, (width + cw) / 2f, (height + ch) / 2f), bgPaint)
        }
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        drawStage(canvas)
        canvas.restore()
    }
}
