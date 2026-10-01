package com.islandblast.game.e2e

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.MotionEvent
import com.islandblast.game.GameView
import com.islandblast.game.MainActivity
import com.islandblast.game.SliceView
import com.islandblast.game.app.Screen
import com.islandblast.game.home.HomeScreen
import com.islandblast.game.level6.StormBot
import com.islandblast.game.level6.StormView
import com.islandblast.game.levels.LevelOverlay
import com.islandblast.game.levels.LevelScreen
import com.islandblast.game.levels.PlayState
import com.islandblast.game.map.MapScreen
import com.islandblast.game.map.WorldCompleteScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** How the flow checklist talks to the running app (Robolectric or a real device). */
interface FlowDriver {
    val activity: MainActivity

    /** Level 5's temple with every moving part removed, for its screen checks. */
    val level5Background: Bitmap

    fun <T> onMain(block: () -> T): T

    /** A touch at screen coordinates of the app's content area. */
    fun touch(action: Int, x: Float, y: Float)

    /** Lets the app run until [cond] holds (checked on the UI thread), or fails after [timeoutS] seconds. */
    fun waitFor(what: String, timeoutS: Float, cond: () -> Boolean)

    /** Lets the app run for [seconds]. */
    fun idle(seconds: Float)

    /** The whole screen as the app draws it. */
    fun screenshot(): Bitmap
    fun save(bmp: Bitmap, name: String)
    fun log(line: String)
    fun pressBack()
}

/**
 * The whole game as a player meets it, with real touches:
 * Launch → Home → PLAY → World 1 map (Levels 1–4 coming soon) → Level 5 → complete it →
 * back on the map → Level 6 → complete it → World 1 Complete → World 2 Unlocked → back
 * on the map (World 2's gate open) → Back → Home.
 * Every screen is captured and checked on the way.
 */
class FlowChecklist(private val d: FlowDriver) {
    private val report = StringBuilder()
    private var shot = 0

    private fun say(s: String) {
        report.appendLine(s)
        d.log(s)
    }

    private val screen: Screen? get() = d.onMain { d.activity.flow.navigator.current }

    private fun capture(name: String): Bitmap = d.screenshot().also { d.save(it, "%02d_%s".format(++shot, name)) }

    private fun tap(p: PointF) {
        d.touch(MotionEvent.ACTION_DOWN, p.x, p.y)
        d.touch(MotionEvent.ACTION_UP, p.x, p.y)
    }

