package com.islandblast.game.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.islandblast.game.app.FrameLoop
import com.islandblast.game.app.GameFlow
import com.islandblast.game.app.SafeArea
import com.islandblast.game.app.Screen
import com.islandblast.game.levels.AssetCache
import com.islandblast.game.levels.LevelDef
import com.islandblast.game.levels.LevelKinds
import com.islandblast.game.levels.Progress
import com.islandblast.game.levels.WorldDef
import com.islandblast.game.levels.WorldGate
import com.islandblast.game.ui.ButtonStyle
import com.islandblast.game.ui.Icon
import com.islandblast.game.ui.UiKit
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A world's map: tap a level to play it, back to return home. */
class MapScreen(private val flow: GameFlow, val world: WorldDef, finished: LevelDef?) : Screen {
    val map: WorldMapView = WorldMapView(flow.context, world, AssetCache.get("map:${world.id}") { MapArt(flow.context.assets, world.id) },
        flow.ui, flow.progress, finished) { level ->
        if (level.playable) flow.openLevel(level) else map.toast("Level ${level.number} is coming soon!")
    }.apply {
        onBack = { flow.openHome() }
        onGate = { gate ->
            map.toast(if (flow.progress.worldUnlocked(gate.to)) "World ${gate.to} is coming soon!"
            else "Finish World ${world.number} to unlock World ${gate.to}!")
        }
    }
    override val view: View get() = map

    override fun start() {
        map.loop.start()
        LevelKinds.preload(flow.context, world.levels)
    }

    override fun stop() = map.loop.stop()

    override fun onBack(): Boolean {
        flow.openHome()
        return true
    }
}

/** A world map's backdrop and where its level buttons sit (assets/maps/<world>/map.json). */
class MapArt(am: AssetManager, worldId: String) {
    private val dir = "maps/$worldId"
    private val json = JSONObject(am.open("$dir/map.json").bufferedReader().use { it.readText() })
    val w = json.getJSONArray("size").getDouble(0).toFloat()
    val h = json.getJSONArray("size").getDouble(1).toFloat()
    val marginX = json.getJSONArray("margin").getDouble(0).toFloat()
    val marginY = json.getJSONArray("margin").getDouble(1).toFloat()
    val backdrop: Bitmap = (am.open("$dir/${json.getString("backdrop")}").use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
    } ?: error("Missing map backdrop")).apply { density = Bitmap.DENSITY_NONE }
    val blur: Bitmap = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(backdrop, 40, 80, true), 16, 32, true)
}

/**
 * Draws a world map edge to edge. The backdrop covers the screen like the levels do,
 * but is never scaled so far that a level button leaves the safe area below the
 * header. Level buttons, the path, the header and the player's marker are drawn live.
 */
