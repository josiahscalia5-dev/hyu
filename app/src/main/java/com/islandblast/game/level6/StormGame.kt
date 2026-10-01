package com.islandblast.game.level6

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class StormRules(
    /** Starts as painted in the approved Storm Dodge screen. */
    val startScore: Int = 2310,
    val startSeconds: Float = 36f,
    val startStars: Int = 2,
    val coinPoints: Int = 10,
    val shieldPoints: Int = 50,
    val hitPenalty: Int = 150,
    /** A hit also knocks the ski back this far along the river (lost progress). */
    val hitKnockback: Float = 6f,
    val hitSlowSeconds: Float = 1.0f,
    val invulnerableSeconds: Float = 1.3f,
    val shieldSeconds: Float = 12f,
    val timeBonusPerSecond: Int = 40,
    val noHitBonus: Int = 500,
    /** Stars at the finish: from coins collected and hits taken. */
    val threeStarCoins: Float = 0.7f,
    val maxHitsForThree: Int = 1,
    val maxHitsForTwo: Int = 4,
    val steerSpeed: Float = 5.2f,
    val introSeconds: Float = 1.2f,
)

enum class StormPhase { RACING, FINISHED, OUT_OF_TIME }

sealed class StormEvent {
    class Hit(val obj: Placed, val shielded: Boolean, val x: Float, val z: Float) : StormEvent()
    class Coin(val obj: Placed) : StormEvent()
    class Shield(val obj: Placed) : StormEvent()
    class SectionStart(val index: Int, val bonusSeconds: Float) : StormEvent()
    object Lightning : StormEvent()
    object Finished : StormEvent()
    object OutOfTime : StormEvent()
}

/**
 * Level 6 rules and motion, independent of Android so it can be tested headless.
 *
 * The jet ski rides forward along the river (z). The player steers sideways (x, in
 * lanes); controls set a target and the ski glides there. Objects are fixed in the
 * river; the ski collides with a hazard when their x and z footprints overlap.
 */
class StormGame(val rules: StormRules = StormRules(), seed: Int = 6) {
    val objects: List<Placed> = StormCourse.build(seed)
    private val taken = HashSet<Placed>()
    val events = ArrayList<StormEvent>()

    var time = 0f; private set
    var timeLeft = rules.startSeconds; private set
    var z = 0f; private set
    var x = 0f; private set
    var targetX = 0f; private set
    var score = rules.startScore; private set
    var coins = 0; private set
    var hits = 0; private set
    var shieldsUsed = 0; private set
    var phase = StormPhase.RACING; private set
    var paused = false
    var section = 0; private set
    var lastHitAt = -10f; private set
    var shieldUntil = -1f; private set
    var lastCheckpointAt = -10f; private set
    /** Game time the level ended (finish line or time out), -1 while racing. */
    var finishedAt = -1f; private set
    var stars = rules.startStars; private set
    var finishBonus = 0; private set
    private var slowUntil = -1f
    private var nextLightning = 2.5f
    private var lightningIndex = 0

    val totalCoins = objects.count { it.kind == Kind.COIN }
    val shielded get() = time < shieldUntil
    val invulnerable get() = time - lastHitAt < rules.invulnerableSeconds
    val over get() = phase != StormPhase.RACING
    val timerSeconds get() = max(0, ceil(timeLeft - 1e-4f).toInt())
    val progress get() = (z / StormCourse.length).coerceIn(0f, 1f)
    val introActive get() = time < rules.introSeconds

    fun isTaken(o: Placed) = o in taken

    /** Current forward speed (units/s): the section's speed, briefly slowed after a hit. */
    val speed: Float
        get() {
            if (introActive) return 0f
            val base = StormCourse.sections[section].speed
            return if (time < slowUntil) base * 0.45f else base
        }

    // ---- input ----------------------------------------------------------------

    /** Arrow buttons: one lane left/right. */
    fun steer(dir: Int) {
        if (over || paused) return
        val lanes = StormCourse.lanes
        val cur = lanes.indices.minBy { abs(lanes[it] - targetX) }
        targetX = lanes[(cur + dir.sign).coerceIn(0, lanes.size - 1)]
    }

