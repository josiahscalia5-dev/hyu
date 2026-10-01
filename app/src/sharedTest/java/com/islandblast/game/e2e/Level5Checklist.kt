package com.islandblast.game.e2e

import android.graphics.Bitmap
import android.view.MotionEvent
import com.islandblast.game.GameView
import com.islandblast.game.model.GameColor
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs

/** How the checklist talks to a running app (Robolectric or a real device). */
interface Driver {
    /** The Level 5 view now on screen. */
    val view: GameView
    val background: Bitmap

    /** Runs on the UI thread and returns the result. */
    fun <T> onMain(block: () -> T): T

    /** Injects a touch at view coordinates. */
    fun touch(action: Int, x: Float, y: Float)

    /** Lets the app run until [cond] holds (checked on the UI thread), or fails after [timeoutS] real seconds. */
    fun waitFor(what: String, timeoutS: Float, cond: () -> Boolean)

    /** The view as currently shown on screen, in view pixels. */
    fun screenshot(): Bitmap

    fun save(bmp: Bitmap, name: String)
    fun log(line: String)
}

/**
 * Plays Level 5 through the real UI and checks, shot by shot:
 *  1. the level starts with a coloured ball            6. the player can aim and shoot again
 *  2. aiming + shooting at matching blocks             7. the colour shift repeats after each clear
 *  3. the matching group is really cleared (and gone    8. play continues until the board is empty
 *     from the screen: no leftover fragments)          9. score, combo, timer, stars, coins update
 *  4. touching blocks change colour, on screen too     10. Level Complete is reached
 *  5. the ball changes to the next colour, on screen
 */
class Level5Checklist(private val d: Driver) {
    private val game: Level5Game get() = d.view.game
    private var shotsSaved = 0

    /*
     * The game clock is held while the harness works (finding the shot, injecting
     * touches, capturing and reading screenshots), which on a slow emulator takes far
     * longer than a player would. It runs only for what a player actually spends time
     * on: the pause to line up a shot, the ball's flight, the clear and its effects.
     */
    private fun hold(held: Boolean) = d.onMain { d.view.clockHeld = held }

    /** Lets game time run until [cond] holds. */
    private fun play(what: String, timeoutS: Float, cond: () -> Boolean) {
        hold(false)
        try {
            d.waitFor(what, timeoutS, cond)
        } finally {
            hold(true)
        }
    }

    private fun capture(): Bitmap = d.screenshot()

