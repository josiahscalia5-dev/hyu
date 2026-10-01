package com.islandblast.game.e2e

import android.graphics.Bitmap
import android.view.MotionEvent
import com.islandblast.game.SliceView
import com.islandblast.game.levels.LevelOverlay
import com.islandblast.game.levels.LevelScreen
import com.islandblast.game.model.SlicePhase
import com.islandblast.game.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * Plays Level 4 through the real UI: real swipes (down, moves, up) across the
 * treasure, the pause button, and the timer running out. After every swipe it checks:
 *  - what the blade crossed is cut: gone from play, with two halves of its art flying;
 *  - a chest takes three cuts (one per swipe) before it bursts and spills loot;
 *  - a barrel adds its seconds to the clock;
 *  - the score is exactly the sum of what was earned, the combo follows the chain,
 *    stars follow the score;
 * and at the end that the level is won at the goal with the right stars.
 *
 * The game clock is held while the harness plans and injects a swipe (on a slow
 * emulator that takes far longer than a player's flick); it runs between swipes.
 *
 * Not part of the World 1 flow while Mystic Harvest is off the map (it is not Level 4,
 * Jungle Zip); run it from [FlowChecklist] again if the level is placed on a map.
 */
class SliceChecklist(
    private val d: FlowDriver,
    private val screen: LevelScreen,
    private val capture: (String) -> Bitmap,
    private val say: (String) -> Unit,
) {
    private val view get() = screen.play as SliceView
    private val game get() = view.game

    private fun hold(h: Boolean) = d.onMain { view.clockHeld = h }

    fun run() {
        d.onMain {
            assertEquals(SlicePhase.INTRO, game.phase)
            assertEquals("the opening burst: every painted treasure", game.spec.targets.size, game.alive.size)
            assertEquals(0, game.score)
            assertEquals(game.rules.seconds.toInt(), game.timerSeconds)
        }
        say("   start: ${game.alive.size} treasures hover where the approved screen paints them; " +
            "goal %,d points in %ds; the clock waits for the first swipe".format(game.rules.goal, game.timerSeconds))

        var swipes = 0
        var cuts = 0
        var chestHits = 0
        var chestsBurst = 0
        var barrels = 0
        var bestCombo = 1
        var paused = false
        var savedCut = false
        var savedCrack = false
        var savedMulti = false
        while (!d.onMain { game.over }) {
            hold(true)
            val plan = d.onMain { SliceBot.plan(game) }
            if (plan == null) {
                hold(false)
                d.idle(0.05f)
                continue
            }
            val before = d.onMain { Snap(this) }
            // A real swipe: finger down, four moves across, up.
            val pts = SliceBot.points(plan, 4).map { (x, y) -> d.onMain { view.stageToView(x, y) } }
            d.touch(MotionEvent.ACTION_DOWN, pts[0].x, pts[0].y)
            for (i in 1 until pts.size) d.touch(MotionEvent.ACTION_MOVE, pts[i].x, pts[i].y)
            d.touch(MotionEvent.ACTION_UP, pts.last().x, pts.last().y)
            swipes++
            val after = d.onMain { Snap(this) }

            // What was cut is gone, and flies apart in two halves.
            val gone = before.alive - after.alive
            val newCuts = after.cuts - before.cuts
            assertEquals("swipe $swipes: every cut treasure left play", gone.size, newCuts)
            d.onMain { assertTrue("swipe $swipes: halves of the cut treasure are flying", newCuts == 0 || view.halvesInFlight >= 2) }
            // Chests: one cut per swipe until they burst.
            for ((id, hp) in before.chestHp) {
                val now = after.chestHp[id]
                if (now != null && now < hp) {
                    assertEquals("a chest loses one hit per swipe", hp - 1, now)
                    chestHits++
                }
            }
            val burst = gone.count { it in before.chestHp }
            chestsBurst += burst
            if (burst > 0) d.onMain { assertTrue("a burst chest throws up loot", game.alive.count { it.bonus } >= game.rules.lootCount) }
            // Barrels add time (the clock is held during the swipe).
            val barrelCuts = gone.count { it in before.barrels }
            barrels += barrelCuts
            val expectLeft = minOf(game.rules.seconds, before.timeLeft + barrelCuts * game.rules.barrelSeconds)
            assertTrue("swipe $swipes: timer ${after.timeLeft} vs $expectLeft", abs(after.timeLeft - expectLeft) < 1e-3f ||
                barrelCuts > 1)
            // Score is exactly what was earned; stars follow it.
            d.onMain {
                assertEquals("score is the sum of every cut", game.ledger.sumOf { it.second }, game.score)
                assertEquals("stars follow the score", game.rules.starScores.count { game.score >= it }, game.stars)
            }
            cuts += newCuts
            bestCombo = maxOf(bestCombo, after.multiplier)
            if (swipes == 1) {
                d.onMain { assertEquals("the first swipe starts the level", SlicePhase.PLAYING, game.phase) }
            }
            if (!savedCut && newCuts >= 1) {
                capture("level4_first_cut")
                savedCut = true
                say("   swipe 1 cuts ${newCuts}: ${gone.joinToString()} — each splits in two along the blade (" +
                    "${d.onMain { view.halvesInFlight }} halves flying)")
            } else if (!savedMulti && newCuts >= 4) {
                capture("level4_multi_cut")
                savedMulti = true
                say("   swipe $swipes cuts $newCuts at once (combo x${after.multiplier})")
            } else if (!savedCrack && after.chestHp.values.any { it in 1 until game.rules.chestHits }) {
                capture("level4_chest_cracked")
                savedCrack = true
            }

            // The pause button pauses, Resume resumes.
            if (!paused && d.onMain { game.clock } > 20f) {
                val p = d.onMain { game.spec.hud.pause.let { view.stageToView(it.cx, it.cy) } }
                d.touch(MotionEvent.ACTION_DOWN, p.x, p.y)
                d.touch(MotionEvent.ACTION_UP, p.x, p.y)
                d.waitFor("pause menu", 10f) { screen.overlay.mode == LevelOverlay.Mode.PAUSE && screen.overlay.ready }
                val left = d.onMain { game.timeLeft }
                hold(false)
                d.idle(0.5f)
                d.onMain { assertEquals("time stands still while paused", left, game.timeLeft, 0f) }
                capture("level4_paused")
                val r = d.onMain { screen.overlay.buttonCenter(LevelOverlay.Action.RESUME)!! }
                d.touch(MotionEvent.ACTION_DOWN, r.x, r.y)
                d.touch(MotionEvent.ACTION_UP, r.x, r.y)
                d.waitFor("resume", 10f) { !game.paused && screen.overlay.mode == LevelOverlay.Mode.NONE }
                paused = true
                say("   pause button → Paused menu (clock stopped) → Resume")
            }
            if (swipes % 40 == 0) {
                say("   %ds left: score %,d, %d stars, %d cut, combo x%d".format(
                    d.onMain { game.timerSeconds }, after.score, after.stars, cuts, after.multiplier))
            }
            if (swipes == 60) capture("level4_mid_level")
            // A player's moment between flicks: the clock runs.
            hold(false)
            d.idle(0.12f)
        }
        hold(false)
        d.onMain {
            assertEquals("time ran out with the goal reached: Level Complete", SlicePhase.WON, game.phase)
            assertEquals(0, game.timerSeconds)
            assertTrue(game.score >= game.rules.goal)
        }
        assertTrue("chests were cracked open over the level", chestsBurst >= 1 && chestHits >= 2)
        say("   time up: Level Complete — score %,d, %d stars, %d treasures cut in %d swipes, %d chests burst, %d barrels (+time), best combo x%d, %d dropped"
            .format(game.score, game.stars, cuts, swipes, chestsBurst, barrels, bestCombo, game.dropped))
    }

    private class Snap(c: SliceChecklist) {
        private val g = c.game
        val alive: Set<String> = g.alive.map { label(it) }.toSet()
        val chestHp: Map<String, Int> = g.alive.filter { it.kind == TargetKind.CHEST }.associate { label(it) to it.hp }
        val barrels: Set<String> = g.alive.filter { it.kind == TargetKind.BARREL }.map { label(it) }.toSet()
        val cuts = g.cuts
        val score = g.score
        val stars = g.stars
        val timeLeft = g.timeLeft
        val multiplier = g.multiplier

        companion object {
            fun label(p: com.islandblast.game.model.Piece) = "${p.spec.id}#${p.id}"
        }
    }
}
