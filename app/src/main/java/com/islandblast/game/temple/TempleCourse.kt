package com.islandblast.game.temple

import kotlin.math.abs
import kotlin.random.Random

/** Things on the temple path. Boulders and stone blocks hurt; the rest are pickups. */
enum class Item(val hazard: Boolean, val halfWidth: Float, val depth: Float) {
    /** A lava boulder thrown by the golem: rolls down the path toward the runner. */
    BOULDER(true, 0.42f, 0.55f),
    /** A stone block with red chevrons standing in a lane. */
    BLOCK(true, 0.44f, 0.5f),
    COIN(false, 0.3f, 0.45f),
    GEM(false, 0.32f, 0.5f),
    LIGHTNING(false, 0.34f, 0.55f),
    SHIELD(false, 0.34f, 0.55f),
    MAGNET(false, 0.34f, 0.55f);

    val power get() = this == LIGHTNING || this == SHIELD || this == MAGNET
}

/**
 * One placed item: [x] in lanes (0 = middle lane, ±1 = side lanes), [z] distance along the
 * path where the runner meets it. [decor] items are out of reach (a boulder rolling through the
 * lava, a gem on a ledge): scenery only.
 */
class Placed(val kind: Item, val x: Float, val z: Float, val section: Int, val decor: Boolean = false)

/** [bonusSeconds] is added to the timer when the runner reaches this section (a checkpoint). */
class Section(val name: String, val start: Float, val end: Float, val speed: Float, val bonusSeconds: Float)

/**
 * The fixed Level 5 course: four sections from the lava path to the temple escape, the same
 * every play so it can be learned. It opens exactly as the approved screen is painted (coins,
 * gems and the golem's first boulder where they are drawn). Every hazard row leaves an open
 * lane, and the open lane moves at most one lane per row, so a clean line always exists.
 */
object TempleCourse {
    val sections = listOf(
        Section("Lava Path", 0f, 140f, 9f, 0f),
        Section("Boulder Alley", 140f, 300f, 10.5f, 10f),
        Section("Golem's Wrath", 300f, 470f, 12f, 10f),
        Section("Temple Escape", 470f, 600f, 13.5f, 8f),
    )
    val length get() = sections.last().end

    val lanes = floatArrayOf(-1f, 0f, 1f)

