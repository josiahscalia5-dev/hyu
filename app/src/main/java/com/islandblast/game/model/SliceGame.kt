package com.islandblast.game.model

import android.content.res.AssetManager
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Settings of a treasure-slicing level: assets/<dir>/rules.json, with any keys in the
 * catalog entry's "config" taking precedence. Every value has a default.
 */
class SliceRules(
    val seconds: Float = 60f,
    /** Score for one, two and three stars; the first is the level's goal. */
    val starScores: IntArray = intArrayOf(3000, 18000, 50000),
    /** A cut within this many seconds of the last one keeps the combo going. */
    val comboWindow: Float = 1.0f,
    val maxMultiplier: Int = 8,
    /** Seconds a sliced barrel adds to the clock. */
    val barrelSeconds: Float = 2f,
    /** Cuts a chest takes before it bursts. */
    val chestHits: Int = 3,
    /** A finger slower than this (stage px per second) does not cut. */
    val minCutSpeed: Float = 500f,
    val gravity: Float = 1500f,
    /** The clock starts at the first swipe, or after this many seconds. */
    val introHold: Float = 4f,
    val firstToss: Float = 0.8f,
    /** Seconds between tosses at the start and at the end of the level. */
    val tossEvery: FloatArray = floatArrayOf(1.7f, 0.9f),
    /** Treasure per toss: fewest/most at the start, fewest/most at the end. */
    val tossCount: IntArray = intArrayOf(1, 2, 2, 4),
    val weights: Map<TargetKind, Float> = mapOf(
        TargetKind.GEM to 5f, TargetKind.COIN to 3f, TargetKind.CRATE to 2f, TargetKind.BARREL to 1.2f,
        TargetKind.CHEST to 0.7f,
    ),
    val points: Map<TargetKind, Int> = mapOf(
        TargetKind.GEM to 15, TargetKind.COIN to 20, TargetKind.CRATE to 10, TargetKind.BARREL to 15,
        TargetKind.CHEST to 60,
    ),
    val bigGemPoints: Int = 30,
    val chestHitPoints: Int = 10,
    /** Coins and gems a burst chest throws up. */
    val lootCount: Int = 5,
    /** Bonus per cut for a swipe that cuts three or more. */
    val swipeBonus: Int = 10,
    val seed: Int = 4,
) {
    val goal: Int get() = starScores[0]

    fun basePoints(p: Piece): Int = when {
        p.kind == TargetKind.GEM && p.big -> bigGemPoints
        else -> points[p.kind] ?: 10
    }

    companion object {
        fun load(am: AssetManager, dir: String, overrides: JSONObject = JSONObject()): SliceRules {
            val file = try {
                JSONObject(am.open("$dir/rules.json").bufferedReader().use { it.readText() })
            } catch (e: java.io.IOException) {
                JSONObject()
            }
            for (k in overrides.keys()) file.put(k, overrides.get(k))
            return parse(file)
        }

        fun parse(o: JSONObject): SliceRules {
            val d = SliceRules()
            fun f(k: String, def: Float) = if (o.has(k)) o.getDouble(k).toFloat() else def
            fun i(k: String, def: Int) = if (o.has(k)) o.getInt(k) else def
            fun ints(k: String, def: IntArray) = o.optJSONArray(k)?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: def
            fun floats(k: String, def: FloatArray) =
                o.optJSONArray(k)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } ?: def
            fun kinds(k: String, def: Map<TargetKind, Float>) = o.optJSONObject(k)?.let { m ->
                m.keys().asSequence().associate { TargetKind.valueOf(it.uppercase()) to m.getDouble(it).toFloat() }
            } ?: def
            return SliceRules(
                seconds = f("seconds", d.seconds),
                starScores = ints("starScores", d.starScores),
                comboWindow = f("comboWindow", d.comboWindow),
                maxMultiplier = i("maxMultiplier", d.maxMultiplier),
                barrelSeconds = f("barrelSeconds", d.barrelSeconds),
                chestHits = i("chestHits", d.chestHits),
                minCutSpeed = f("minCutSpeed", d.minCutSpeed),
                gravity = f("gravity", d.gravity),
                introHold = f("introHold", d.introHold),
                firstToss = f("firstToss", d.firstToss),
                tossEvery = floats("tossEvery", d.tossEvery),
                tossCount = ints("tossCount", d.tossCount),
                weights = kinds("weights", d.weights),
                points = kinds("points", d.points.mapValues { it.value.toFloat() }).mapValues { it.value.toInt() },
                bigGemPoints = i("bigGemPoints", d.bigGemPoints),
                chestHitPoints = i("chestHitPoints", d.chestHitPoints),
                lootCount = i("lootCount", d.lootCount),
                swipeBonus = i("swipeBonus", d.swipeBonus),
                seed = i("seed", d.seed),
            )
        }
    }
}

