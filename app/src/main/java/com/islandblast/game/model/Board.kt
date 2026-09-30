package com.islandblast.game.model

import kotlin.math.max
import kotlin.math.min

class BlockState(val index: Int, val spec: BlockSpec) {
    var color: GameColor = spec.color
    var alive = true

    /** Colour before the most recent shift, and when that shift started (game seconds). */
    var shiftFrom: GameColor? = null
    var shiftAt = -1f

    /** Marked with the swirl icon: changed colour in the most recent shift. */
    var shifted = false

    /** When this block was cleared (for the break-apart animation). */
    var clearedAt = -1f

    /** Last time a wrong-colour ball bounced off it (for the shake). */
    var bumpAt = -1f
}

/**
 * The block formation and the colour-shift rules.
 *
 * Rules (deterministic, so players can learn them):
 *  1. A ball clears the block it hits only if the colours match, and then the whole
 *     connected group of that colour goes with it.
 *  2. Every surviving block that touched the cleared group steps one colour along
 *     the cycle cyan -> violet -> magenta -> red -> yellow -> cyan.
 */
class Board(val spec: LevelSpec) {
    val blocks: List<BlockState> = spec.blocks.mapIndexed { i, b -> BlockState(i, b) }
    val adjacency: List<IntArray> = blocks.map { a ->
        blocks.filter { it !== a && touching(a.spec.rect, it.spec.rect) }.map { it.index }.toIntArray()
    }

    val alive get() = blocks.filter { it.alive }
    val aliveCount get() = blocks.count { it.alive }
    val cleared get() = aliveCount == 0

    fun colorsPresent(): Set<GameColor> = blocks.filter { it.alive }.mapTo(LinkedHashSet()) { it.color }

    /** The connected same-colour group containing [start]. */
    fun group(start: Int): List<Int> {
        val colour = blocks[start].color
        val seen = BooleanArray(blocks.size)
        val out = ArrayList<Int>()
        val stack = ArrayDeque<Int>().apply { add(start) }
        seen[start] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            out += i
            for (j in adjacency[i]) {
                if (!seen[j] && blocks[j].alive && blocks[j].color == colour) {
                    seen[j] = true
                    stack.add(j)
                }
            }
        }
        return out.sorted()
    }

    /** Surviving blocks touching [group] that will shift colour when it is cleared. */
    fun neighboursOf(group: Collection<Int>): List<Int> {
        val inGroup = group.toHashSet()
        return group.flatMap { adjacency[it].asIterable() }
            .filter { it !in inGroup && blocks[it].alive }
            .distinct().sorted()
    }

    /** Removes [group]; returns the neighbours that must shift. Does not shift them. */
    fun clear(group: List<Int>, now: Float): List<Int> {
        val shifting = neighboursOf(group)
        for (i in group) {
            blocks[i].alive = false
            blocks[i].clearedAt = now
            blocks[i].shifted = false
        }
        return shifting
    }

    /** Applies rule 2 to [indices], starting the visible transition at [now]. */
    fun shift(indices: List<Int>, now: Float) {
        for (b in blocks) b.shifted = false
        for (i in indices) {
            val b = blocks[i]
            if (!b.alive) continue
            b.shiftFrom = b.color
            b.color = b.color.next()
            b.shiftAt = now
            b.shifted = true
        }
    }

    /** Colour of the biggest group on the board; ties go to [prefer] then cycle order. */
    fun bestColor(prefer: GameColor?): GameColor? {
        var best: GameColor? = null
        var bestSize = 0
        val seen = BooleanArray(blocks.size)
        val order = GameColor.entries.sortedBy { if (it == prefer) -1 else it.ordinal }
        for (c in order) {
            for (b in blocks) {
                if (!b.alive || b.color != c || seen[b.index]) continue
                val g = group(b.index)
                g.forEach { seen[it] = true }
                if (g.size > bestSize) {
                    bestSize = g.size
                    best = c
                }
            }
        }
        return best
    }

    companion object {
        private const val GAP = 6f
        private const val MIN_SHARED = 14f

        /** Blocks touch when they share a real edge, not just a corner. */
        fun touching(a: Box, b: Box): Boolean {
            val overlapX = min(a.r, b.r) - max(a.l, b.l)
            val overlapY = min(a.b, b.b) - max(a.t, b.t)
            val gapX = max(a.l, b.l) - min(a.r, b.r)
            val gapY = max(a.t, b.t) - min(a.b, b.b)
            return (overlapX >= MIN_SHARED && gapY <= GAP) || (overlapY >= MIN_SHARED && gapX <= GAP)
        }
    }
}