    fun build(seed: Int = 5): List<Placed> {
        val rnd = Random(seed)
        val out = ArrayList<Placed>()
        fun add(k: Item, x: Float, z: Float, s: Int, decor: Boolean = false) = out.add(Placed(k, x, z, s, decor))
        val (s1, s2, s3, s4) = sections

        // ---- the opening, as painted -----------------------------------------------------
        // Coins and gems where the approved screen draws them (lanes/depth measured from it).
        for ((x, z) in listOf(1.36f to 19.8f, -0.35f to 13.2f, 0.96f to 12.75f, -0.41f to 10.4f, 1.22f to 8.3f,
            -0.74f to 5.6f, 1.47f to 1.76f)) add(Item.COIN, x, z, 0)
        add(Item.GEM, 1.45f, 6.1f, 0)
        add(Item.GEM, -1.45f, 1.06f, 0)
        add(Item.GEM, 3.2f, 19.8f, 0, decor = true)
        // The golem's boulder rolling down the middle, and one tumbling through the lava.
        add(Item.BOULDER, 0f, 12f, 0)
        add(Item.BOULDER, -2.7f, 7.5f, 0, decor = true)

        // ---- SECTION 1, Lava Path: single hazards, straight coin lines, an early shield ------
        var z = s1.start + 30f
        var lastLane = 1
        while (z < s1.end - 10f) {
            val lane = (lastLane + 1 + rnd.nextInt(2)) % 3
            add(if (rnd.nextInt(3) == 0) Item.BLOCK else Item.BOULDER, lanes[lane], z, 0)
            val coinLane = lanes.indices.filter { it != lane }[rnd.nextInt(2)]
            for (i in 0 until 5) add(Item.COIN, lanes[coinLane], z - 6f + i * 2.4f, 0)
            lastLane = lane
            z += 15f
        }
        add(Item.SHIELD, 0f, s1.start + 52f, 0)
        add(Item.GEM, lanes[rnd.nextInt(3)], s1.start + 100f, 0)

        // ---- SECTION 2, Boulder Alley: two lanes blocked, coins curve through the gap ---------
        z = s2.start + 10f
        var gap = 1
        while (z < s2.end - 10f) {
            for (i in lanes.indices) if (i != gap) {
                add(if (rnd.nextInt(4) == 0) Item.BLOCK else Item.BOULDER, lanes[i], z + rnd.nextFloat() * 1.2f, 1)
            }
            val next = (gap + if (rnd.nextBoolean()) 1 else -1).coerceIn(0, 2)
            for (i in 0 until 5) {
                val t = i / 4f
                val x = lanes[gap] + (lanes[next] - lanes[gap]) * (t * t * (3 - 2 * t))
                add(Item.COIN, x, z + 2.4f + i * 2.1f, 1)
            }
            gap = next
            z += 14f
        }
        add(Item.MAGNET, 0f, s2.start + 4f, 1)
        add(Item.GEM, lanes[1], s2.start + 80f + 6.5f, 1)
        add(Item.LIGHTNING, lanes[0], s2.end - 4f, 1)

        // ---- SECTION 3, Golem's Wrath: blocks and boulders, risky gems beside them ------------
        z = s3.start + 10f
        var open = 1
        while (z < s3.end - 8f) {
            for (i in lanes.indices) if (i != open) add(if (rnd.nextBoolean()) Item.BOULDER else Item.BLOCK, lanes[i], z, 2)
            val risky = lanes.indices.first { it != open }
            add(Item.GEM, lanes[risky], z - 3.2f, 2)
            for (i in 0 until 3) add(Item.COIN, lanes[open], z - 1f + i * 2.2f, 2)
            val next = (open + rnd.nextInt(3) - 1).coerceIn(0, 2)
            val free = lanes.indices.filter { it != open && it != next }
            if (free.isNotEmpty() && rnd.nextFloat() < 0.4f) add(Item.BOULDER, lanes[free[rnd.nextInt(free.size)]], z + 6f, 2)
            open = next
            z += 12f
        }
        add(Item.SHIELD, lanes[2], s3.start + 70f + 6f, 2)

        // ---- SECTION 4, Temple Escape: a fast slalom, then a coin arch to the temple ----------
        z = s4.start + 8f
        var g = 1
        while (z < s4.end - 30f) {
            for (i in lanes.indices) if (i != g) add(if (rnd.nextInt(3) == 0) Item.BLOCK else Item.BOULDER, lanes[i], z, 3)
            for (i in 0 until 3) add(Item.COIN, lanes[g], z - 1.2f + i * 1.9f, 3)
            g = when (g) {
                0, 2 -> 1
                else -> if (rnd.nextBoolean()) 0 else 2
            }
            z += 10f
        }
        for (i in 0 until 12) {
            val t = i / 11f
            add(Item.COIN, -1f + 2f * t, s4.end - 26f + i * 2f, 3)
        }
        return fixUnsafe(out).sortedBy { it.z }
    }

    /**
     * Safety pass: wherever hazards within a 3-unit window block all three lanes, drop the
     * farthest; and pickups that sit inside a hazard are removed (they could not be taken).
     */
    private fun fixUnsafe(list: MutableList<Placed>): MutableList<Placed> {
        val hazards = list.filter { it.kind.hazard && !it.decor }.sortedBy { it.z }
        val remove = HashSet<Placed>()
        for (h in hazards) {
            val window = hazards.filter { it !in remove && it.z >= h.z - 1.6f && it.z <= h.z + 1.6f }
            val covered = lanes.indices.filter { i -> window.any { abs(it.x - lanes[i]) < 0.5f } }
            if (covered.size == 3) remove += window.maxBy { it.z }
        }
        list.removeAll(remove)
        val hz = list.filter { it.kind.hazard && !it.decor }
        list.removeAll { c -> !c.kind.hazard && hz.any { abs(it.x - c.x) < 0.55f && abs(it.z - c.z) < 1.2f } }
        return list
    }
}
