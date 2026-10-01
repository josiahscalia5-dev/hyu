package com.islandblast.game.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.islandblast.game.home.HomeScreen
import com.islandblast.game.levels.AssetCache
import com.islandblast.game.levels.Catalog
import com.islandblast.game.levels.LevelDef
import com.islandblast.game.levels.LevelKinds
import com.islandblast.game.levels.LevelScreen
import com.islandblast.game.levels.Progress
import com.islandblast.game.levels.WorldDef
import com.islandblast.game.map.MapArt
import com.islandblast.game.map.MapScreen
import com.islandblast.game.ui.UiKit

/**
 * The game's flow: Home → World map → a level → back to the map. Every screen asks
 * this to move on, so the whole navigation lives here.
 */
class GameFlow(val context: Context, val navigator: Navigator) {
    val catalog: Catalog = Catalog.load(context.assets)
    val progress = Progress(context)
    val ui: UiKit by lazy { UiKit(context.assets) }

    fun openHome() = navigator.show(HomeScreen(this))

    /** Decodes [world]'s map art in the background, so PLAY opens it at once. */
    fun preloadMap(world: WorldDef = catalog.worlds.first()) {
        val key = "map:${world.id}"
        if (AssetCache.contains(key)) return
        Thread { runCatching { AssetCache.get(key) { MapArt(context.assets, world.id) } } }.apply {
            name = "load-map"
            isDaemon = true
        }.start()
    }

    /** Shows [world]'s map; [finished] is a level just completed (its stars get celebrated). */
    fun openMap(world: WorldDef = catalog.worlds.first(), finished: LevelDef? = null) {
        val key = "map:${world.id}"
        whenLoaded(AssetCache.contains(key), "World ${world.number}", world.name,
            load = { AssetCache.get(key) { MapArt(context.assets, world.id) } },
            show = { navigator.show(MapScreen(this, world, finished)) })
    }

    fun openLevel(level: LevelDef) {
        val kind = LevelKinds.get(level.kind) ?: error("No gameplay for level ${level.id} (${level.kind})")
        whenLoaded(kind.loaded(level), "Level ${level.number}", level.name,
            load = { kind.preload(context, level) },
            show = { navigator.show(LevelScreen(this, level, kind.create(context, level))) })
    }

    /**
     * Runs [show] at once if its art is already decoded; otherwise shows a loading card
     * while a background thread decodes it, so a tap never stalls the UI thread.
     */
    private fun whenLoaded(ready: Boolean, title: String, subtitle: String, load: () -> Unit, show: () -> Unit) {
        if (ready) {
            show()
            return
        }
        val card = LoadingScreen(context, ui, title, subtitle)
        navigator.show(card)
        Thread {
            val error = runCatching(load).exceptionOrNull()
            main.post {
                if (error != null) throw error
                if (navigator.current === card) show()
            }
        }.apply {
            name = "load-art"
            isDaemon = true
        }.start()
    }

    private val main = Handler(Looper.getMainLooper())
}
