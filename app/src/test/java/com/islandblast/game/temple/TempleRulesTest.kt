package com.islandblast.game.temple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Temple Chase rules: pure Kotlin, no Android needed. */
class TempleRulesTest {
    private fun run(g: TempleGame, maxS: Float = 150f, cond: (TempleGame) -> Boolean = { false }) {
        var t = 0f
        while (!g.over && t < maxS && !cond(g)) { g.update(1f / 60f); t += 1f / 60f }
    }

    /** Plays with the bot pressing arrows (and optionally using power-ups). */
    private fun play(g: TempleGame, usePowers: Boolean = false, greedy: Boolean = true): TempleGame {
        var t = 0f
        while (!g.over && t < 200f) {
            when (TempleBot.decide(g, greedy = greedy)) {
                -1 -> g.steer(-1)
                1 -> g.steer(1)
            }
            if (usePowers && !g.introActive) {
                if (g.section >= 1 && !g.active(Power.MAGNET)) g.use(Power.MAGNET)
                if (g.section >= 2 && !g.active(Power.SHIELD)) g.use(Power.SHIELD)
                if (g.section == 3 && !g.active(Power.LIGHTNING)) g.use(Power.LIGHTNING)
            }
            g.update(1f / 60f); t += 1f / 60f
        }
        return g
    }

    @Test
    fun startsAsPainted() {
        val g = TempleGame()
        assertEquals(1960, g.score)
        assertEquals(124, g.coins)
        assertEquals(35, g.timerSeconds)
        assertEquals(2, g.hearts)
        assertEquals(2, g.stars)
        assertEquals(listOf(3, 3, 3), g.powers.toList())
        assertEquals(TemplePhase.RUNNING, g.phase)
    }

    @Test
    fun holdsStillForTheIntroThenRuns() {
        val g = TempleGame()
        run(g, 1.0f)
        assertEquals(0f, g.dist, 0f)
        assertEquals(35, g.timerSeconds)
        run(g, 1.0f)
        assertTrue("running after the intro", g.dist > 4f)
        assertTrue("timer counts down once running", g.timerSeconds <= 35)
    }

    @Test
    fun courseIsFairAndUsesEveryItem() {
        val objs = TempleCourse.build()
        assertEquals(4, TempleCourse.sections.size)
        val lane = objs.filter { !it.decor }
        for (k in Item.entries) assertTrue("course uses $k", lane.any { it.kind == k })
        val hz = lane.filter { it.kind.hazard }
        for (h in hz) {
            val window = hz.filter { abs(it.z - h.z) <= 1.6f }
            val blocked = TempleCourse.lanes.count { l -> window.any { abs(it.x - l) < 0.5f } }
            assertTrue("all lanes blocked near z=${h.z}", blocked < 3)
        }
        val density = (0..3).map { s ->
            val sec = TempleCourse.sections[s]
            hz.count { it.section == s } * 100f / (sec.end - sec.start)
        }
        assertTrue("hazards get denser: $density", density[0] < density[1] && density[1] <= density[3] + 0.5f)
        assertTrue("plenty of coins", lane.count { it.kind == Item.COIN } in 100..300)
        assertTrue("gems to chase", lane.count { it.kind == Item.GEM } >= 8)
    }

    @Test
    fun aHitCostsAHeartAndTwoHitsAreCaught() {
        val g = TempleGame()
        val first = g.objects.first { it.kind.hazard && !it.decor }
        g.steerTo(first.x)
        run(g) { it.hits > 0 }
        assertEquals(1, g.hits)
        assertEquals(1, g.hearts)
        assertTrue("slowed right after the hit", g.speed < TempleCourse.sections[g.section].speed)
        assertTrue(g.invulnerable)
        // keep running into hazards
        run(g) {
            val next = it.objects.firstOrNull { o -> o.kind.hazard && !o.decor && !it.isTaken(o) && o.z > it.dist + 0.6f }
            if (next != null) it.steerTo(next.x)
            false
        }
        assertEquals(TemplePhase.CAUGHT, g.phase)
        assertEquals(0, g.hearts)
    }

