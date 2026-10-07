package com.arnav.music.domain.metadata

/** Sections of the credits page, in display order. */
enum class CreditGroup(val title: String) {
    PERFORMED("Performed by"),
    WRITTEN("Written by"),
    PRODUCED("Produced by"),
    ENGINEERING("Engineering"),
    RIGHTS("Label & rights"),
    IDENTIFIERS("Identifiers"),
}

/**
 * One line on the credits page: "Producer — Max Martin". [person] entries name people or acts and
 * link to an artist page; the rest are facts (label, ℗ line, ISRC, release date).
 */
data class CreditEntry(val group: CreditGroup, val role: String, val name: String, val person: Boolean)

data class TrackCredits(val entries: List<CreditEntry>) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** Non-empty groups in display order. */
    fun grouped(): List<Pair<CreditGroup, List<CreditEntry>>> =
        CreditGroup.entries.mapNotNull { g -> entries.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }

    /** Adds entries of [other] this one doesn't already have (same group and name). */
    fun merge(other: TrackCredits): TrackCredits {
        if (other.isEmpty) return this
        if (isEmpty) return other
        val b = CreditsBuilder()
        (entries + other.entries).forEach { b.add(it) }
        return b.build()
    }

    companion object {
        val Empty = TrackCredits(emptyList())
    }
}

/**
 * Maps free-form role names from tags and descriptions ("Studio Personnel, Mixing Engineer",
 * "Music Director", "Lyrics", "Background Vocal") onto credit groups with a tidy display role.
 */
object CreditRoles {
    data class Classified(val group: CreditGroup, val role: String)

    private val ws = Regex("""\s+""")
    private val generic = setOf("associated performer", "studio personnel", "personnel", "main artist", "primary artist")

    /** Never music credits, checked before anything else ("Lyric Video: …", "Instagram: …"). */
    private val hardSkip = listOf(
        "video", "instagram", "facebook", "twitter", "tiktok", "youtube", "spotify", "apple music", "itunes", "deezer",
        "soundcloud", "subscribe", "follow", "website", "email", "e-mail", "booking", "management", "chapters", "tracklist",
        "timestamps", "http", "www",
    )

    private val skipWords = listOf(
        "listen", "stream", "download", "contact", "merch", "cast", "starring", "choreograph", "cinematograph", "dop",
        "camera", "makeup", "costume", "dancer", "movie", "film", "banner", "presented", "story", "screenplay", "dialogue",
        "colorist", "colourist", "vfx", "promotion", "digital partner", "watch", "click", "link", "hashtag", "playlist",
        "join", "support", "patreon", "donate", "director", "directed", "editor", "edit", "special thanks", "thanks",
    )

    /** Word prefixes of performing roles ("Background Vocal", "Drums", "Featured Artist"). */
    private val instruments = listOf(
        "vocal", "singer", "sung", "voice", "rap", "guitar", "bass", "drum", "piano", "keyboard", "keys", "synth", "violin",
        "viola", "cello", "string", "percussion", "sax", "trumpet", "trombone", "horn", "flute", "clarinet", "oboe", "harmonica",
        "organ", "harp", "banjo", "mandolin", "ukulele", "tabla", "sitar", "veena", "sarangi", "shehnai", "dhol", "mridangam",
        "nadaswaram", "santoor", "choir", "chorus", "orchestra", "ensemble", "band", "performer", "artist", "feat",
        "conductor", "rhythm", "accordion", "bansuri", "whistle", "beatbox", "soloist", "turntable", "dj",
    )

