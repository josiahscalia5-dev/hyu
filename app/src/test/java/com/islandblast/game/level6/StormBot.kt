package com.islandblast.game.level6

import kotlin.math.abs

/**
 * Steers like an attentive player: looks a short way ahead, picks the lane with no
 * hazard in reach (preferring coins), and moves there with the arrow buttons.
 */
object StormBot {
    /** Returns -1, 0 or +1: which arrow to press now (0 = none). */
    fun decide(g: StormGame, lookAhead: Float = 9f, greedy: Boolean = true): Int {
        val lanes = StormCourse.lanes
        val cur = lanes.indices.minBy { abs(lanes[it] - g.targetX) }
        fun danger(lane: Int, from: Float, to: Float) = g.objects.any {
            it.kind.hazard && !it.drift && !g.isTaken(it) && it.z > g.z + from && it.z < g.z + to &&
                abs(it.x - lanes[lane]) < it.kind.halfWidth + StormGame.SKI_HALF_WIDTH + 0.08f
        }
        fun coinsIn(lane: Int) = g.objects.count {
            it.kind == Kind.COIN && !g.isTaken(it) && it.z > g.z && it.z < g.z + lookAhead * 1.4f && abs(it.x - lanes[lane]) < 0.3f
        } + if (g.objects.any { it.kind == Kind.SHIELD && !g.isTaken(it) && it.z > g.z && it.z < g.z + lookAhead && abs(it.x - lanes[lane]) < 0.3f }) 8 else 0
        val safe = lanes.indices.filter { !danger(it, -0.8f, lookAhead) }
        if (safe.isEmpty()) return 0
        // A lane is reachable if moving through the lanes in between is also safe soon.
        val reachable = safe.filter { l ->
            val between = if (l > cur) (cur + 1)..l else l until cur
            between.all { !danger(it, -0.8f, 3.5f) }
        }.ifEmpty { safe }
        val best = if (cur in reachable && (!greedy || coinsIn(cur) >= reachable.maxOf { coinsIn(it) })) cur
        else reachable.maxWith(compareBy<Int> { if (greedy) coinsIn(it) else 0 }.thenBy { -abs(it - cur) })
        return (best - cur).coerceIn(-1, 1)
    }
}
