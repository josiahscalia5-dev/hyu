package com.islandblast.game.app

import android.graphics.RectF
import android.os.Build
import android.view.WindowInsets

/**
 * The part of the screen that UI must stay inside: clear of the camera cutout, the
 * status/navigation bars (if the player swipes them in) and rounded corners.
 */
class SafeArea {
    /** Insets in view pixels: left, top, right, bottom. */
    val insets = IntArray(4)

    fun set(l: Int, t: Int, r: Int, b: Int) {
        insets[0] = l; insets[1] = t; insets[2] = r; insets[3] = b
    }

    fun apply(w: WindowInsets) {
        var l = 0; var t = 0; var r = 0; var b = 0
        w.displayCutout?.let {
            l = it.safeInsetLeft; t = it.safeInsetTop; r = it.safeInsetRight; b = it.safeInsetBottom
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val vis = w.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            l = maxOf(l, vis.left); t = maxOf(t, vis.top); r = maxOf(r, vis.right); b = maxOf(b, vis.bottom)
            if (Build.VERSION.SDK_INT >= 31) {
                val tl = w.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
                val bl = w.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                val corner = (maxOf(tl, bl) * 0.3f).toInt()
                l = maxOf(l, corner); r = maxOf(r, corner)
                t = maxOf(t, corner); b = maxOf(b, corner)
            }
        }
        set(l, t, r, b)
    }

    /** The safe rectangle of a [w] x [h] view, with [pad] pixels to spare. */
    fun rect(w: Int, h: Int, pad: Float): RectF =
        RectF(insets[0] + pad, insets[1] + pad, w - insets[2] - pad, h - insets[3] - pad)
}
