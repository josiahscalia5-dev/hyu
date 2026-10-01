package com.islandblast.game.level6

import kotlin.random.Random

/** Things floating in the river. Every hazard kind has collision; coins and shields are pickups. */
enum class Kind(val hazard: Boolean, val halfWidth: Float, val depth: Float, val sprite: String) {
    BARREL(true, 0.3f, 0.6f, "barrel"),
    BARREL_OPEN(true, 0.36f, 0.6f, "barrel_open"),
    MINE(true, 0.3f, 0.55f, "mine"),
    LOGS(true, 0.62f, 0.6f, "logs"),
    CRATE(true, 0.38f, 0.7f, "crate_x"),
    X_HAZARD(true, 0.27f, 0.5f, "x_hazard"),
    COIN(false, 0.2f, 0.45f, "coin"),
    SHIELD(false, 0.26f, 0.55f, "shield"),
}

/**
 * One placed object: [x] in lanes (0 = centre, ±1 = side lanes), [z] distance along the river.
 * [drift] objects float along the river banks, outside the jet ski's reach, as scenery.
 */
class Placed(val kind: Kind, val x: Float, val z: Float, val section: Int, val drift: Boolean = false)

/** [bonusSeconds] is added to the timer when the jet ski reaches this section (a checkpoint). */
class Section(val name: String, val start: Float, val end: Float, val speed: Float, val bonusSeconds: Float)

/**
 * The fixed Level 6 river: five sections from a calm start to the temple finish.
 * The same layout every play (seeded), so players can learn it. Every hazard row
 * leaves at least one open lane, and coin trails always have a safe line.
 */
object StormCourse {
    val sections = listOf(
        Section("Calm Storm", 0f, 180f, 14f, 0f),
        Section("Stronger Current", 180f, 400f, 17f, 8f),
        Section("Heavy Storm", 400f, 660f, 20f, 8f),
        Section("Storm Escape", 660f, 920f, 23f, 8f),
        Section("Temple Finish", 920f, 980f, 16f, 4f),
    )
    val length get() = sections.last().end

    /** Lanes the jet ski can steer between. */
    val lanes = floatArrayOf(-1f, 0f, 1f)

