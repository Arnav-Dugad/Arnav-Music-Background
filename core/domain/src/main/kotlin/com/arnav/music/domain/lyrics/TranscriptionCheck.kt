package com.arnav.music.domain.lyrics

/** What [TranscriptionCheck] made of a model's lyrics transcription. */
sealed interface TranscriptionVerdict {
    /** Usable lyrics: LRC when [synced], otherwise plain lines (auto-timing then fills in the timing). */
    data class Accepted(val text: String, val synced: Boolean, val lineCount: Int) : TranscriptionVerdict

    /** The model says the recording has no sung vocals. */
    data object NoVocals : TranscriptionVerdict

    /** A refusal, a description of the song, a looped/hallucinated timeline or too little text. */
    data class Rejected(val reason: String) : TranscriptionVerdict
}

/**
 * Validates and cleans lyrics transcribed by a model (Gemini) before they are saved.
 *
 * Accepts LRC (`[mm:ss.xx] line`) or, failing that, plain lines. Strips code fences, a leading
 * "Here are the lyrics:" line, section labels and untimed commentary around LRC; turns
 * `[instrumental]` markers into breaks. Rejects refusals ("I can't transcribe…"), descriptions of
 * the music, output that loops one line, and timelines that run far past the song. Timestamps that
 * are all the same, mostly out of order or crammed into a fraction of the song are dropped (the
 * lyrics are then kept as plain text for auto-timing).
 */
object TranscriptionCheck {
    const val NO_VOCALS = "NO_VOCALS"
    private const val MIN_LINES = 3

    private val stampPrefix = Regex("""^((?:\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+)\s*(.*)$""")
    private val instrumentalMarker = Regex(
        """^[\[(<*_]*\s*(instrumental(?: break| bridge| outro| intro)?|music|interlude|(?:guitar |piano |sax )?solo|no vocals|humming)\s*[\])>*_]*$""",
        RegexOption.IGNORE_CASE,
    )
    private val preface = Regex("""^(here(?:'s| is| are)|sure|okay|ok|certainly|below)\b.{0,80}$""", RegexOption.IGNORE_CASE)

    /** Always a refusal or a note about the audio, never a sung line. */
    private val strongRefusals = listOf(
        "as an ai", "as a language model", "unable to transcribe", "can't transcribe", "can’t transcribe",
        "cannot transcribe", "not able to transcribe", "unable to provide", "copyrighted", "copyright",
        "i don't have access", "i do not have access", "no audible lyrics", "no discernible", "no intelligible",
        "doesn't contain any lyrics", "does not contain any lyrics", "there are no lyrics", "no sung vocals",
        "i can't help", "i cannot help", "i can't assist", "i cannot assist",
    )

    /** A refusal only together with words about the task ("I'm sorry" alone could be a lyric). */
    private val weakRefusals = listOf("i can't", "i can’t", "i cannot", "i'm unable", "i’m unable", "i am unable", "i'm not able", "i am not able", "sorry")
    private val taskWords = listOf("lyric", "transcri", "audio", "recording", "provide", "assist", "file", "this song", "the song")