    fun run(): String {
        val world = d.onMain { d.activity.flow.catalog.worlds.first() }
        val l5 = world.level(5)!!
        val l6 = world.level(6)!!

        // 1. The app opens on the home screen.
        d.waitFor("home screen", 30f) { screen is HomeScreen && (screen as HomeScreen).home.width > 0 }
        val home = (screen as HomeScreen).home
        idle(0.5f)
        capture("home")
        d.onMain {
            val w = home.width.toFloat()
            val h = home.height.toFloat()
            for ((name, r) in home.mustShow()) {
                assertTrue("home: $name ($r) is cut off the ${w}x$h screen",
                    r.left >= -0.5f && r.top >= -0.5f && r.right <= w + 0.5f && r.bottom <= h + 0.5f)
            }
        }
        say("1. Home screen: logo, character, PLAY, top bar and bottom nav all on screen")

        // 2. PLAY opens the World 1 map.
        val play = d.onMain { home.targets().getValue("play").let { PointF(it.centerX(), it.centerY()) } }
        tap(play)
        d.waitFor("World 1 map", 30f) { screen is MapScreen }
        val map = (screen as MapScreen).map
        d.waitFor("map laid out", 10f) { map.width > 0 }
        idle(0.5f)
        capture("world1_map")
        d.onMain {
            assertEquals("Tropical Islands", map.world.name)
            assertEquals("World 1 has Levels 1-6", (1..6).toList(), world.levels.map { it.number })
            assertEquals("only Levels 5 and 6 are built", listOf(5, 6), world.levels.filter { it.playable }.map { it.number })
            for (lv in world.levels.take(4)) assertTrue("level ${lv.number} has no name until it is built", lv.name.isEmpty())
            assertEquals("Temple Chase", l5.name)
            assertEquals("Storm Dodge", l6.name)
            assertEquals("Level 6 is the world's last level", l6, world.lastLevel)
            assertEquals("the marker starts on Level 5, the first built level", l5, map.current)
            val buttons = map.world.levels.map { "level ${it.number}" to map.nodeCenter(it) } + ("World 2 gate" to map.gateCenter()!!)
            for ((name, c) in buttons) {
                assertTrue("map: $name button off screen at $c",
                    c.x - map.nodeRadius() >= 0f && c.x + map.nodeRadius() <= map.width &&
                        c.y - map.nodeRadius() >= 0f && c.y + map.nodeRadius() <= map.height)
            }
            assertFalse("World 2 starts locked", d.activity.flow.progress.worldUnlocked(2))
        }
        say("2. PLAY → World 1 \"${world.name}\": ${world.levels.size} levels " +
            "(${world.levels.joinToString { if (it.playable) "${it.number} ${it.name}" else "${it.number} coming soon" }}), " +
            "playable ${world.levels.filter { it.playable }.map { it.number }}; the marker is on Level 5; World 2's gate is locked")

        // Levels that are not built yet only say so (Level 4 no longer opens anything).
        for (soon in world.levels.filter { !it.playable }) {
            tap(d.onMain { map.nodeCenter(soon) })
            idle(0.3f)
            assertTrue("coming-soon level ${soon.number} keeps the player on the map", screen is MapScreen)
            if (soon.number == 1 || soon.number == 4) capture("level${soon.number}_coming_soon")
            idle(1.8f)
        }
        say("   Levels 1–4 (not built yet) each show \"Level N is coming soon!\" and stay on the map")
        tap(d.onMain { map.gateCenter()!! })
        idle(0.3f)
        assertTrue("the locked World 2 gate keeps the player on the map", screen is MapScreen)
        capture("world2_locked_toast")
        say("   World 2's gate (locked) says to finish World 1 first")

        // 3. Level 5 from the map, played to Level Complete.
        tap(d.onMain { map.nodeCenter(l5) })
        d.waitFor("Level 5", 30f) { (screen as? LevelScreen)?.level == l5 }
        val s5 = screen as LevelScreen
        assertTrue("Level 5 is the colour-shift block game", s5.play is GameView)
        d.waitFor("Level 5 laid out", 10f) { s5.play.view.width > 0 }
        idle(0.3f)
        capture("level5_start")
        say("3. Level 5 \"${l5.name}\" opens from the map (the colour-shift block level)")
        val level5 = Level5Checklist(object : Driver {
            override val view: GameView get() = s5.play as GameView
            override val background: Bitmap get() = d.level5Background
            override fun <T> onMain(block: () -> T): T = d.onMain(block)
            override fun touch(action: Int, x: Float, y: Float) = d.touch(action, x, y)
            override fun waitFor(what: String, timeoutS: Float, cond: () -> Boolean) = d.waitFor(what, timeoutS, cond)
            override fun screenshot(): Bitmap = d.screenshot()
            override fun save(bmp: Bitmap, name: String) = d.save(bmp, "level5_$name")
            override fun log(line: String) = d.log(line)
        }).run()
        report.append(level5.prependIndent("   "))
        finishLevel(s5, "level5")
        d.waitFor("back on the map", 30f) { screen is MapScreen }
        val map3 = (screen as MapScreen).map
        idle(2.4f)
        capture("map_after_level5")
        val stars5 = d.onMain { d.activity.flow.progress.stars(l5) }
        d.onMain {
            assertTrue("Level 5 is recorded complete", d.activity.flow.progress.completed(l5))
            assertEquals(s5.play.stars, stars5)
            assertTrue(map3.world === world)
        }
        d.onMain { assertEquals("the marker moves on to Level 6", l6, map3.current) }
        say("   Continue → back on World 1: Level 5 shows $stars5 stars, the marker moves on to Level 6; World 1 total ${d.onMain {
            d.activity.flow.progress.totalStars(world) }} stars")

        // 4. Level 6 from the map, played to Level Complete, then World 1 Complete.
        val map4 = level6(map3, l6)

