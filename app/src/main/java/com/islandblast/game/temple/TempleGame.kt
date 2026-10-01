package com.islandblast.game.temple

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class TempleRules(
    /** The level opens as painted in the approved Temple Chase screen. */
    val startScore: Int = 1960,
    val startCoins: Int = 124,
    val startSeconds: Float = 35f,
    val hearts: Int = 2,
    val startPowers: Int = 3,
    val maxPowers: Int = 5,
    val coinPoints: Int = 10,
    val gemPoints: Int = 50,
    val powerPoints: Int = 25,
    val smashPoints: Int = 30,
    val timeBonusPerSecond: Int = 20,
    val heartBonus: Int = 250,
    /** Stars light up as the score passes these. */
    val starScores: IntArray = intArrayOf(1000, 1500, 4500),
    val hitSlowSeconds: Float = 0.8f,
    val invulnerableSeconds: Float = 1.5f,
    val lightningSeconds: Float = 3.5f,
    val lightningSpeedUp: Float = 1.55f,
    val shieldSeconds: Float = 10f,
    val magnetSeconds: Float = 8f,
    /** Lanes a magnet reaches to each side. */
    val magnetReach: Float = 1.9f,
    val steerSpeed: Float = 6.5f,
    val introSeconds: Float = 1.3f,
)

enum class TemplePhase { RUNNING, COMPLETE, CAUGHT, OUT_OF_TIME }

enum class Power { LIGHTNING, SHIELD, MAGNET }

sealed class TempleEvent {
    class Coin(val obj: Placed, val magnet: Boolean) : TempleEvent()
    class Gem(val obj: Placed, val magnet: Boolean) : TempleEvent()
    class Pickup(val obj: Placed, val power: Power) : TempleEvent()
    class Hit(val obj: Placed, val heartsLeft: Int) : TempleEvent()
    class Blocked(val obj: Placed) : TempleEvent()
    class Smash(val obj: Placed) : TempleEvent()
    class Activated(val power: Power) : TempleEvent()
    class SectionStart(val index: Int, val bonusSeconds: Float) : TempleEvent()
    class Star(val count: Int) : TempleEvent()
    object Go : TempleEvent()
    object Complete : TempleEvent()
    object Caught : TempleEvent()
    object OutOfTime : TempleEvent()
}

/**
 * Level 5 rules and motion, independent of Android so it can be tested headless.
 *
 * The runner runs forward along the path ([dist]) and steers sideways ([x], in lanes): the
 * controls set a target and the runner glides there. Items are fixed along the path; the
 * runner meets an item when their x and z footprints overlap.
 */
class TempleGame(val rules: TempleRules = TempleRules(), seed: Int = 5, course: List<Placed>? = null) {
    val objects: List<Placed> = course ?: TempleCourse.build(seed)
    private val taken = HashSet<Placed>()
    val events = ArrayList<TempleEvent>()

    var time = 0f; private set
    var timeLeft = rules.startSeconds; private set
    var dist = 0f; private set
    var x = 0f; private set
    var targetX = 0f; private set
    var score = rules.startScore; private set
    var coins = rules.startCoins; private set
    var coinsTaken = 0; private set
    var gemsTaken = 0; private set
    var hearts = rules.hearts; private set
    var hits = 0; private set
    var phase = TemplePhase.RUNNING; private set
    var paused = false
    var section = 0; private set
    var lastHitAt = -10f; private set
    var lastCheckpointAt = -10f; private set
    /** Game time the level ended (finish, caught or out of time); -1 while running. */
    var endedAt = -1f; private set
    var finishBonus = 0; private set
    val powers = IntArray(3) { rules.startPowers }
    /** Game time each power runs out (-1: not active). */
    val powerUntil = FloatArray(3) { -1f }
    private var slowUntil = -1f
    private var shown = false

    val totalCoins = objects.count { it.kind == Item.COIN && !it.decor }
    val totalGems = objects.count { it.kind == Item.GEM && !it.decor }
    val over get() = phase != TemplePhase.RUNNING
    val won get() = phase == TemplePhase.COMPLETE
    val introActive get() = time < rules.introSeconds
    val invulnerable get() = time - lastHitAt < rules.invulnerableSeconds
    val timerSeconds get() = max(0, ceil(timeLeft - 1e-4f).toInt())
    val progress get() = (dist / TempleCourse.length).coerceIn(0f, 1f)
    val stars: Int get() = rules.starScores.count { score >= it }

    fun active(p: Power) = time < powerUntil[p.ordinal]
    fun isTaken(o: Placed) = o in taken

    /** Current forward speed (units/s). */
    val speed: Float
        get() {
            if (introActive || over) return 0f
            var v = TempleCourse.sections[section].speed
            if (active(Power.LIGHTNING)) v *= rules.lightningSpeedUp
            if (time < slowUntil) v *= 0.5f
            return v
        }

    // ---- input -------------------------------------------------------------------------

    /** Arrow buttons: one lane left/right. */
    fun steer(dir: Int) {
        if (over || paused) return
        val lanes = TempleCourse.lanes
        val cur = lanes.indices.minBy { abs(lanes[it] - targetX) }
        targetX = lanes[(cur + dir.sign).coerceIn(0, lanes.size - 1)]
    }

