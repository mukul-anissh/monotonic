package com.monotonic.metronome.audio

/**
 * Local-only per-beat accent utilities and 32-bit bitmask packing for lock-free
 * C++ Oboe audio engine consumption.
 *
 * Accent levels:
 * - `2` = Accent (high sharp click)
 * - `1` = Normal (medium click)
 * - `0` = Mute (silent beat and silent subdivisions; visual indicator still pulses dimly)
 */
object AccentPattern {
    const val ACCENT_MUTE = 0
    const val ACCENT_NORMAL = 1
    const val ACCENT_HIGH = 2
    const val MAX_BEATS = 16

    fun defaultPattern(beatsPerBar: Int): List<Int> {
        val count = beatsPerBar.coerceIn(1, MAX_BEATS)
        return List(count) { index ->
            if (index == 0) ACCENT_HIGH else ACCENT_NORMAL
        }
    }

    /**
     * Adapts an existing accent list when [newBeatsPerBar] changes, preserving
     * overlapping beat states and defaulting newly added beats to [ACCENT_NORMAL].
     */
    fun adaptPattern(existing: List<Int>, newBeatsPerBar: Int): List<Int> {
        val count = newBeatsPerBar.coerceIn(1, MAX_BEATS)
        if (existing.isEmpty()) {
            return defaultPattern(count)
        }
        return List(count) { index ->
            if (index < existing.size) {
                existing[index].coerceIn(ACCENT_MUTE, ACCENT_HIGH)
            } else {
                ACCENT_NORMAL
            }
        }
    }

    /**
     * Cycles a beat's accent state: Normal (1) -> Accent (2) -> Mute (0) -> Normal (1).
     */
    fun cycleLevel(current: Int): Int {
        return when (current) {
            ACCENT_NORMAL -> ACCENT_HIGH
            ACCENT_HIGH -> ACCENT_MUTE
            else -> ACCENT_NORMAL
        }
    }

    /**
     * Packs up to 16 beat accent levels (2 bits per beat) into a 32-bit bitmask.
     */
    fun packToBitmask(levels: List<Int>): Int {
        var mask = 0
        val count = minOf(levels.size, MAX_BEATS)
        for (i in 0 until count) {
            val level = levels[i].coerceIn(ACCENT_MUTE, ACCENT_HIGH)
            mask = mask or (level shl (i * 2))
        }
        return mask
    }

    fun unpackFromBitmask(bitmask: Int, beatsPerBar: Int): List<Int> {
        val count = beatsPerBar.coerceIn(1, MAX_BEATS)
        return List(count) { i ->
            ((bitmask ushr (i * 2)) and 0x3).coerceIn(ACCENT_MUTE, ACCENT_HIGH)
        }
    }

    fun serialize(levels: List<Int>): String {
        return levels.take(MAX_BEATS).joinToString(separator = ",") {
            it.coerceIn(ACCENT_MUTE, ACCENT_HIGH).toString()
        }
    }

    fun deserialize(raw: String?, beatsPerBar: Int): List<Int> {
        val count = beatsPerBar.coerceIn(1, MAX_BEATS)
        if (raw.isNullOrBlank()) return defaultPattern(count)
        val parsed = raw.split(",").mapNotNull { token ->
            token.trim().toIntOrNull()?.coerceIn(ACCENT_MUTE, ACCENT_HIGH)
        }
        return if (parsed.isEmpty()) defaultPattern(count) else adaptPattern(parsed, count)
    }
}