        // 5. Back from the map returns home.
        d.pressBack()
        d.waitFor("home again", 30f) { screen is HomeScreen }
        idle(0.5f)
        capture("home_again")
        say("5. Back from the map → Home")
        d.onMain { assertTrue(map4.world === world) }
        return report.toString()
    }

    private fun level6(map: com.islandblast.game.map.WorldMapView, l6: com.islandblast.game.levels.LevelDef):
        com.islandblast.game.map.WorldMapView {
        val world = l6.world
        tap(d.onMain { map.nodeCenter(l6) })
        d.waitFor("Level 6", 60f) { (screen as? LevelScreen)?.level == l6 }
        val s6 = screen as LevelScreen
        assertTrue("Level 6 is Storm Dodge", s6.play is StormView)
        val v = s6.play as StormView
        d.waitFor("Level 6 laid out", 10f) { v.width > 0 }
        idle(1.4f)
        capture("level6_start")
        say("4. Level 6 \"${l6.name}\" opens from the map (jet ski storm run)")

        // Its own pause button opens the shared pause menu; Resume carries on.
        val pauseAt = d.onMain { val p = v.spec.pause; v.stageToView(p.cx, p.cy) }
        tap(pauseAt)
        d.waitFor("Level 6 pause menu", 10f) { s6.overlay.mode == LevelOverlay.Mode.PAUSE && s6.overlay.ready }
        val pausedAt = d.onMain { v.game.time }
        idle(0.5f)
        capture("level6_paused")
        d.onMain { assertEquals("the river stops while paused", pausedAt, v.game.time, 1e-4f) }
        tap(d.onMain { s6.overlay.buttonCenter(LevelOverlay.Action.RESUME)!! })
        d.waitFor("Level 6 resumed", 10f) { s6.overlay.mode == LevelOverlay.Mode.NONE && !v.game.paused }
        say("   pause button → Paused menu (river stopped) → Resume")

        // Ride to the temple, steering with the arrow buttons like a player.
        var lastSection = 0
        var taps = 0
        while (!d.onMain { v.game.over }) {
            check(d.onMain { v.game.time } < 300f) { "Level 6 did not finish" }
            val dir = d.onMain { StormBot.decide(v.game) }
            if (dir != 0) {
                val p = d.onMain {
                    val a = if (dir < 0) v.spec.arrowLeft else v.spec.arrowRight
                    v.stageToView(a[0], a[1])
                }
                tap(p)
                taps++
            } else {
                idle(1f / 60f)
            }
            val (section, line) = d.onMain {
                val g = v.game
                g.section to "   section ${g.section + 1}: score %,d, coins ${g.coins}, hits ${g.hits}, ${g.timerSeconds}s left"
                    .format(g.score)
            }
            if (section != lastSection) {
                lastSection = section
                say(line)
                if (section == 2) capture("level6_section3")
            }
        }
        val g = d.onMain { v.game }
        say("   reached the temple: score %,d, coins ${g.coins}/${g.totalCoins}, hits ${g.hits}, ${g.stars} stars, $taps arrow taps"
            .format(g.score))
        finishLevel(s6, "level6")

        // World 1 Complete, then World 2 Unlocked.
        d.waitFor("World 1 Complete", 30f) { screen is WorldCompleteScreen }
        val wc = (screen as WorldCompleteScreen).card
        d.waitFor("World Complete laid out", 10f) { wc.width > 0 }
        idle(1.0f)
        capture("world1_complete")
        d.onMain {
            assertTrue("World 1 is recorded complete", d.activity.flow.progress.worldComplete(world))
            assertTrue("World 2 is unlocked", d.activity.flow.progress.worldUnlocked(2))
        }
        say("   Continue → \"World 1 Complete!\": ${d.onMain { d.activity.flow.progress.totalStars(world) }} stars in World 1")
        d.waitFor("World 2 Unlocked card", 10f) { wc.unlockShown && wc.ready }
        idle(0.8f)
        capture("world2_unlocked")
        d.onMain {
            val w = wc.width.toFloat()
            val h = wc.height.toFloat()
            for ((name, r) in wc.mustShow()) {
                assertTrue("World Complete: $name ($r) is cut off the ${w}x$h screen",
                    r.left >= -0.5f && r.top >= -0.5f && r.right <= w + 0.5f && r.bottom <= h + 0.5f)
            }
            assertEquals(2, wc.nextWorld)
        }
        say("   \"World 2 Unlocked!\" → Continue")
        tap(d.onMain { wc.continueCenter() })
        d.waitFor("back on the map", 30f) { screen is MapScreen }
        val map4 = (screen as MapScreen).map
        idle(2.4f)
        capture("map_world1_complete")
        d.onMain {
            assertTrue("Level 6 is recorded complete", d.activity.flow.progress.completed(l6))
            assertEquals(s6.play.stars, d.activity.flow.progress.stars(l6))
        }
        tap(d.onMain { map4.gateCenter()!! })
        idle(0.3f)
        assertTrue("World 2 is not built yet: its gate keeps the player on the map", screen is MapScreen)
        capture("world2_gate_open")
        say("   back on World 1: Level 6 shows ${d.onMain { d.activity.flow.progress.stars(l6) }} stars, " +
            "World 2's gate is open (\"coming soon\")")
        return map4
    }

    /** Waits for the result card, checks it, and taps Continue. */
    private fun finishLevel(s: LevelScreen, name: String) {
        d.onMain { assertEquals("$name is won", PlayState.WON, s.play.state) }
        d.waitFor("$name result card", 60f) { s.overlay.mode == LevelOverlay.Mode.RESULT && s.overlay.ready }
        idle(1.6f)
        capture("${name}_complete")
        val cont = d.onMain { s.overlay.buttonCenter(LevelOverlay.Action.CONTINUE) }
        assertTrue("Level Complete offers Continue", cont != null)
        d.onMain { assertFalse(s.overlay.buttonCenter(LevelOverlay.Action.RESTART) == null) }
        say("   Level Complete card: ${s.play.stars} stars, score %,d".format(s.play.score))
        tap(cont!!)
    }

    private fun idle(seconds: Float) = d.idle(seconds)
}
