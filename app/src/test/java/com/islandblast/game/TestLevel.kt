package com.islandblast.game

import com.islandblast.game.model.GameColor
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.LevelSpec
import com.islandblast.game.model.Phase
import com.islandblast.game.render.Assets
import org.robolectric.RuntimeEnvironment

object TestLevel {
    val assets: Assets by lazy { Assets(RuntimeEnvironment.getApplication().assets) }
    val spec: LevelSpec get() = assets.spec

    fun id(game: Level5Game, id: String) = game.board.blocks.first { it.spec.id == id }.index

    /** Advances game time in 60 fps steps. */
    fun run(game: Level5Game, seconds: Float, onFrame: (Level5Game) -> Unit = {}) {
        var t = 0f
        while (t < seconds - 1e-4f) {
            game.update(1f / 60f)
            onFrame(game)
            t += 1f / 60f
        }
    }

    /** Runs until the shot resolves (ball back in the launcher, or the level ended). */
    fun runShot(game: Level5Game, onFrame: (Level5Game) -> Unit = {}): Float {
        var t = 0f
        do {
            game.update(1f / 60f)
            onFrame(game)
            t += 1f / 60f
        } while (game.phase != Phase.READY && !game.over && t < 10f)
        return t
    }

    class Aim(val angleDeg: Float, val hit: Int, val groupSize: Int)

    /**
     * Finds an aim that the real aim guide says reaches a block of the loaded colour,
     * preferring the biggest group. Uses the same physics the shot will use.
     */
    fun bestAim(game: Level5Game, stepDeg: Float = 0.5f): Aim? {
        var best: Aim? = null
        var a = -game.rules.maxAimDegrees
        while (a <= game.rules.maxAimDegrees + 1e-3f) {
            aimAtAngle(game, a)
            val path = game.aimPath()
            if (path.hit >= 0 && path.matches) {
                val size = game.board.group(path.hit).size
                // Prefer bigger groups, then the most central (least glancing) aim.
                if (best == null || size > best.groupSize ||
                    (size == best.groupSize && kotlin.math.abs(a) < kotlin.math.abs(best.angleDeg))
                ) best = Aim(a, path.hit, size)
            }
            a += stepDeg
        }
        return best
    }

    fun aimAtAngle(game: Level5Game, deg: Float) {
        val r = 600f
        val rad = Math.toRadians(deg.toDouble())
        game.aimAt(
            game.spec.ballX + (kotlin.math.sin(rad) * r).toFloat(),
            game.spec.ballY - (kotlin.math.cos(rad) * r).toFloat(),
        )
    }

    fun colorsOf(game: Level5Game): Map<String, GameColor> =
        game.board.blocks.filter { it.alive }.associate { it.spec.id to it.color }
}
