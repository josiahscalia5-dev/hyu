package com.islandblast.game.level6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Storm Dodge rules: pure Kotlin, no Android needed. */
class StormRulesTest {
    private fun game() = StormGame()

    /** Rides straight in the current lane until [cond] or [maxS] seconds. */
    private fun ride(g: StormGame, maxS: Float = 120f, cond: (StormGame) -> Boolean = { false }) {
        var t = 0f
        while (!g.over && t < maxS && !cond(g)) { g.update(1f / 60f); t += 1f / 60f }
    }

    @Test
    fun startsAsPainted() {
        val g = game()
        assertEquals(2310, g.score)
        assertEquals(36, g.timerSeconds)
        assertEquals(2, g.liveStars())
        assertEquals(0, g.section)
        assertEquals(StormPhase.RACING, g.phase)
    }

    @Test
    fun courseHasFiveSectionsAndEveryHazardKind() {
        val objs = StormCourse.build()
        assertEquals(5, StormCourse.sections.size)
        val lane = objs.filter { !it.drift }
        for (k in Kind.entries) assertTrue("course uses $k", lane.any { it.kind == k })
        // Difficulty rises: hazards per 100 units grow from section 1 to section 4.
        val density = (0..3).map { s ->
            val sec = StormCourse.sections[s]
            lane.count { it.kind.hazard && it.section == s } * 100f / (sec.end - sec.start)
        }
        assertTrue("hazard density rises: $density", density[0] < density[1] && density[1] < density[2] && density[2] <= density[3] + 0.5f)
        assertTrue("finish section has no hazards", lane.none { it.kind.hazard && it.section == 4 })
        assertTrue("several coin formations", lane.count { it.kind == Kind.COIN } in 120..360)
        assertTrue("shields in the course", lane.count { it.kind == Kind.SHIELD } >= 3)
    }

    @Test
    fun everyHazardRowLeavesAnOpenLane() {
        val hz = StormCourse.build().filter { it.kind.hazard && !it.drift }
        for (h in hz) {
            val window = hz.filter { abs(it.z - h.z) <= 1.6f }
            val blocked = StormCourse.lanes.count { lane -> window.any { abs(it.x - lane) < 0.5f } }
            assertTrue("all lanes blocked near z=${h.z}", blocked < 3)
        }
    }

    @Test
    fun hittingAHazardPenalisesAndKnocksBack() {
        val g = game()
        val first = g.objects.first { it.kind.hazard && !it.drift }
        g.steerTo(first.x)
        val before = g.score
        ride(g) { it.hits > 0 }
        assertEquals(1, g.hits)
        assertEquals(before - g.rules.hitPenalty + 0, g.score - coinsOnTheWay(g))
        assertTrue("knocked back", g.z < first.z)
    }

    private fun coinsOnTheWay(g: StormGame) = g.coins * g.rules.coinPoints

    @Test
    fun shieldAbsorbsExactlyOneCollision() {
        val g = game()
        val shield = g.objects.first { it.kind == Kind.SHIELD }
        g.steerTo(shield.x)
        ride(g) { it.shielded }
        assertTrue("shield collected", g.shielded)
        // Drive into the next hazards in the current lane.
        // A hazard far enough ahead to steer into before the shield runs out.
        val lane = g.objects.first { it.kind.hazard && !it.drift && it.z > g.z + 8f }
        g.steerTo(lane.x)
        val hitsBefore = g.hits
        ride(g) { it.shieldsUsed > 0 || it.hits > hitsBefore }
        assertEquals("shield blocked the first hit", 1, g.shieldsUsed)
        assertEquals("no damage taken", hitsBefore, g.hits)
        assertFalse("shield is used up", g.shielded)
    }

    @Test
    fun coinsAddPointsAndCheckpointsAddTime() {
        val g = game()
        val coin = g.objects.first { it.kind == Kind.COIN }
        g.steerTo(coin.x)
        ride(g) { it.coins > 0 }
        assertEquals(1, g.coins)
        val left = g.timeLeft
        ride(g) { it.section == 1 }
        assertEquals(1, g.section)
        assertTrue("checkpoint added time", g.timeLeft > left - (g.time) + StormCourse.sections[1].bonusSeconds - 30f)
        assertTrue(g.lastCheckpointAt > 0f)
    }

    /** A careful rider (no coin chasing) can always find a clean line: the course is fair. */
    @Test
    fun aCarefulRiderCanFinishWithoutAHit() {
        val g = game()
        var t = 0f
        while (!g.over && t < 120f) {
            val d = StormBot.decide(g, greedy = false)
            if (d != 0) g.steer(d)
            g.update(1f / 60f)
            t += 1f / 60f
        }
        println("careful rider: ${g.phase} hits=${g.hits} left=${g.timeLeft} coins=${g.coins}/${g.totalCoins} stars=${g.stars}")
        assertEquals(StormPhase.FINISHED, g.phase)
        assertEquals("hits", 0, g.hits)
        assertTrue("time to spare", g.timeLeft > 5f)
    }

    @Test
    fun steeringIsClampedToTheRiver() {
        val g = game()
        repeat(5) { g.steer(-1) }
        assertEquals(-1f, g.targetX)
        repeat(5) { g.steer(1) }
        assertEquals(1f, g.targetX)
        g.steerTo(9f)
        assertTrue(g.targetX <= 1.15f)
    }

    @Test
    fun runsOutOfTimeIfTheRiderStalls() {
        val g = StormGame(StormRules(startSeconds = 3f))
        ride(g)
        assertEquals(StormPhase.OUT_OF_TIME, g.phase)
        assertEquals(0, g.stars)
    }
}
