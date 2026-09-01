package com.loadingbyte.cinecred.common

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder


class RenderProfiling(private val label: String) {

    private val nanosByKey = ConcurrentHashMap<String, LongAdder>()
    private val countsByKey = ConcurrentHashMap<String, LongAdder>()

    inline fun <T> measure(key: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            addNanos(key, System.nanoTime() - start)
        }
    }

    fun addNanos(key: String, nanos: Long) {
        nanosByKey.computeIfAbsent(key) { LongAdder() }.add(nanos)
        countsByKey.computeIfAbsent(key) { LongAdder() }.increment()
    }

    fun increment(key: String, amount: Int = 1) {
        countsByKey.computeIfAbsent(key) { LongAdder() }.add(amount.toLong())
    }

    fun summary(topN: Int = 12): String {
        val rows = nanosByKey.entries
            .map { entry ->
                val nanos = entry.value.sum()
                val count = countsByKey[entry.key]?.sum() ?: 0L
                Triple(entry.key, nanos, count)
            }
            .sortedByDescending { it.second }

        return buildString {
            append(label)
            append(": ")
            append(
                rows.take(topN).joinToString(", ") { (key, nanos, count) ->
                    String.format(Locale.ROOT, "%s=%.1fms/%d", key, nanos / 1_000_000.0, count)
                }
            )
            val countOnlyRows = countsByKey.entries
                .filter { !nanosByKey.containsKey(it.key) }
                .sortedByDescending { it.value.sum() }
                .take(topN)
            if (countOnlyRows.isNotEmpty()) {
                append(" | counts: ")
                append(
                    countOnlyRows.joinToString(", ") { entry ->
                        "${entry.key}=${entry.value.sum()}"
                    }
                )
            }
        }
    }

}


inline fun <T> RenderProfiling?.measure(key: String, block: () -> T): T =
    if (this == null) block() else measure(key, block)

fun RenderProfiling?.increment(key: String, amount: Int = 1) {
    this?.increment(key, amount)
}
