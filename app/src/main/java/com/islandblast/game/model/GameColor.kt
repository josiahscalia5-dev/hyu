package com.islandblast.game.model

/**
 * The five block/ball colours, in the order of the colour-shift cycle.
 *
 * When a group is cleared, every surviving block touching it steps one place along
 * this cycle (yellow wraps back to cyan). The Goal panel shows the cycle left to right.
 */
enum class GameColor(val key: String, val swatch: Int, val glow: Int) {
    CYAN("cyan", 0xFF10A8FE.toInt(), 0xFF6078FF.toInt()),
    VIOLET("violet", 0xFFA044FD.toInt(), 0xFFB05CFF.toInt()),
    MAGENTA("magenta", 0xFFF81EF6.toInt(), 0xFFFF54EE.toInt()),
    RED("red", 0xFFF42238.toInt(), 0xFFFF4854.toInt()),
    YELLOW("yellow", 0xFFFDD21A.toInt(), 0xFFFFCC30.toInt());

    fun next(): GameColor = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String): GameColor =
            entries.firstOrNull { it.key == key } ?: error("Unknown colour '$key'")
    }
}
