package com.islandblast.game.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Tunable rules for Level 4 "Mystic Harvest". */
class Rules4(
    val startSeconds: Float = 28f,
    val startScore: Int = 1240,
    val startCombo: Int = 8,
    /** Seconds a full time bar holds: the painted bar shows 0:28 of it. */
    val barSeconds: Float = 64f,
    /** Seconds added for every treasure harvested (up to a full bar). */
    val timeBonusPerHit: Float = 0.4f,
    /** Extra seconds a barrel is worth. */
    val barrelTimeBonus: Float = 3f,
    val starThresholds: IntArray = intArrayOf(600, 1200, 8000),
    val clearBonusPerSecond: Int = 20,
    val introSeconds: Float = 1.0f,
    val boltSpeed: Float = 2000f,
    val boltRadius: Float = 14f,
    /** How far the bolt swoops left of the straight line to its aim point, per unit of distance. */
    val bend: Float = 0.55f,
    val reloadDelay: Float = 0.35f,
    /** Hover amplitude of the treasure, in stage pixels. */
    val hover: Float = 4f,
    /** How far the hilt turns from its painted pose: counter-clockwise (toward pointing left) and clockwise. */
    val maxTurnLeft: Float = 40f,
    val maxTurnRight: Float = 85f,
    /** The aim point stays at least this far above the launcher tip. */
    val minRise: Float = 60f,
) {
    /** Points for harvesting [t], before the combo multiplier. */
    fun basePoints(t: TargetSpec): Int = when (t.kind) {
        TargetKind.GEM -> if (t.radius >= 40f) 20 else 10
        TargetKind.COIN -> 25
        TargetKind.CRATE -> 10
        TargetKind.BARREL -> 15
        TargetKind.CHEST -> 40
    }
}

sealed class Level4Event {
    class Hit(val target: Int, val x: Float, val y: Float, val points: Int, val combo: Int) : Level4Event()
    class TimeBonus(val seconds: Float) : Level4Event()
    object Fired : Level4Event()
    object Missed : Level4Event()
    object Won : Level4Event()
    object Lost : Level4Event()
    class StarEarned(val stars: Int) : Level4Event()
}

class Treasure(val index: Int, val spec: TargetSpec) {
    var alive = true
    var hitAt = -1f

    // Each piece bobs on its own rhythm; every offset is zero at t = 0 so the opening
    // frame is the approved screen exactly.
    val freq = 1.25f + ((index * 0.618034f) % 1f) * 0.9f
    val sign = if (index % 2 == 0) 1f else -1f
}

/**
 * A quadratic curve from the launcher tip to the aim point that swoops to the left of
 * the straight line, like the painted crescent. Sampled by arc length.
 */
class BoltPath(val x0: Float, val y0: Float, val cx: Float, val cy: Float, val x1: Float, val y1: Float) {
    private val n = 64
    private val xs = FloatArray(n + 1)
    private val ys = FloatArray(n + 1)
    private val ds = FloatArray(n + 1)
    val length: Float

    init {
        var d = 0f
        for (i in 0..n) {
            val t = i / n.toFloat()
            val u = 1f - t
            xs[i] = u * u * x0 + 2f * u * t * cx + t * t * x1
            ys[i] = u * u * y0 + 2f * u * t * cy + t * t * y1
            if (i > 0) d += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            ds[i] = d
        }
        length = d
    }

    /** Point [dist] along the curve, written into [out] (x, y). */
    fun at(dist: Float, out: FloatArray) {
        val d = dist.coerceIn(0f, length)
        var i = 1
        while (i < n && ds[i] < d) i++
        val seg = ds[i] - ds[i - 1]
        val k = if (seg > 1e-4f) (d - ds[i - 1]) / seg else 0f
        out[0] = xs[i - 1] + (xs[i] - xs[i - 1]) * k
        out[1] = ys[i - 1] + (ys[i] - ys[i - 1]) * k
    }
}

class Bolt(val path: BoltPath, val firedAt: Float) {
    var dist = 0f
    internal var carry = 0f
    internal var steps = 0
    /** Where the bolt ends: the path's end, or the chest that stopped it. */
    var end = path.length
    var done = false
    /** Game time the bolt stopped (its trail then shrinks into the end point). */
    var doneAt = -1f
}

/** What the aim guide shows: the curve and the treasure it will harvest, in order. */
class AimPreview(val path: BoltPath, val hits: List<Int>, val end: Float)

class Level4Game(val spec: Level4Spec, val rules: Rules4 = Rules4()) {
    val treasures = spec.targets.mapIndexed { i, t -> Treasure(i, t) }
    val events = ArrayList<Level4Event>()

    var time = 0f
        private set
    var timeLeft = rules.startSeconds
        private set
    var score = rules.startScore
        private set
    var combo = rules.startCombo
        private set
    var stars = starsFor(rules.startScore)
        private set
    var phase = Phase.READY
        private set
    var paused = false

    /** Aim point (stage units) and whether the player has aimed yet. */
    var aimX = spec.tipX
        private set
    var aimY = spec.tipY - 400f
        private set
    var aimTouched = false
        private set