@SuppressLint("ViewConstructor")
class WorldMapView(
    context: Context,
    val world: WorldDef,
    private val art: MapArt,
    private val ui: UiKit,
    private val progress: Progress,
    private val finished: LevelDef?,
    private val onLevel: (LevelDef) -> Unit,
) : View(context) {
    var onBack: () -> Unit = {}
    var onGate: (WorldGate) -> Unit = {}
    val loop = FrameLoop { dt ->
        time += dt
        invalidate()
    }
    private var time = 0f
    private val safe = SafeArea()

    var scale = 1f
        private set
    private var offX = 0f
    private var offY = 0f
    private var k = 1f
    private val header = RectF()
    private val backBtn = PointF()
    private var backR = 0f

    private var pressed: Any? = null
    private var toastText = ""
    private var toastAt = -10f

    /** Where the player's marker stands: the level to play next. */
    val current: LevelDef? = progress.current(world)
    private val pinFrom: LevelDef? = finished?.takeIf { it != current }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val tmp = RectF()

    fun toast(msg: String) {
        toastText = msg
        toastAt = time
        invalidate()
    }

    fun setSafeInsetsForTest(l: Int, t: Int, r: Int, b: Int) {
        safe.set(l, t, r, b)
        fit()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        safe.apply(insets)
        fit()
        invalidate()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    private fun fit() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        k = UiKit.unit(width, height)
        val area = safe.rect(width, height, 8f * k)
        // Header: back button, world title, stars.
        val hh = 190f * k
        header.set(area.left, area.top, area.right, area.top + hh)
        backR = 62f * k
        backBtn.set(area.left + 20f * k + backR, area.top + hh * 0.5f)
        // The level buttons (with room for their stars and labels) must fit below the header.
        val xs = world.levels.map { it.nodeX } + listOfNotNull(world.gate?.nodeX)
        val ys = world.levels.map { it.nodeY } + listOfNotNull(world.gate?.nodeY)
        val l = xs.min() - NODE_ROOM
        val r = xs.max() + NODE_ROOM
        val t = ys.min() - NODE_ROOM - 40f
        val b = ys.max() + NODE_ROOM + 30f
        val room = RectF(area.left, header.bottom, area.right, area.bottom)
        val cover = max(w / art.w, h / art.h)
        val fitNodes = min(room.width() / (r - l), room.height() / (b - t))
        scale = min(cover, fitNodes)
        offX = place((w - art.w * scale) / 2f, l, r, room.left, room.right)
        offY = place((h - art.h * scale) / 2f, t, b, room.top, room.bottom)
    }

    private fun place(centred: Float, a: Float, b: Float, lo: Float, hi: Float): Float {
        val min = lo - a * scale
        val max = hi - b * scale
        return if (min <= max) centred.coerceIn(min, max) else (min + max) / 2f
    }

    /** Map-art point to view pixels. */
    fun toView(x: Float, y: Float) = PointF(offX + x * scale, offY + y * scale)

    /** Centre of [level]'s button in view pixels (tests tap it). */
    fun nodeCenter(level: LevelDef): PointF = toView(level.nodeX, level.nodeY)

    fun nodeRadius(): Float = NODE_R * scale

    /** Centre of the gate to the next world in view pixels, if the world has one (tests tap it). */
    fun gateCenter(): PointF? = world.gate?.let { toView(it.nodeX, it.nodeY) }

    fun backCenter(): PointF = PointF(backBtn.x, backBtn.y)

    override fun onDraw(c: Canvas) {
        if (width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        tmp.set(offX - art.marginX * scale, offY - art.marginY * scale, offX + (art.w + art.marginX) * scale,
            offY + (art.h + art.marginY) * scale)
        if (tmp.left > 0f || tmp.top > 0f || tmp.right < w || tmp.bottom < h) {
            c.drawBitmap(art.blur, null, RectF(0f, 0f, w, h), bmp)
        }
        c.drawBitmap(art.backdrop, null, tmp, bmp)
        drawPath(c)
        for (level in world.levels) drawNode(c, level)
        drawGate(c)
        drawPin(c)
        drawHeader(c)
        val tk = time - toastAt
        if (tk in 0f..1.8f) {
            val alpha = min(1f, min(tk / 0.15f, (1.8f - tk) / 0.3f))
            ui.toast(c, toastText, w / 2f, h - safe.insets[3] - 170f * k, alpha, k)
        }
        if (time < 0.3f) {
            fill.shader = null
            fill.color = ((255 * (1f - time / 0.3f)).toInt() shl 24)
            c.drawRect(0f, 0f, w, h, fill)
        }
    }

    // ---- path ----------------------------------------------------------------------

    /** Stepping-stone dots along a smooth curve through the level buttons. */
    private fun drawPath(c: Canvas) {
        val gate = world.gate
        val pts = world.levels.map { toView(it.nodeX, it.nodeY) } + listOfNotNull(gate?.let { toView(it.nodeX, it.nodeY) })
        // The path is gold up to the last level completed (and on to the gate once the world is complete).
        val reached = if (gate != null && progress.worldComplete(world)) pts.size - 1
        else world.levels.indexOfLast { progress.completed(it) }
        val gap = 34f * scale
        for (i in 0 until pts.size - 1) {
            val p0 = pts[max(0, i - 1)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[min(pts.size - 1, i + 2)]
            val segLen = hypot(p2.x - p1.x, p2.y - p1.y)
            val n = max(2, (segLen / gap).toInt())
            val done = i < reached
            for (j in 1 until n) {
                val t = j / n.toFloat()
                val x = catmull(p0.x, p1.x, p2.x, p3.x, t)
                val y = catmull(p0.y, p1.y, p2.y, p3.y, t)
                // Skip the dots under the buttons.
                if (hypot(x - p1.x, y - p1.y) < NODE_R * scale * 1.15f || hypot(x - p2.x, y - p2.y) < NODE_R * scale * 1.15f) continue
                fill.shader = null
                fill.color = 0x66000000
                c.drawCircle(x, y + 3f * scale, 9.5f * scale, fill)
                fill.color = if (done) 0xFFFFD84A.toInt() else 0xFFFFF4DC.toInt()
                c.drawCircle(x, y, 9f * scale, fill)
                stroke.color = if (done) 0xFF8A4A00.toInt() else 0xFF7A5A3A.toInt()
                stroke.strokeWidth = 3f * scale
                c.drawCircle(x, y, 9f * scale, stroke)
            }
        }
    }

    private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    // ---- level buttons -------------------------------------------------------------

    private fun drawNode(c: Canvas, level: LevelDef) {
        val p = toView(level.nodeX, level.nodeY)
        val r = NODE_R * scale
        val stars = progress.stars(level)
        val isCurrent = level == current
        val press = if (pressed == level) 0.92f else 1f
        val playable = level.playable
        val style = when {
            !playable -> ButtonStyle.GRAY
            progress.completed(level) -> ButtonStyle.BLUE
            else -> ButtonStyle.GREEN
        }
        // The level to play next glows and breathes.
        if (isCurrent) {
            val g = 0.5f + 0.5f * sin(time * 4f)
            fill.shader = RadialGradient(p.x, p.y, r * 1.9f, intArrayOf(0xCCFFF6A0.toInt(), 0x00FFE060), null,
                Shader.TileMode.CLAMP)
            c.drawCircle(p.x, p.y, r * (1.7f + 0.2f * g), fill)
            fill.shader = null
        }
        c.save()
        val breathe = if (isCurrent) 1f + 0.05f * sin(time * 4f) else 1f
        c.scale(press * breathe, press * breathe, p.x, p.y)
        // Gold rim, glossy face.
        fill.shader = null
        fill.color = 0x66000000
        c.drawCircle(p.x, p.y + r * 0.16f, r * 1.12f, fill)
        fill.shader = LinearGradient(0f, p.y - r * 1.15f, 0f, p.y + r * 1.15f, 0xFFFFE680.toInt(), 0xFFC07A10.toInt(),
            Shader.TileMode.CLAMP)
        c.drawCircle(p.x, p.y, r * 1.14f, fill)
        fill.shader = null
        stroke.color = 0xFF5A2E04.toInt()
        stroke.strokeWidth = r * 0.08f
        c.drawCircle(p.x, p.y, r * 1.14f, stroke)
        ui.roundButton(c, p.x, p.y - r * 0.04f, r * 0.9f, style, null, false, k)
        if (playable) {
            val size = r * (if (level.number >= 10) 0.9f else 1.05f)
            ui.text.white(c, "${level.number}", p.x, p.y + size * 0.36f, size, Color.WHITE, Paint.Align.CENTER, outline = 1.1f)
        } else {
            ui.drawIcon(c, Icon.LOCK, p.x, p.y - r * 0.05f, r * 0.42f, Color.WHITE, 0xFF20263A.toInt(), k)
            ui.text.white(c, "${level.number}", p.x, p.y + r * 0.78f, r * 0.42f, Color.WHITE, Paint.Align.CENTER, outline = 0.9f)
        }
        c.restore()
        // Stars above a played level (empty ones show what is left to earn).
        if (playable) {
            val pop = if (level == finished) ((time - 0.5f) / 0.9f).coerceIn(0f, 1f) else 1f
            for (i in 0 until 3) {
                val sx = p.x + (i - 1) * r * 0.78f
                val sy = p.y - r * 1.28f + (if (i == 1) -r * 0.16f else 0f)
                val size = r * (if (i == 1) 0.78f else 0.66f)
                ui.star(c, sx, sy, size, false)
                val shown = i < stars && (level != finished || pop * 3f > i + 0.2f)
                if (shown) {
                    val local = if (level == finished) ((pop * 3f - i) / 1f).coerceIn(0f, 1f) else 1f
                    val s = if (local < 1f) 1.4f - 0.4f * local else 1f
                    ui.star(c, sx, sy, size * s, true)
                }
            }
            // Name tag.
            if (level.name.isNotEmpty()) {
                val size = 30f * scale
                val tw = ui.text.measure(level.name, size)
                tmp.set(p.x - tw / 2f - 22f * scale, p.y + r * 1.24f, p.x + tw / 2f + 22f * scale, p.y + r * 1.24f + 50f * scale)
                ui.pill(c, tmp, scale)
                ui.text.white(c, level.name, p.x, tmp.centerY() + size * 0.36f, size, Color.WHITE, Paint.Align.CENTER,
                    outline = 0.6f)
            }
        } else {
            val size = 26f * scale
            val label = "Coming Soon"
            val tw = ui.text.measure(label, size)
            tmp.set(p.x - tw / 2f - 18f * scale, p.y + r * 1.2f, p.x + tw / 2f + 18f * scale, p.y + r * 1.2f + 42f * scale)
            ui.pill(c, tmp, scale, 0xB3101C3E.toInt())
            ui.text.white(c, label, p.x, tmp.centerY() + size * 0.36f, size, 0xFFDDE6F5.toInt(), Paint.Align.CENTER,
                outline = 0.5f)
        }
    }

    /**
     * The gate to the next world at the end of the path: locked (grey, padlock) until
     * this world is complete, then green and glowing with the next world's number.
     */
    private fun drawGate(c: Canvas) {
        val gate = world.gate ?: return
        val p = toView(gate.nodeX, gate.nodeY)
        val r = NODE_R * scale * 1.08f
        val open = progress.worldUnlocked(gate.to)
        if (open) {
            val g = 0.5f + 0.5f * sin(time * 3f)
            fill.shader = RadialGradient(p.x, p.y, r * 2f, intArrayOf(0xCCB8FF8A.toInt(), 0x0060FF40), null,
                Shader.TileMode.CLAMP)
            c.drawCircle(p.x, p.y, r * (1.7f + 0.2f * g), fill)
            fill.shader = null
        }
        c.save()
        val press = if (pressed == gate) 0.92f else 1f
        c.scale(press, press, p.x, p.y)
        fill.shader = null
        fill.color = 0x66000000
        c.drawCircle(p.x, p.y + r * 0.16f, r * 1.12f, fill)
        fill.shader = LinearGradient(0f, p.y - r * 1.15f, 0f, p.y + r * 1.15f, 0xFFFFE680.toInt(), 0xFFC07A10.toInt(),
            Shader.TileMode.CLAMP)
        c.drawCircle(p.x, p.y, r * 1.14f, fill)
        fill.shader = null
        stroke.color = 0xFF5A2E04.toInt()
        stroke.strokeWidth = r * 0.08f
        c.drawCircle(p.x, p.y, r * 1.14f, stroke)
        ui.roundButton(c, p.x, p.y - r * 0.04f, r * 0.9f, if (open) ButtonStyle.GREEN else ButtonStyle.GRAY, null, false, k)
        if (open) {
            ui.text.white(c, "${gate.to}", p.x, p.y + r * 0.38f, r * 1.05f, Color.WHITE, Paint.Align.CENTER, outline = 1.1f)
        } else {
            ui.drawIcon(c, Icon.LOCK, p.x, p.y - r * 0.05f, r * 0.42f, Color.WHITE, 0xFF20263A.toInt(), k)
        }
        c.restore()
        val label = "World ${gate.to}"
        val size = 30f * scale
        val tw = ui.text.measure(label, size)
        tmp.set(p.x - tw / 2f - 22f * scale, p.y + r * 1.24f, p.x + tw / 2f + 22f * scale, p.y + r * 1.24f + 50f * scale)
        ui.pill(c, tmp, scale, if (open) 0xD9101C3E.toInt() else 0xB3101C3E.toInt())
        ui.text.white(c, label, p.x, tmp.centerY() + size * 0.36f, size, if (open) Color.WHITE else 0xFFDDE6F5.toInt(),
            Paint.Align.CENTER, outline = 0.6f)
    }

    /** The player's marker: a pin with the hero's face, over the level to play next. */
    private fun drawPin(c: Canvas) {
        val target = current ?: return
        var p = toView(target.nodeX, target.nodeY)
        val from = pinFrom
        if (from != null) {
            // Walk over from the level just finished.
            val t = ((time - 1.2f) / 0.9f).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)
            val a = toView(from.nodeX, from.nodeY)
            p = PointF(a.x + (p.x - a.x) * e, a.y + (p.y - a.y) * e - sin(e * Math.PI.toFloat()) * 120f * scale)
        }
        val r = NODE_R * scale
        val bob = abs(sin(time * 3f)) * 14f * scale
        val cy = p.y - r * 2.15f - bob
        // Pin body.
        path.reset()
        path.moveTo(p.x, p.y - r * 1.3f - bob * 0.3f)
        path.lineTo(p.x - r * 0.42f, cy + r * 0.45f)
        path.lineTo(p.x + r * 0.42f, cy + r * 0.45f)
        path.close()
        fill.shader = null
        fill.color = 0xFFE8382A.toInt()
        c.drawPath(path, fill)
        fill.color = 0xFFE8382A.toInt()
        c.drawCircle(p.x, cy, r * 0.66f, fill)
        stroke.color = 0xFF5A0E06.toInt()
        stroke.strokeWidth = r * 0.07f
        c.drawCircle(p.x, cy, r * 0.66f, stroke)
        fill.color = Color.WHITE
        c.drawCircle(p.x, cy, r * 0.5f, fill)
        ui.drawIcon(c, Icon.PLAY, p.x + r * 0.05f, cy, r * 0.32f, 0xFFE8382A.toInt(), 0xFF5A0E06.toInt(), k)
    }

    // ---- header --------------------------------------------------------------------

    private fun drawHeader(c: Canvas) {
        ui.roundButton(c, backBtn.x, backBtn.y, backR, ButtonStyle.BLUE, Icon.BACK, pressed == BACK, k)
        // Wooden sign with the world's name.
        val cx = header.centerX()
        val sw = 520f * k
        val sh = 150f * k
        tmp.set(cx - sw / 2f, header.top + 12f * k, cx + sw / 2f, header.top + 12f * k + sh)
        woodSign(c, tmp)
        ui.text.white(c, "World ${world.number}", cx, tmp.top + 56f * k, 42f * k, Color.WHITE, Paint.Align.CENTER, outline = 0.8f)
        ui.text.gold(c, world.name, cx, tmp.top + 122f * k, 58f * k, Paint.Align.CENTER, 0f)
        // Stars earned in this world.
        val total = progress.totalStars(world)
        val max = world.levels.count { it.playable } * 3
        val label = "$total/$max"
        val size = 40f * k
        val tw = ui.text.measure(label, size)
        tmp.set(header.right - tw - 120f * k, header.centerY() - 38f * k, header.right - 10f * k, header.centerY() + 38f * k)
        ui.pill(c, tmp, k)
        ui.star(c, tmp.left + 40f * k, tmp.centerY() - 2f * k, 70f * k, true)
        ui.text.white(c, label, tmp.left + 80f * k, tmp.centerY() + size * 0.36f, size, Color.WHITE, Paint.Align.LEFT, outline = 0.7f)
    }

    private fun woodSign(c: Canvas, r: RectF) {
        val rad = 30f * k
        fill.shader = null
        fill.color = 0x66000000
        c.drawRoundRect(r.left, r.top + 10f * k, r.right, r.bottom + 10f * k, rad, rad, fill)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(0xFFC9803E.toInt(), 0xFFA45E26.toInt(), 0xFF7C4318.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        // Planks.
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = 0x66401E06
        stroke.strokeWidth = 4f * k
        c.drawLine(r.left + 16f * k, r.centerY(), r.right - 16f * k, r.centerY(), stroke)
        stroke.color = 0x40FFE0B0
        stroke.strokeWidth = 3f * k
        c.drawLine(r.left + 26f * k, r.top + 10f * k, r.right - 26f * k, r.top + 10f * k, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = 0xFF3E1C06.toInt()
        stroke.strokeWidth = 7f * k
        c.drawRoundRect(r, rad, rad, stroke)
        // Nails.
        for (x in floatArrayOf(r.left + 28f * k, r.right - 28f * k)) {
            fill.color = 0xFF2A1404.toInt()
            c.drawCircle(x, r.centerY(), 9f * k, fill)
            fill.color = 0x99FFFFFF.toInt()
            c.drawCircle(x - 2.5f * k, r.centerY() - 2.5f * k, 3f * k, fill)
        }
    }

    // ---- input ---------------------------------------------------------------------

    private fun hitTest(x: Float, y: Float): Any? {
        if (hypot(x - backBtn.x, y - backBtn.y) < backR * 1.3f) return BACK
        val r = NODE_R * scale * 1.35f
        world.gate?.let { g -> val p = toView(g.nodeX, g.nodeY); if (hypot(x - p.x, y - p.y) < r) return g }
        return world.levels.minByOrNull { val p = toView(it.nodeX, it.nodeY); hypot(x - p.x, y - p.y) }
            ?.takeIf { val p = toView(it.nodeX, it.nodeY); hypot(x - p.x, y - p.y) < r }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = hitTest(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = hit
            MotionEvent.ACTION_MOVE -> if (hit != pressed) pressed = null
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                if (p != null && p == hit && time > 0.3f) {
                    when (p) {
                        BACK -> onBack()
                        is WorldGate -> onGate(p)
                        else -> onLevel(p as LevelDef)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        invalidate()
        return true
    }

    companion object {
        /** Level button radius, in map-art pixels. */
        const val NODE_R = 62f
        /** Room around a button for its stars, pin and name tag. */
        private const val NODE_ROOM = 120f
        private const val BACK = "back"
    }
}
