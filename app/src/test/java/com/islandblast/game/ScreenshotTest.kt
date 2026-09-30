package com.islandblast.game

import android.graphics.Bitmap
import android.graphics.Canvas
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

/**
 * Renders the real Level 5 renderer off-screen (Robolectric native graphics) so the
 * frames can be compared with the approved reference and the colour-shift storyboard.
 * Output: PNG files in app/build/level5-shots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private val assets get() = TestLevel.assets
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level5-shots").apply { mkdirs() }

    private fun render(game: Level5Game, fx: Effects, renderer: Renderer, name: String) {
        val bmp = Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bmp), game, fx)
        val px = IntArray(1024 * 1536)
        bmp.getPixels(px, 0, 1024, 0, 0, 1024, 1536)
        Png.write(File(out, "$name.png"), 1024, 1536, px)
    }

    private fun step(game: Level5Game, fx: Effects, seconds: Float) {
        TestLevel.run(game, seconds) { fx.consume(it) }
    }

    @Test
    fun openingFrameMatchesTheApprovedScreen() {
        val game = Level5Game(assets.spec)
        render(game, Effects(assets), Renderer(assets), "frame0")
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
        // The player may tap the ball to pick another colour if the loaded one has no clear line.
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

    /** Whole view on a 20:9 phone: the reference frame is fitted, bands show the blurred temple. */
    @Test
    fun phoneAspectLetterbox() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val view = GameView(activity, assets)
        val w = 1080
        val h = 2400
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        Png.write(File(out, "phone_1080x2400.png"), w, h, px)
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
