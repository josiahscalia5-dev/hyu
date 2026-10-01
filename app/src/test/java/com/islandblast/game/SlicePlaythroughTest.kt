package com.islandblast.game

import com.islandblast.game.e2e.SliceBot
import com.islandblast.game.model.SliceEvent
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.SlicePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plays Level 4 start to finish on the real rules, two ways: a keen player who always
 * finds the best line, and a casual one who cuts one treasure at a time with a pause
 * between swipes. Both must be able to pass (the goal is reachable without perfect
 * play); only the keen player should take all three stars.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlicePlaythroughTest {
    private class Stats(val score: Int, val stars: Int, val phase: SlicePhase, val cuts: Int, val dropped: Int,
                        val maxMult: Int, val swipes: Int, val chests: Int)

    private fun play(pause: Float, single: Boolean): Stats {
        val g = TestSlice.game()
        var swipes = 0
        var maxMult = 1
        var chests = 0
        var idle = 0f
        while (!g.over) {
            if (idle > 0f) {
                g.update(1f / 60f)
                idle -= 1f / 60f
            } else {
                val plan = SliceBot.plan(g)
                if (plan == null) {
                    g.update(1f / 60f)
                } else {
                    val s = if (single) singleTarget(g, plan) else plan
                    TestSlice.swipe(g, s)
                    swipes++
                    idle = pause
                }
            }
            maxMult = maxOf(maxMult, g.multiplier)
            chests += g.events.count { it is SliceEvent.Cut && it.piece.kind == com.islandblast.game.model.TargetKind.CHEST }
            g.events.clear()
            assertEquals(g.score, g.ledger.sumOf { it.second })
        }
        return Stats(g.score, g.stars, g.phase, g.cuts, g.dropped, maxMult, swipes, chests)
    }

    /** A short swipe through just the first treasure of the plan. */
    private fun singleTarget(g: SliceGame, s: SliceBot.Swipe): SliceBot.Swipe {
        val p = s.targets.first()
        val pose = g.pose(p)
        val r = p.radius + 40f
        return SliceBot.Swipe(pose[0] - r, pose[1] - r * 0.3f, pose[0] + r, pose[1] + r * 0.3f, listOf(p))
    }

    private fun Stats.line(name: String) = "%-7s %s score %,6d  stars %d  cuts %3d  dropped %3d  best combo x%d  swipes %d  chests %d"
        .format(name, phase, score, stars, cuts, dropped, maxMult, swipes, chests)

    @Test
    fun everyKindOfPlayerCanPassAndOnlyKeenOnesTakeThreeStars() {
        val keen = play(pause = 0.05f, single = false)
        val casual = play(pause = 0.45f, single = true)
        val beginner = play(pause = 0.9f, single = true)
        println(keen.line("keen"))
        println(casual.line("casual"))
        println(beginner.line("beginner"))
        assertEquals(SlicePhase.WON, keen.phase)
        assertEquals("a keen player takes all three stars", 3, keen.stars)
        assertEquals("a casual player passes", SlicePhase.WON, casual.phase)
        assertTrue("but does not take all three stars", casual.stars < 3)
        assertEquals("so does a beginner", SlicePhase.WON, beginner.phase)
        assertEquals("with one star", 1, beginner.stars)
        assertTrue("chests get broken open", keen.chests >= 2)
    }
}