    /** Drag: steer continuously to a lateral position (in lanes, clamped to the river). */
    fun steerTo(lanePos: Float) {
        if (over || paused) return
        targetX = lanePos.coerceIn(-1.15f, 1.15f)
    }

    // ---- simulation -----------------------------------------------------------------

    fun update(dt: Float) {
        if (paused) return
        time += dt
        if (over) return
        timeLeft -= dt

        // Steering glides toward the target.
        val d = targetX - x
        val step = rules.steerSpeed * dt
        x = if (abs(d) <= step) targetX else x + step * d.sign

        val before = z
        z = min(StormCourse.length, z + speed * dt)

        val s = StormCourse.sections.indexOfLast { z >= it.start }.coerceAtLeast(0)
        if (s != section) {
            section = s
            // Checkpoint: reaching a new section adds time.
            val bonus = StormCourse.sections[s].bonusSeconds
            timeLeft += bonus
            lastCheckpointAt = time
            events += StormEvent.SectionStart(s, bonus)
        }

        // Collisions and pickups between the previous and current position.
        for (o in objects) {
            if (o.drift || o in taken || o.z < before - o.kind.depth || o.z > z + o.kind.depth) continue
            if (abs(o.z - z) > o.kind.depth) continue
            if (abs(o.x - x) > o.kind.halfWidth + SKI_HALF_WIDTH) continue
            when (o.kind) {
                Kind.COIN -> {
                    taken += o; coins++; score += rules.coinPoints
                    events += StormEvent.Coin(o)
                }
                Kind.SHIELD -> {
                    taken += o; shieldUntil = time + rules.shieldSeconds; score += rules.shieldPoints
                    events += StormEvent.Shield(o)
                }
                else -> hit(o)
            }
        }

        // Lightning strikes on a rhythm that speeds up with the storm.
        if (time >= nextLightning) {
            events += StormEvent.Lightning
            lightningIndex++
            val gapBySection = floatArrayOf(4.5f, 3.6f, 2.4f, 1.9f, 6f)
            nextLightning = time + gapBySection[section] + ((lightningIndex * 37) % 10) / 10f
        }

        if (z >= StormCourse.length) finish()
        else if (timeLeft <= 0f) {
            timeLeft = 0f
            phase = StormPhase.OUT_OF_TIME
            finishedAt = time
            stars = starsFor(false)
            events += StormEvent.OutOfTime
        }
    }

    private fun hit(o: Placed) {
        if (invulnerable) return
        taken += o
        if (shielded) {
            // The shield absorbs exactly one collision.
            shieldUntil = -1f
            shieldsUsed++
            lastHitAt = time
            events += StormEvent.Hit(o, shielded = true, x = o.x, z = o.z)
            return
        }
        hits++
        lastHitAt = time
        slowUntil = time + rules.hitSlowSeconds
        score = max(0, score - rules.hitPenalty)
        z = max(0f, z - rules.hitKnockback)
        events += StormEvent.Hit(o, shielded = false, x = o.x, z = o.z)
    }

    private fun finish() {
        phase = StormPhase.FINISHED
        finishedAt = time
        finishBonus = timerSeconds * rules.timeBonusPerSecond + if (hits == 0) rules.noHitBonus else 0
        score += finishBonus
        stars = starsFor(true)
        events += StormEvent.Finished
    }

    /** Coins the ski has already reached or passed (collected or missed). */
    val coinsPassed get() = objects.count { it.kind == Kind.COIN && it.z <= z }

    /** Star rating at the current pace (shown in the HUD while racing). */
    fun liveStars(): Int {
        if (over) return stars
        if (coinsPassed == 0 && hits == 0) return rules.startStars
        val share = coins.toFloat() / max(1, coinsPassed)
        return when {
            share >= rules.threeStarCoins && hits <= rules.maxHitsForThree -> 3
            hits <= rules.maxHitsForTwo -> 2
            else -> 1
        }
    }

    private fun starsFor(finished: Boolean): Int {
        if (!finished) return 0
        val share = coins.toFloat() / max(1, totalCoins)
        return when {
            share >= rules.threeStarCoins && hits <= rules.maxHitsForThree -> 3
            hits <= rules.maxHitsForTwo -> 2
            else -> 1
        }
    }

    companion object {
        /** Half the jet ski's width, in lanes. */
        const val SKI_HALF_WIDTH = 0.22f
    }
}
