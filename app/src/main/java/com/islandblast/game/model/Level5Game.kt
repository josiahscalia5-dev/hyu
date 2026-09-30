package com.islandblast.game.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Tunables. Starting values reproduce the approved Level 5 screen (score, timer, combo, coins). */
class Rules(
    val startSeconds: Float = 36f,
    val startScore: Int = 1760,
    val startCombo: Int = 9,
    val startCoins: Int = 30,
    val startColor: GameColor = GameColor.CYAN,
    /** Seconds added per block cleared, so a 31-block board is clearable from 0:36. */
    val timeBonusPerBlock: Float = 0.5f,
    val pointsPerBlock: Int = 10,
    val coinsPerBlock: Int = 1,
    val clearBonusPerSecond: Int = 20,
    val starThresholds: IntArray = intArrayOf(1000, 1500, 4000),
    val ballSpeed: Float = 2100f,
    val introSeconds: Float = 1.1f,
    val shiftDelay: Float = 0.2f,
    val shiftDuration: Float = 0.42f,
    val reloadDelay: Float = 0.66f,
    val maxShotSeconds: Float = 5f,
    val maxAimDegrees: Float = 72f,
)

sealed class GameEvent {
    class Cleared(val group: List<Int>, val color: GameColor, val x: Float, val y: Float) : GameEvent()
    class Shifted(val blocks: List<Int>) : GameEvent()
    class Bounced(val block: Int, val x: Float, val y: Float) : GameEvent()
    class Loaded(val color: GameColor, val switched: Boolean) : GameEvent()
    object Missed : GameEvent()
    object Won : GameEvent()
    object Lost : GameEvent()
    class StarEarned(val stars: Int) : GameEvent()
}

enum class Phase { READY, FLYING, RESOLVING, RETURNING, WON, LOST }

class Ball {
    var x = 0f
    var y = 0f
    var dx = 0f
    var dy = -1f
    var r = 0f
    var visible = true
    var loadedAt = -10f
}

/** One point of the aim guide; `hit` is the block the shot would reach first (or -1). */
class AimPath(val points: List<FloatArray>, val hit: Int, val matches: Boolean)

/**
 * Level 5 rules and physics, independent of Android so it can be tested headless.
 *
 * Stage units match the reference image (1024x1536). The ball travels up the temple
 * path "into" the screen, so its radius and speed shrink with height ([depthScale]).
 */
class Level5Game(val spec: LevelSpec, val rules: Rules = Rules()) {
    val board = Board(spec)
    val ball = Ball()
    val events = ArrayList<GameEvent>()

    var time = 0f
        private set
    var timeLeft = rules.startSeconds
        private set
    var score = rules.startScore
        private set
    var combo = rules.startCombo
        private set
    var coins = rules.startCoins
        private set
    var stars = starsFor(rules.startScore)
        private set
    var color = rules.startColor
        private set
    var phase = Phase.READY
        private set
    var paused = false
    var aimAngle = 0f
        private set

    /** True once the player has aimed; the opening frame's guide stops where the reference's does. */
    var aimTouched = false
        private set
    var shots = 0
        private set
    var clears = 0
        private set
    var lastTimeBonusAt = -10f
        private set

    private var shotTime = 0f
    private var carry = 0f
    private var resolveAt = 0f
    private var pendingShift: List<Int>? = null
    private var shiftAt = 0f
    private var lastBounceBlock = -1

    init {
        resetBall()
    }

    val timerSeconds: Int get() = max(0, ceil(timeLeft - 1e-4f).toInt())
    val introActive get() = time < rules.introSeconds
    val over get() = phase == Phase.WON || phase == Phase.LOST
    val canShoot get() = phase == Phase.READY && !paused

    fun depthScale(y: Float): Float =
        (DEPTH_MIN + (1f - DEPTH_MIN) * (y - DEPTH_TOP) / (spec.ballY - DEPTH_TOP)).coerceIn(DEPTH_MIN, 1f)

    fun update(dt: Float) {
        if (paused || over) return
        time += dt
        timeLeft -= dt
        when (phase) {
            Phase.FLYING, Phase.RETURNING -> stepBall(dt)
            Phase.RESOLVING -> resolve()
            else -> Unit
        }
        if (timeLeft <= 0f && !over) {
            timeLeft = 0f
            phase = Phase.LOST
            events += GameEvent.Lost
        }
    }

    // ---- input ----------------------------------------------------------------