enum class SlicePhase { INTRO, PLAYING, WON, LOST }

/** One treasure in play: hovering where it is painted, or tossed out of the lagoon. */
class Piece(
    val id: Int,
    /** Index of its sprite in the level's targets. */
    val sprite: Int,
    val spec: TargetSpec,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var rot: Float,
    var spin: Float,
    val scale: Float,
    /** Painted in the opening burst: bobs in place instead of falling. */
    val hovering: Boolean,
    /** Thrown out of a chest: dropping it costs nothing. */
    val bonus: Boolean,
    var hp: Int,
    val bornAt: Float,
) {
    val kind get() = spec.kind
    var alive = true
    var lastHitAt = -10f
    var lastSwipe = -1
    val big get() = spec.radius * scale >= 40f
    val radius get() = spec.radius * scale

    // Each hovering piece bobs on its own rhythm; zero offset at t = 0 so the opening
    // frame is the approved screen exactly.
    internal val freq = 1.25f + ((id * 0.618034f) % 1f) * 0.9f
    internal val sign = if (id % 2 == 0) 1f else -1f
    internal val baseX = x
    internal val baseY = y
}

sealed class SliceEvent {
    /**
     * [piece] was cut through. The cut runs through (lx, ly) along (ldx, ldy) in the
     * piece's sprite coordinates (stage units at rest), and through (wx, wy) along the
     * swipe (dx, dy) on screen.
     */
    class Cut(
        val piece: Piece, val lx: Float, val ly: Float, val ldx: Float, val ldy: Float,
        val wx: Float, val wy: Float, val dx: Float, val dy: Float, val points: Int, val multiplier: Int,
    ) : SliceEvent()

    /** A chest took a cut but holds: [hitsLeft] more to go. */
    class ChestHit(val piece: Piece, val wx: Float, val wy: Float, val dx: Float, val dy: Float, val hitsLeft: Int,
                   val points: Int) : SliceEvent()
    class Tossed(val piece: Piece) : SliceEvent()
    /** A tossed treasure fell back into the lagoon unsliced. */
    class Dropped(val piece: Piece, val comboLost: Boolean) : SliceEvent()
    class SwipeBonus(val cuts: Int, val points: Int, val x: Float, val y: Float) : SliceEvent()
    class TimeBonus(val seconds: Float, val x: Float, val y: Float) : SliceEvent()
    class StarEarned(val stars: Int) : SliceEvent()
    /** The clock started: by the first swipe, or by itself after the intro. */
    class Started(val bySwipe: Boolean) : SliceEvent()
    object Won : SliceEvent()
    object Lost : SliceEvent()
}

/** A point of the blade's trail (stage units, game time). */
class BladePoint(val x: Float, val y: Float, val t: Float)

/**
 * Level 4 "Mystic Harvest", or any treasure-slicing level: swipe across treasure to
 * cut it in two. Treasure hovers where the approved screen paints it, then more is
 * tossed out of the lagoon all level long. Chained cuts raise the combo multiplier,
 * chests take several cuts and burst into loot, barrels add time. When the clock
 * runs out the level is won if the score reached the goal (the first star).
 */
class SliceGame(val spec: SliceSpec, val rules: SliceRules = SliceRules()) {
    private val rnd = Random(rules.seed)
    val pieces = ArrayList<Piece>()
    val events = ArrayList<SliceEvent>()

    /** Every point scored, as (what, points): the HUD score is their sum. */
    val ledger = ArrayList<Pair<String, Int>>()

    var phase = SlicePhase.INTRO
        private set
    var paused = false

    /** Game time: runs whenever not paused (effects use it, also after the end). */
    var time = 0f
        private set

