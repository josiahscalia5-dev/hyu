package com.islandblast.game

import com.islandblast.game.e2e.SliceBot
import com.islandblast.game.model.SliceGame
import com.islandblast.game.model.SliceRules
import com.islandblast.game.render.SliceAssets
import org.robolectric.RuntimeEnvironment

object TestSlice {
    val assets: SliceAssets by lazy { SliceAssets(RuntimeEnvironment.getApplication().assets, "level4") }
    val rules: SliceRules by lazy { SliceRules.load(RuntimeEnvironment.getApplication().assets, "level4") }

    fun game(rules: SliceRules = this.rules) = SliceGame(assets.spec, rules)

    /** Advances game time in 60 fps steps. */
    fun run(game: SliceGame, seconds: Float) {
        var t = 0f
        while (t < seconds - 1e-4f) {
            game.update(1f / 60f)
            t += 1f / 60f
        }
    }

    private var ms = 1000L

    /** A real swipe in game terms: down, [steps] moves 16 ms apart (one per frame), up. */
    fun swipe(game: SliceGame, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 4, frame: Boolean = true) {
        game.touchDown(x0, y0, ms)
        for (i in 1..steps) {
            ms += 16
            game.touchMove(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps, ms)
            if (frame) game.update(1f / 60f)
        }
        game.touchUp()
        ms += 16
    }

    fun swipe(game: SliceGame, s: SliceBot.Swipe) = swipe(game, s.x0, s.y0, s.x1, s.y1)
}
