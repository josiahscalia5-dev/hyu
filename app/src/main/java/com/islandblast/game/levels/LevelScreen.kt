package com.islandblast.game.levels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.islandblast.game.app.FrameLoop
import com.islandblast.game.app.GameFlow
import com.islandblast.game.app.Screen
import com.islandblast.game.model.TextSpot
import com.islandblast.game.ui.ButtonStyle
import com.islandblast.game.ui.Icon
import com.islandblast.game.ui.UiKit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hosts any level: its own full-screen view, with the pause menu and the result card
 * drawn on top. Records the result and leads back to the world map (or, after the
 * world's last level, to World Complete).
 */
class LevelScreen(private val flow: GameFlow, val level: LevelDef, val play: LevelPlay) : Screen {
    val overlay = LevelOverlay(flow.context, flow.ui, level, play, ::act)
    override val view: View = FrameLayout(flow.context).apply {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        addView(play.view, FrameLayout.LayoutParams(match, match))
        // (Qualified: inside apply, a bare "overlay" is View.getOverlay().)
        addView(this@LevelScreen.overlay, FrameLayout.LayoutParams(match, match))
    }
    private val loop = FrameLoop(::frame)
    private var clock = 0f
    private var overAt = -1f
    private var recorded = false

    /** True once the finished level's result has been saved. */
    var newBest = false
        private set

    private fun frame(dt: Float) {
        clock += dt
        val state = play.state
        if (state == PlayState.PLAYING) {
            overAt = -1f
            recorded = false
        } else if (overAt < 0f) {
            overAt = clock
        }
        // The result card waits a beat so the last effects (and the win burst) play out.
        val sinceOver = clock - overAt
        val showResult = state != PlayState.PLAYING && sinceOver >= RESULT_DELAY && (!play.settling || sinceOver > 3f)
        if (showResult && !recorded) {
            recorded = true
            newBest = flow.progress.record(level, state == PlayState.WON, play.stars, play.score)
        }
        val mode = when {
            showResult -> LevelOverlay.Mode.RESULT
            state == PlayState.PLAYING && play.paused -> LevelOverlay.Mode.PAUSE
            else -> LevelOverlay.Mode.NONE
        }
        overlay.update(dt, mode, newBest, flow.progress.bestScore(level))
    }

    private fun act(action: LevelOverlay.Action) {
        when (action) {
            LevelOverlay.Action.RESUME -> play.paused = false
            LevelOverlay.Action.RESTART -> {
                play.restart()
                overAt = -1f
                recorded = false
            }
            LevelOverlay.Action.MAP -> flow.openMap(level.world)
            LevelOverlay.Action.CONTINUE -> flow.continueAfter(level)
        }
    }

    override fun start() {
        play.view.start()
        loop.start()
    }

    override fun stop() {
        play.view.stop()
        loop.stop()
    }

    override fun onAppPause() {
        if (play.state == PlayState.PLAYING) play.paused = true
    }

    override fun onBack(): Boolean {
        when (overlay.mode) {
            LevelOverlay.Mode.RESULT -> act(if (play.state == PlayState.WON) LevelOverlay.Action.CONTINUE else LevelOverlay.Action.MAP)
            LevelOverlay.Mode.PAUSE -> act(LevelOverlay.Action.RESUME)
            LevelOverlay.Mode.NONE -> if (play.state == PlayState.PLAYING) play.paused = true
        }
        return true
    }

    companion object {
        const val RESULT_DELAY = 1.1f
    }
}

/** The pause menu and the result card, drawn over the level in screen pixels. */
@SuppressLint("ViewConstructor")
class LevelOverlay(
    context: Context,
    private val ui: UiKit,
    private val level: LevelDef,
    private val play: LevelPlay,
    private val onAction: (Action) -> Unit,
) : View(context) {
    enum class Mode { NONE, PAUSE, RESULT }
    enum class Action { RESUME, RESTART, MAP, CONTINUE }

    private class Button(val action: Action, val label: String, val style: ButtonStyle, val icon: Icon?) {
        val box = RectF()
    }

    var mode = Mode.NONE
        private set
    private var modeTime = 0f
    private var newBest = false
    private var best = 0
    private var buttons: List<Button> = emptyList()
    private var pressed: Button? = null
    private val panel = RectF()
    private val dim = Paint()

    fun update(dt: Float, next: Mode, newBest: Boolean, best: Int) {
        if (next != mode) {
            mode = next
            modeTime = 0f
            pressed = null
            buttons = when (next) {
                Mode.NONE -> emptyList()
                Mode.PAUSE -> listOf(
                    Button(Action.RESUME, "Resume", ButtonStyle.GREEN, Icon.PLAY),
                    Button(Action.RESTART, "Restart", ButtonStyle.BLUE, Icon.RESTART),
                    Button(Action.MAP, "World Map", ButtonStyle.ORANGE, Icon.MAP),
                )
                Mode.RESULT -> if (play.state == PlayState.WON) listOf(
                    Button(Action.RESTART, "Replay", ButtonStyle.BLUE, Icon.RESTART),
                    Button(Action.CONTINUE, "Continue", ButtonStyle.GREEN, null),
                ) else listOf(
                    Button(Action.MAP, "Map", ButtonStyle.BLUE, Icon.MAP),
                    Button(Action.RESTART, "Try Again", ButtonStyle.GREEN, Icon.RESTART),
                )
            }
            layoutButtons()
        } else {
            modeTime += dt
        }
        this.newBest = newBest
        this.best = best
        if (mode != Mode.NONE) invalidate()
    }

    /** Centre of the button for [action] in view pixels, if it is showing (tests tap it). */
    fun buttonCenter(action: Action): PointF? =
        buttons.firstOrNull { it.action == action }?.let { PointF(it.box.centerX(), it.box.centerY()) }

    /** Buttons can be pressed once the card has popped in. */
    val ready: Boolean get() = mode != Mode.NONE && modeTime >= 0.35f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutButtons()

    private fun layoutButtons() {
        if (width == 0) return
        val k = UiKit.unit(width, height)
        val cx = width / 2f
        val cy = height * 0.47f
        when (mode) {
            Mode.PAUSE -> {
                panel.set(cx - 420f * k, cy - 400f * k, cx + 420f * k, cy + 430f * k)
                buttons.forEachIndexed { i, b ->
                    val top = cy - 140f * k + i * 170f * k
                    b.box.set(cx - 300f * k, top, cx + 300f * k, top + 124f * k)
                }
            }
            Mode.RESULT -> {
                panel.set(cx - 440f * k, cy - 470f * k, cx + 440f * k, cy + 420f * k)
                buttons.forEachIndexed { i, b ->
                    val left = cx - 380f * k + i * 400f * k
                    b.box.set(left, cy + 230f * k, left + 360f * k, cy + 354f * k)
                }
            }
            Mode.NONE -> Unit
        }
    }

    override fun onDraw(c: Canvas) {
        if (mode == Mode.NONE) return
        val k = UiKit.unit(width, height)
        dim.color = Color.argb((150 * min(1f, modeTime / 0.2f)).roundToInt(), 4, 10, 30)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        // The card pops in.
        val p = min(1f, modeTime / 0.32f)
        val s = backOut(p)
        c.save()
        c.scale(s, s, panel.centerX(), panel.centerY())
        ui.panel(c, panel, k)
        val cx = panel.centerX()
        val subtitle = "Level ${level.number}" + if (level.name.isNotEmpty()) " · ${level.name}" else ""
        when (mode) {
            Mode.PAUSE -> {
                ui.text.gold(c, "Paused", cx, panel.top + 150f * k, 100f * k, Paint.Align.CENTER, 0f)
                ui.text.white(c, subtitle, cx, panel.top + 235f * k, 46f * k, Color.WHITE, Paint.Align.CENTER, outline = 0.7f)
            }
            Mode.RESULT -> drawResult(c, k, cx, subtitle)
            Mode.NONE -> Unit
        }
        for (b in buttons) ui.button(c, b.box, b.label, b.style, b === pressed, k, b.icon)
        c.restore()
    }

    private fun drawResult(c: Canvas, k: Float, cx: Float, subtitle: String) {
        val won = play.state == PlayState.WON
        ui.text.gold(c, if (won) "Level Complete!" else "Time's Up!", cx, panel.top + 140f * k, 92f * k,
            Paint.Align.CENTER, 0f)
        ui.text.white(c, subtitle, cx, panel.top + 220f * k, 44f * k, Color.WHITE, Paint.Align.CENTER, outline = 0.7f)
        // Stars pop in one after another.
        val sy = panel.top + 345f * k
        for (i in 0 until 3) {
            val sx = cx + (i - 1) * 190f * k
            val size = (if (i == 1) 190f else 160f) * k
            val y = if (i == 1) sy - 22f * k else sy
            ui.star(c, sx, y, size, false)
            if (i < play.stars) {
                val t = (modeTime - 0.35f - i * 0.28f) / 0.3f
                if (t > 0f) {
                    val sc = if (t >= 1f) 1f else backOut(t) * 1.0f
                    ui.star(c, sx, y, size * sc, true)
                }
            }
        }
        // Score counts up.
        val count = (play.score * min(1f, modeTime / 0.9f)).roundToInt()
        ui.text.cream(c, "%,d".format(count), centred("%,d".format(count), cx, panel.top + 560f * k, 104f * k))
        val note = when {
            !won -> "Goal: ${play.goal}"
            newBest -> "New best score!"
            else -> "Best %,d".format(best)
        }
        val color = if (won && newBest) 0xFF8CFF6A.toInt() else Color.WHITE
        ui.text.white(c, note, cx, panel.top + 640f * k, 44f * k, color, Paint.Align.CENTER, outline = 0.7f)
    }

    private fun centred(text: String, cx: Float, y: Float, size: Float): TextSpot {
        val tw = ui.text.measure(text, size)
        return TextSpot(cx - tw / 2f, y, size, 0f, 1f, tw)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        if (!ready) return true
        val hit = buttons.firstOrNull { it.box.contains(e.x, e.y) || grow(it.box).contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = hit
            MotionEvent.ACTION_MOVE -> if (pressed != null && hit !== pressed) pressed = null
            MotionEvent.ACTION_UP -> {
                val b = pressed
                pressed = null
                if (b != null && b === hit) onAction(b.action)
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        invalidate()
        return true
    }

    private fun grow(r: RectF): RectF {
        val k = UiKit.unit(width, height)
        return RectF(r.left - 12f * k, r.top - 12f * k, r.right + 12f * k, r.bottom + 20f * k)
    }

    private fun backOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f) - 1f
        return 1f + 2.2f * x * x * x + 1.2f * x * x
    }
}
