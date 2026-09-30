package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.idb.metrics.MetricData
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

data class MetricSample(
    val timestampMillis: Long,
    val fraction: Double,
    val raw: Double,
)

/**
 * A gateway metric series, time-ordered and normalized to 0..1 usage fractions. Log rows farther
 * than [gapToleranceMillis] from every sample have no usage to report - the gateway was down, or
 * the metrics file only partially covers the log.
 */
class MetricSeries private constructor(
    private val timestamps: LongArray,
    private val fractions: DoubleArray,
    private val rawValues: DoubleArray,
    val peakNormalized: Boolean = false,
) {
    // twice the median sampling interval, so a real hole in the series reads as uncovered
    val gapToleranceMillis: Long = if (timestamps.size < 2) {
        GAP_TOLERANCE_FLOOR_MS
    } else {
        val intervals = LongArray(timestamps.size - 1) { timestamps[it + 1] - timestamps[it] }.apply { sort() }
        (GAP_TOLERANCE_FACTOR * intervals[intervals.size / 2]).coerceAtLeast(GAP_TOLERANCE_FLOOR_MS)
    }

    val firstTimestamp: Long = timestamps.first()
    val lastTimestamp: Long = timestamps.last()

    fun sampleNear(timestampMillis: Long): MetricSample? {
        val index = nearestIndex(timestamps, timestampMillis)
        return if (abs(timestamps[index] - timestampMillis) <= gapToleranceMillis) {
            MetricSample(timestamps[index], fractions[index], rawValues[index])
        } else {
            null
        }
    }

    fun forEachSample(action: (MetricSample) -> Unit) {
        for (index in timestamps.indices) {
            action(MetricSample(timestamps[index], fractions[index], rawValues[index]))
        }
    }

    fun overlaps(startMillis: Long, endMillis: Long): Boolean = startMillis <= lastTimestamp + gapToleranceMillis && endMillis >= firstTimestamp - gapToleranceMillis

    companion object {
        private const val GAP_TOLERANCE_FACTOR = 2
        private const val GAP_TOLERANCE_FLOOR_MS = 30_000L

        /** CPU usage, stored as 0-100 percentages. */
        fun cpu(samples: List<MetricData>): MetricSeries? {
            val sorted = samples.sortedBy(MetricData::timestamp)
            if (sorted.isEmpty()) return null
            return MetricSeries(
                timestamps = LongArray(sorted.size) { sorted[it].timestamp.time },
                fractions = DoubleArray(sorted.size) { (sorted[it].value / 100.0).coerceIn(0.0, 1.0) },
                rawValues = DoubleArray(sorted.size) { sorted[it].value },
            )
        }

        fun heap(used: List<MetricData>, max: List<MetricData>): MetricSeries? {
            val sorted = used.sortedBy(MetricData::timestamp)
            if (sorted.isEmpty()) return null
            val limits = max.filter { it.value > 0.0 }.sortedBy(MetricData::timestamp)
            val limitTimes = LongArray(limits.size) { limits[it].timestamp.time }
            val peak = sorted.maxOf(MetricData::value).takeIf { it > 0.0 } ?: 1.0
            return MetricSeries(
                timestamps = LongArray(sorted.size) { sorted[it].timestamp.time },
                fractions = DoubleArray(sorted.size) { i ->
                    val limit = if (limits.isEmpty()) peak else limits[nearestIndex(limitTimes, sorted[i].timestamp.time)].value
                    (sorted[i].value / limit).coerceIn(0.0, 1.0)
                },
                rawValues = DoubleArray(sorted.size) { sorted[it].value },
                peakNormalized = limits.isEmpty(),
            )
        }

        private fun nearestIndex(timestamps: LongArray, timestampMillis: Long): Int {
            val search = timestamps.binarySearch(timestampMillis)
            if (search >= 0) return search
            val insertion = -(search + 1)
            return when {
                insertion == 0 -> 0
                insertion == timestamps.size -> timestamps.size - 1
                timestampMillis - timestamps[insertion - 1] <= timestamps[insertion] - timestampMillis -> insertion - 1
                else -> insertion
            }
        }
    }
}

// re-express absolute timestamps as the same wall-clock instants read in another zone
internal fun remapWallClock(samples: List<MetricData>, from: ZoneId, to: ZoneId): List<MetricData> {
    if (from == to) return samples
    return samples.map { sample ->
        val wallClock = sample.timestamp.toInstant().atZone(from).toLocalDateTime()
        MetricData(sample.value, Date.from(wallClock.atZone(to).toInstant()))
    }
}

private val String.isLegacyMetric: Boolean
    get() = firstOrNull()?.isUpperCase() == true

private val String.isNonHeap: Boolean
    get() = replace("-", "").contains("nonheap", ignoreCase = true)

internal fun metricCandidates(names: List<String>, token: String): List<String> {
    val (legacy, modern) = names.filter { it.contains(token, ignoreCase = true) }.partition { it.isLegacyMetric }
    return modern.ifEmpty { legacy }.sorted()
}

internal fun heapUsedCandidates(names: List<String>): List<String> {
    val heap = metricCandidates(names, "heap").filterNot(String::isNonHeap)
    return heap.filter { it.contains("used", ignoreCase = true) }.ifEmpty { heap }
}

internal fun heapMaxFor(usedName: String, names: List<String>): String? = names
    .filter { it.contains("heap", ignoreCase = true) && !it.isNonHeap && it.isLegacyMetric == usedName.isLegacyMetric }
    .singleOrNull { it.contains("max", ignoreCase = true) }
