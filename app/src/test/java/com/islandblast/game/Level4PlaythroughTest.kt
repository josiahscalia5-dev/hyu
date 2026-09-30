package com.islandblast.game

import com.islandblast.game.model.Level4Event
import com.islandblast.game.model.Level4Game
import com.islandblast.game.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A bot plays Level 4 the way a player would: it takes 1.2 s to aim each shot, aims
 * where the real aim guide shows the most treasure, and must harvest everything before
 * the time runs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Level4PlaythroughTest {
    @Test
    fun aPlayerCanHarvestEverythingInTime() {
        val g = Level4Game(TestLevel4.assets.spec)
        TestLevel4.run(g, g.rules.introSeconds)
        var shots = 0
        while (!g.over && shots < 60) {
            TestLevel4.run(g, 1.2f)
            if (g.over) break
            val aim = TestLevel4.bestAim(g)
            assertTrue("no treasure reachable with ${g.remaining} left: " +
                g.treasures.filter { it.alive }.joinToString { it.spec.id }, aim != null)
            g.shoot()
            shots++
            var harvested = 0
            TestLevel4.runShot(g) { harvested += g.events.count { it is Level4Event.Hit }; g.events.clear() }
            assertEquals("shot $shots harvests what the guide showed", aim!!.hits.size, harvested)
            println("shot %2d: aim (%3.0f, %4.0f) harvested %d, left %2d | combo x%d score %,d stars %d time %.1fs"
                .format(shots, aim.x, aim.y, harvested, g.remaining, g.combo, g.score, g.stars, g.timeLeft))
        }
        println("result: %s after %d shots, score %,d, %d stars, %.1fs left".format(g.phase, shots, g.score, g.stars, g.timeLeft))
        assertEquals(Phase.WON, g.phase)
        assertEquals(0, g.remaining)
        assertTrue(g.stars >= 2)
    }
}
