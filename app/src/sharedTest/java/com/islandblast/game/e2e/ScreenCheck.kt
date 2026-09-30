package com.islandblast.game.e2e

import android.graphics.Bitmap
import android.graphics.Color
import com.islandblast.game.GameView
import com.islandblast.game.model.BlockState
import com.islandblast.game.model.Box
import com.islandblast.game.model.GameColor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Reads the rendered screen back: which colour each block and the ball show, and
 * whether a cleared block's area shows only the temple behind it.
 * Hues are OpenCV-style (0..180) to match tools/assets/check_assets.py.
 */
class ScreenCheck(private val view: GameView, private val shot: Bitmap, private val background: Bitmap) {

    private fun px(stageX: Float, stageY: Float): Int {
        val p = view.stageToView(stageX, stageY)
        val x = p.x.toInt().coerceIn(0, shot.width - 1)
        val y = p.y.toInt().coerceIn(0, shot.height - 1)
        return shot.getPixel(x, y)
    }

    /** Median hue of the saturated pixels in the block's face (avoids symbol, badge and corner dot). */
    fun blockHue(b: BlockState): Int? {
        val part = b.spec.parts.maxBy { it.w * it.h }
        val cx = part.cx
        val cy = part.cy
        val keepOut = min(part.w, part.h) * 0.36f
        val hues = ArrayList<Int>()
        var y = part.t + 5f
        while (y < part.b - 5f) {
            var x = part.l + 5f
            while (x < part.r - 5f) {
                val inBadge = hypot(x - cx, y - cy) < keepOut
                val inDot = x > part.r - 30f && y < part.t + 30f
                if (!inBadge && !inDot) hue(px(x, y))?.let { hues += it }
                x += 3f
            }
            y += 3f
        }
        return circularMedian(hues)
    }

    fun ballHue(stageX: Float, stageY: Float, radius: Float): Int? {
        val hues = ArrayList<Int>()
        var y = stageY - radius
        while (y <= stageY + radius) {
            var x = stageX - radius
            while (x <= stageX + radius) {
                if (hypot(x - stageX, y - stageY) <= radius) hue(px(x, y))?.let { hues += it }
                x += 2f
            }
            y += 2f
        }
        return circularMedian(hues)
    }

    class Residue(val cells: Int, val badCells: Int, val worst: Int, val worstAt: String)

    /**
     * Compares 8x8 stage cells of a cleared block's area with the background plate.
     * Cells near [exclude] boxes (live blocks) or the aim guide are skipped.
     */
    fun residue(area: Box, exclude: List<Box>, aimPath: List<FloatArray>): Residue {
        var cells = 0
        var bad = 0
        var worst = 0
        var worstAt = ""
        var y = area.t + 4f
        while (y + CELL <= area.b - 4f) {
            var x = area.l + 4f
            while (x + CELL <= area.r - 4f) {
                val cx = x + CELL / 2
                val cy = y + CELL / 2
                val nearLive = exclude.any { cx > it.l - 10 && cx < it.r + 10 && cy > it.t - 10 && cy < it.b + 10 }
                if (!nearLive && distToPath(cx, cy, aimPath) > 44f) {
                    val d = cellDiff(x, y)
                    cells++
                    if (d > worst) {
                        worst = d
                        worstAt = "stage (${cx.toInt()}, ${cy.toInt()})"
                    }
                    if (d > CELL_TOLERANCE) bad++
                }
                x += CELL
            }
            y += CELL
        }
        return Residue(cells, bad, worst, worstAt)
    }

    private fun cellDiff(x0: Float, y0: Float): Int {
        var sr = 0; var sg = 0; var sb = 0
        var br = 0; var bg = 0; var bb = 0
        var n = 0
        var y = y0 + 1f
        while (y < y0 + CELL) {
            var x = x0 + 1f
            while (x < x0 + CELL) {
                val p = px(x, y)
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p)
                val q = background.getPixel(x.toInt(), y.toInt())
                br += Color.red(q); bg += Color.green(q); bb += Color.blue(q)
                n++
                x += 2f
            }
            y += 2f
        }
        return maxOf(abs(sr - br), abs(sg - bg), abs(sb - bb)) / n
    }

    private fun distToPath(x: Float, y: Float, pts: List<FloatArray>): Float {
        var best = Float.MAX_VALUE
        for (i in 1 until pts.size) {
            val ax = pts[i - 1][0]; val ay = pts[i - 1][1]
            val bx = pts[i][0]; val by = pts[i][1]
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 > 0f) (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f) else 0f
            best = min(best, hypot(x - (ax + t * vx), y - (ay + t * vy)))
        }
        return best
    }

    companion object {
        const val CELL = 8f
        const val CELL_TOLERANCE = 28

        /** Hue window per colour, same as check_assets.py. */
        val WINDOW = mapOf(
            GameColor.CYAN to (92 to 112),
            GameColor.VIOLET to (126 to 146),
            GameColor.MAGENTA to (144 to 160),
            GameColor.RED to (168 to 186),
            GameColor.YELLOW to (16 to 34),
        )

        fun reads(hue: Int?, c: GameColor): Boolean {
            if (hue == null) return false
            val (lo, hi) = WINDOW.getValue(c)
            return if (hi > 180) hue >= lo || hue <= hi - 180 else hue in lo..hi
        }

        /** Hue 0..180 of a saturated, reasonably bright pixel, else null. */
        fun hue(p: Int): Int? {
            val hsv = FloatArray(3)
            Color.RGBToHSV(Color.red(p), Color.green(p), Color.blue(p), hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) return null
            return (hsv[0] / 2f).toInt()
        }

        fun circularMedian(h: List<Int>): Int? {
            if (h.isEmpty()) return null
            val plain = h.sorted()
            val shifted = h.map { (it + 90) % 180 }.sorted()
            fun spread(v: List<Int>) = v[v.size * 9 / 10] - v[v.size / 10]
            return if (spread(shifted) < spread(plain)) (shifted[shifted.size / 2] - 90 + 180) % 180
            else plain[plain.size / 2]
        }
    }
}
