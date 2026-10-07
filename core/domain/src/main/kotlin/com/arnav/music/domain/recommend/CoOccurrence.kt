package com.arnav.music.domain.recommend

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One neighbour of an item, with the parts of its score kept for explanations. */
data class Neighbour<K>(
    val key: K,
    val score: Double,
    val cosine: Double,
    val ppmi: Double,
    /** P(next = key | current = query) from session order. */
    val markov: Double,
    /** Weighted number of sessions the two shared. */
    val support: Double,
)

/**
 * Item–item co-occurrence learned from listening sessions, for tracks or artists.
 *
 * Within a session two items co-occur with weight 1/distance when at most [window] positions apart
 * (the strongest single weight per pair per session is kept, so looping one album doesn't swamp
 * everything). Consecutive items also count as a directed transition (A → B) for the Markov part.
 *
 * Normalisations:
 * - cosine: c(a,b) / √(sessions(a)·sessions(b)), in 0..1;
 * - PPMI: max(0, ln(p(a,b) / (p(a)·p_α(b)))) with context smoothing α (0.75 = word2vec's choice,
 *   which damps the bias towards rare items);
 * - Markov: transitions(a→b) / transitions(a→·).
 *
 * Sessions are added incrementally with [addSession]; nothing needs recomputing afterwards.
 * Keys are interned to ints and pairs live in primitive open-addressing maps, so 20k plays index
 * in a few milliseconds.
 */
class CoOccurrence<K : Any>(val window: Int = 4, val alpha: Double = 0.75) {
    private val ids = HashMap<K, Int>()
    private val keys = ArrayList<K>()
    private var occ = DoubleArray(64)
    private var marginal = DoubleArray(64)
    private var transOut = DoubleArray(64)
    private var adj = arrayOfNulls<IntList>(64)
    private val pairs = LongDoubleMap()
    private val trans = LongDoubleMap()
    private var total = 0.0
    private var smoothed: DoubleArray? = null
    private var smoothedNorm = 0.0
    private val scratch = LongDoubleMap()

    var sessionCount = 0
        private set

    val items: Collection<K> get() = keys

    private fun intern(k: K): Int = ids.getOrPut(k) {
        val i = keys.size
        keys += k
        if (i >= occ.size) {
            val n = occ.size * 2
            occ = occ.copyOf(n); marginal = marginal.copyOf(n); transOut = transOut.copyOf(n); adj = adj.copyOf(n)
        }
        i
    }

    /** Adds one session: the items the listener kept (early skips already removed), in play order. */
    fun addSession(items: List<K>) {
        if (items.isEmpty()) return
        sessionCount++
        val seq = IntArray(items.size) { intern(items[it]) }
        val seen = HashSet<Int>(seq.size * 2)
        for (i in seq) if (seen.add(i)) occ[i] += 1.0
        if (seq.size < 2) return
        scratch.clear()
        for (i in seq.indices) {
            val a = seq[i]
            val end = minOf(seq.lastIndex, i + window)
            for (j in i + 1..end) {
                val b = seq[j]
                if (a == b) continue
                val w = 1.0 / (j - i)
                val key = pairKey(minOf(a, b), maxOf(a, b))
                if (w > scratch.get(key)) scratch.put(key, w)
            }
            if (i < seq.lastIndex && a != seq[i + 1]) {
                trans.add(pairKey(a, seq[i + 1]), 1.0)
                transOut[a] += 1.0
            }
        }
        scratch.forEach { key, w ->
            val a = (key ushr 32).toInt()
            val b = key.toInt()
            if (pairs.get(pairKey(a, b)) == 0.0) {
                (adj[a] ?: IntList().also { adj[a] = it }).add(b)
                (adj[b] ?: IntList().also { adj[b] = it }).add(a)
            }
            pairs.add(pairKey(a, b), w)
            pairs.add(pairKey(b, a), w)
            marginal[a] += w; marginal[b] += w
            total += 2 * w
        }
        smoothed = null
    }

    fun sessionsWith(a: K): Double = ids[a]?.let { occ[it] } ?: 0.0
    fun pair(a: K, b: K): Double = idPair(a, b)?.let { pairs.get(it) } ?: 0.0
    fun transitions(a: K, b: K): Double = idPair(a, b)?.let { trans.get(it) } ?: 0.0

    /** Calls [f] for every item that shared a session window with [a]. */
    fun forEachNeighbour(a: K, f: (K, Double) -> Unit) {
        val i = ids[a] ?: return
        val row = adj[i] ?: return
        for (x in 0 until row.size) { val j = row[x]; f(keys[j], pairs.get(pairKey(i, j))) }
    }

    /** Calls [f] once per co-occurring pair (a, b) with their cosine. */
    fun forEachPair(f: (K, K, Double) -> Unit) {
        for (i in keys.indices) {
            val row = adj[i] ?: continue
            for (x in 0 until row.size) {
                val j = row[x]
                if (j > i) f(keys[i], keys[j], cosineIdx(i, j))
            }
        }
    }

    fun cosine(a: K, b: K): Double {
        val i = ids[a] ?: return 0.0
        val j = ids[b] ?: return 0.0
        return cosineIdx(i, j)
    }