    /** Launcher turn from its painted pose, in degrees (positive = clockwise). */
    var launcherTurn = 0f
        private set

    var bolt: Bolt? = null
        private set
    var shots = 0
        private set
    var harvested = 0
        private set
    var lastHitAt = -10f
        private set
    var lastTimeBonusAt = -10f
        private set
    var clearBonus = 0
        private set

    private var shotHits = 0
    private var resolveAt = 0f
    private val hitThisShot = HashSet<Int>()
    private val p = FloatArray(2)

    val timerSeconds: Int get() = max(0, ceil(timeLeft - 1e-4f).toInt())
    val over get() = phase == Phase.WON || phase == Phase.LOST
    val canShoot get() = phase == Phase.READY && !paused
    val introActive get() = time < rules.introSeconds
    val remaining get() = treasures.count { it.alive }

    /** Share of a full time bar left (0..1). */
    val timeFraction: Float get() = (timeLeft / rules.barSeconds).coerceIn(0f, 1f)

    // ---- motion ----------------------------------------------------------------

    /** Hover offset of [t] now: (dx, dy, degrees). */
    fun hover(t: Treasure, out: FloatArray = FloatArray(3)): FloatArray = hoverAt(t, time, out)

    /** Hover offset of [t] at game time [at]; zero at t = 0, easing in over the intro. */
    fun hoverAt(t: Treasure, at: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val ramp = (at / rules.introSeconds).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val a = rules.hover * ramp
        val w = t.freq
        out[0] = a * 0.5f * sin(0.7f * w * at) * t.sign
        out[1] = a * sin(w * at) * t.sign
        if (out.size > 2) out[2] = 1.6f * ramp * sin(0.8f * w * at) * t.sign
        return out
    }

    // ---- input ------------------------------------------------------------------

    /** Aims at a stage point; the bolt will swoop there from the launcher. */
    fun aimAt(x: Float, y: Float) {
        if (over || paused) return
        aimX = x.coerceIn(16f, spec.stageW - 16f)
        aimY = y.coerceIn(40f, spec.tipY - rules.minRise)
        aimTouched = true
        launcherTurn = turnFor(aimX, aimY)
    }

    fun shoot(): Boolean {
        if (!canShoot || !aimTouched) return false
        val path = pathTo(aimX, aimY)
        bolt = Bolt(path, time)
        hitThisShot.clear()
        shotHits = 0
        shots++
        phase = Phase.FLYING
        events += Level4Event.Fired
        return true
    }

    /**
     * The curve a shot fired now would take, and what it would harvest: the bolt is
     * stepped exactly as a real shot steps it, against where each piece will have
     * bobbed to by the time the bolt gets there.
     */
    fun aimPreview(): AimPreview {
        val path = pathTo(aimX, aimY)
        val hits = ArrayList<Int>()
        val seen = HashSet<Int>()
        var k = 1
        while (k * STEP <= path.length) {
            val d = k * STEP
            val t = probe(path, time, d, seen)
            if (t != null) {
                hits += t.index
                seen += t.index
                if (t.spec.kind == TargetKind.CHEST) return AimPreview(path, hits, d)
            }
            k++
        }
        return AimPreview(path, hits, path.length)
    }

    // ---- simulation -------------------------------------------------------------

    fun update(dt: Float) {
        if (paused) return
        // Time keeps running after the level ends so the win/lose effects play out.
        time += dt
        if (over) return
        timeLeft = max(0f, timeLeft - dt)
        when (phase) {
            Phase.FLYING -> fly(dt)
            Phase.RESOLVING -> if (time >= resolveAt) resolve()
            else -> Unit
        }
        if (phase == Phase.READY && timeLeft <= 0f) lose()
    }

    private fun fly(dt: Float) {
        val b = bolt ?: return
        // Whole steps only, the same ones aimPreview() takes, so the guide never lies.
        b.carry += rules.boltSpeed * dt
        while (b.carry >= STEP && !b.done) {
            b.carry -= STEP
            b.steps++
            val d = b.steps * STEP
            if (d > b.path.length) {
                b.dist = b.path.length
                b.done = true
                break
            }
            b.dist = d
            val t = probe(b.path, b.firedAt, d, hitThisShot)
            if (t != null) {
                b.path.at(d, p)
                harvest(t, p[0], p[1])
                if (t.spec.kind == TargetKind.CHEST) {
                    b.end = d
                    b.done = true
                }
            }
        }
        if (b.done) {
            b.doneAt = time
            phase = Phase.RESOLVING
            resolveAt = time + rules.reloadDelay
        }
    }

    private fun resolve() {
        if (shotHits == 0) {
            combo = 1
            events += Level4Event.Missed
        }
        when {
            remaining == 0 -> win()
            timeLeft <= 0f -> lose()
            else -> phase = Phase.READY
        }
    }

