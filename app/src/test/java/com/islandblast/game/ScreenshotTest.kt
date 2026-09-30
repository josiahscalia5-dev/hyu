package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.islandblast.game.model.GameEvent
import com.islandblast.game.model.Level5Game
import com.islandblast.game.model.Phase
import com.islandblast.game.render.Effects
import com.islandblast.game.render.Renderer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * Renders the real Level 5 renderer off-screen (Robolectric native graphics) at the
 * approved reference's own size, so frames can be compared with it pixel for pixel.
 * Output: PNG files in app/build/level5-shots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private val assets get() = TestLevel.assets
    private val repo = System.getProperty("level5.repo") ?: "."
    private val out = File(repo, "app/build/level5-shots").apply { mkdirs() }
    private val w get() = assets.spec.stageW.toInt()
    private val h get() = assets.spec.stageH.toInt()

    private fun render(game: Level5Game, fx: Effects, renderer: Renderer, name: String): IntArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bmp), game, fx)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        Png.write(File(out, "$name.png"), w, h, px)
        return px
    }

    private fun step(game: Level5Game, fx: Effects, seconds: Float) {
        TestLevel.run(game, seconds) { fx.consume(it) }
    }

    /** The opening frame must reproduce the approved full-screen design. */
    @Test
    fun openingFrameMatchesTheApprovedScreen() {
        val game = Level5Game(assets.spec)
        val frame = render(game, Effects(assets), Renderer(assets), "frame0")
        val ref = BitmapFactory.decodeFile("$repo/design/level5_fullscreen_reference.png")
        val goal = assets.spec.hud.goalText
        var off = 0
        var counted = 0
        for (y in 0 until h) for (x in 0 until w) {
            // The Goal board's text is live (the painted "all! al!" typo is not reproduced).
            if (x >= goal.l && x < goal.r && y >= goal.t && y < goal.b) continue
            val a = frame[y * w + x]
            val b = ref.getPixel(x, y)
            val d = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b))
            if (d > 30) off++
            counted++
        }
        val share = off * 100.0 / counted
        println("frame 0 vs approved reference: %.2f%% of pixels differ by more than 30".format(share))
        assertTrue("frame 0 drifted from the approved screen: %.2f%% pixels differ".format(share), share < 6.0)
    }

    /** The six beats of the colour-shift storyboard, driven by real shots. */
    @Test
    fun colourShiftStoryboard() {
        val game = Level5Game(assets.spec)
        val fx = Effects(assets)
        val r = Renderer(assets)
        step(game, fx, 1.3f)

        // 1. Aim and shoot the blue ball at the blue blocks.
        TestLevel.aimAtAngle(game, 0f)
        render(game, fx, r, "stage1_aim_blue")
        game.shoot()
        var cleared = false
        while (!cleared) {
            game.update(1f / 60f)
            cleared = game.events.any { it is GameEvent.Cleared }
            fx.consume(game)
        }
        // 2. The blue blocks burst when the ball hits them.
        step(game, fx, 0.08f)
        render(game, fx, r, "stage2_blue_cleared")
        // 3. The blocks that touched them change colour.
        step(game, fx, 0.4f)
        render(game, fx, r, "stage3_shift")
        TestLevel.runShot(game) { fx.consume(it) }
        step(game, fx, 0.6f)

        // 4. Next ball: aim at the best target for the loaded colour.
        var aim = TestLevel.bestAim(game)
        var switches = 0
        while (aim == null && switches++ < 4) {
            game.switchColor()
            fx.consume(game)
            aim = TestLevel.bestAim(game)
        }
        assertTrue(aim != null)
        TestLevel.aimAtAngle(game, aim!!.angleDeg)
        step(game, fx, 0.3f)
        render(game, fx, r, "stage4_aim_${game.color.key}")
        game.shoot()
        cleared = false
        while (!cleared) {
            game.update(1f / 60f)
            cleared = game.events.any { it is GameEvent.Cleared }
            fx.consume(game)
        }
        // 5. Those blocks are cleared.
        step(game, fx, 0.08f)
        render(game, fx, r, "stage5_cleared")
        // 6. The remaining blocks shift again.
        step(game, fx, 0.4f)
        render(game, fx, r, "stage6_shift_again")
        TestLevel.runShot(game) { fx.consume(it) }
        step(game, fx, 0.7f)
        render(game, fx, r, "stage7_next_ball")
        assertTrue(game.phase == Phase.READY)
    }

    @Test
    fun pausedAndEndStates() {
        val game = Level5Game(assets.spec)
        val fx = Effects(assets)
        val r = Renderer(assets)
        step(game, fx, 1.3f)
        game.paused = true
        render(game, fx, r, "paused")
        game.paused = false
        step(game, fx, 60f)
        render(game, fx, r, "time_up")
    }
}