    /** Level clock: seconds played since the start. */
    var clock = 0f
        private set
    var timeLeft = rules.seconds
        private set
    var score = 0
        private set
    var stars = 0
        private set

    /** Cuts in the current chain; the multiplier is this, capped. */
    var chain = 0
        private set
    var lastCutAt = -10f
        private set
    var lastTimeBonusAt = -10f
        private set
    var cuts = 0
        private set
    var dropped = 0
        private set
    var startedAt = -1f
        private set

    val multiplier: Int get() = min(max(chain, 1), rules.maxMultiplier)
    val over get() = phase == SlicePhase.WON || phase == SlicePhase.LOST
    val timerSeconds: Int get() = max(0, ceil(timeLeft - 1e-4f).toInt())
    val timeFraction: Float get() = (timeLeft / rules.seconds).coerceIn(0f, 1f)
    val alive: List<Piece> get() = pieces.filter { it.alive }

    // ---- the blade --------------------------------------------------------------

    val blade = ArrayDeque<BladePoint>()
    var swiping = false
        private set
    private var swipeId = 0
    private var swipeCuts = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastMs = 0L
    private var lastCutX = 0f
    private var lastCutY = 0f

    private var nextId = 0
    private var nextTossAt = 0f

    init {
        // The opening burst: every treasure where the approved screen paints it.
        spec.targets.forEachIndexed { i, t ->
            pieces += Piece(nextId++, i, t, t.cx, t.cy, 0f, 0f, 0f, 0f, 1f, hovering = true, bonus = false,
                hp = if (t.kind == TargetKind.CHEST) rules.chestHits else 1, bornAt = 0f)
        }
    }

    // ---- input --------------------------------------------------------------------

    fun touchDown(x: Float, y: Float, ms: Long) {
        if (over || paused) return
        swiping = true
        swipeId++
        swipeCuts = 0
        lastX = x; lastY = y; lastMs = ms
        blade.clear()
        blade.addLast(BladePoint(x, y, time))
    }

    fun touchMove(x: Float, y: Float, ms: Long) {
        if (!swiping || over || paused) return
        val dist = hypot(x - lastX, y - lastY)
        if (dist < 1f) return
        val dt = max(ms - lastMs, 4L) / 1000f
        if (dist / dt >= rules.minCutSpeed) {
            if (phase == SlicePhase.INTRO) start(bySwipe = true)
            cutAlong(lastX, lastY, x, y)
        }
        lastX = x; lastY = y; lastMs = ms
        blade.addLast(BladePoint(x, y, time))
        while (blade.size > 40) blade.removeFirst()
    }

    fun touchUp() {
        if (!swiping) return
        swiping = false
        if (swipeCuts >= 3 && !over) {
            val bonus = swipeCuts * rules.swipeBonus * multiplier
            addScore("swipe x$swipeCuts", bonus)
            events += SliceEvent.SwipeBonus(swipeCuts, bonus, lastCutX, lastCutY)
        }
        swipeCuts = 0
    }

    // ---- simulation -----------------------------------------------------------------

    fun update(dt: Float) {
        if (paused) return
        time += dt
        while (blade.isNotEmpty() && time - blade.first().t > BLADE_LIFE) blade.removeFirst()
        if (phase == SlicePhase.INTRO && time >= rules.introHold) start(bySwipe = false)
        for (p in pieces) if (p.alive && !p.hovering) move(p, dt)
        if (phase != SlicePhase.PLAYING) {
            pieces.removeAll { !it.alive && time - it.lastHitAt > 2f }
            return
        }
        clock += dt
        timeLeft = max(0f, timeLeft - dt)
        if (clock >= nextTossAt) toss()
        if (timeLeft <= 0f) finish()
        pieces.removeAll { !it.alive && time - it.lastHitAt > 2f }
    }

    private fun start(bySwipe: Boolean) {
        if (phase != SlicePhase.INTRO) return
        phase = SlicePhase.PLAYING
        startedAt = time
        nextTossAt = rules.firstToss
        events += SliceEvent.Started(bySwipe)
    }

