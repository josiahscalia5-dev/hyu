package com.islandblast.game

import com.islandblast.game.model.GameEvent
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plays the whole level with the real physics: aim with the aim guide, shoot,
 * watch the matching group clear and its neighbours shift, pick up the next ball,
 * repeat until the formation is gone. A human-like pause is spent aiming each shot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaythroughTest {
    @Test
    fun levelCanBeClearedBeforeTheTimerRunsOut() {
        val g = Level5Game(TestLevel.spec)
        val log = StringBuilder()
        var shifts = 0
        var shots = 0
        while (!g.over && shots < 60) {
            TestLevel.run(g, AIM_SECONDS)
            if (g.over) break
            var aim = TestLevel.bestAim(g)
            var switches = 0
            while (aim == null && switches < 5) {
                g.switchColor(); switches++
                aim = TestLevel.bestAim(g)
            }
            assertNotNull("no reachable target for any colour at shot $shots", aim)
            TestLevel.aimAtAngle(g, aim!!.angleDeg)
            val before = TestLevel.colorsOf(g)
            val colour = g.color
            val target = g.board.blocks[aim.hit].spec.id
            val group = g.board.group(aim.hit).map { g.board.blocks[it].spec.id }.toSet()
            val expectShift = g.board.neighboursOf(g.board.group(aim.hit)).map { g.board.blocks[it].spec.id }.toSet()
            assertTrue(g.shoot())
            shots++
            var sawClear = false
            TestLevel.runShot(g) { game ->
                for (e in game.events) when (e) {
                    is GameEvent.Cleared -> sawClear = true
                    is GameEvent.Shifted -> shifts++
                    else -> Unit
                }
                game.events.clear()
            }
            assertTrue("shot $shots at $target ($colour) should clear it", sawClear)
            val after = TestLevel.colorsOf(g)
            for (id in group) assertTrue("$id should be cleared", id !in after)
            for ((id, c) in after) {
                val want = if (id in expectShift) before.getValue(id).next() else before.getValue(id)
                assertEquals("shot $shots: $id colour after shift", want, c)
            }
            log.append("shot $shots: ${colour.key} ball @ ${"%.1f".format(aim.angleDeg)}° -> $target, " +
                "cleared ${group.size}, shifted ${expectShift.size}, left ${g.board.aliveCount}, " +
                "combo x${g.combo}, time ${g.timerSeconds}s\n")
        }
        println(log)
        println("shots=$shots shifts=$shifts score=${g.score} stars=${g.stars} timeLeft=${g.timeLeft}")
        assertEquals(Phase.WON, g.phase)
        assertEquals(0, g.board.aliveCount)
        assertTrue("colour shifts must happen during the level", shifts >= 3)
        assertTrue(g.timeLeft > 0f)
    }

    companion object {
        /** Time a player spends lining up each shot. */
        const val AIM_SECONDS = 1.2f
    }
}
