package com.islandblast.game

import com.islandblast.game.model.Level4Event
import com.islandblast.game.model.Level4Game
import com.islandblast.game.model.Phase
import com.islandblast.game.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Level4RulesTest {
    private val spec get() = TestLevel4.assets.spec

    @Test
    fun startsExactlyAsPainted() {
        val g = Level4Game(spec)
        assertEquals(28, g.timerSeconds)
        assertEquals(1240, g.score)
        assertEquals(8, g.combo)
        assertEquals(2, g.stars)
        assertEquals(33, g.remaining)
        assertEquals(Phase.READY, g.phase)
        // Nothing has moved on the opening frame, and the launcher is in its painted pose.
        for (t in g.treasures) g.hover(t).forEach { assertEquals(0f, it, 1e-6f) }
        assertEquals(0f, g.launcherTurn, 0f)
        // The time bar shows the painted fill: 0:28 of a full bar.
        val h = spec.hud
        val painted = (h.timeBarFill.r - h.timeBarFill.l) / (h.timeBarTrack.r - h.timeBarFill.l)
        assertEquals(painted, g.timeFraction, 0.01f)
        // Every kind of treasure the screen paints is in play.
        assertEquals(TargetKind.entries.toSet(), g.treasures.map { it.spec.kind }.toSet())
    }

    @Test
    fun theAimGuideShowsExactlyWhatTheShotHarvests() {
        val g = Level4Game(spec)
        TestLevel4.run(g, 1.2f)
        repeat(6) {
            val aim = TestLevel4.bestAim(g) ?: return@repeat
            val predicted = g.aimPreview().hits
            val hits = ArrayList<Int>()
            assertTrue(g.shoot())
            TestLevel4.runShot(g) { hits += g.events.filterIsInstance<Level4Event.Hit>().map { it.target }; g.events.clear() }
            assertEquals("shot ${it + 1} at (${aim.x}, ${aim.y})", predicted, hits)
        }
    }

    @Test
    fun eachHarvestRaisesTheComboAndScoresPointsTimesCombo() {
        val g = Level4Game(spec)
        TestLevel4.run(g, 1.2f)
        TestLevel4.bestAim(g)!!
        g.shoot()
        var combo = g.combo
        var score = g.score
        TestLevel4.runShot(g) {
            for (e in g.events.filterIsInstance<Level4Event.Hit>()) {
                combo++
                assertEquals(combo, e.combo)
                assertEquals(g.rules.basePoints(g.treasures[e.target].spec) * combo, e.points)
                score += e.points
                assertFalse(g.treasures[e.target].alive)
            }
            g.events.clear()
        }
        assertEquals(combo, g.combo)
        assertEquals(score, g.score)
    }

    @Test
    fun aChestStopsTheBolt() {
        val g = Level4Game(spec)
        TestLevel4.run(g, 1.2f)
        val chest = TestLevel4.id(g, "chest_gold")
        val c = g.treasures[chest].spec
        // Aim through the chest at something beyond it.
        g.aimAt(c.cx + 60f, c.cy - 260f)
        val preview = g.aimPreview()
        if (chest in preview.hits) {
            assertEquals("the chest is the last thing the bolt harvests", chest, preview.hits.last())
            assertTrue(preview.end < preview.path.length - 1f)
        } else {
            g.aimAt(c.cx, c.cy)
            val p2 = g.aimPreview()
            assertTrue(chest in p2.hits)
            assertEquals(chest, p2.hits.last())
        }
    }

    @Test
    fun aMissResetsTheCombo() {
        val g = Level4Game(spec)
        TestLevel4.run(g, 1.2f)
        val miss = TestLevel4.missAim(g)
        assertTrue("some aim misses everything", miss != null)
        g.shoot()
        TestLevel4.runShot(g)
        assertEquals(1, g.combo)
        assertEquals(1240, g.score)
    }

    @Test
    fun barrelsAddTime() {
        val g = Level4Game(spec)
        TestLevel4.run(g, 1.2f)
        // At the start the jewelled chest shields the barrels; take it out of play.
        g.treasures[TestLevel4.id(g, "chest_jewel")].alive = false
        val barrel = TestLevel4.id(g, "barrel")
        val aim = TestLevel4.aimFor(g, barrel) { hits -> hits.none { g.treasures[it].spec.kind == TargetKind.CHEST } }
        assertTrue("the barrel can be hit", aim != null)
        val hits = aim!!.hits
        val before = g.timeLeft
        g.shoot()
        val t0 = g.time
        TestLevel4.runShot(g)
        val barrels = hits.count { g.treasures[it].spec.kind == TargetKind.BARREL }
        val bonus = g.rules.timeBonusPerHit * hits.size + g.rules.barrelTimeBonus * barrels
        assertEquals(before - (g.time - t0) + bonus, g.timeLeft, 0.05f)
    }

    @Test
    fun pauseFreezesTheLevel() {
        val g = Level4Game(spec)
        g.paused = true
        TestLevel4.run(g, 5f)
        assertEquals(0f, g.time, 0f)
        assertEquals(28, g.timerSeconds)
        assertFalse(g.shoot())
    }

    @Test
    fun runningOutOfTimeEndsTheLevel() {
        val g = Level4Game(spec)
        TestLevel4.run(g, 29f)
        assertEquals(Phase.LOST, g.phase)
        assertEquals(0, g.timerSeconds)
        assertFalse(g.shoot())
    }
}