    private fun move(p: Piece, dt: Float) {
        p.vy += rules.gravity * dt
        p.x += p.vx * dt
        p.y += p.vy * dt
        p.rot += p.spin * dt
        if (p.vy > 0f && p.y > DROP_Y) {
            p.alive = false
            p.lastHitAt = time
            if (over) return
            val lose = !p.bonus
            if (lose) {
                dropped++
                chain = 0
            }
            events += SliceEvent.Dropped(p, lose)
        }
    }

    private fun progress() = (clock / rules.seconds).coerceIn(0f, 1f)

    private fun toss() {
        val k = progress()
        val every = rules.tossEvery[0] + (rules.tossEvery[1] - rules.tossEvery[0]) * k
        nextTossAt = clock + every * (0.85f + rnd.nextFloat() * 0.3f)
        val lo = lerpInt(rules.tossCount[0], rules.tossCount[2], k)
        val hi = lerpInt(rules.tossCount[1], rules.tossCount[3], k)
        val n = lo + rnd.nextInt(max(1, hi - lo + 1))
        repeat(n) { i -> launch(pickKind(), i, n) }
    }

    private fun lerpInt(a: Int, b: Int, k: Float) = (a + (b - a) * k + 0.5f).toInt()

    private fun pickKind(): TargetKind {
        val total = rules.weights.values.sum()
        var r = rnd.nextFloat() * total
        for ((kind, w) in rules.weights) {
            r -= w
            if (r <= 0f) return kind
        }
        return TargetKind.GEM
    }

    /** Throws one treasure of [kind] up out of the lagoon. */
    private fun launch(kind: TargetKind, i: Int, n: Int) {
        val choices = spec.targets.indices.filter { spec.targets[it].kind == kind && spec.targets[it].radius >= 18f }
        if (choices.isEmpty()) return
        val sprite = choices[rnd.nextInt(choices.size)]
        val t = spec.targets[sprite]
        val want = SIZE[kind] ?: 50f
        val scale = (want * (0.9f + rnd.nextFloat() * 0.25f) / t.radius).coerceIn(0.45f, 2f)
        // Spread a group across the lagoon, each heading for its own apex.
        val lane = (i + 0.5f) / n
        val x = LAUNCH_L + (LAUNCH_R - LAUNCH_L) * (lane * 0.7f + 0.15f + (rnd.nextFloat() - 0.5f) * 0.25f)
        val apexY = APEX_TOP + (APEX_BOTTOM - APEX_TOP) * rnd.nextFloat()
        val apexX = (x + (spec.stageW / 2f - x) * (0.3f + rnd.nextFloat() * 0.5f)).coerceIn(120f, spec.stageW - 120f)
        val vy = -sqrt(2f * rules.gravity * (TOSS_Y - apexY))
        val tApex = -vy / rules.gravity
        val p = Piece(nextId++, sprite, t, x, TOSS_Y, (apexX - x) / tApex, vy, rnd.nextFloat() * 40f - 20f,
            (rnd.nextFloat() - 0.5f) * 260f, scale, hovering = false, bonus = false,
            hp = if (kind == TargetKind.CHEST) rules.chestHits else 1, bornAt = time)
        pieces += p
        events += SliceEvent.Tossed(p)
    }

    /** A burst chest throws its loot up: coins and gems, each sliceable for points. */
    private fun spill(chest: Piece) {
        val loot = spec.targets.indices.filter {
            val k = spec.targets[it].kind
            (k == TargetKind.COIN || k == TargetKind.GEM) && spec.targets[it].radius >= 18f
        }
        if (loot.isEmpty()) return
        repeat(rules.lootCount) { i ->
            val sprite = loot[rnd.nextInt(loot.size)]
            val t = spec.targets[sprite]
            val a = (-PI / 2 + (i - (rules.lootCount - 1) / 2f) * 0.32f + (rnd.nextFloat() - 0.5f) * 0.2f).toFloat()
            val sp = 1050f + rnd.nextFloat() * 300f
            val scale = (40f / t.radius).coerceIn(0.45f, 1.6f)
            val p = Piece(nextId++, sprite, t, chest.x, chest.y, cos(a) * sp * 0.6f, sin(a) * sp, 0f,
                (rnd.nextFloat() - 0.5f) * 400f, scale, hovering = false, bonus = true, hp = 1, bornAt = time)
            pieces += p
            events += SliceEvent.Tossed(p)
        }
    }