    private fun harvest(t: Treasure, x: Float, y: Float) {
        t.alive = false
        t.hitAt = time
        hitThisShot += t.index
        shotHits++
        harvested++
        combo++
        val pts = rules.basePoints(t.spec) * combo
        score += pts
        lastHitAt = time
        events += Level4Event.Hit(t.index, x, y, pts, combo)
        var bonus = rules.timeBonusPerHit
        if (t.spec.kind == TargetKind.BARREL) bonus += rules.barrelTimeBonus
        timeLeft = min(rules.barSeconds, timeLeft + bonus)
        if (t.spec.kind == TargetKind.BARREL) {
            lastTimeBonusAt = time
            events += Level4Event.TimeBonus(bonus)
        }
        updateStars()
    }

    private fun win() {
        phase = Phase.WON
        clearBonus = rules.clearBonusPerSecond * timerSeconds
        score += clearBonus
        updateStars()
        events += Level4Event.Won
    }

    private fun lose() {
        phase = Phase.LOST
        events += Level4Event.Lost
    }

    private fun updateStars() {
        val s = starsFor(score)
        if (s > stars) {
            stars = s
            events += Level4Event.StarEarned(s)
        }
    }

    private fun starsFor(points: Int) = rules.starThresholds.count { points >= it }

    // ---- geometry ----------------------------------------------------------------

    private val restAngle = atan2(spec.tipY - spec.pivotY, spec.tipX - spec.pivotX)
    private val hiltLength = hypot(spec.tipX - spec.pivotX, spec.tipY - spec.pivotY)

    /** Launcher tip after turning the hilt [turn] degrees about its pommel. */
    fun tipFor(turn: Float, out: FloatArray = FloatArray(2)): FloatArray {
        val a = restAngle + turn * DEG
        out[0] = spec.pivotX + cos(a) * hiltLength
        out[1] = spec.pivotY + sin(a) * hiltLength
        return out
    }

    private fun curve(x0: Float, y0: Float, x1: Float, y1: Float): BoltPath {
        val dx = x1 - x0
        val dy = y1 - y0
        // Left of the direction of travel (screen coordinates, y down).
        val cx = (x0 + x1) / 2f + dy * rules.bend
        val cy = (y0 + y1) / 2f - dx * rules.bend
        return BoltPath(x0, y0, cx, cy, x1, y1)
    }

    /** Hilt turn that lines the blade up with the curve's first stretch. */
    private fun turnFor(x: Float, y: Float): Float {
        var turn = 0f
        val tip = FloatArray(2)
        repeat(3) {
            tipFor(turn, tip)
            val c = curve(tip[0], tip[1], x, y)
            var a = (atan2(c.cy - tip[1], c.cx - tip[0]) - restAngle) / DEG
            while (a > 180f) a -= 360f
            while (a < -180f) a += 360f
            turn = a.coerceIn(-rules.maxTurnLeft, rules.maxTurnRight)
        }
        return turn
    }

    private fun pathTo(x: Float, y: Float): BoltPath {
        val tip = tipFor(launcherTurn)
        return curve(tip[0], tip[1], x, y)
    }

    /** Treasure the bolt [d] along [path] touches, for a shot fired at [firedAt]. */
    private fun probe(path: BoltPath, firedAt: Float, d: Float, skip: Set<Int>): Treasure? {
        path.at(d, p)
        return touching(p[0], p[1], firedAt + d / rules.boltSpeed, skip)
    }

    /** First live treasure (not in [skip]) the bolt at (x, y) touches at game time [at]. */
    private fun touching(x: Float, y: Float, at: Float, skip: Set<Int>): Treasure? {
        val o = FloatArray(3)
        var best: Treasure? = null
        var bestD = Float.MAX_VALUE
        for (t in treasures) {
            if (!t.alive || t.index in skip) continue
            val s = t.spec
            hoverAt(t, at, o)
            val lx = x - o[0]
            val ly = y - o[1]
            if (hypot(lx - s.cx, ly - s.cy) > s.radius * 2f + rules.boltRadius + 20f) continue
            val d = hullDistance(s.hull, lx, ly)
            if (d <= rules.boltRadius && d < bestD) {
                best = t
                bestD = d
            }
        }
        return best
    }

    companion object {
        private const val STEP = 6f
        private const val DEG = (PI / 180.0).toFloat()

        /** Distance from (x, y) to a convex polygon; 0 inside. */
        fun hullDistance(h: FloatArray, x: Float, y: Float): Float {
            val n = h.size / 2
            var inside = true
            var sign = 0f
            var best = Float.MAX_VALUE
            for (i in 0 until n) {
                val ax = h[2 * i]
                val ay = h[2 * i + 1]
                val bx = h[(2 * i + 2) % h.size]
                val by = h[(2 * i + 3) % h.size]
                val ex = bx - ax
                val ey = by - ay
                val cross = ex * (y - ay) - ey * (x - ax)
                if (cross != 0f) {
                    if (sign == 0f) sign = cross
                    else if (cross * sign < 0f) inside = false
                }
                val len2 = ex * ex + ey * ey
                val k = if (len2 > 0f) (((x - ax) * ex + (y - ay) * ey) / len2).coerceIn(0f, 1f) else 0f
                val qx = ax + ex * k - x
                val qy = ay + ey * k - y
                best = min(best, sqrt(qx * qx + qy * qy))
            }
            return if (inside) 0f else best
        }
    }
}
