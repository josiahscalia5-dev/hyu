package com.islandblast.game.temple

import kotlin.math.abs

/**
 * Runs like an attentive player: looks a short way ahead, picks a lane with no hazard in
 * reach (preferring coins and gems), and moves there with the arrow buttons.
 */
object TempleBot {
    /** Returns -1, 0 or +1: which arrow to press now (0 = none). */
    fun decide(g: TempleGame, lookAhead: Float = 8f, greedy: Boolean = true): Int {
        val lanes = TempleCourse.lanes
        val cur = lanes.indices.minBy { abs(lanes[it] - g.targetX) }
        fun danger(lane: Int, from: Float, to: Float) = g.objects.any {
            it.kind.hazard && !it.decor && !g.isTaken(it) && it.z > g.dist + from && it.z < g.dist + to &&
                abs(it.x - lanes[lane]) < it.kind.halfWidth + TempleGame.RUNNER_HALF_WIDTH + 0.08f
        }
        fun value(lane: Int): Int = g.objects.filter {
            !it.kind.hazard && !it.decor && !g.isTaken(it) && it.z > g.dist && it.z < g.dist + lookAhead * 1.4f &&
                abs(it.x - lanes[lane]) < 0.45f
        }.fold(0) { acc, o -> acc + if (o.kind == Item.COIN) 1 else 4 }
        val safe = lanes.indices.filter { !danger(it, -0.8f, lookAhead) }
        if (safe.isEmpty()) return 0
        val reachable = safe.filter { l ->
            val between = if (l > cur) (cur + 1)..l else l until cur
            between.all { !danger(it, -0.8f, 3f) }
        }.ifEmpty { safe }
        val best = if (cur in reachable && (!greedy || value(cur) >= reachable.maxOf { value(it) })) cur
        else reachable.maxWith(compareBy<Int> { if (greedy) value(it) else 0 }.thenBy { -abs(it - cur) })
        return (best - cur).coerceIn(-1, 1)
    }
}
