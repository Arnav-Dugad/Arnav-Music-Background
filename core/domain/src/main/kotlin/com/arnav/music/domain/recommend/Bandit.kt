package com.arnav.music.domain.recommend

import com.arnav.music.domain.model.PlayEvent
import com.arnav.music.domain.model.TrackId
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Beta posterior of one arm's "the listener kept it" rate. */
data class ArmStats(val alpha: Double = PRIOR, val beta: Double = PRIOR) {
    val mean: Double get() = alpha / (alpha + beta)
    /** Evidence beyond the prior. */
    val trials: Double get() = alpha + beta - 2 * PRIOR

    companion object { const val PRIOR = 2.0 }
}

/**
 * Thompson sampling over recommendation sources. Every source starts at the same Beta(2, 2) prior
 * (so the hand-tuned blend is the starting point); completions of a recommended song move its
 * source's posterior up, early skips move it down. A gentle discount pulls old evidence back
 * towards the prior so the blend keeps adapting as taste drifts.
 */
class SourceBandit(arms: Map<RecSource, ArmStats> = emptyMap()) {
    val arms: Map<RecSource, ArmStats> = RecSource.entries.associateWith { arms[it] ?: ArmStats() }

    fun stats(source: RecSource): ArmStats = arms.getValue(source)

    /** Adds one outcome; [reward] in 0..1 (1 = finished it, 0 = skipped straight away). */
    fun update(source: RecSource, reward: Double, discount: Double = DISCOUNT): SourceBandit {
        val r = reward.coerceIn(0.0, 1.0)
        val s = stats(source)
        val a = ArmStats.PRIOR + discount * (s.alpha - ArmStats.PRIOR) + r
        val b = ArmStats.PRIOR + discount * (s.beta - ArmStats.PRIOR) + (1 - r)
        return SourceBandit(arms + (source to ArmStats(a, b)))
    }

    fun updateAll(outcomes: List<Pair<RecSource, Double>>): SourceBandit = outcomes.fold(this) { b, (s, r) -> b.update(s, r) }

    /**
     * Weight multipliers for the scorer, 1.0 = the prior mean. With [rng], each arm's rate is a
     * Thompson sample (exploration); without, the posterior mean (exploitation only).
     */
    fun multipliers(rng: Random? = null): Map<RecSource, Double> = arms.mapValues { (_, s) ->
        val theta = if (rng == null) s.mean else Beta.sample(s.alpha, s.beta, rng)
        (theta / 0.5).coerceIn(MIN_MULT, MAX_MULT)
    }

    /** Compact JSON: {"v":1,"arms":{"SESSION":[2.0,2.0],…}}. */
    fun encode(): String = arms.entries.joinToString(",", prefix = "{\"v\":1,\"arms\":{", postfix = "}}") { (k, s) ->
        "\"${k.name}\":[${s.alpha},${s.beta}]"
    }

    companion object {
        const val DISCOUNT = 0.995
        const val MIN_MULT = 0.4
        const val MAX_MULT = 1.8
        private val armRe = Regex(""""([A-Z_]+)"\s*:\s*\[\s*([0-9.eE+-]+)\s*,\s*([0-9.eE+-]+)\s*]""")

        /** Reads [encode]'s format; anything unreadable falls back to the prior. */
        fun decode(raw: String?): SourceBandit {
            if (raw.isNullOrBlank()) return SourceBandit()
            val arms = HashMap<RecSource, ArmStats>()
            for (m in armRe.findAll(raw)) {
                val src = RecSource.entries.firstOrNull { it.name == m.groupValues[1] } ?: continue
                val a = m.groupValues[2].toDoubleOrNull() ?: continue
                val b = m.groupValues[3].toDoubleOrNull() ?: continue
                if (a.isFinite() && b.isFinite() && a > 0 && b > 0) arms[src] = ArmStats(a, b)
            }
            return SourceBandit(arms)
        }
    }
}

/** Beta sampling via two Gamma draws (Marsaglia–Tsang), deterministic for a seeded [Random]. */
object Beta {
    fun sample(a: Double, b: Double, rng: Random): Double {
        val x = gamma(a, rng)
        val y = gamma(b, rng)
        return if (x + y <= 0) 0.5 else x / (x + y)
    }

    fun gamma(shape: Double, rng: Random): Double {
        if (shape < 1) return gamma(shape + 1, rng) * rng.nextDouble().coerceAtLeast(1e-12).pow(1 / shape)
        val d = shape - 1.0 / 3
        val c = 1 / sqrt(9 * d)
        while (true) {
            var x: Double
            var v: Double
            do { x = normal(rng); v = 1 + c * x } while (v <= 0)
            v = v * v * v
            val u = rng.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (ln(u.coerceAtLeast(1e-300)) < 0.5 * x * x + d * (1 - v + ln(v))) return d * v
        }
    }

    private fun normal(rng: Random): Double {
        // Box–Muller.
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2 * ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}

/** A recommendation that was shown or queued, waiting to see whether it gets played. */
data class Impression(val trackId: TrackId, val source: RecSource, val servedAt: Long, val surface: String) {
    fun encode(): String = "${trackId.value}\t${source.name}\t$servedAt\t${surface.replace('\t', ' ')}"

    companion object {
        fun decode(line: String): Impression? {
            val p = line.split('\t')
            if (p.size < 4) return null
            val src = RecSource.entries.firstOrNull { it.name == p[1] } ?: return null
            val at = p[2].toLongOrNull() ?: return null
            return Impression(TrackId(p[0]), src, at, p[3])
        }
    }
}

/**
 * Turns plays of recommended songs into bandit rewards. An impression is settled by the first play
 * of that song within [horizonMs] after it was served: finished → 1, early skip → 0, late skip → 0.35,
 * otherwise by how much was heard. Impressions never played expire without a reward (a shelf item
 * the listener scrolled past says little), except on surfaces in [penaliseUnplayed] (endless radio:
 * queued songs that never got a listen because the listener stopped).
 */
object RewardModel {
    const val HORIZON_MS = 3 * 86_400_000L

    data class Settlement(val outcomes: List<Pair<RecSource, Double>>, val pending: List<Impression>)

    fun reward(e: PlayEvent): Double = when {
        Engagement.isEarlySkip(e) -> 0.0
        e.skipped -> 0.35
        e.completed -> 1.0
        else -> (0.3 + 0.6 * e.completionRatio).coerceIn(0.0, 1.0)
    }

    fun settle(
        impressions: List<Impression>,
        events: List<PlayEvent>,
        now: Long,
        horizonMs: Long = HORIZON_MS,
        penaliseUnplayed: Set<String> = emptySet(),
    ): Settlement {
        if (impressions.isEmpty()) return Settlement(emptyList(), emptyList())
        val byTrack = events.groupBy { it.trackId }.mapValues { (_, v) -> v.sortedBy { it.startedAt } }
        val outcomes = ArrayList<Pair<RecSource, Double>>()
        val pending = ArrayList<Impression>()
        for (imp in impressions) {
            val play = byTrack[imp.trackId]?.firstOrNull { it.startedAt >= imp.servedAt - 60_000 && it.startedAt <= imp.servedAt + horizonMs }
            when {
                play != null -> outcomes += imp.source to reward(play)
                now - imp.servedAt > horizonMs -> if (imp.surface in penaliseUnplayed) outcomes += imp.source to 0.2
                else -> pending += imp
            }
        }
        return Settlement(outcomes, pending)
    }
}
