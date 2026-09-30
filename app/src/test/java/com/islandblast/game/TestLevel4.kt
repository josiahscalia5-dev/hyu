package com.islandblast.game

import com.islandblast.game.model.Level4Game
import com.islandblast.game.model.Phase
import com.islandblast.game.render.Level4Assets
import org.robolectric.RuntimeEnvironment

object TestLevel4 {
    val assets: Level4Assets by lazy { Level4Assets(RuntimeEnvironment.getApplication().assets) }

    fun id(game: Level4Game, id: String) = game.treasures.first { it.spec.id == id }.index

    /** Advances game time in 60 fps steps. */
    fun run(game: Level4Game, seconds: Float, onFrame: (Level4Game) -> Unit = {}) {
        var t = 0f
        while (t < seconds - 1e-4f) {
            game.update(1f / 60f)
            onFrame(game)
            t += 1f / 60f
        }
    }

    /** Runs until the shot resolves (launcher ready again, or the level ended). */
    fun runShot(game: Level4Game, onFrame: (Level4Game) -> Unit = {}): Float {
        var t = 0f
        do {
            game.update(1f / 60f)
            onFrame(game)
            t += 1f / 60f
        } while (game.phase != Phase.READY && !game.over && t < 10f)
        return t
    }

    class Aim(val x: Float, val y: Float, val hits: List<Int>)

    /** Some aim whose guide shows [target] harvested (searches the whole playfield). */
    fun aimFor(game: Level4Game, target: Int, filter: (List<Int>) -> Boolean = { true }): Aim? {
        var y = 60f
        while (y < game.spec.tipY) {
            var x = 20f
            while (x < game.spec.stageW) {
                game.aimAt(x, y)
                val hits = game.aimPreview().hits
                if (target in hits && filter(hits)) return Aim(game.aimX, game.aimY, hits)
                x += 30f
            }
            y += 30f
        }
        return null
    }

    /** Some aim whose guide shows nothing harvested. */
    fun missAim(game: Level4Game): Aim? {
        var y = 60f
        while (y < game.spec.tipY) {
            var x = 20f
            while (x < game.spec.stageW) {
                game.aimAt(x, y)
                if (game.aimPreview().hits.isEmpty()) return Aim(game.aimX, game.aimY, emptyList())
                x += 40f
            }
            y += 40f
        }
        return null
    }

    /**
     * The aim the real aim guide says harvests the most treasure. Candidates: every live
     * treasure's centre and points around it, so curved shots through several pieces
     * are found. Leaves the game aimed at the choice.
     */
    fun bestAim(game: Level4Game): Aim? {
        var best: Aim? = null
        for (t in game.treasures) {
            if (!t.alive) continue
            for (dx in floatArrayOf(0f, -40f, 40f, -90f, 90f)) for (dy in floatArrayOf(0f, -60f, 60f)) {
                val x = t.spec.cx + dx
                val y = t.spec.cy + dy
                game.aimAt(x, y)
                val hits = game.aimPreview().hits
                if (hits.isNotEmpty() && (best == null || hits.size > best.hits.size)) best = Aim(game.aimX, game.aimY, hits)
            }
        }
        if (best != null) game.aimAt(best.x, best.y)
        return best
    }
}
