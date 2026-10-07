package com.arnav.music.domain.intelligence

import com.arnav.music.domain.model.PlayEvent
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class StarNode(val key: String, val label: String, val weight: Float, var x: Float, var y: Float)
data class StarEdge(val a: Int, val b: Int, val strength: Float)
data class ConstellationGraph(val nodes: List<StarNode>, val edges: List<StarEdge>)

/**
 * Builds the "Taste Constellation": artists as stars, edges from co-listening within sessions
 * and shared genres. Layout is a small deterministic force simulation (no randomness).
 */
object ConstellationBuilder {
    fun build(
        events: List<PlayEvent>,
        artistNames: Map<String, String>,
        artistGenres: Map<String, Set<String>> = emptyMap(),
        maxNodes: Int = 48,
        iterations: Int = 220,
    ): ConstellationGraph {
        if (events.isEmpty()) return ConstellationGraph(emptyList(), emptyList())
        val minutes = events.groupBy { it.artistKey }.mapValues { (_, v) -> v.sumOf { it.listenedMs }.toFloat() }
        val top = minutes.entries.sortedByDescending { it.value }.take(maxNodes)
        val maxW = top.first().value.coerceAtLeast(1f)
        val index = top.withIndex().associate { it.value.key to it.index }

        // Golden-angle spiral seed → deterministic, well-spread initial layout.
        val nodes = top.mapIndexed { i, e ->
            val r = 40f * sqrt(i.toFloat() + 1)
            val a = i * 2.39996f
            StarNode(e.key, artistNames[e.key] ?: e.key, e.value / maxW, r * cos(a), r * sin(a))
        }

        val co = HashMap<Long, Float>()
        val sorted = events.sortedBy { it.startedAt }
        var sessionStart = 0
        for (i in sorted.indices) {
            if (i > 0 && sorted[i].startedAt - sorted[i - 1].startedAt > 45 * 60_000L) sessionStart = i
            val ai = index[sorted[i].artistKey] ?: continue
            for (j in (i - 4).coerceAtLeast(sessionStart) until i) {
                val bi = index[sorted[j].artistKey] ?: continue
                if (ai == bi) continue
                val k = if (ai < bi) ai.toLong() shl 32 or bi.toLong() else bi.toLong() shl 32 or ai.toLong()
                co.merge(k, 1f, Float::plus)
            }
        }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val g1 = artistGenres[nodes[i].key] ?: continue
            val g2 = artistGenres[nodes[j].key] ?: continue
            val shared = g1.intersect(g2).size
            if (shared > 0) co.merge(i.toLong() shl 32 or j.toLong(), 0.5f * shared, Float::plus)
        }
        val maxCo = co.values.maxOrNull() ?: 1f
        val edges = co.entries.map { StarEdge((it.key shr 32).toInt(), (it.key and 0xffffffffL).toInt(), it.value / maxCo) }
            .filter { it.strength > 0.08f }
            .sortedByDescending { it.strength }
            .take(nodes.size * 3)

        layout(nodes, edges, iterations)
        return ConstellationGraph(nodes, edges)
    }

    private fun layout(nodes: List<StarNode>, edges: List<StarEdge>, iterations: Int) {
        val n = nodes.size
        if (n < 2) return
        val dx = FloatArray(n); val dy = FloatArray(n)
        var temp = 30f
        repeat(iterations) {
            dx.fill(0f); dy.fill(0f)
            for (i in 0 until n) for (j in i + 1 until n) {
                val vx = nodes[i].x - nodes[j].x
                val vy = nodes[i].y - nodes[j].y
                val d2 = max(vx * vx + vy * vy, 1f)
                val f = 6000f / d2
                val d = sqrt(d2)
                dx[i] += vx / d * f; dy[i] += vy / d * f
                dx[j] -= vx / d * f; dy[j] -= vy / d * f
            }
            for (e in edges) {
                val a = nodes[e.a]; val b = nodes[e.b]
                val vx = a.x - b.x; val vy = a.y - b.y
                val d = max(sqrt(vx * vx + vy * vy), 1f)
                val f = (d - 90f) * 0.05f * e.strength
                dx[e.a] -= vx / d * f; dy[e.a] -= vy / d * f
                dx[e.b] += vx / d * f; dy[e.b] += vy / d * f
            }
            for (i in 0 until n) {
                // Gentle gravity keeps the universe compact; heavier stars sink to the centre.
                dx[i] -= nodes[i].x * 0.01f * (1 + nodes[i].weight)
                dy[i] -= nodes[i].y * 0.01f * (1 + nodes[i].weight)
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01f)
                val step = minOf(d, temp)
                nodes[i].x += dx[i] / d * step
                nodes[i].y += dy[i] / d * step
            }
            temp = max(1f, temp * 0.985f)
        }
    }
}
