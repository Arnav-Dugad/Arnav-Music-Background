package com.arnav.music.domain.recommend

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Low-dimensional track vectors from the PPMI co-occurrence matrix: a truncated eigen-decomposition
 * (the symmetric-matrix case of SVD) by block power / subspace iteration with a fixed random seed.
 * Row i of the result is u_i·√λ, so dot products approximate PPMI between tracks while smoothing
 * over shared neighbours — two songs never played together still end up close when they keep the
 * same company.
 */
object SpectralEmbedding {

    /** Sparse symmetric matrix: for each row, column indices and values. */
    class SparseSym(val n: Int, val cols: Array<IntArray>, val vals: Array<DoubleArray>) {
        fun times(x: Array<DoubleArray>, d: Int): Array<DoubleArray> {
            val out = Array(n) { DoubleArray(d) }
            for (i in 0 until n) {
                val ci = cols[i]; val vi = vals[i]; val oi = out[i]
                for (e in ci.indices) {
                    val xj = x[ci[e]]; val v = vi[e]
                    for (k in 0 until d) oi[k] += v * xj[k]
                }
            }
            return out
        }
    }

    /** PPMI among [items] (symmetrised), as a sparse matrix aligned with [items]. */
    fun <K : Any> ppmiMatrix(co: CoOccurrence<K>, items: List<K>): SparseSym {
        val pos = HashMap<K, Int>(items.size * 2)
        items.forEachIndexed { i, k -> pos[k] = i }
        val cols = Array(items.size) { IntArray(0) }
        val vals = Array(items.size) { DoubleArray(0) }
        for ((i, a) in items.withIndex()) {
            val c = ArrayList<Int>(); val v = ArrayList<Double>()
            co.forEachNeighbour(a) { b, _ ->
                val j = pos[b]
                if (j != null) {
                    val w = 0.5 * (co.ppmi(a, b) + co.ppmi(b, a))
                    if (w > 0) { c += j; v += w }
                }
            }
            cols[i] = c.toIntArray(); vals[i] = v.toDoubleArray()
        }
        return SparseSym(items.size, cols, vals)
    }

    /**
     * Top-[dim] eigenpairs of [m] by subspace iteration. Returns one vector per row (zeros for rows
     * with no co-occurrence at all). Deterministic for a given [seed].
     */
    fun embed(m: SparseSym, dim: Int = 16, iterations: Int = 12, seed: Int = 7): Array<DoubleArray> {
        val n = m.n
        val d = minOf(dim, n)
        if (n == 0 || d == 0) return Array(n) { DoubleArray(0) }
        val rng = Random(seed)
        var q = Array(n) { DoubleArray(d) { rng.nextDouble() * 2 - 1 } }
        orthonormalize(q, d)
        repeat(iterations) {
            q = m.times(q, d)
            orthonormalize(q, d)
        }
        val mq = m.times(q, d)
        val lambda = DoubleArray(d) { k -> var s = 0.0; for (i in 0 until n) s += q[i][k] * mq[i][k]; s }
        return Array(n) { i -> DoubleArray(d) { k -> q[i][k] * sqrt(maxOf(0.0, lambda[k])) } }
    }

    /** Modified Gram–Schmidt on the columns of [q] (n × d), in place. */
    private fun orthonormalize(q: Array<DoubleArray>, d: Int) {
        val n = q.size
        for (k in 0 until d) {
            for (j in 0 until k) {
                var dot = 0.0
                for (i in 0 until n) dot += q[i][k] * q[i][j]
                for (i in 0 until n) q[i][k] -= dot * q[i][j]
            }
            var norm = 0.0
            for (i in 0 until n) norm += q[i][k] * q[i][k]
            norm = sqrt(norm)
            if (norm < 1e-12) { for (i in 0 until n) q[i][k] = 0.0 } else for (i in 0 until n) q[i][k] /= norm
        }
    }
}

object VectorMath {
    fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
    fun norm(a: DoubleArray): Double = sqrt(dot(a, a))
    fun cosine(a: DoubleArray, b: DoubleArray): Double {
        val na = norm(a); val nb = norm(b)
        return if (na < 1e-12 || nb < 1e-12) 0.0 else dot(a, b) / (na * nb)
    }
    fun normalized(a: DoubleArray): DoubleArray { val n = norm(a); return if (n < 1e-12) a.copyOf() else DoubleArray(a.size) { a[it] / n } }
}

/**
 * Spherical k-means (cosine) with k-means++ seeding from a fixed seed — same input, same clusters.
 * Points with a zero vector all land in the first cluster's nearest; callers should avoid them.
 */
object KMeans {
    fun cluster(points: List<DoubleArray>, k: Int, seed: Int = 11, iterations: Int = 25, weights: DoubleArray? = null): IntArray {
        val n = points.size
        if (n == 0) return IntArray(0)
        val kk = k.coerceIn(1, n)
        val unit = points.map { VectorMath.normalized(it) }
        val w = weights ?: DoubleArray(n) { 1.0 }
        val rng = Random(seed)
        // k-means++: first centre = heaviest point (deterministic), the rest by D² sampling.
        val centres = ArrayList<DoubleArray>()
        centres += unit[(0 until n).maxByOrNull { w[it] } ?: 0].copyOf()
        val dist = DoubleArray(n) { 1 - VectorMath.dot(unit[it], centres[0]) }
        while (centres.size < kk) {
            val total = (0 until n).sumOf { maxOf(0.0, dist[it]) * w[it] }
            val pick = if (total <= 1e-12) centres.size % n else {
                var r = rng.nextDouble() * total
                var chosen = n - 1
                for (i in 0 until n) { r -= maxOf(0.0, dist[i]) * w[i]; if (r <= 0) { chosen = i; break } }
                chosen
            }
            centres += unit[pick].copyOf()
            for (i in 0 until n) dist[i] = minOf(dist[i], 1 - VectorMath.dot(unit[i], centres.last()))
        }
        val assign = IntArray(n) { -1 }
        repeat(iterations) {
            var changed = false
            for (i in 0 until n) {
                var best = 0; var bestSim = Double.NEGATIVE_INFINITY
                for (c in centres.indices) {
                    val s = VectorMath.dot(unit[i], centres[c])
                    if (s > bestSim + 1e-12) { bestSim = s; best = c }
                }
                if (assign[i] != best) { assign[i] = best; changed = true }
            }
            if (!changed) return assign
            val dim = unit[0].size
            for (c in centres.indices) {
                val sum = DoubleArray(dim)
                var any = false
                for (i in 0 until n) if (assign[i] == c) { any = true; for (d in 0 until dim) sum[d] += unit[i][d] * w[i] }
                if (any) centres[c] = VectorMath.normalized(sum)
            }
        }
        return assign
    }
}
