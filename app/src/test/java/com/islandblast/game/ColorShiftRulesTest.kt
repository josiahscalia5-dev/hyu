package com.islandblast.game

import com.islandblast.game.model.GameColor
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorShiftRulesTest {
    private fun newGame() = Level5Game(TestLevel.spec)

    @Test
    fun startsInTheApprovedState() {
        val g = newGame()
        assertEquals(31, g.board.aliveCount)
        assertEquals(1760, g.score)
        assertEquals(36, g.timerSeconds)
        assertEquals(9, g.combo)
        assertEquals(30, g.coins)
        assertEquals(2, g.stars)
        assertEquals(GameColor.CYAN, g.color)
        assertEquals(Phase.READY, g.phase)
    }

    @Test
    fun colourCycleWraps() {
        assertEquals(GameColor.VIOLET, GameColor.CYAN.next())
        assertEquals(GameColor.CYAN, GameColor.YELLOW.next())
        assertEquals(GameColor.entries.toSet(), GameColor.entries.map { it.next() }.toSet())
    }

    @Test
    fun centralBlueClusterIsOneGroup() {
        val g = newGame()
        val group = g.board.group(TestLevel.id(g, "b19")).map { g.board.blocks[it].spec.id }.toSet()
        assertEquals(setOf("b15", "b16", "b17", "b18", "b19", "b20"), group)
        // The two blue blocks at the bottom right are separate.
        assertEquals(setOf("b30", "b31"),
            g.board.group(TestLevel.id(g, "b30")).map { g.board.blocks[it].spec.id }.toSet())
    }

    @Test
    fun clearingShiftsOnlyTouchingBlocksOneStep() {
        val g = newGame()
        val before = TestLevel.colorsOf(g)
        val group = g.board.group(TestLevel.id(g, "b19"))
        val neighbours = g.board.neighboursOf(group).map { g.board.blocks[it].spec.id }.toSet()
        assertTrue("expected neighbours around the blue cluster", neighbours.size >= 4)
        val shifting = g.board.clear(group, 0f)
        g.board.shift(shifting, 0f)
        val after = TestLevel.colorsOf(g)
        for ((id, c) in after) {
            if (id in neighbours) assertEquals("$id should step once", before.getValue(id).next(), c)
            else assertEquals("$id should keep its colour", before.getValue(id), c)
        }
        for (b in g.board.blocks) assertEquals(b.spec.id in neighbours && b.alive, b.shifted)
    }

    @Test
    fun wrongColourBouncesAndMatchingColourClears() {
        val g = newGame()
        g.setColorForTest(GameColor.YELLOW)
        TestLevel.aimAtAngle(g, 0f)
        val path = g.aimPath()
        assertEquals("b19", g.board.blocks[path.hit].spec.id)
        assertFalse(path.matches)
        g.shoot()
        TestLevel.runShot(g)
        assertEquals(31, g.board.aliveCount)
        assertEquals("a miss resets the combo", 0, g.combo)

        val g2 = newGame()
        TestLevel.aimAtAngle(g2, 0f)
        assertTrue(g2.aimPath().matches)
        g2.shoot()
        TestLevel.runShot(g2)
        assertEquals(25, g2.board.aliveCount)
        assertEquals(10, g2.combo)
        assertEquals(1760 + 10 * 6 * 10, g2.score)
        assertEquals(36, g2.coins)
    }

    @Test
    fun tappingTheBallCyclesThroughColoursOnTheBoard() {
        val g = newGame()
        val seen = mutableListOf(g.color)
        repeat(5) { g.switchColor(); seen += g.color }
        assertEquals(listOf(GameColor.CYAN, GameColor.VIOLET, GameColor.MAGENTA, GameColor.RED,
            GameColor.YELLOW, GameColor.CYAN), seen)
    }
}
