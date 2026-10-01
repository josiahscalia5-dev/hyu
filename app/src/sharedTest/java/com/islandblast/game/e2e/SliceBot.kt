package com.islandblast.game.e2e

import com.islandblast.game.model.Piece
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.TargetKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Plans swipes the way a keen player would: through as much treasure as one line can catch. */
object SliceBot {
    /** A straight swipe from (x0, y0) to (x1, y1) in stage units, and what it should cut. */
    class Swipe(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val targets: List<Piece>)

    /**
     * The best swipe now, or null if nothing is in reach. Only treasure fully on the
     * playfield counts (not behind the HUD, not still rising out of the water).
     */
    fun plan(game: SliceGame): Swipe? {
        val pose = FloatArray(3)
        val live = game.pieces.filter { it.alive }.mapNotNull { p ->
            game.pose(p, pose)
            val x = pose[0]
            val y = pose[1]
            if (y < 150f || y > 1215f || x < 40f || x > game.spec.stageW - 40f) null
            else Triple(p, x, y)
        }
        if (live.isEmpty()) return null
        var best: Swipe? = null
        var bestScore = -1f
        for ((p, cx, cy) in live) {
            for (k in 0 until 8) {
                val a = (k * PI / 8).toFloat()
                val dx = cos(a)
                val dy = sin(a)
                // Everything this line passes through near its middle.
                val hits = live.filter { (q, qx, qy) ->
                    val along = (qx - cx) * dx + (qy - cy) * dy
                    val off = kotlin.math.abs(-(qx - cx) * dy + (qy - cy) * dx)
                    kotlin.math.abs(along) < 330f && off < q.radius * 0.45f
                }
                var lo = 0f
                var hi = 0f
                for ((q, qx, qy) in hits) {
                    val along = (qx - cx) * dx + (qy - cy) * dy
                    lo = min(lo, along - q.radius - 50f)
                    hi = max(hi, along + q.radius + 50f)
                }
                lo = min(lo, -p.radius - 60f)
                hi = max(hi, p.radius + 60f)
                var x0 = cx + dx * lo
                var y0 = cy + dy * lo
                var x1 = cx + dx * hi
                var y1 = cy + dy * hi
                // Start from the side away from the pause button, and stay on the stage.
                if (onPause(game, x0, y0)) { val tx = x0; val ty = y0; x0 = x1; y0 = y1; x1 = tx; y1 = ty }
                if (onPause(game, x0, y0)) continue
                x0 = x0.coerceIn(8f, game.spec.stageW - 8f); x1 = x1.coerceIn(8f, game.spec.stageW - 8f)
                y0 = y0.coerceIn(8f, game.spec.stageH - 8f); y1 = y1.coerceIn(8f, game.spec.stageH - 8f)
                // Most treasure first; chests and barrels (time) are worth a little extra; then the lowest (falling soonest).
                val value = hits.sumOf { (q, _, _) ->
                    when (q.kind) {
                        TargetKind.CHEST -> 1.5
                        TargetKind.BARREL -> 1.3
                        else -> 1.0
                    }
                }.toFloat() + cy / 4000f
                if (value > bestScore) {
                    bestScore = value
                    best = Swipe(x0, y0, x1, y1, hits.map { it.first })
                }
            }
        }
        return best
    }

    private fun onPause(game: SliceGame, x: Float, y: Float): Boolean {
        val p = game.spec.hud.pause
        return x in p.l - 30f..p.r + 30f && y in p.t - 30f..p.b + 30f
    }

    /** Points along a swipe, start to end, for [steps] moves. */
    fun points(s: Swipe, steps: Int): List<Pair<Float, Float>> =
        (0..steps).map { i -> val t = i / steps.toFloat(); (s.x0 + (s.x1 - s.x0) * t) to (s.y0 + (s.y1 - s.y0) * t) }

    fun length(s: Swipe) = hypot(s.x1 - s.x0, s.y1 - s.y0)
}
