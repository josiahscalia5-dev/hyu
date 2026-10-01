package com.islandblast.game

import android.graphics.RectF
import android.os.Build
import android.view.WindowInsets
import com.islandblast.game.model.Box
import kotlin.math.max
import kotlin.math.min

/**
 * Fits an approved design (the "stage", in its own pixels) to a phone screen, full
 * screen and never stretched:
 *  - scaled as large as needed to cover the whole display, but
 *  - never so large that the HUD extent leaves the safe area (cutout, bars, corners).
 * The scene is painted past the stage edges, so any uncovered strip still shows scenery.
 */
class StageFit(private val stageW: Float, private val stageH: Float, private val hud: Box) {
    var scale = 1f; private set
    var offX = 0f; private set
    var offY = 0f; private set
    val stage = RectF()

    fun fit(w: Float, h: Float, safe: RectF) {
        if (w <= 0f || h <= 0f) return
        val cover = max(w / stageW, h / stageH)
        val fitHud = min(safe.width() / hud.w, safe.height() / hud.h)
        scale = min(cover, fitHud)
        offX = place((w - stageW * scale) / 2f, hud.l, hud.r, safe.left, safe.right)
        offY = place((h - stageH * scale) / 2f, hud.t, hud.b, safe.top, safe.bottom)
        stage.set(offX, offY, offX + stageW * scale, offY + stageH * scale)
    }

    /** Offset nearest [centred] that keeps stage span [a, b] inside screen span [lo, hi]. */
    private fun place(centred: Float, a: Float, b: Float, lo: Float, hi: Float): Float {
        val min = lo - a * scale
        val max = hi - b * scale
        return if (min <= max) centred.coerceIn(min, max) else (min + max) / 2f
    }

    fun toStageX(viewX: Float) = (viewX - offX) / scale
    fun toStageY(viewY: Float) = (viewY - offY) / scale

    companion object {
        /**
         * Safe-area insets (left, top, right, bottom) in px: camera cutout, any visible
         * system bars, and a margin for rounded screen corners.
         */
        fun safeInsets(insets: WindowInsets): IntArray {
            var l = 0; var t = 0; var r = 0; var b = 0
            insets.displayCutout?.let {
                l = it.safeInsetLeft; t = it.safeInsetTop; r = it.safeInsetRight; b = it.safeInsetBottom
            }
            if (Build.VERSION.SDK_INT >= 30) {
                // Bars are hidden while playing; if the user swipes them in, keep clear of them.
                val vis = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = maxOf(l, vis.left); t = maxOf(t, vis.top); r = maxOf(r, vis.right); b = maxOf(b, vis.bottom)
                if (Build.VERSION.SDK_INT >= 31) {
                    val tl = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                    val bl = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                    val corner = (maxOf(tl, bl) * 0.3f).toInt()
                    l = maxOf(l, corner); r = maxOf(r, corner)
                    t = maxOf(t, corner); b = maxOf(b, corner)
                }
            }
            return intArrayOf(l, t, r, b)
        }
    }
}