    /** Aims toward a stage point. Points below the launcher mirror upward, so dragging anywhere works. */
    fun aimAt(x: Float, y: Float) {
        if (over || paused) return
        val dx = x - spec.ballX
        var up = spec.ballY - y
        if (up < 40f) up = max(40f, abs(up))
        val limit = (rules.maxAimDegrees * PI / 180.0).toFloat()
        aimAngle = atan2(dx, up).coerceIn(-limit, limit)
        aimTouched = true
    }

    fun shoot(): Boolean {
        if (!canShoot) return false
        ball.x = spec.ballX
        ball.y = spec.ballY
        ball.dx = sin(aimAngle)
        ball.dy = -cos(aimAngle)
        ball.r = spec.ballRadius
        ball.visible = true
        phase = Phase.FLYING
        shotTime = 0f
        carry = 0f
        lastBounceBlock = -1
        shots++
        return true
    }

    /** Tap on the ball: load the next colour (cycle order) that is still on the board. */
    fun switchColor(): Boolean {
        if (!canShoot) return false
        val present = board.colorsPresent()
        var c = color.next()
        repeat(GameColor.entries.size) {
            if (c in present && c != color) {
                color = c
                ball.loadedAt = time
                events += GameEvent.Loaded(c, switched = true)
                return true
            }
            c = c.next()
        }
        return false
    }

    fun setColorForTest(c: GameColor) {
        color = c
    }

    // ---- aim guide ------------------------------------------------------------

    fun aimPath(maxLength: Float = 2200f): AimPath {
        val p = Probe(spec.ballX, spec.ballY, sin(aimAngle), -cos(aimAngle), spec.ballRadius)
        val pts = ArrayList<FloatArray>()
        pts += floatArrayOf(p.x, p.y)
        var travelled = 0f
        var hit = -1
        while (travelled < maxLength) {
            val bouncedBefore = p.walls
            val c = advance(p, 6f)
            travelled += 6f
            if (p.walls != bouncedBefore) pts += floatArrayOf(p.x, p.y)
            if (c != null) {
                hit = c.block
                break
            }
            if (p.dy > 0f && p.y > MISS_Y) break
        }
        pts += floatArrayOf(p.x, p.y)
        val matches = hit >= 0 && board.blocks[hit].color == color
        return AimPath(pts, hit, matches)
    }

    // ---- simulation ------------------------------------------------------------

    private class Probe(var x: Float, var y: Float, var dx: Float, var dy: Float, var r: Float) {
        var walls = 0
    }

    private class Contact(val block: Int, val nx: Float, val ny: Float, val pen: Float)

    /** Moves [p] up to [dist], reflecting off walls; stops at the first block overlap. */
    private fun advance(p: Probe, dist: Float): Contact? {
        var left = dist
        while (left > 0f) {
            val h = min(left, SUBSTEP)
            p.x += p.dx * h
            p.y += p.dy * h
            p.r = spec.ballRadius * depthScale(p.y)
            if (p.x - p.r < ARENA_L) {
                p.x = ARENA_L + p.r; p.dx = abs(p.dx); p.walls++
            } else if (p.x + p.r > ARENA_R) {
                p.x = ARENA_R - p.r; p.dx = -abs(p.dx); p.walls++
            }
            if (p.y - p.r < ARENA_T) {
                p.y = ARENA_T + p.r; p.dy = abs(p.dy); p.walls++
            }
            val c = firstContact(p)
            if (c != null) return c
            left -= h
        }
        return null
    }

    private fun firstContact(p: Probe): Contact? {
        var best: Contact? = null
        for (b in board.blocks) {
            if (!b.alive) continue
            val rc = b.spec.rect
            val qx = p.x.coerceIn(rc.l, rc.r)
            val qy = p.y.coerceIn(rc.t, rc.b)
            val ddx = p.x - qx
            val ddy = p.y - qy
            val d2 = ddx * ddx + ddy * ddy
            if (d2 >= p.r * p.r) continue
            val c = if (d2 > 1e-6f) {
                val d = sqrt(d2)
                Contact(b.index, ddx / d, ddy / d, p.r - d)
            } else {
                val pl = p.x - rc.l; val pr = rc.r - p.x; val pt = p.y - rc.t; val pb = rc.b - p.y
                val m = minOf(pl, pr, pt, pb)
                when (m) {
                    pb -> Contact(b.index, 0f, 1f, p.r + pb)
                    pt -> Contact(b.index, 0f, -1f, p.r + pt)
                    pl -> Contact(b.index, -1f, 0f, p.r + pl)
                    else -> Contact(b.index, 1f, 0f, p.r + pr)
                }
            }
            if (best == null || c.pen > best.pen) best = c
        }
        return best
    }

