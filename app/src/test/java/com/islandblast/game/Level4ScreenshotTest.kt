package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.islandblast.game.model.Level4Game
import com.islandblast.game.render.Level4Effects
import com.islandblast.game.render.Level4Renderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Renders the real Level 4 renderer off-screen at the approved screen's own size, so
 * frames compare with it pixel for pixel. Output: app/build/level4-shots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Level4ScreenshotTest {
    private val assets get() = TestLevel4.assets
    private val repo = System.getProperty("level5.repo") ?: "."
    private val out = File(repo, "app/build/level4-shots").apply { mkdirs() }
    private val w get() = assets.spec.stageW.toInt()
    private val h get() = assets.spec.stageH.toInt()

    private fun render(game: Level4Game, fx: Level4Effects, r: Level4Renderer, name: String): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        r.draw(Canvas(bmp), game, fx)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        Png.write(File(out, "$name.png"), w, h, px)
        return px
    }

    private fun step(game: Level4Game, fx: Level4Effects, seconds: Float) = TestLevel4.run(game, seconds) { fx.consume(it) }

    /** The opening frame must reproduce the approved screen. */
    @Test
    fun openingFrameMatchesTheApprovedScreen() {
        val game = Level4Game(assets.spec)
        val frame = render(game, Level4Effects(assets), Level4Renderer(assets), "frame0")
        val ref = BitmapFactory.decodeFile("$repo/design/level4_reference.png")
        var off = 0
        for (y in 0 until h) for (x in 0 until w) {
            val a = frame[y * w + x]
            val b = ref.getPixel(x, y)
            val d = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            if (d > 30) off++
        }
        val share = off * 100.0 / (w * h)
        println("level 4 frame 0 vs approved screen: %.2f%% of pixels differ by more than 30".format(share))
        assertTrue("frame 0 drifted from the approved screen: %.2f%%".format(share), share < 4.0)
    }

    /** Aim, bolt in flight, harvest, next aim, pause and the end cards. */
    @Test
    fun storyboard() {
        val game = Level4Game(assets.spec)
        val fx = Level4Effects(assets)
        val r = Level4Renderer(assets)
        step(game, fx, 1.2f)
        render(game, fx, r, "s1_settled")
        val aim = TestLevel4.bestAim(game)!!
        step(game, fx, 0.3f)
        render(game, fx, r, "s2_aim")
        game.shoot()
        fx.consume(game)
        step(game, fx, 0.18f)
        render(game, fx, r, "s3_bolt")
        step(game, fx, 0.12f)
        render(game, fx, r, "s4_harvest")
        TestLevel4.runShot(game) { fx.consume(it) }
        step(game, fx, 0.4f)
        render(game, fx, r, "s5_after")
        println("first shot at (%.0f, %.0f) harvested %d".format(aim.x, aim.y, aim.hits.size))
        game.paused = true
        render(game, fx, r, "paused")
        game.paused = false
        step(game, fx, 70f)
        render(game, fx, r, "time_up")
    }
}