    private fun finish() {
        if (over) return
        phase = if (score >= rules.goal) SlicePhase.WON else SlicePhase.LOST
        swiping = false
        events += if (phase == SlicePhase.WON) SliceEvent.Won else SliceEvent.Lost
    }

    // ---- cutting --------------------------------------------------------------------

    /** Where [p] is drawn now: centre (x, y) and rotation in degrees. */
    fun pose(p: Piece, out: FloatArray = FloatArray(3)): FloatArray {
        if (p.hovering) {
            hover(p, time, out)
            out[0] += p.baseX
            out[1] += p.baseY
        } else {
            out[0] = p.x; out[1] = p.y; out[2] = p.rot
        }
        return out
    }

    /** Hover offset of an opening piece at game time [at]: (dx, dy, degrees). */
    fun hover(p: Piece, at: Float, out: FloatArray): FloatArray {
        val ramp = (at / 1f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val a = HOVER * ramp
        out[0] = a * 0.5f * sin(0.7f * p.freq * at) * p.sign
        out[1] = a * sin(p.freq * at) * p.sign
        out[2] = 1.6f * ramp * sin(0.8f * p.freq * at) * p.sign
        return out
    }

    private val pose = FloatArray(3)

    /** Cuts every live piece the blade's segment (x0, y0)→(x1, y1) passes through. */
    private fun cutAlong(x0: Float, y0: Float, x1: Float, y1: Float) {
        val len = hypot(x1 - x0, y1 - y0)
        val dx = (x1 - x0) / len
        val dy = (y1 - y0) / len
        for (p in pieces.toList()) {
            if (!p.alive) continue
            // A chest takes one cut per pass of the blade.
            if (p.lastSwipe == swipeId && time - p.lastHitAt < CHEST_REHIT) continue
            // Treasure just thrown (a chest's loot bursts out under the blade) flies clear first.
            if (!p.hovering && time - p.bornAt < FRESH) continue
            pose(p, pose)
            val cx = pose[0]
            val cy = pose[1]
            if (segmentDistance(cx, cy, x0, y0, x1, y1) > p.radius * 1.6f + 20f) continue
            // Into the sprite's own coordinates (at rest), where its outline is.
            val a = -pose[2] * DEG
            val ca = cos(a)
            val sa = sin(a)
            fun lx(x: Float, y: Float) = p.spec.cx + ((x - cx) * ca - (y - cy) * sa) / p.scale
            fun ly(x: Float, y: Float) = p.spec.cy + ((x - cx) * sa + (y - cy) * ca) / p.scale
            val ax = lx(x0, y0); val ay = ly(x0, y0)
            val bx = lx(x1, y1); val by = ly(x1, y1)
            if (!segmentHitsHull(p.spec.hull, ax, ay, bx, by, BLADE_RADIUS / p.scale)) continue
            // The cut line: the swipe's direction, through the point of the swipe nearest the centre.
            val ldx = bx - ax
            val ldy = by - ay
            val ll = hypot(ldx, ldy)
            val u = (((p.spec.cx - ax) * ldx + (p.spec.cy - ay) * ldy) / (ll * ll)).coerceIn(0f, 1f)
            val qx = ax + ldx * u
            val qy = ay + ldy * u
            val wx = x0 + (x1 - x0) * u
            val wy = y0 + (y1 - y0) * u
            hit(p, qx, qy, ldx / ll, ldy / ll, wx, wy, dx, dy)
        }
    }

    private fun hit(p: Piece, lx: Float, ly: Float, ldx: Float, ldy: Float, wx: Float, wy: Float, dx: Float, dy: Float) {
        p.lastSwipe = swipeId
        p.lastHitAt = time
        swipeCuts++
        lastCutX = wx
        lastCutY = wy
        // The combo: cuts in quick succession multiply.
        chain = if (time - lastCutAt <= rules.comboWindow) chain + 1 else 1
        lastCutAt = time
        val m = multiplier
        if (p.kind == TargetKind.CHEST && p.hp > 1) {
            p.hp--
            val pts = rules.chestHitPoints * m
            addScore("chest hit", pts)
            events += SliceEvent.ChestHit(p, wx, wy, dx, dy, p.hp, pts)
            if (!p.hovering) {
                // The blow knocks it back up a little.
                p.vy = min(p.vy, -420f)
                p.vx += dx * 120f
            }
            return
        }
        p.alive = false
        p.hp = 0
        cuts++
        if (p.hovering) {
            // Its pose stops bobbing once cut: freeze it where it was.
            pose(p, pose)
            p.x = pose[0]; p.y = pose[1]; p.rot = pose[2]
        }
        val pts = rules.basePoints(p) * m
        addScore(p.kind.name.lowercase(), pts)
        events += SliceEvent.Cut(p, lx, ly, ldx, ldy, wx, wy, dx, dy, pts, m)
        when (p.kind) {
            TargetKind.BARREL -> {
                timeLeft = min(rules.seconds, timeLeft + rules.barrelSeconds)
                lastTimeBonusAt = time
                events += SliceEvent.TimeBonus(rules.barrelSeconds, wx, wy)
            }
            TargetKind.CHEST -> spill(p)
            else -> Unit
        }
    }

    private fun addScore(what: String, pts: Int) {
        score += pts
        ledger += what to pts
        val s = rules.starScores.count { score >= it }
        if (s > stars) {
            stars = s
            events += SliceEvent.StarEarned(s)
        }
    }

    companion object {
        /** Where tossed treasure leaves the water, and below which it has fallen back in. */
        const val TOSS_Y = 1250f
        const val DROP_Y = 1330f
        const val LAUNCH_L = 120f
        const val LAUNCH_R = 820f
        const val APEX_TOP = 300f
        const val APEX_BOTTOM = 720f
        const val HOVER = 4f
        const val BLADE_RADIUS = 10f
        const val BLADE_LIFE = 0.16f
        const val CHEST_REHIT = 0.12f
        const val FRESH = 0.2f
        private const val DEG = (PI / 180.0).toFloat()

        /** How big tossed treasure is drawn, by kind (radius in stage pixels). */
        val SIZE = mapOf(
            TargetKind.GEM to 52f, TargetKind.COIN to 50f, TargetKind.CRATE to 48f, TargetKind.BARREL to 66f,
            TargetKind.CHEST to 84f,
        )

        /** Distance from (px, py) to the segment (ax, ay)→(bx, by). */
        fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val ex = bx - ax
            val ey = by - ay
            val l2 = ex * ex + ey * ey
            val t = if (l2 > 0f) (((px - ax) * ex + (py - ay) * ey) / l2).coerceIn(0f, 1f) else 0f
            return hypot(ax + ex * t - px, ay + ey * t - py)
        }

        /** True if the segment passes within [tol] of the convex polygon [h] (x0, y0, x1, y1...). */
        fun segmentHitsHull(h: FloatArray, ax: Float, ay: Float, bx: Float, by: Float, tol: Float): Boolean {
            if (inside(h, ax, ay) || inside(h, bx, by)) return true
            val n = h.size / 2
            for (i in 0 until n) {
                val cx = h[2 * i]
                val cy = h[2 * i + 1]
                val dx = h[(2 * i + 2) % h.size]
                val dy = h[(2 * i + 3) % h.size]
                if (segmentsCross(ax, ay, bx, by, cx, cy, dx, dy)) return true
                if (segmentDistance(cx, cy, ax, ay, bx, by) <= tol) return true
            }
            return false
        }

        private fun inside(h: FloatArray, x: Float, y: Float): Boolean {
            var sign = 0f
            val n = h.size / 2
            for (i in 0 until n) {
                val ax = h[2 * i]
                val ay = h[2 * i + 1]
                val bx = h[(2 * i + 2) % h.size]
                val by = h[(2 * i + 3) % h.size]
                val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                if (cross != 0f) {
                    if (sign == 0f) sign = cross else if (cross * sign < 0f) return false
                }
            }
            return true
        }

        private fun segmentsCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
            fun orient(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float) =
                (qx - px) * (ry - py) - (qy - py) * (rx - px)
            val o1 = orient(ax, ay, bx, by, cx, cy)
            val o2 = orient(ax, ay, bx, by, dx, dy)
            val o3 = orient(cx, cy, dx, dy, ax, ay)
            val o4 = orient(cx, cy, dx, dy, bx, by)
            return o1 * o2 < 0f && o3 * o4 < 0f || abs(o1) < 1e-6f && abs(o2) < 1e-6f
        }
    }
}
