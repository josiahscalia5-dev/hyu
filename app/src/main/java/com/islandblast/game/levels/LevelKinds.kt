package com.islandblast.game.levels

import android.content.Context
import com.islandblast.game.GameView
import com.islandblast.game.SliceView
import com.islandblast.game.StageView
import com.islandblast.game.model.SliceRules
import com.islandblast.game.render.Assets
import com.islandblast.game.render.SliceAssets

enum class PlayState { PLAYING, WON, LOST }

/**
 * What the level host needs from a running level, whatever its gameplay. The host
 * draws the pause menu and the result card on top, records progress and navigates.
 */
interface LevelPlay {
    /** The level's full-screen view (it draws its own HUD, including its pause button). */
    val view: StageView
    val state: PlayState
    val score: Int
    val stars: Int

    /** What the player must do to pass, for the result card ("2,000 points"). */
    val goal: String

    /** Set by the level's own pause button, or by the host (back button, app paused). */
    var paused: Boolean

    /** Effects of the last moves are still playing; the result card waits for them. */
    val settling: Boolean

    /** Starts the level again from the beginning. */
    fun restart()
}

/** Builds a playable level of one kind of gameplay from its catalog entry. */
interface LevelKind {
    fun create(context: Context, level: LevelDef): LevelPlay
}

/**
 * Every kind of gameplay the app knows. A new level of an existing kind needs only a
 * catalog entry and its asset folder; a new kind of gameplay registers here.
 */
object LevelKinds {
    private val kinds = linkedMapOf<String, LevelKind>(
        // Swipe to slice treasure tossed out of the lagoon (Level 4).
        "treasure-slice" to object : LevelKind {
            override fun create(context: Context, level: LevelDef): LevelPlay {
                val dir = level.dir!!
                val assets = AssetCache.get("slice:$dir") { SliceAssets(context.assets, dir) }
                val rules = SliceRules.load(context.assets, dir, level.config)
                return SliceView(context, assets, rules, level.number, level.name)
            }
        },
        // Shoot a colour-shifting ball at blocks (Level 5).
        "color-shift" to object : LevelKind {
            override fun create(context: Context, level: LevelDef): LevelPlay {
                val dir = level.dir!!
                val assets = AssetCache.get("colorshift:$dir") { Assets(context.assets, dir) }
                return GameView(context, assets)
            }
        },
    )

    fun get(kind: String?): LevelKind? = kind?.let { kinds[it] }

    fun register(kind: String, impl: LevelKind) {
        kinds[kind] = impl
    }
}

/** Decoded level art, kept while the app runs so replaying a level starts instantly. */
object AssetCache {
    private val loaded = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T : Any> get(key: String, load: () -> T): T = loaded.getOrPut(key, load) as T
}
