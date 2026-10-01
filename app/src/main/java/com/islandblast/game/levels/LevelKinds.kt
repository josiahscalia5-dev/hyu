package com.islandblast.game.levels

import android.content.Context
import com.islandblast.game.GameView
import com.islandblast.game.SliceView
import com.islandblast.game.StageView
import com.islandblast.game.level6.StormAssets
import com.islandblast.game.level6.StormView
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

    /** Decodes the level's art ahead of time (called off the UI thread). */
    fun preload(context: Context, level: LevelDef) = Unit

    /** True if [create] will not have to decode art first. */
    fun loaded(level: LevelDef): Boolean = true
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
                val rules = SliceRules.load(context.assets, level.dir!!, level.config)
                return SliceView(context, assets(context, level), rules, level.number, level.name)
            }

            override fun preload(context: Context, level: LevelDef) {
                assets(context, level)
            }

            override fun loaded(level: LevelDef) = AssetCache.contains("slice:${level.dir}")

            private fun assets(context: Context, level: LevelDef) =
                AssetCache.get("slice:${level.dir}") { SliceAssets(context.assets, level.dir!!) }
        },
        // Shoot a colour-shifting ball at blocks (Level 5).
        "color-shift" to object : LevelKind {
            override fun create(context: Context, level: LevelDef): LevelPlay = GameView(context, assets(context, level))

            override fun preload(context: Context, level: LevelDef) {
                assets(context, level)
            }

            override fun loaded(level: LevelDef) = AssetCache.contains("colorshift:${level.dir}")

            private fun assets(context: Context, level: LevelDef) =
                AssetCache.get("colorshift:${level.dir}") { Assets(context.assets, level.dir!!) }
        },
        // Ride a jet ski down a stormy river, dodging hazards (Level 6).
        "storm-dodge" to object : LevelKind {
            override fun create(context: Context, level: LevelDef): LevelPlay = StormView(context, assets(context, level))

            override fun preload(context: Context, level: LevelDef) {
                assets(context, level)
            }

            override fun loaded(level: LevelDef) = AssetCache.contains("storm:${level.dir}")

            private fun assets(context: Context, level: LevelDef) =
                AssetCache.get("storm:${level.dir}") { StormAssets(context.assets, level.dir!!) }
        },
    )

    fun get(kind: String?): LevelKind? = kind?.let { kinds[it] }

    /** Decodes the art of [levels] on a background thread, so tapping one opens it at once. */
    fun preload(context: Context, levels: List<LevelDef>) {
        val app = context.applicationContext
        Thread {
            for (l in levels) {
                if (!l.playable) continue
                try {
                    get(l.kind)?.preload(app, l)
                } catch (e: Exception) {
                    // Opening the level will load it (and report any problem) instead.
                }
            }
        }.apply { isDaemon = true; name = "level-preload" }.start()
    }

    fun register(kind: String, impl: LevelKind) {
        kinds[kind] = impl
    }
}

/**
 * Decoded art, kept while the app runs so replaying a level starts instantly. Each
 * entry loads at most once; loading one never blocks reading another.
 */
object AssetCache {
    private val loaded = java.util.concurrent.ConcurrentHashMap<String, Lazy<Any>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, load: () -> T): T = loaded.computeIfAbsent(key) { lazy(load) }.value as T

    /** True once [key] has finished loading. */
    fun contains(key: String): Boolean = loaded[key]?.isInitialized() == true
}
