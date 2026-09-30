package com.islandblast.game.e2e

import com.islandblast.game.model.Level5Game
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Picks shots the way a careful player would: the game's own aim guide, biggest group first. */
object Bot {
    class Aim(val angleDeg: Float, val hit: Int, val groupSize: Int, val widthDeg: Float)

    const val REACH = 600f

    /**
     * Sweeps the aim across the whole arc. Neighbouring angles that reach the same
     * matching block form a window; the shot goes through the middle of the best
     * window (biggest group, then widest window), so it is robust to touch rounding.
     */
    fun bestAim(game: Level5Game, stepDeg: Float = 0.5f): Aim? {
        val max = game.rules.maxAimDegrees
        var best: Aim? = null
        var runStart = Float.NaN
        var runHit = -1
        var a = -max
        fun close(end: Float) {
            if (runHit < 0) return
            val size = game.board.group(runHit).size
            val width = end - runStart
            val mid = (runStart + end) / 2f
            val b = best
            if (b == null || size > b.groupSize || (size == b.groupSize && width > b.widthDeg)) {
                best = Aim(mid, runHit, size, width)
            }
        }
        while (a <= max + 1e-3f) {
            aimAtAngle(game, a)
            val path = game.aimPath()
            val hit = if (path.hit >= 0 && path.matches) path.hit else -1
            if (hit != runHit) {
                close(a - stepDeg)
                runStart = a
                runHit = hit
            }
            a += stepDeg
        }
        close(max)
        return best
    }

    /** Stage point a finger would drag to for this angle. */
    fun aimPoint(game: Level5Game, deg: Float, reach: Float = REACH): Pair<Float, Float> {
        val r = Math.toRadians(deg.toDouble())
        return (game.spec.ballX + (sin(r) * reach).toFloat()) to (game.spec.ballY - (cos(r) * reach).toFloat())
    }

    fun aimAtAngle(game: Level5Game, deg: Float) {
        val (x, y) = aimPoint(game, deg)
        game.aimAt(x, y)
    }

    fun isCentral(aim: Aim) = abs(aim.angleDeg) < 45f
}
