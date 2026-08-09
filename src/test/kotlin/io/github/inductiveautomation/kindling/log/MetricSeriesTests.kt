package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.idb.metrics.MetricData
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import java.util.Date

class MetricSeriesTests :
    FunSpec(
        {
            fun samples(vararg pairs: Pair<Long, Double>) = pairs.map { (timestamp, value) -> MetricData(value, Date(timestamp)) }

            // ten samples, 10s apart: tolerance = max(2 * 10s, 30s floor) = 30s
            val steady = MetricSeries.cpu(samples(*(0..9).map { it * 10_000L to 0.5 }.toTypedArray()))!!

            test("The nearest sample wins") {
                val series = MetricSeries.cpu(samples(0L to 25.0, 10_000L to 75.0))!!
                series.sampleNear(4_000)!!.fraction shouldBe 0.25
                series.sampleNear(6_000)!!.fraction shouldBe 0.75
            }

            test("Ties prefer the earlier sample") {
                val series = MetricSeries.cpu(samples(0L to 0.25, 10_000L to 0.75))!!
                series.sampleNear(5_000)!!.timestampMillis shouldBe 0L
            }

            test("Edges are covered out to the gap tolerance") {
                steady.sampleNear(-30_000).shouldNotBeNull()
                steady.sampleNear(-30_001).shouldBeNull()
                steady.sampleNear(120_000).shouldNotBeNull()
                steady.sampleNear(120_001).shouldBeNull()
            }

            test("Holes wider than the tolerance are uncovered") {
                val series = MetricSeries.cpu(samples(0L to 0.5, 10_000L to 0.5, 20_000L to 0.5, 1_000_000L to 0.5))!!
                series.sampleNear(500_000).shouldBeNull()
                series.sampleNear(980_000).shouldNotBeNull()
            }

            test("The tolerance scales with the sampling interval") {
                val series = MetricSeries.cpu(samples(*(0..4).map { it * 60_000L to 0.5 }.toTypedArray()))!!
                series.gapToleranceMillis shouldBe 120_000L
            }

            test("A single sample covers only the floor") {
                val series = MetricSeries.cpu(samples(0L to 0.5))!!
                series.gapToleranceMillis shouldBe 30_000L
                series.sampleNear(30_000).shouldNotBeNull()
                series.sampleNear(30_001).shouldBeNull()
            }

            test("CPU percentages rescale to fractions") {
                val series = MetricSeries.cpu(samples(0L to 87.5, 10_000L to 12.5))!!
                series.sampleNear(0)!!.fraction shouldBe 0.875
                series.sampleNear(10_000)!!.fraction shouldBe 0.125
                series.sampleNear(0)!!.raw shouldBe 87.5
            }

            test("Idle percentages stay proportional") {
                MetricSeries.cpu(samples(0L to 1.2))!!.sampleNear(0)!!.fraction shouldBe 0.012
                MetricSeries.cpu(samples(0L to 26.22))!!.sampleNear(0)!!.fraction shouldBe 0.2622
            }

            test("CPU values above 100 clamp") {
                MetricSeries.cpu(samples(0L to 150.0))!!.sampleNear(0)!!.fraction shouldBe 1.0
            }

            test("Heap divides by the nearest max sample") {
                val series = MetricSeries.heap(
                    used = samples(0L to 800.0, 100_000L to 800.0),
                    max = samples(0L to 1_000.0, 100_000L to 1_600.0),
                )!!
                series.sampleNear(0)!!.fraction shouldBe 0.8
                series.sampleNear(100_000)!!.fraction shouldBe 0.5
                series.sampleNear(100_000)!!.raw shouldBe 800.0
            }

            test("Heap without a max series divides by the observed peak") {
                val series = MetricSeries.heap(samples(0L to 250.0, 10_000L to 1_000.0), emptyList())!!
                series.sampleNear(0)!!.fraction shouldBe 0.25
                series.sampleNear(10_000)!!.fraction shouldBe 1.0
            }

            test("Non-positive max samples are ignored") {
                val series = MetricSeries.heap(samples(0L to 250.0, 10_000L to 1_000.0), samples(0L to 0.0))!!
                series.sampleNear(0)!!.fraction shouldBe 0.25
            }

            test("Heap fractions clamp at one") {
                MetricSeries.heap(samples(0L to 1_200.0), samples(0L to 1_000.0))!!.sampleNear(0)!!.fraction shouldBe 1.0
            }

            test("Peak normalization is flagged") {
                MetricSeries.heap(samples(0L to 800.0), samples(0L to 1_000.0))!!.peakNormalized shouldBe false
                MetricSeries.heap(samples(0L to 800.0), emptyList())!!.peakNormalized shouldBe true
                MetricSeries.cpu(samples(0L to 50.0))!!.peakNormalized shouldBe false
            }

            test("Empty series yield null") {
                MetricSeries.cpu(emptyList()).shouldBeNull()
                MetricSeries.heap(emptyList(), samples(0L to 1_000.0)).shouldBeNull()
            }

            test("Overlap extends to the gap tolerance") {
                steady.overlaps(10_000, 20_000) shouldBe true
                steady.overlaps(-40_000, -30_000) shouldBe true
                steady.overlaps(-50_000, -30_001) shouldBe false
                steady.overlaps(120_000, 200_000) shouldBe true
                steady.overlaps(120_001, 200_000) shouldBe false
            }

            test("Modern metric names are preferred over legacy") {
                metricCandidates(listOf("ProcessCpuLoad", "cpu.load", "heap.used"), "cpu") shouldContainExactly listOf("cpu.load")
                metricCandidates(listOf("ProcessCpuLoad", "heap.used"), "cpu") shouldContainExactly listOf("ProcessCpuLoad")
            }

            test("Candidate matching is case-insensitive") {
                metricCandidates(listOf("MyCPUMetric"), "cpu") shouldContainExactly listOf("MyCPUMetric")
            }

            test("Multiple candidates in one generation stay ambiguous") {
                metricCandidates(listOf("cpu.load", "cpu.usage"), "cpu") shouldContainExactly listOf("cpu.load", "cpu.usage")
            }

            test("Heap used prefers used names") {
                heapUsedCandidates(listOf("heap.used", "heap.max", "heap.committed")) shouldContainExactly listOf("heap.used")
                heapUsedCandidates(listOf("HeapMemory")) shouldContainExactly listOf("HeapMemory")
                heapUsedCandidates(listOf("heap.max", "heap.committed")) shouldContainExactly listOf("heap.committed", "heap.max")
            }

            test("Non-heap metrics are never heap candidates") {
                heapUsedCandidates(listOf("ignition.performance.heap-used", "ignition.performance.non-heap")) shouldContainExactly
                    listOf("ignition.performance.heap-used")
                heapUsedCandidates(listOf("PerformanceMonitor.heapMemoryGauge", "PerformanceMonitor.nonHeapMemoryGauge")) shouldContainExactly
                    listOf("PerformanceMonitor.heapMemoryGauge")
            }

            test("Heap max pairs within the same schema generation") {
                heapMaxFor("heap.used", listOf("heap.used", "heap.max", "HeapMax")) shouldBe "heap.max"
                heapMaxFor("HeapMemory", listOf("HeapMemory", "heap.max")) shouldBe null
                heapMaxFor("heap.used", listOf("heap.used", "heap.max", "heap.maximum")) shouldBe null
                heapMaxFor("heap.used", listOf("heap.used", "heap.max", "non-heap.max")) shouldBe "heap.max"
            }

            test("Wall-clock remap follows per-sample zone rules") {
                val remapped = remapWallClock(
                    listOf(
                        MetricData(1.0, Date.from(Instant.parse("2026-08-01T12:00:00Z"))),
                        MetricData(2.0, Date.from(Instant.parse("2026-01-01T12:00:00Z"))),
                    ),
                    ZoneId.of("America/New_York"),
                    ZoneId.of("UTC"),
                )
                remapped[0].timestamp.toInstant() shouldBe Instant.parse("2026-08-01T08:00:00Z")
                remapped[1].timestamp.toInstant() shouldBe Instant.parse("2026-01-01T07:00:00Z")
            }

            test("Same-zone remap returns the samples unchanged") {
                val data = samples(0L to 1.0)
                remapWallClock(data, ZoneId.of("America/New_York"), ZoneId.of("America/New_York")) shouldBe data
            }

            test("Gateway info parses timezone and heap max") {
                // structure measured from a real 8.1 diagnostic bundle: sections nest under "system"
                val info = parseGatewayInfo(
                    """{"system":{"memory":{"heap":{"used":7530488320,"max":10712252416}},"user":{"country":"US","timezone":"America/New_York"}}}""",
                )!!
                info.zone shouldBe ZoneId.of("America/New_York")
                info.heapMax shouldBe 10712252416.0
            }

            test("Gateway info tolerates a byte order mark") {
                parseGatewayInfo("\uFEFF" + """{"system":{"user":{"timezone":"America/New_York"}}}""")!!.zone shouldBe ZoneId.of("America/New_York")
            }

            test("Gateway info degrades to nulls") {
                parseGatewayInfo("""{"system":{"user":{"timezone":"Not/AZone"}}}""")!!.zone.shouldBeNull()
                parseGatewayInfo("""{"system":{"memory":{}}}""") shouldBe GatewayInfo(null, null)
                parseGatewayInfo("""{"user":{"timezone":"America/New_York"}}""") shouldBe GatewayInfo(null, null)
                parseGatewayInfo("not json").shouldBeNull()
            }
        },
    )
