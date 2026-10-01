package com.islandblast.game.levels

import android.content.Context

/** Best result per level, kept on the device. */
class Progress(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stars(level: LevelDef): Int = prefs.getInt("${level.id}.stars", 0)
    fun bestScore(level: LevelDef): Int = prefs.getInt("${level.id}.score", 0)
    fun completed(level: LevelDef): Boolean = prefs.getBoolean("${level.id}.done", false)

    /** Records a finished level; keeps the best stars and score. Returns true if it is a new best. */
    fun record(level: LevelDef, won: Boolean, stars: Int, score: Int): Boolean {
        if (!won) return false
        val newBest = score > bestScore(level) || stars > stars(level)
        prefs.edit()
            .putBoolean("${level.id}.done", true)
            .putInt("${level.id}.stars", maxOf(stars, stars(level)))
            .putInt("${level.id}.score", maxOf(score, bestScore(level)))
            .apply()
        return newBest
    }

    fun totalStars(world: WorldDef): Int = world.levels.sumOf { stars(it) }

    /** The level the map points the player to: the first playable level not yet completed. */
    fun current(world: WorldDef): LevelDef? =
        world.levels.firstOrNull { it.playable && !completed(it) } ?: world.levels.lastOrNull { it.playable }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val PREFS = "progress"
    }
}