    fun build(seed: Int = 6): List<Placed> {
        val rnd = Random(seed)
        val out = ArrayList<Placed>()
        fun add(k: Kind, x: Float, z: Float, s: Int) = out.add(Placed(k, x, z, s))

        val (s1, s2, s3, s4, s5) = sections

        // SECTION 1 — Calm Storm: single barrels, straight coin lines, an early shield.
        var z = s1.start + 18f
        val calm = listOf(Kind.BARREL, Kind.BARREL_OPEN, Kind.BARREL)
        while (z < s1.end - 10f) {
            val lane = lanes[rnd.nextInt(3)]
            add(calm[rnd.nextInt(calm.size)], lane, z, 0)
            val coinLane = lanes.filter { it != lane }[rnd.nextInt(2)]
            for (i in 0 until 4) add(Kind.COIN, coinLane, z + 3f + i * 2.8f, 0)
            z += 15f
        }
        add(Kind.SHIELD, 0f, s1.start + 92f, 0)

        // SECTION 2 — Stronger Current: pairs of hazards, curved coin paths between them.
        z = s2.start + 8f
        val mid = listOf(Kind.BARREL, Kind.BARREL_OPEN, Kind.LOGS, Kind.MINE)
        var gap = 1
        while (z < s2.end - 10f) {
            val blocked = lanes.indices.filter { it != gap }
            for (i in blocked) add(mid[rnd.nextInt(mid.size)], lanes[i], z + rnd.nextFloat() * 1.5f, 1)
            // coins curve from the old gap to the new one
            val next = (gap + if (rnd.nextBoolean()) 1 else -1).coerceIn(0, 2)
            for (i in 0 until 5) {
                val t = i / 4f
                val x = lanes[gap] + (lanes[next] - lanes[gap]) * (t * t * (3 - 2 * t))
                add(Kind.COIN, x, z + 2.5f + i * 2.2f, 1)
            }
            gap = next
            z += 13f
        }
        add(Kind.SHIELD, 0f, s2.start + 110f, 1)

        // SECTION 3 — Heavy Storm: mines, logs, crates, X hazards; risky coins right beside hazards.
        // The open lane moves at most one lane per row, and extra hazards between rows never
        // sit on the way from one open lane to the next, so a clean line always exists.
        z = s3.start + 8f
        val heavy = listOf(Kind.MINE, Kind.LOGS, Kind.CRATE, Kind.X_HAZARD, Kind.BARREL)
        var open = 1
        while (z < s3.end - 8f) {
            for (i in lanes.indices) if (i != open) add(heavy[rnd.nextInt(heavy.size)], lanes[i], z, 2)
            val risky = lanes.indices.first { it != open }
            for (dz in floatArrayOf(-2.4f, -4.4f)) add(Kind.COIN, lanes[risky], z + dz, 2)
            for (i in 0 until 3) add(Kind.COIN, lanes[open], z + i * 2.2f, 2)
            val next = (open + rnd.nextInt(3) - 1).coerceIn(0, 2)
            val free = lanes.indices.filter { it != open && it != next }
            if (free.isNotEmpty() && rnd.nextFloat() < 0.45f) {
                add(heavy[rnd.nextInt(heavy.size)], lanes[free[rnd.nextInt(free.size)]], z + 5.5f, 2)
            }
            open = next
            z += 11f
        }
        add(Kind.SHIELD, 0f, s3.start + 130f, 2)

        // SECTION 4 — Storm Escape: a fast, dense slalom of mines and X hazards. The gap steps
        // one lane at a time, quickly; a coin line rewards holding the racing line.
        z = s4.start + 8f
        var g = 1
        while (z < s4.end - 8f) {
            for (i in lanes.indices) if (i != g) add(if (rnd.nextBoolean()) Kind.MINE else Kind.X_HAZARD, lanes[i], z, 3)
            for (i in 0 until 3) add(Kind.COIN, lanes[g], z - 1.2f + i * 1.9f, 3)
            g = when (g) {
                0 -> 1
                2 -> 1
                else -> if (rnd.nextBoolean()) 0 else 2
            }
            z += 8.5f
        }

        // SECTION 5 — Temple Finish: a celebratory coin arch, no hazards.
        for (i in 0 until 12) {
            val t = i / 11f
            add(Kind.COIN, -1f + 2f * t, s5.start + 6f + i * 2.4f, 4)
        }

        // Debris drifting along both banks the whole way: the storm's busy river.
        val driftKinds = listOf(Kind.BARREL, Kind.BARREL_OPEN, Kind.LOGS, Kind.MINE, Kind.CRATE, Kind.BARREL)
        var dz = 4f
        while (dz < s4.end) {
            val side = if (rnd.nextBoolean()) 1f else -1f
            val k = driftKinds[rnd.nextInt(driftKinds.size)]
            out.add(Placed(k, side * (1.95f + rnd.nextFloat() * 0.7f), dz, sections.indexOfLast { dz >= it.start }, drift = true))
            dz += 5f + rnd.nextFloat() * 6f
        }
        val fix = fixUnsafeRows(out)
        return fix.sortedBy { it.z }
    }

    /**
     * Safety pass: wherever hazards in any 3-unit window block all three lanes,
     * drop the one in the lane the previous window left open, so a path always exists.
     */
    private fun fixUnsafeRows(list: MutableList<Placed>): MutableList<Placed> {
        val hazards = list.filter { it.kind.hazard && !it.drift }.sortedBy { it.z }
        val remove = HashSet<Placed>()
        for (h in hazards) {
            val window = hazards.filter { it !in remove && it.z >= h.z - 1.6f && it.z <= h.z + 1.6f }
            val covered = lanes.indices.filter { i -> window.any { kotlin.math.abs(it.x - lanes[i]) < 0.5f } }
            if (covered.size == 3) remove += window.maxBy { it.z }
        }
        list.removeAll(remove)
        // Coins inside a hazard would be impossible to collect: nudge them clear.
        val hz = list.filter { it.kind.hazard && !it.drift }
        list.removeAll { c -> c.kind == Kind.COIN && hz.any { kotlin.math.abs(it.x - c.x) < 0.35f && kotlin.math.abs(it.z - c.z) < 1.0f } }
        return list
    }
}