    fun run(): String {
        val report = StringBuilder()
        fun say(s: String) {
            report.appendLine(s)
            d.log(s)
        }

        // 1. Start.
        d.waitFor("intro to finish", 60f) { game.time > game.rules.introSeconds + 0.2f && !d.view.effectsBusy }
        hold(true)
        d.onMain {
            assertEquals(Phase.READY, game.phase)
            assertEquals(GameColor.CYAN, game.color)
            assertEquals(31, game.board.aliveCount)
            assertTrue(game.ball.visible)
        }
        val start = capture()
        d.save(start, "01_start")
        verifyScreen(start, "start")
        say("1. start: ${game.color.key} ball loaded, 31 blocks, timer ${game.timerSeconds}s, score ${game.score}")

        var shots = 0
        var shifts = 0
        var switches = 0
        while (!d.onMain { game.over } && shots < 60) {
            // A player takes a moment to line up the shot; the clock runs.
            val t0 = d.onMain { game.time to game.timeLeft }
            play("aiming pause", 60f) { game.time >= t0.first + AIM_SECONDS }
            val (t1, left1) = d.onMain { game.time to game.timeLeft }
            assertTrue("timer counts down while aiming", abs((t0.second - left1) - (t1 - t0.first)) < 1e-3f)

            // Tap the ball to switch colour if the loaded one has no clear line.
            var aim = d.onMain { Bot.bestAim(game) }
            while (aim == null) {
                val before = d.onMain { game.color }
                val ball = d.view.stageToView(game.spec.ballX, game.spec.ballY)
                d.touch(MotionEvent.ACTION_DOWN, ball.x, ball.y)
                d.touch(MotionEvent.ACTION_UP, ball.x, ball.y)
                d.waitFor("colour switch", 30f) { game.color != before }
                switches++
                val shot = capture()
                val hue = ScreenCheck(d.view, shot, d.background)
                    .ballHue(game.spec.ballX, game.spec.ballY, 20f)
                assertTrue("ball shows ${game.color} after tap (hue $hue)", ScreenCheck.reads(hue, game.color))
                say("   tapped the ball: ${before.key} -> ${game.color.key}")
                aim = d.onMain { Bot.bestAim(game) }
                assertTrue("some colour must have a shot", switches < 40)
            }

            val before = d.onMain { Snapshot(game) }
            val group = d.onMain { game.board.group(aim.hit) }
            val shifting = d.onMain { game.board.neighboursOf(group) }
            val colour = before.color

            // 2. Aim with a real drag and release to shoot.
            val (sx, sy) = Bot.aimPoint(game, aim.angleDeg)
            val p = d.view.stageToView(sx, sy)
            d.touch(MotionEvent.ACTION_DOWN, p.x, p.y - 40f)
            d.touch(MotionEvent.ACTION_MOVE, p.x, p.y - 20f)
            d.touch(MotionEvent.ACTION_MOVE, p.x, p.y)
            val guide = d.onMain { game.aimPath() }
            assertTrue("aim guide predicts a matching hit", guide.matches && guide.hit == aim.hit)
            if (shots < 2) d.save(capture(), "%02d_shot%d_aim_%s".format(++shotsSaved + 1, shots + 1, colour.key))
            d.touch(MotionEvent.ACTION_UP, p.x, p.y)
            shots++
            play("ball to fly", 30f) { game.phase != Phase.READY }

            // 3. Group cleared.
            play("hit", 60f) { game.board.aliveCount < before.alive || game.over }
            if (shots <= 2) {
                play("burst", 10f) { game.time > game.board.blocks[group.first()].clearedAt + 0.06f }
                d.save(capture(), "%02d_shot%d_burst".format(++shotsSaved + 1, shots))
            }
            play("next ball", 60f) { game.phase == Phase.READY || game.over }
            d.onMain {
                for (i in group) assertTrue("block ${game.board.blocks[i].spec.id} cleared", !game.board.blocks[i].alive)
                assertEquals(before.alive - group.size, game.board.aliveCount)
            }

            // 4. Neighbours shifted exactly one step; everyone else kept their colour.
            val shiftedNow = d.onMain {
                var n = 0
                for (b in game.board.blocks) {
                    if (!b.alive) continue
                    val was = before.colours.getValue(b.index)
                    if (b.index in shifting) {
                        assertEquals("block ${b.spec.id} shifts once", was.next(), b.color)
                        n++
                    } else assertEquals("block ${b.spec.id} keeps its colour", was, b.color)
                }
                n
            }
            if (shiftedNow > 0) shifts++

            // 9. Score, combo, coins, stars, timer.
            d.onMain {
                val n = group.size
                val combo = before.combo + 1
                val bonus = if (game.phase == Phase.WON) game.rules.clearBonusPerSecond * game.timerSeconds else 0
                assertEquals("combo", combo, game.combo)
                assertEquals("score", before.score + game.rules.pointsPerBlock * n * combo + bonus, game.score)
                assertEquals("coins", before.coins + game.rules.coinsPerBlock * n, game.coins)
                assertEquals("stars", game.rules.starThresholds.count { game.score >= it }, game.stars)
                val expectLeft = before.timeLeft + game.rules.timeBonusPerBlock * n - (game.time - before.time)
                // Once the level is won its timer stops while game time runs on for the win
                // effects, so a frame or two after the win may count in game time only.
                val slack = if (game.phase == Phase.WON) 0.25f else 0f
                assertTrue("timer: got ${game.timeLeft}, expected $expectLeft",
                    game.timeLeft >= expectLeft - 1e-3f && game.timeLeft <= expectLeft + slack + 1e-3f)
            }

            val won = d.onMain { game.phase == Phase.WON }
            if (!won) {
                // 5. Next ball: the colour of the biggest group, shown on the ball.
                d.onMain {
                    assertTrue("next colour is on the board", game.color in game.board.colorsPresent())
                    assertEquals(game.nextColor(prefer = colour.next()), game.color)
                    assertTrue("the new ball has a block it can hit", Bot.bestAim(game) != null)
                }
                play("effects to settle", 30f) { !d.view.effectsBusy }
                val shot = capture()
                verifyScreen(shot, "after shot $shots")
                if (shots <= 2) d.save(shot, "%02d_shot%d_shifted_next_%s".format(++shotsSaved + 1, shots, game.color.key))
            }
            val after = d.onMain { Snapshot(game) }
            say("%2d. %-7s ball -> cleared %d, shifted %d, left %2d | combo x%d  score %,d  coins +%d  stars %d  timer %ds%s".format(
                shots, colour.key, group.size, shiftedNow, after.alive, after.combo, after.score, after.coins,
                after.stars, game.timerSeconds, if (won) "" else "  next: ${game.color.key}"))
        }

        // 10. Level Complete.
        d.onMain {
            assertEquals("level ends in a win", Phase.WON, game.phase)
            assertEquals(0, game.board.aliveCount)
        }
        play("win effects", 30f) { !d.view.effectsBusy }
        d.save(capture(), "90_level_complete")
        say("10. Level Complete after $shots shots ($shifts colour shifts, $switches ball taps): " +
            "score ${game.score}, ${game.stars} stars, +${game.coins} coins, ${game.timerSeconds}s left")

        hold(false)
        return report.toString()
    }

    /** Every live block and the ball show their game colour; cleared areas show only the temple. */
    private fun verifyScreen(shot: Bitmap, where: String) {
        try {
            checkScreen(shot, where)
        } catch (e: AssertionError) {
            d.save(shot, "FAILED_" + where.replace(' ', '_'))
            throw e
        }
    }

    private fun checkScreen(shot: Bitmap, where: String) = d.onMain {
        val sc = ScreenCheck(d.view, shot, d.background)
        for (b in game.board.blocks) {
            if (!b.alive) continue
            val hue = sc.blockHue(b)
            assertTrue("$where: block ${b.spec.id} should show ${b.color} (screen hue $hue)", ScreenCheck.reads(hue, b.color))
        }
        val ballHue = sc.ballHue(game.spec.ballX, game.spec.ballY, 20f)
        assertTrue("$where: ball should show ${game.color} (screen hue $ballHue)", ScreenCheck.reads(ballHue, game.color))
        val live = game.board.blocks.filter { it.alive }.map { it.spec.rect }
        val path = game.aimPath().points
        for (b in game.board.blocks) {
            if (b.alive) continue
            val r = sc.residue(b.spec.rect, live, path)
            assertTrue("$where: leftover fragment in cleared ${b.spec.id} (${r.badCells}/${r.cells} cells, worst ${r.worst} at ${r.worstAt})",
                r.badCells == 0)
        }
    }

    private class Snapshot(g: Level5Game) {
        val color = g.color
        val alive = g.board.aliveCount
        val score = g.score
        val combo = g.combo
        val coins = g.coins
        val stars = g.stars
        val time = g.time
        val timeLeft = g.timeLeft
        val colours = g.board.blocks.associate { it.index to it.color }
    }

    companion object {
        const val AIM_SECONDS = 1.2f
    }
}