    /** Pointwise mutual information (can be negative); 0 when the two never co-occurred. */
    fun pmi(a: K, b: K): Double {
        val i = ids[a] ?: return 0.0
        val j = ids[b] ?: return 0.0
        return pmiIdx(i, j)
    }

    fun ppmi(a: K, b: K): Double = max(0.0, pmi(a, b))

    fun markov(a: K, b: K): Double {
        val i = ids[a] ?: return 0.0
        val j = ids[b] ?: return 0.0
        return markovIdx(i, j)
    }

    /** The [k] strongest neighbours of [a], strongest first (ties broken by key order for determinism). */
    fun neighbours(a: K, k: Int = 20, exclude: (K) -> Boolean = { false }): List<Neighbour<K>> {
        val i = ids[a] ?: return emptyList()
        val row = adj[i] ?: return emptyList()
        val out = ArrayList<Neighbour<K>>(row.size)
        for (x in 0 until row.size) {
            val j = row[x]
            val b = keys[j]
            if (exclude(b)) continue
            val c = pairs.get(pairKey(i, j))
            val t = trans.get(pairKey(i, j))
            val cos = cosineIdx(i, j)
            val p = max(0.0, pmiIdx(i, j))
            val m = markovIdx(i, j)
            // Squash PPMI into 0..1 and shrink scores that rest on a single shared session.
            val raw = 0.45 * cos + 0.35 * (p / (p + 2.0)) + 0.20 * m
            val confidence = (c + t) / (c + t + 1.0)
            out += Neighbour(b, raw * confidence, cos, p, m, c)
        }
        out.sortWith(compareByDescending<Neighbour<K>> { it.score }.thenBy { it.key.toString() })
        return if (out.size > k) ArrayList(out.subList(0, k)) else out
    }

    private fun idPair(a: K, b: K): Long? {
        val i = ids[a] ?: return null
        val j = ids[b] ?: return null
        return pairKey(i, j)
    }

    private fun cosineIdx(i: Int, j: Int): Double {
        val c = pairs.get(pairKey(i, j))
        if (c <= 0) return 0.0
        return (c / sqrt(occ[i] * occ[j])).coerceAtMost(1.0)
    }

    private fun pmiIdx(i: Int, j: Int): Double {
        val c = pairs.get(pairKey(i, j))
        if (c <= 0 || total <= 0) return 0.0
        val pab = c / total
        val pa = marginal[i] / total
        val pb = if (alpha == 1.0) marginal[j] / total else smoothed()[j] / smoothedNorm
        return ln(pab / (pa * pb))
    }

    private fun markovIdx(i: Int, j: Int): Double {
        val out = transOut[i]
        return if (out <= 0) 0.0 else trans.get(pairKey(i, j)) / out
    }

    private fun smoothed(): DoubleArray {
        smoothed?.let { return it }
        val s = DoubleArray(keys.size) { marginal[it].pow(alpha) }
        smoothedNorm = s.sum()
        smoothed = s
        return s
    }

    private fun pairKey(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
}

/** Growable int list. */
internal class IntList {
    private var data = IntArray(4)
    var size = 0
        private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int): Int = data[i]
}

/** Open-addressing Long → Double map (linear probing); missing keys read as 0. */
internal class LongDoubleMap(initial: Int = 64) {
    private var keys = LongArray(cap(initial))
    private var vals = DoubleArray(keys.size)
    private var used = BooleanArray(keys.size)
    var size = 0
        private set

    fun get(k: Long): Double {
        var i = slot(k, keys.size)
        while (used[i]) { if (keys[i] == k) return vals[i]; i = (i + 1) and (keys.size - 1) }
        return 0.0
    }

    fun put(k: Long, v: Double) { val i = find(k); vals[i] = v }
    fun add(k: Long, v: Double) { val i = find(k); vals[i] += v }

    fun clear() {
        if (size == 0) return
        java.util.Arrays.fill(used, false); size = 0
    }

    inline fun forEach(f: (Long, Double) -> Unit) { for (i in keysArray().indices) if (usedArray()[i]) f(keysArray()[i], valsArray()[i]) }

    @PublishedApi internal fun keysArray() = keys
    @PublishedApi internal fun valsArray() = vals
    @PublishedApi internal fun usedArray() = used

    private fun find(k: Long): Int {
        if ((size + 1) * 2 > keys.size) grow()
        var i = slot(k, keys.size)
        while (used[i]) { if (keys[i] == k) return i; i = (i + 1) and (keys.size - 1) }
        used[i] = true; keys[i] = k; vals[i] = 0.0; size++
        return i
    }

    private fun grow() {
        val ok = keys; val ov = vals; val ou = used
        keys = LongArray(ok.size * 2); vals = DoubleArray(ok.size * 2); used = BooleanArray(ok.size * 2); size = 0
        for (i in ok.indices) if (ou[i]) { val j = find(ok[i]); vals[j] = ov[i] }
    }

    private fun slot(k: Long, n: Int): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return (h.toInt()) and (n - 1)
    }

    private companion object {
        fun cap(n: Int): Int { var c = 16; while (c < n * 2) c = c shl 1; return c }
    }
}