    private fun stepBall(dt: Float) {
        shotTime += dt
        val p = Probe(ball.x, ball.y, ball.dx, ball.dy, ball.r)
        val speed = rules.ballSpeed * (0.5f + 0.5f * depthScale(ball.y))
        // Fixed-size substeps, identical to the aim guide's, so the guide predicts the real shot.
        carry += speed * dt
        while (carry >= SUBSTEP && (phase == Phase.FLYING || phase == Phase.RETURNING)) {
            val step = SUBSTEP
            carry -= step
            val c = if (phase == Phase.RETURNING) {
                p.x += p.dx * step; p.y += p.dy * step
                p.r = spec.ballRadius * depthScale(p.y)
                null
            } else advance(p, step)
            if (c != null) {
                val b = board.blocks[c.block]
                if (b.color == color) {
                    sync(p)
                    hitGroup(c.block, p.x - c.nx * p.r, p.y - c.ny * p.r)
                    return
                }
                // Wrong colour: bounce off and keep going.
                val dot = p.dx * c.nx + p.dy * c.ny
                if (dot < 0f) {
                    p.dx -= 2f * dot * c.nx
                    p.dy -= 2f * dot * c.ny
                    val n = sqrt(p.dx * p.dx + p.dy * p.dy)
                    p.dx /= n; p.dy /= n
                }
                p.x += c.nx * (c.pen + 0.5f)
                p.y += c.ny * (c.pen + 0.5f)
                if (c.block != lastBounceBlock || b.bumpAt < time - 0.15f) {
                    b.bumpAt = time
                    events += GameEvent.Bounced(c.block, p.x - c.nx * p.r, p.y - c.ny * p.r)
                }
                lastBounceBlock = c.block
            }
            if (phase == Phase.FLYING && ((p.dy > 0f && p.y > MISS_Y) || shotTime > rules.maxShotSeconds)) {
                // Missed: the ball rolls back down the path to the launcher.
                phase = Phase.RETURNING
                val tx = spec.ballX - p.x
                val ty = spec.ballY - p.y
                val n = sqrt(tx * tx + ty * ty).coerceAtLeast(1f)
                p.dx = tx / n; p.dy = ty / n
                if (combo != 0) combo = 0
                events += GameEvent.Missed
            }
            if (phase == Phase.RETURNING && p.y >= spec.ballY - 1f) {
                resetBall()
                phase = Phase.READY
                ball.loadedAt = time
                events += GameEvent.Loaded(color, switched = false)
                return
            }
        }
        sync(p)
    }

    private fun sync(p: Probe) {
        ball.x = p.x; ball.y = p.y; ball.dx = p.dx; ball.dy = p.dy; ball.r = p.r
    }

    private fun hitGroup(block: Int, x: Float, y: Float) {
        val group = board.group(block)
        pendingShift = board.clear(group, time)
        combo += 1
        clears++
        score += rules.pointsPerBlock * group.size * combo
        coins += rules.coinsPerBlock * group.size
        if (rules.timeBonusPerBlock > 0f) {
            timeLeft += rules.timeBonusPerBlock * group.size
            lastTimeBonusAt = time
        }
        events += GameEvent.Cleared(group, color, x, y)
        updateStars()
        ball.visible = false
        phase = Phase.RESOLVING
        shiftAt = time + rules.shiftDelay
        resolveAt = time + rules.reloadDelay
    }

    private fun resolve() {
        val pending = pendingShift
        if (pending != null && time >= shiftAt) {
            pendingShift = null
            board.shift(pending, time)
            if (pending.any { board.blocks[it].alive }) events += GameEvent.Shifted(pending)
        }
        if (time < resolveAt) return
        if (board.cleared) {
            score += rules.clearBonusPerSecond * timerSeconds
            updateStars()
            phase = Phase.WON
            events += GameEvent.Won
            return
        }
        color = board.bestColor(prefer = color.next()) ?: color
        resetBall()
        ball.loadedAt = time
        phase = Phase.READY
        events += GameEvent.Loaded(color, switched = false)
    }

    private fun resetBall() {
        ball.x = spec.ballX
        ball.y = spec.ballY
        ball.dx = 0f
        ball.dy = -1f
        ball.r = spec.ballRadius
        ball.visible = true
    }

    private fun updateStars() {
        val s = starsFor(score)
        if (s > stars) {
            stars = s
            events += GameEvent.StarEarned(s)
        }
    }

    private fun starsFor(points: Int) = rules.starThresholds.count { points >= it }

    companion object {
        const val ARENA_L = 150f
        const val ARENA_R = 874f
        const val ARENA_T = 224f
        const val MISS_Y = 930f
        const val DEPTH_TOP = 700f
        const val DEPTH_MIN = 0.45f
        private const val SUBSTEP = 3f
    }
}
