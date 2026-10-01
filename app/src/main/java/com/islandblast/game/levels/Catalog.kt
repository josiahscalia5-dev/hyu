package com.islandblast.game.levels

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * One level on a world map. A level with a [kind] is playable: [LevelKinds] knows how
 * to build it from the asset folder [dir]. A level without one is on the map as
 * "coming soon".
 */
class LevelDef(
    val world: WorldDef,
    val number: Int,
    val name: String,
    val kind: String?,
    /** Asset folder holding the level's art and settings (e.g. "level4"). */
    val dir: String?,
    /** Where the level's button sits on its world's map, in map-art pixels. */
    val nodeX: Float,
    val nodeY: Float,
    /** Kind-specific settings from the catalog entry (may be empty). */
    val config: JSONObject,
) {
    /** Stable id used for saved progress, e.g. "w1-4". */
    val id: String get() = "${world.id}-$number"

    val playable: Boolean get() = kind != null && dir != null && LevelKinds.get(kind) != null
}

class WorldDef(val id: String, val number: Int, val name: String) {
    lateinit var levels: List<LevelDef>
        internal set

    /** The way on to the next world, at the end of this world's path (null for the last world). */
    var gate: WorldGate? = null
        internal set

    fun level(number: Int): LevelDef? = levels.firstOrNull { it.number == number }

    /** The world's final level: finishing it completes the world. */
    val lastLevel: LevelDef get() = levels.last()
}

/** The gate to World [to], drawn on the map where its button sits (map-art pixels). */
class WorldGate(val to: Int, val nodeX: Float, val nodeY: Float)

/**
 * Every world and level in the game, read from assets/worlds.json. Adding a level is
 * a new entry there plus its asset folder; adding a new kind of gameplay is one
 * [LevelKind] registered in [LevelKinds].
 */
class Catalog(val worlds: List<WorldDef>) {
    fun world(id: String): WorldDef = worlds.first { it.id == id }

    fun level(id: String): LevelDef? = worlds.flatMap { it.levels }.firstOrNull { it.id == id }

    /** The level after [level] in its world, if any. */
    fun next(level: LevelDef): LevelDef? = level.world.levels.firstOrNull { it.number == level.number + 1 }

    companion object {
        const val FILE = "worlds.json"

        fun load(am: AssetManager): Catalog = parse(am.open(FILE).bufferedReader().use { it.readText() })

        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val worlds = root.getJSONArray("worlds").objects().map { w ->
                val world = WorldDef(w.getString("id"), w.getInt("number"), w.getString("name"))
                world.levels = w.getJSONArray("levels").objects().map { l ->
                    val node = l.getJSONArray("node")
                    LevelDef(
                        world = world,
                        number = l.getInt("number"),
                        name = l.optString("name", ""),
                        kind = l.optString("kind").ifEmpty { null },
                        dir = l.optString("dir").ifEmpty { null },
                        nodeX = node.getDouble(0).toFloat(),
                        nodeY = node.getDouble(1).toFloat(),
                        config = l.optJSONObject("config") ?: JSONObject(),
                    )
                }.sortedBy { it.number }
                w.optJSONObject("gate")?.let { g ->
                    val node = g.getJSONArray("node")
                    world.gate = WorldGate(g.getInt("to"), node.getDouble(0).toFloat(), node.getDouble(1).toFloat())
                }
                world
            }
            return Catalog(worlds)
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}
