package com.islandblast.game.e2e

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.MotionEvent
import com.islandblast.game.GameView
import com.islandblast.game.MainActivity
import com.islandblast.game.SliceView
import com.islandblast.game.app.Screen
import com.islandblast.game.home.HomeScreen
import com.islandblast.game.levels.LevelOverlay
import com.islandblast.game.levels.LevelScreen
import com.islandblast.game.levels.PlayState
import com.islandblast.game.map.MapScreen
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
 * Launch → Home → PLAY → World 1 map → Level 4 → complete it → back on the map →
 * Level 5 → complete it → back on the map → Back → Home.
 * Every screen is captured and checked on the way. With [playLevel4] false the run
 * skips Level 4 (for re-checking the rest quickly on a slow emulator).
 */
class FlowChecklist(private val d: FlowDriver, private val playLevel4: Boolean = true) {
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
        val l4 = world.level(4)!!
        val l5 = world.level(5)!!

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
            assertTrue(l4.playable && l5.playable)
            for (lv in map.world.levels) {
                val c = map.nodeCenter(lv)
                assertTrue("map: level ${lv.number} button off screen at $c",
                    c.x - map.nodeRadius() >= 0f && c.x + map.nodeRadius() <= map.width &&
                        c.y - map.nodeRadius() >= 0f && c.y + map.nodeRadius() <= map.height)
            }
        }
        say("2. PLAY → World 1 \"${world.name}\": ${world.levels.size} levels, " +
            "playable ${world.levels.filter { it.playable }.map { it.number }}, the rest coming soon")

        // A level that is not built yet only says so.
        val soon = world.levels.first { !it.playable }
        tap(d.onMain { map.nodeCenter(soon) })
        idle(0.3f)
        assertTrue("a coming-soon level keeps the player on the map", screen is MapScreen)
        capture("coming_soon_toast")
        say("   Level ${soon.number} (not built yet) shows \"coming soon\" and stays on the map")

        // 3. Level 4 from the map, played to Level Complete.
        val mapNow = if (playLevel4) level4(map, l4, l5) else map

        // 4. Level 5 from the map, played to Level Complete.
        tap(d.onMain { mapNow.nodeCenter(l5) })
        d.waitFor("Level 5", 30f) { (screen as? LevelScreen)?.level == l5 }
        val s5 = screen as LevelScreen
        assertTrue("Level 5 is the colour-shift block game", s5.play is GameView)
        d.waitFor("Level 5 laid out", 10f) { s5.play.view.width > 0 }
        idle(0.3f)
        capture("level5_start")
        say("4. Level 5 \"${l5.name}\" opens from the map (colour-shift blocks)")
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
        say("   Continue → back on World 1: Level 5 shows $stars5 stars; World 1 total ${d.onMain {
            d.activity.flow.progress.totalStars(world) }} stars")

        // 5. Back from the map returns home.
        d.pressBack()
        d.waitFor("home again", 30f) { screen is HomeScreen }
        idle(0.5f)
        capture("home_again")
        say("5. Back from the map → Home")
        return report.toString()
    }

    private fun level4(map: com.islandblast.game.map.WorldMapView, l4: com.islandblast.game.levels.LevelDef,
                       l5: com.islandblast.game.levels.LevelDef): com.islandblast.game.map.WorldMapView {
        tap(d.onMain { map.nodeCenter(l4) })
        d.waitFor("Level 4", 30f) { (screen as? LevelScreen)?.level == l4 }
        val s4 = screen as LevelScreen
        assertTrue("Level 4 is the slicing game", s4.play is SliceView)
        d.waitFor("Level 4 laid out", 10f) { s4.play.view.width > 0 }
        idle(0.4f)
        capture("level4_start")
        say("3. Level 4 \"${l4.name}\" opens from the map (treasure slicing)")
        SliceChecklist(d, s4, ::capture, ::say).run()
        finishLevel(s4, "level4")
        d.waitFor("back on the map", 30f) { screen is MapScreen }
        val map2 = (screen as MapScreen).map
        idle(2.4f)
        capture("map_after_level4")
        val stars4 = d.onMain { d.activity.flow.progress.stars(l4) }
        d.onMain {
            assertTrue("Level 4 is recorded complete", d.activity.flow.progress.completed(l4))
            assertEquals("the map shows Level 4's stars", s4.play.stars, stars4)
            assertEquals("the marker moves on to Level 5", l5, map2.current)
        }
        say("   Continue → back on World 1: Level 4 shows $stars4 stars, the marker moves on to Level 5")
        return map2
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