    private val descriptions = Regex(
        """\b(this|the) (song|track|audio|recording|clip|video|music|piece|instrumental)\b.{0,40}\b(is|was|features|contains|appears|seems|consists|has|starts|begins)\b|\bthe (singer|vocalist|artist) (sings|is singing)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun check(raw: String, durationMs: Long?): TranscriptionVerdict {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return TranscriptionVerdict.Rejected("empty")
        val firstLines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }.take(2).toList()
        if (firstLines.firstOrNull()?.trim('*', '_', '.', ' ')?.equals(NO_VOCALS, ignoreCase = true) == true) return TranscriptionVerdict.NoVocals

        // Normalise line by line.
        val timed = ArrayList<String>()   // LRC lines (timestamp + text, or a bare timestamp for a break)
        val untimed = ArrayList<String>() // plain lines (blank = stanza break)
        var timedText = 0
        var seenContent = false
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("```")) continue
            if (line.isEmpty()) {
                untimed += ""
                timed += ""
                continue
            }
            val m = stampPrefix.find(line)
            if (m != null) {
                val stamps = m.groupValues[1].trim()
                val body = stripDecoration(m.groupValues[2])
                when {
                    body.isEmpty() || instrumentalMarker.matches(body) -> timed += stamps
                    LyricAligner.isSectionLabel(body) -> Unit
                    else -> { timed += "$stamps$body"; timedText++ }
                }
                seenContent = true
                continue
            }
            val body = stripDecoration(line)
            if (!seenContent && preface.matches(body) && body.endsWith(":")) continue
            seenContent = true
            when {
                body.isEmpty() || instrumentalMarker.matches(body) || LyricAligner.isSectionLabel(body) -> untimed += ""
                else -> untimed += body
            }
        }

        val prose = untimed.filter { it.isNotBlank() }
        val looksLikeRefusal = prose.take(4).any { l -> isRefusal(l) } || (timedText == 0 && prose.size <= 3 && prose.any { descriptions.containsMatchIn(it) })

        if (timedText >= MIN_LINES) {
            // LRC answer: untimed text around it is commentary and is dropped.
            if (looksLikeRefusal && timedText < 6) return TranscriptionVerdict.Rejected("refusal")
            return checkSynced(timed.joinToString("\n"), durationMs)
        }
        if (looksLikeRefusal) return TranscriptionVerdict.Rejected("refusal")
        if (timedText > 0) {
            // A couple of timestamps only: keep everything as plain text.
            val merged = text.split('\n').filterNot { it.trim().startsWith("```") }.map { l -> stampPrefix.find(l.trim())?.groupValues?.get(2)?.let(::stripDecoration) ?: stripDecoration(l.trim()) }
            return checkPlain(merged.filterNot { LyricAligner.isSectionLabel(it) || instrumentalMarker.matches(it) })
        }
        return checkPlain(untimed)
    }

    private fun stripDecoration(s: String): String = s.trim().removePrefix("- ").removePrefix("* ").trim().trim('*', '_').trim()

    private fun isRefusal(line: String): Boolean {
        val l = line.lowercase()
        return strongRefusals.any { it in l } || (weakRefusals.any { it in l } && taskWords.any { it in l })
    }

    private fun checkPlain(lines: List<String>): TranscriptionVerdict {
        val parsed = LrcParser.parse(lines.joinToString("\n")) as? Lyrics.Plain ?: return TranscriptionVerdict.Rejected("empty")
        val content = parsed.lines.filter { it.isNotBlank() }
        if (content.size < MIN_LINES) return TranscriptionVerdict.Rejected("too short")
        if (content.take(4).any { isRefusal(it) }) return TranscriptionVerdict.Rejected("refusal")
        val prosy = content.count { it.length > 90 || (it.endsWith(".") && descriptions.containsMatchIn(it)) }
        if (prosy * 2 >= content.size || content.take(2).any { descriptions.containsMatchIn(it) && it.length > 40 }) {
            return TranscriptionVerdict.Rejected("description")
        }
        if (isLoop(content)) return TranscriptionVerdict.Rejected("repetitive")
        return TranscriptionVerdict.Accepted(parsed.lines.joinToString("\n"), synced = false, lineCount = content.size)
    }

    private fun checkSynced(lrc: String, durationMs: Long?): TranscriptionVerdict {
        // Raw order of the timestamps as the model wrote them.
        val raw = lrc.split('\n').mapNotNull { l ->
            val m = stampPrefix.find(l) ?: return@mapNotNull null
            val first = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""").find(m.groupValues[1]) ?: return@mapNotNull null
            val ms = first.groupValues[1].toLong() * 60_000 + first.groupValues[2].toLong() * 1_000 +
                (first.groupValues[3].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L)
            Triple(ms, m.groupValues[2].trim(), l)
        }
        val withText = raw.filter { it.second.isNotEmpty() }
        if (isLoop(withText.map { it.second })) return TranscriptionVerdict.Rejected("repetitive")

        val times = withText.map { it.first }
        val descending = times.zipWithNext().count { (a, b) -> b < a }
        val spread = (times.maxOrNull() ?: 0L) - (times.minOrNull() ?: 0L)
        val distinct = times.toSet().size
        val unreliable = distinct <= 1 ||
            descending * 5 > times.size ||
            (durationMs != null && durationMs > 60_000 && withText.size >= 8 && spread < durationMs / 10)
        if (unreliable) return checkPlain(lrc.split('\n').map { l -> stampPrefix.find(l)?.groupValues?.get(2)?.trim() ?: "" })

        var kept = raw
        if (durationMs != null && durationMs > 0) {
            val limit = durationMs + 5_000L
            val beyond = withText.count { it.first > limit }
            if (beyond * 10 > withText.size * 3) return TranscriptionVerdict.Rejected("timeline past the end of the song")
            kept = raw.filter { it.first <= limit }
        }
        // Keep blank lines between sections as LRC readers ignore them.
        val out = StringBuilder()
        var keptIdx = 0
        for (l in lrc.split('\n')) {
            if (l.isBlank()) { if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n'); continue }
            if (keptIdx < kept.size && kept[keptIdx].third == l) {
                out.append(l).append('\n')
                keptIdx++
            }
        }
        val result = out.toString().trim()
        val parsed = LrcParser.parse(result, durationMs) as? Lyrics.Synced ?: return TranscriptionVerdict.Rejected("unreadable")
        val count = parsed.lines.count { !it.isInstrumental }
        if (count < MIN_LINES) return TranscriptionVerdict.Rejected("too short")
        return TranscriptionVerdict.Accepted(result, synced = true, lineCount = count)
    }

    /** One line (or two) repeated over and over: a typical failure loop of generative models. */
    private fun isLoop(lines: List<String>): Boolean {
        if (lines.size < 12) return false
        val keys = lines.map { it.lowercase().filter { c -> c.isLetterOrDigit() } }
        val distinct = keys.toSet().size
        return distinct * 100 < lines.size * 12
    }
}