    @Test
    fun shieldBlocksOneHit() {
        val g = TempleGame()
        run(g, 1.4f)
        assertTrue(g.use(Power.SHIELD))
        assertEquals(2, g.powers[Power.SHIELD.ordinal])
        assertFalse("can't stack the same power", g.use(Power.SHIELD))
        val first = g.objects.first { it.kind.hazard && !it.decor && it.z > g.dist + 1f }
        g.steerTo(first.x)
        run(g) { it.isTaken(first) }
        assertEquals("shield took the hit", 2, g.hearts)
        assertFalse("shield is used up", g.active(Power.SHIELD))
    }

    @Test
    fun lightningSmashesThroughAndSpeedsUp() {
        val g = TempleGame()
        run(g, 1.4f)
        val before = g.speed
        assertTrue(g.use(Power.LIGHTNING))
        assertTrue(g.speed > before * 1.4f)
        val first = g.objects.first { it.kind.hazard && !it.decor && it.z > g.dist + 1f }
        g.steerTo(first.x)
        val score0 = g.score
        run(g) { it.isTaken(first) || !it.active(Power.LIGHTNING) }
        assertTrue("smashed", g.isTaken(first))
        assertEquals(2, g.hearts)
        assertTrue(g.score >= score0 + g.rules.smashPoints)
    }

    @Test
    fun magnetPullsInCoinsFromOtherLanes() {
        val g = TempleGame()
        run(g, 1.4f)
        g.use(Power.MAGNET)
        val coinsBefore = g.coins
        // stay in the middle; the painted opening has coins in the side lanes
        run(g, 3f)
        val sideCoins = g.objects.count { it.kind == Item.COIN && g.isTaken(it) && abs(it.x) > 0.9f }
        assertTrue("side-lane coins collected by the magnet: $sideCoins", sideCoins >= 2)
        assertTrue(g.coins > coinsBefore)
    }

    @Test
    fun checkpointsAddTime() {
        val g = TempleGame()
        var events = 0
        var t = 0f
        while (!g.over && g.section < 1 && t < 60f) {
            when (TempleBot.decide(g)) { -1 -> g.steer(-1); 1 -> g.steer(1) }
            g.update(1f / 60f); t += 1f / 60f
            events += g.events.count { it is TempleEvent.SectionStart }
            g.events.clear()
        }
        assertEquals(1, g.section)
        assertEquals(1, events)
        assertTrue("checkpoint just now", g.time - g.lastCheckpointAt < 0.1f)
    }

    @Test
    fun aCarefulRunnerEscapesTheTemple() {
        val g = play(TempleGame(), usePowers = true)
        println("careful: phase=${g.phase} time=${g.time} left=${g.timeLeft} coins=${g.coinsTaken}/${g.totalCoins} gems=${g.gemsTaken}/${g.totalGems} hits=${g.hits} score=${g.score} stars=${g.stars}")
        assertEquals(TemplePhase.COMPLETE, g.phase)
        assertEquals(0, g.hits)
        assertEquals(3, g.stars)
        assertTrue("finish bonus counted", g.finishBonus > 0)
    }

    @Test
    fun aPlayerWithoutPowerUpsCanStillFinish() {
        val g = play(TempleGame(), usePowers = false, greedy = false)
        println("no powers: phase=${g.phase} left=${g.timeLeft} coins=${g.coinsTaken} hits=${g.hits} score=${g.score} stars=${g.stars}")
        assertEquals(TemplePhase.COMPLETE, g.phase)
        assertTrue("some time to spare", g.timeLeft > 2f)
    }

    @Test
    fun standingStillRunsOutOfTimeOrGetsCaught() {
        val g = TempleGame()
        run(g, 200f)
        assertTrue(g.phase == TemplePhase.CAUGHT || g.phase == TemplePhase.OUT_OF_TIME)
    }
}
