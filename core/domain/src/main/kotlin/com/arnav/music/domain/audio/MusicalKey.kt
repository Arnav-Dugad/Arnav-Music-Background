package com.arnav.music.domain.audio

/**
 * Key numbering used across the app: 0–11 = C..B major, 12–23 = C..B minor, −1 = unknown.
 * Pitch classes count semitones up from C (0 = C, 9 = A).
 */
object KeyNames {
    const val UNKNOWN = -1

    private val MAJOR = arrayOf("C", "D♭", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    private val MINOR = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B")

    fun isValid(key: Int): Boolean = key in 0..23
    fun isMinor(key: Int): Boolean = key in 12..23

    /** Pitch class of the tonic (0 = C), or −1 for an unknown key. */
    fun tonic(key: Int): Int = if (isValid(key)) key % 12 else -1

    fun major(tonic: Int): Int = Math.floorMod(tonic, 12)
    fun minor(tonic: Int): Int = 12 + Math.floorMod(tonic, 12)

    /** "A minor", "E♭ major"; empty for an unknown key. */
    fun name(key: Int): String = when {
        !isValid(key) -> ""
        isMinor(key) -> "${MINOR[key - 12]} minor"
        else -> "${MAJOR[key]} major"
    }

    /** Relative major/minor (A minor ↔ C major); −1 for an unknown key. */
    fun relative(key: Int): Int = when {
        !isValid(key) -> -1
        isMinor(key) -> major(key - 12 + 3)
        else -> minor(key - 3)
    }
}

/**
 * The Camelot wheel DJs use for harmonic mixing: numbers 1–12 walk the circle of fifths,
 * "B" is major and "A" is minor (8B = C major, 8A = A minor).
 */
object Camelot {
    /** Wheel position 1..12, or 0 for an unknown key. */
    fun number(key: Int): Int {
        if (!KeyNames.isValid(key)) return 0
        // Minor keys share the number of their relative major.
        val majorTonic = if (KeyNames.isMinor(key)) (key - 12 + 3) % 12 else key
        return ((majorTonic * 7) % 12 + 7) % 12 + 1
    }

    /** "8A", "11B"; empty for an unknown key. */
    fun code(key: Int): String {
        val n = number(key)
        if (n == 0) return ""
        return "$n" + if (KeyNames.isMinor(key)) "A" else "B"
    }

    /**
     * Steps between two wheel numbers (0..6), or −1 when either key is unknown.
     * Major/minor letters are ignored here.
     */
    fun distance(a: Int, b: Int): Int {
        val na = number(a)
        val nb = number(b)
        if (na == 0 || nb == 0) return -1
        val d = Math.floorMod(na - nb, 12)
        return minOf(d, 12 - d)
    }

    /**
     * True for the classic harmonic-mixing moves: the same key, one step around the wheel with
     * the same letter (8A → 7A/9A), or the relative major/minor (8A ↔ 8B).
     */
    fun compatible(a: Int, b: Int): Boolean {
        if (!KeyNames.isValid(a) || !KeyNames.isValid(b)) return false
        if (a == b) return true
        val d = distance(a, b)
        val sameMode = KeyNames.isMinor(a) == KeyNames.isMinor(b)
        return (sameMode && d == 1) || (!sameMode && d == 0)
    }
}
