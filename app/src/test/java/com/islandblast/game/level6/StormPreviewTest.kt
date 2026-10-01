package com.islandblast.game.level6

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.islandblast.game.Png
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Visual preview of Storm Dodge on a 1080x2400 phone (punch-hole camera), drawn by the
 * real game renderer with the real art:
 *  - preview.png: a composed scene for design approval: every element of the Level 6
 *    design in view (barrels, logs, crate, mines, X hazard, coin trail, shield), the HUD
 *    as designed, the jet ski at speed, and a lightning strike;
 *  - opening.png: the level's actual opening moment (the calm first section).
 * Output: app/build/level6-shots/preview/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StormPreviewTest {
    private val out = File(System.getProperty("level5.repo") ?: ".", "app/build/level6-shots/preview").apply { mkdirs() }
    private val assets by lazy { StormAssets(RuntimeEnvironment.getApplication().assets) }

    private fun view(game: StormGame? = null): StormView {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val v = StormView(activity, assets)
        if (game != null) v.replaceGameForPreview(game)
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 2400)
        v.setSafeInsetsForTest(0, 110, 0, 0)
        return v
    }

    private fun save(v: StormView, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val px = IntArray(v.width * v.height)
        bmp.getPixels(px, 0, v.width, 0, 0, v.width, v.height)
        Png.write(File(out, "$name.png"), v.width, v.height, px)
    }

    /** (kind, lanes across, distance ahead) echoing the approved design's composition. */
    private val scene = listOf(
        Triple(Kind.COIN, 0f, 5.5f), Triple(Kind.COIN, 0f, 10f), Triple(Kind.COIN, 0f, 16f),
        Triple(Kind.COIN, 0f, 24f), Triple(Kind.COIN, 0f, 34f), Triple(Kind.COIN, 0f, 46f),
        Triple(Kind.COIN, 0.85f, 7.5f),
        Triple(Kind.SHIELD, -1.35f, 12f),
        Triple(Kind.MINE, -0.62f, 7f),
        Triple(Kind.X_HAZARD, 1.45f, 3.2f),
        Triple(Kind.BARREL_OPEN, 1.75f, 6.5f),
        Triple(Kind.CRATE, 1.3f, 14f),
        Triple(Kind.LOGS, 1.05f, 25f),
        Triple(Kind.LOGS, -1.95f, 6f),
        Triple(Kind.BARREL, -1.2f, 19f),
        Triple(Kind.BARREL, -0.45f, 33f),
        Triple(Kind.MINE, 0.55f, 44f),
        Triple(Kind.MINE, -2.2f, 27f),
        Triple(Kind.BARREL, 1.65f, 38f),
        Triple(Kind.BARREL, -1.0f, 47f),
        Triple(Kind.BARREL_OPEN, 0.9f, 52f),
    )

    @Test
    fun renderPreviewFrames() {
        // The actual opening moment of the level.
        val opening = view()
        repeat(80) { opening.step(1f / 60f) }
        save(opening, "opening")

        // Composed preview: run an empty river to the moment of the shot, so the jet ski is
        // up to speed with spray, then place the scene relative to where it will be.
        val frames = 150
        val dry = StormGame(course = emptyList())
        repeat(frames) { dry.update(1f / 60f) }
        val z0 = dry.z
        val placed = scene.map { (k, x, dz) -> Placed(k, x, z0 + dz, 1, drift = kotlin.math.abs(x) > 1.2f) }
        val game = StormGame(course = placed)
        val v = view(game)
        repeat(frames) { v.step(1f / 60f) }
        assertEquals("ski at the planned spot", z0, game.z, 1e-3f)
        // A lightning strike, caught at its brightest.
        game.events += StormEvent.Lightning
        v.renderer.consume(game)
        repeat(2) { v.step(1f / 60f) }
        save(v, "preview")
        assertEquals("nothing collected or hit yet", 2310, game.score)
        assertEquals("two gold stars", 2, game.liveStars())
        assertTrue(game.objects.none { game.isTaken(it) })
    }
}