    /** Drag: run toward a lateral position (lanes), within the path. */
    fun steerTo(lanePos: Float) {
        if (over || paused) return
        targetX = lanePos.coerceIn(-STEER_LIMIT, STEER_LIMIT)
    }

    /** Power-up buttons. Returns true if the power started. */
    fun use(p: Power): Boolean {
        if (over || paused || introActive || powers[p.ordinal] <= 0 || active(p)) return false
        powers[p.ordinal]--
        powerUntil[p.ordinal] = time + when (p) {
            Power.LIGHTNING -> rules.lightningSeconds
            Power.SHIELD -> rules.shieldSeconds
            Power.MAGNET -> rules.magnetSeconds
        }
        events += TempleEvent.Activated(p)
        return true
    }

    // ---- simulation ------------------------------------------------------------------------

    fun update(dt: Float) {
        if (paused) return
        val wasIntro = introActive
        time += dt
        if (over) return
        if (wasIntro && !introActive) events += TempleEvent.Go
        if (!introActive) timeLeft -= dt

        val d = targetX - x
        val step = rules.steerSpeed * dt
        x = if (abs(d) <= step) targetX else x + step * d.sign

        val before = dist
        dist = min(TempleCourse.length, dist + speed * dt)

        val s = TempleCourse.sections.indexOfLast { dist >= it.start }.coerceAtLeast(0)
        if (s != section) {
            section = s
            val bonus = TempleCourse.sections[s].bonusSeconds
            timeLeft += bonus
            lastCheckpointAt = time
            events += TempleEvent.SectionStart(s, bonus)
        }

        val starsBefore = stars
        val magnet = active(Power.MAGNET)
        for (o in objects) {
            if (o.decor || o in taken) continue
            if (o.z > dist + MAGNET_AHEAD + 1f) break
            if (o.z < before - o.kind.depth - 1f) continue
            val reach = if (magnet && (o.kind == Item.COIN || o.kind == Item.GEM)) rules.magnetReach else o.kind.halfWidth + RUNNER_HALF_WIDTH
            val pulled = magnet && (o.kind == Item.COIN || o.kind == Item.GEM) && abs(o.x - x) <= reach && o.z - dist <= MAGNET_AHEAD && o.z >= before - o.kind.depth
            val touching = o.z <= dist + o.kind.depth && o.z >= before - o.kind.depth && abs(o.x - x) <= o.kind.halfWidth + RUNNER_HALF_WIDTH
            if (!touching && !pulled) continue
            when (o.kind) {
                Item.COIN -> {
                    taken += o; coins++; coinsTaken++; score += rules.coinPoints
                    events += TempleEvent.Coin(o, magnet = !touching)
                }
                Item.GEM -> {
                    taken += o; gemsTaken++; score += rules.gemPoints
                    events += TempleEvent.Gem(o, magnet = !touching)
                }
                Item.LIGHTNING, Item.SHIELD, Item.MAGNET -> {
                    val p = when (o.kind) {
                        Item.LIGHTNING -> Power.LIGHTNING
                        Item.SHIELD -> Power.SHIELD
                        else -> Power.MAGNET
                    }
                    taken += o
                    powers[p.ordinal] = min(rules.maxPowers, powers[p.ordinal] + 1)
                    score += rules.powerPoints
                    events += TempleEvent.Pickup(o, p)
                }
                Item.BOULDER, Item.BLOCK -> collide(o)
            }
            if (over) return
        }
        if (stars > starsBefore) events += TempleEvent.Star(stars)

        if (dist >= TempleCourse.length) complete()
        else if (timeLeft <= 0f) {
            timeLeft = 0f
            end(TemplePhase.OUT_OF_TIME)
            events += TempleEvent.OutOfTime
        }
    }

    private fun collide(o: Placed) {
        if (active(Power.LIGHTNING)) {
            taken += o
            score += rules.smashPoints
            events += TempleEvent.Smash(o)
            return
        }
        if (invulnerable) return
        taken += o
        if (active(Power.SHIELD)) {
            // The shield absorbs exactly one hit.
            powerUntil[Power.SHIELD.ordinal] = -1f
            lastHitAt = time
            events += TempleEvent.Blocked(o)
            return
        }
        hits++
        hearts--
        lastHitAt = time
        slowUntil = time + rules.hitSlowSeconds
        events += TempleEvent.Hit(o, hearts)
        if (hearts <= 0) {
            end(TemplePhase.CAUGHT)
            events += TempleEvent.Caught
        }
    }

    private fun complete() {
        finishBonus = timerSeconds * rules.timeBonusPerSecond + hearts * rules.heartBonus
        score += finishBonus
        end(TemplePhase.COMPLETE)
        events += TempleEvent.Complete
    }

    private fun end(p: TemplePhase) {
        phase = p
        endedAt = time
        for (i in powerUntil.indices) powerUntil[i] = -1f
    }

    companion object {
        /** Half the runner's width, in lanes. */
        const val RUNNER_HALF_WIDTH = 0.3f
        /** How far the runner can steer from the middle lane (the path's edge stones). */
        const val STEER_LIMIT = 1.25f
        /** A magnet pulls coins in from this far ahead (units). */
        const val MAGNET_AHEAD = 0.8f
    }
}
