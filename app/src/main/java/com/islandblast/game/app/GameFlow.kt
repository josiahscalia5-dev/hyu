package com.islandblast.game.app

import android.content.Context
import com.islandblast.game.home.HomeScreen
import com.islandblast.game.levels.Catalog
import com.islandblast.game.levels.LevelDef
import com.islandblast.game.levels.LevelKinds
import com.islandblast.game.levels.LevelScreen
import com.islandblast.game.levels.Progress
import com.islandblast.game.levels.WorldDef
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

    /** Shows [world]'s map; [finished] is a level just completed (its stars get celebrated). */
    fun openMap(world: WorldDef = catalog.worlds.first(), finished: LevelDef? = null) =
        navigator.show(MapScreen(this, world, finished))

    fun openLevel(level: LevelDef) {
        val kind = LevelKinds.get(level.kind) ?: error("No gameplay for level ${level.id} (${level.kind})")
        navigator.show(LevelScreen(this, level, kind.create(context, level)))
    }
}
