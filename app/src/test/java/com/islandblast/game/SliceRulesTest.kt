package com.islandblast.game

import com.islandblast.game.model.SliceEvent
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.SlicePhase
import com.islandblast.game.model.SliceRules
import com.islandblast.game.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Level 4 "Mystic Harvest" slicing rules, on the real level layout and settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SliceRulesTest {
    private fun piece(g: SliceGame, id: String) = g.pieces.first { it.spec.id == id }

    /**
     * Swipes left to right through [id]'s centre. The painted burst is packed tight, so
     * the swipe stays within the treasure to cut only it.
     */
    private fun cutAcross(g: SliceGame, id: String, dy: Float = 0f) {
        val p = piece(g, id)
        val pose = g.pose(p)
        TestSlice.swipe(g, pose[0] - p.radius * 0.7f, pose[1] + dy, pose[0] + p.radius * 0.7f, pose[1] + dy)
    }

    private inline fun <reified T : SliceEvent> SliceGame.drain(): List<T> =
        events.filterIsInstance<T>().also { events.clear() }

    @Test
    fun opensWithTheApprovedComposition() {
        val g = TestSlice.game()
        assertEquals(SlicePhase.INTRO, g.phase)
        assertEquals("every painted treasure is in play", TestSlice.assets.spec.targets.size, g.alive.size)
        assertTrue(g.alive.all { it.hovering })
        for (p in g.alive) {
            val pose = g.pose(p)
            assertEquals(p.spec.cx, pose[0], 0.01f)
            assertEquals(p.spec.cy, pose[1], 0.01f)
        }
        assertEquals(0, g.score)
        assertEquals(0, g.stars)
        assertEquals(60, g.timerSeconds)
        TestSlice.run(g, 1f)
        assertEquals("the clock waits for the first swipe", 60, g.timerSeconds)
    }

    @Test
    fun aSwipeAcrossTreasureCutsItInTwo() {
        val g = TestSlice.game()
        cutAcross(g, "gem_green")
        val cut = g.drain<SliceEvent.Cut>()
        assertEquals(1, cut.size)
        val c = cut.single()
        assertEquals("gem_green", c.piece.spec.id)
        assertFalse(c.piece.alive)
        assertEquals("the first swipe starts the level", SlicePhase.PLAYING, g.phase)
        assertEquals(g.rules.bigGemPoints, g.score)
        assertEquals(g.score, g.ledger.sumOf { it.second })
        // The cut runs through the gem, along the swipe.
        assertTrue(SliceGame.segmentHitsHull(c.piece.spec.hull, c.lx - c.ldx * 5f, c.ly - c.ldy * 5f,
            c.lx + c.ldx * 5f, c.ly + c.ldy * 5f, 1f))
        assertEquals(1f, c.ldx, 1e-3f)
        assertEquals(0f, c.ldy, 1e-3f)
    }

    @Test
    fun aSlowDragDoesNotCut() {
        val g = TestSlice.game()
        val p = piece(g, "gem_blue")
        var ms = 0L
        g.touchDown(p.spec.cx - 140f, p.spec.cy, ms)
        var x = p.spec.cx - 140f
        while (x < p.spec.cx + 140f) {
            x += 3f
            ms += 16
            g.touchMove(x, p.spec.cy, ms)
        }
        g.touchUp()
        assertTrue(p.alive)
        assertEquals(0, g.score)
        assertEquals(SlicePhase.INTRO, g.phase)
    }

    @Test
    fun aSwipeThroughEmptyWaterCutsNothing() {
        val g = TestSlice.game()
        TestSlice.swipe(g, 60f, 1300f, 400f, 1320f)
        assertEquals(TestSlice.assets.spec.targets.size, g.alive.size)
        assertEquals(0, g.score)
    }

    @Test
    fun aChestTakesThreeCutsThenBurstsIntoLoot() {
        val g = TestSlice.game()
        val chest = piece(g, "chest_gold")
        assertEquals(3, chest.hp)
        cutAcross(g, "chest_gold")
        assertEquals(2, g.drain<SliceEvent.ChestHit>().single().hitsLeft)
        TestSlice.run(g, 0.2f)
        cutAcross(g, "chest_gold", dy = 20f)
        assertEquals(1, g.drain<SliceEvent.ChestHit>().single().hitsLeft)
        assertTrue(chest.alive)
        TestSlice.run(g, 0.2f)
        cutAcross(g, "chest_gold", dy = -20f)
        val events = ArrayList(g.events)
        assertFalse(chest.alive)
        assertTrue(events.any { it is SliceEvent.Cut && it.piece === chest })
        val loot = events.filterIsInstance<SliceEvent.Tossed>().map { it.piece }
        assertEquals(g.rules.lootCount, loot.size)
        assertTrue("loot is thrown up, and dropping it costs nothing", loot.all { it.bonus && it.vy < 0f })
        assertTrue(loot.all { it.kind == TargetKind.COIN || it.kind == TargetKind.GEM })
        assertEquals(g.score, g.ledger.sumOf { it.second })
        // The loot bursts out under the blade: it flies clear before it can be cut.
        val pose = g.pose(chest)
        TestSlice.swipe(g, pose[0] - 120f, pose[1], pose[0] + 120f, pose[1], frame = false)
        assertTrue("fresh loot is not cut by the same flick", loot.all { it.alive })
        TestSlice.run(g, 0.3f)
        val p = loot.first { it.alive }
        val lp = g.pose(p)
        TestSlice.swipe(g, lp[0] - p.radius * 0.7f, lp[1], lp[0] + p.radius * 0.7f, lp[1], frame = false)
        assertTrue("then it can be sliced", !p.alive)
    }

    @Test
    fun aBarrelAddsTime() {
        val g = TestSlice.game()
        cutAcross(g, "gem_blue")
        TestSlice.run(g, 5f)
        val before = g.timeLeft
        cutAcross(g, "barrel")
        val bonus = g.events.filterIsInstance<SliceEvent.TimeBonus>().single()
        assertEquals(g.rules.barrelSeconds, bonus.seconds, 0f)
        assertEquals(before + g.rules.barrelSeconds - 4f / 60f, g.timeLeft, 0.02f)
    }

    @Test
    fun quickCutsChainTheComboAndAPauseResetsIt() {
        val g = TestSlice.game()
        cutAcross(g, "gem_green")
        assertEquals(1, g.multiplier)
        cutAcross(g, "gem_red")
        assertEquals("a second cut within the window", 2, g.multiplier)
        val c = g.events.filterIsInstance<SliceEvent.Cut>().last()
        assertEquals(g.rules.bigGemPoints * 2, c.points)
        TestSlice.run(g, g.rules.comboWindow + 0.2f)
        cutAcross(g, "gem_blue")
        assertEquals("too slow: the chain starts over", 1, g.multiplier)
        assertEquals(g.score, g.ledger.sumOf { it.second })
    }

    @Test
    fun treasureFallingBackUnslicedBreaksTheCombo() {
        val g = TestSlice.game()
        cutAcross(g, "gem_green")
        cutAcross(g, "gem_red")
        assertEquals(2, g.chain)
        var dropped: SliceEvent.Dropped? = null
        var t = 0f
        while (dropped == null && t < 10f) {
            g.update(1f / 60f)
            t += 1f / 60f
            dropped = g.events.filterIsInstance<SliceEvent.Dropped>().firstOrNull { it.comboLost }
            g.events.clear()
        }
        assertTrue("tossed treasure falls back into the lagoon", dropped != null)
        assertEquals(0, g.chain)
        assertEquals(1, g.dropped)
    }

    @Test
    fun treasureIsTossedUpOutOfTheLagoon() {
        val g = TestSlice.game()
        cutAcross(g, "gem_green")
        g.events.clear()
        var tossed: com.islandblast.game.model.Piece? = null
        var t = 0f
        while (tossed == null && t < 3f) {
            g.update(1f / 60f)
            t += 1f / 60f
            tossed = g.events.filterIsInstance<SliceEvent.Tossed>().firstOrNull()?.piece
        }
        val p = tossed ?: error("nothing tossed")
        assertEquals(SliceGame.TOSS_Y, p.y, 30f)
        assertTrue(p.vy < 0f)
        var top = p.y
        while (p.alive && t < 10f) {
            g.update(1f / 60f)
            t += 1f / 60f
            top = minOf(top, p.y)
        }
        assertTrue("it rises into reach (apex $top)", top in SliceGame.APEX_TOP - 20f..SliceGame.APEX_BOTTOM + 20f)
        assertFalse("and falls back", p.alive)
    }

    @Test
    fun oneSwipeThroughSeveralEarnsABonus() {
        val g = TestSlice.game()
        // The painted burst: the blue gem, the violet chip and the cyan gem sit in a row.
        val s = com.islandblast.game.e2e.SliceBot.plan(g) ?: error("no swipe")
        assertTrue("the bot finds a line through several (${s.targets.size})", s.targets.size >= 3)
        TestSlice.swipe(g, s)
        val bonus = g.events.filterIsInstance<SliceEvent.SwipeBonus>().single()
        val cuts = g.events.count { it is SliceEvent.Cut || it is SliceEvent.ChestHit }
        assertEquals(cuts, bonus.cuts)
        assertEquals(g.score, g.ledger.sumOf { it.second })
    }

    @Test
    fun theClockStartsByItselfAfterTheIntro() {
        val g = TestSlice.game()
        TestSlice.run(g, g.rules.introHold + 0.1f)
        assertEquals(SlicePhase.PLAYING, g.phase)
        TestSlice.run(g, 3f)
        assertEquals(57, g.timerSeconds)
    }

    @Test
    fun timeUpWinsAtTheGoalAndLosesBelowIt() {
        val lose = TestSlice.game(SliceRules(seconds = 5f, starScores = intArrayOf(100000, 200000, 300000)))
        TestSlice.run(lose, lose.rules.introHold + 6f)
        assertEquals(SlicePhase.LOST, lose.phase)
        assertTrue(lose.events.any { it is SliceEvent.Lost })

        val win = TestSlice.game(SliceRules(seconds = 5f, starScores = intArrayOf(20, 60, 100000)))
        cutAcross(win, "gem_green")
        cutAcross(win, "gem_red")
        TestSlice.run(win, 6f)
        assertEquals(SlicePhase.WON, win.phase)
        assertEquals(2, win.stars)
        // Nothing more can be cut once the level is over.
        val score = win.score
        cutAcross(win, "gem_blue")
        assertEquals(score, win.score)
    }

    @Test
    fun pausingFreezesTheLevel() {
        val g = TestSlice.game()
        cutAcross(g, "gem_green")
        TestSlice.run(g, 2f)
        g.paused = true
        val t = g.timeLeft
        val poses = g.alive.map { g.pose(it).toList() }
        TestSlice.run(g, 2f)
        assertEquals(t, g.timeLeft, 0f)
        assertEquals(poses, g.alive.map { g.pose(it).toList() })
        cutAcross(g, "gem_blue")
        assertTrue("no cutting while paused", piece(g, "gem_blue").alive)
    }
}