    /**
     * Splits [rawRole] on commas ("Composer, Lyricist") and classifies each part. With [lenient]
     * unknown roles count as performers (YouTube "Topic" pages and file tags list instruments
     * freely); otherwise unknown roles are dropped, so stray "Something: text" lines in a free-form
     * description never show up as credits.
     */
    fun classify(rawRole: String, lenient: Boolean): List<Classified> {
        val parts = rawRole.split(Regex("""\s*(?:,|;|/|&|\band\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.replace(ws, " ").trim().trim(':', '-', '–', '.').trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val meaningful = parts.filter { it.lowercase() !in generic }
        val use = meaningful.ifEmpty { parts }
        val out = ArrayList<Classified>()
        for (p in use) {
            val c = one(p, lenient) ?: continue
            val same = out.indexOfFirst { it.group == c.group }
            if (same < 0) out += c
            else if (!out[same].role.split(", ").contains(c.role)) out[same] = out[same].copy(role = out[same].role + ", " + c.role)
        }
        return out
    }

    private fun one(part: String, lenient: Boolean): Classified? {
        val r = part.lowercase().replace(ws, " ").removeSuffix(" by").removeSuffix(" by:").trim()
        if (r.isEmpty() || r.length > 40 || r.split(' ').size > 5) return null
        if (r.first().isDigit()) return null
        if (hardSkip.any { r.contains(it) }) return null
        val words = r.split(' ', '-')
        when (r) {
            "associated performer", "performer", "main artist", "primary artist", "artist" -> return Classified(CreditGroup.PERFORMED, "Performer")
            "studio personnel", "personnel" -> return Classified(CreditGroup.ENGINEERING, "Studio personnel")
        }
        // Music roles first so "Music Director" or "Lyrics" aren't caught by the film/video skip list.
        when {
            r.contains("music director") || r == "music director" -> return Classified(CreditGroup.WRITTEN, "Music director")
            r.contains("compos") || r == "music" || r == "music by" || r == "tune" || r == "tunes" -> return Classified(CreditGroup.WRITTEN, "Composer")
            r.contains("lyric") || r == "words" || r == "lyrics" || r == "lyrics writer" -> return Classified(CreditGroup.WRITTEN, "Lyricist")
            r.contains("songwriter") || r.contains("writer") || r == "written" || r == "author" || r == "penned" -> return Classified(CreditGroup.WRITTEN, "Writer")
            r.contains("arrang") || r.contains("orchestrat") -> return Classified(CreditGroup.WRITTEN, "Arranger")
            r.contains("executive producer") -> return Classified(CreditGroup.PRODUCED, "Executive producer")
            r.contains("co-producer") || r.contains("co producer") || r.contains("coproducer") -> return Classified(CreditGroup.PRODUCED, "Co-producer")
            r.contains("additional producer") -> return Classified(CreditGroup.PRODUCED, "Additional producer")
            r.contains("music producer") || r.contains("record producer") || r == "producer" || r == "producers" || r == "produced" || r == "production" || r == "music production" -> return Classified(CreditGroup.PRODUCED, "Producer")
            r.contains("remix") -> return Classified(CreditGroup.PRODUCED, "Remixer")
            r.contains("programm") -> return Classified(CreditGroup.PRODUCED, "Programming")
            r.contains("master") -> return Classified(CreditGroup.ENGINEERING, "Mastering engineer")
            r.contains("mix") -> return Classified(CreditGroup.ENGINEERING, if (r.contains("assist")) "Assistant mixing engineer" else "Mixing engineer")
            r.contains("record") && !r.contains("label") -> return Classified(CreditGroup.ENGINEERING, "Recording engineer")
            r.contains("engineer") -> return Classified(CreditGroup.ENGINEERING, if (r.contains("assist")) "Assistant engineer" else "Engineer")
            r.contains("sound design") || r.contains("audio editor") || r == "editing" || r == "vocal editor" -> return Classified(CreditGroup.ENGINEERING, sentence(part))
            r.contains("conduct") -> return Classified(CreditGroup.PERFORMED, "Conductor")
            r.contains("label") -> return Classified(CreditGroup.RIGHTS, "Label")
            r.contains("publish") -> return Classified(CreditGroup.RIGHTS, "Publisher")
            r.contains("distribut") -> return Classified(CreditGroup.RIGHTS, "Distributor")
            r.contains("copyright") || r == "©" -> return Classified(CreditGroup.RIGHTS, "Copyright")
            r == "℗" || r == "phonographic copyright" -> return Classified(CreditGroup.RIGHTS, "℗")
            // "Vocal Producer" on a Topic page is a music credit; "Film Producer" in a free-form description isn't.
            r.contains("producer") -> return if (lenient || r.contains("vocal")) Classified(CreditGroup.PRODUCED, sentence(part)) else null
        }
        if (skipWords.any { r.contains(it) }) return null
        if (words.any { w -> instruments.any { w.startsWith(it) } }) {
            val role = when {
                r == "featuring" || r == "feat" || r == "feat." || r == "featured artist" || r == "featured" -> "Featured artist"
                r == "singer" || r == "singers" || r == "sung" || r == "vocals" || r == "vocal" || r == "vocalist" || r == "lead vocal" || r == "lead vocals" -> "Vocals"
                else -> sentence(part)
            }
            return Classified(CreditGroup.PERFORMED, role)
        }
        return if (lenient) Classified(CreditGroup.PERFORMED, sentence(part)) else null
    }

    /** "BACKGROUND   Vocal" → "Background vocal". */
    fun sentence(s: String): String {
        val t = s.replace(ws, " ").trim().lowercase()
        return t.replaceFirstChar { it.uppercaseChar() }
    }
}

/** Collects credits, merging people credited with several roles in one group. */
class CreditsBuilder {
    private val entries = ArrayList<CreditEntry>()

    /** Credits [name] with a free-form [rawRole]; see [CreditRoles.classify]. */
    fun person(rawRole: String, name: String, lenient: Boolean = true) {
        val n = cleanName(name) ?: return
        for (c in CreditRoles.classify(rawRole, lenient)) {
            add(CreditEntry(c.group, c.role, n, person = c.group != CreditGroup.RIGHTS && c.group != CreditGroup.IDENTIFIERS))
        }
    }

    /** Credits [name] with an already known group and display role. */
    fun person(group: CreditGroup, role: String, name: String) {
        val n = cleanName(name) ?: return
        add(CreditEntry(group, role, n, person = true))
    }

    fun fact(group: CreditGroup, role: String, value: String?) {
        val v = value?.replace(WS, " ")?.trim()?.trimEnd(',', ';')?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return
        add(CreditEntry(group, role, v, person = false))
    }

    fun add(e: CreditEntry) {
        val i = entries.indexOfFirst { it.group == e.group && it.person == e.person && it.name.equals(e.name, ignoreCase = true) && (it.person || it.role == e.role) }
        if (i < 0) { entries += e; return }
        val old = entries[i]
        val roles = old.role.split(", ")
        val added = e.role.split(", ").filter { r -> roles.none { it.equals(r, ignoreCase = true) } }
        if (added.isNotEmpty()) entries[i] = old.copy(role = (roles + added).joinToString(", "))
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun build(): TrackCredits = TrackCredits(entries.toList())

    companion object {
        private val WS = Regex("""\s+""")
        private val placeholders = setOf("<unknown>", "unknown", "unknown artist", "n/a", "na", "none", "-", "various", "null")

        fun cleanName(raw: String): String? {
            val n = raw.replace('\u0000', ' ').replace(WS, " ").trim().trim(',', ';', '.', '·', '|').trim()
            if (n.isEmpty() || n.length > 120) return null
            if (n.lowercase() in placeholders) return null
            if (n.contains("http://") || n.contains("https://") || n.startsWith("www.") || n.startsWith("@")) return null
            return n
        }
    }
}
