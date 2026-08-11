package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.idb.metrics.MetricData
import io.kotest.assertions.asClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jfree.data.Range
import org.jfree.data.xy.XYBarDataset
import java.nio.file.Path
import java.time.Instant
import java.util.Date

class ClockDriftTests :
    FunSpec(
        {
            val timestamp = Instant.parse("2024-01-01T00:00:00Z")

            fun event(
                logger: String,
                message: String,
            ) = WrapperLogEvent(
                timestamp = timestamp,
                message = message,
                logger = logger,
                level = Level.WARN,
            )

            test("Single-line drift message extracts actual deviation, not allowed deviation") {
                event(
                    logger = "ClockDriftDetector",
                    message = "Clock drift, degraded performance, or pause-the-world detected.  Max allowed deviation=1000ms, actual deviation=1500ms",
                ).toClockDriftData().shouldNotBeNull().asClue { drift ->
                    drift.timestamp shouldBe timestamp
                    drift.deviationMs shouldBe 1500L
                }
            }

            test("Multi-line drift message extracts deviation from the second line") {
                event(
                    logger = "ClockDriftDetector",
                    message = "Clock drift, degraded performance, or pause-the-world detected.\nMax allowed deviation=1000ms, actual deviation=2500ms",
                ).toClockDriftData().shouldNotBeNull().deviationMs shouldBe 2500L
            }

            test("Wrapper-style drift event carries the deviation as a stacktrace line") {
                event(
                    logger = "ClockDriftDetector",
                    message = "Clock drift, degraded performance, or pause-the-world detected.",
                ).copy(
                    stacktrace = listOf("Max allowed deviation=1000ms, actual deviation=3521ms"),
                ).toClockDriftData().shouldNotBeNull().deviationMs shouldBe 3521L
            }

            test("Whitespace and case variations are tolerated") {
                event(
                    logger = "ClockDriftDetector",
                    message = "Clock drift detected. Actual Deviation = 250 ms",
                ).toClockDriftData().shouldNotBeNull().deviationMs shouldBe 250L
            }

            test("Fully qualified logger names match by suffix") {
                event(
                    logger = "com.inductiveautomation.ignition.gateway.clock.ClockDriftDetector",
                    message = "Clock drift, degraded performance, or pause-the-world detected.  Max allowed deviation=1000ms, actual deviation=3000ms",
                ).toClockDriftData().shouldNotBeNull().deviationMs shouldBe 3000L
            }

            test("Drift-like message from another logger is not a drift event") {
                event(
                    logger = "SomeOtherLogger",
                    message = "Max allowed deviation=1000ms, actual deviation=1500ms",
                ).toClockDriftData().shouldBeNull()
            }

            test("Non-drift message from the drift logger is not a drift event") {
                event(
                    logger = "ClockDriftDetector",
                    message = "Some other message without a deviation",
                ).toClockDriftData().shouldBeNull()
            }

            test("Drift events are extracted from a parsed wrapper log") {
                val events = WrapperLogParsingTests.parse(
                    """
                    INFO   | jvm 3    | 2026/07/29 14:49:39 | W [c.i.i.g.o.c.c.OpcUaSubscriptionManager] [14:49:39.502]: onWatchdogTimerElapsed() subscription-name=tag-group-status, connection-name=Ignition OPC UA Server, subscription-id=335
                    INFO   | jvm 3    | 2026/07/29 14:49:39 | W [ClockDriftDetector            ] [14:49:39.503]: Clock drift, degraded performance, or pause-the-world detected.
                    INFO   | jvm 3    | 2026/07/29 14:49:39 | Max allowed deviation=1000ms, actual deviation=3521ms
                    INFO   | jvm 3    | 2026/07/29 14:49:43 | W [ClockDriftDetector            ] [14:49:43.704]: Clock drift, degraded performance, or pause-the-world detected.
                    INFO   | jvm 3    | 2026/07/29 14:49:43 | Max allowed deviation=1000ms, actual deviation=3201ms
                    """,
                )
                events.shouldHaveSize(3)
                events.mapNotNull(LogEvent::toClockDriftData).asClue { drifts ->
                    drifts.shouldHaveSize(2)
                    drifts[0].deviationMs shouldBe 3521L
                    drifts[1].deviationMs shouldBe 3201L
                }
            }

            test("Stem width adapts to the visible axis range") {
                val chart = clockDriftChart(
                    listOf(
                        ClockDriftData(timestamp, 1500),
                        ClockDriftData(timestamp.plusSeconds(4), 3000),
                        ClockDriftData(timestamp.plusSeconds(3600), 2000),
                    ),
                )

                val base = timestamp.toEpochMilli().toDouble()
                chart.xyPlot.domainAxis.range = Range(base, base + 80_000.0)

                (chart.xyPlot.dataset as XYBarDataset).barWidth shouldBe (100.0 plusOrMinus 1.0)
            }

            test("Drag-zoom keeps the selected deviation range") {
                val chart = clockDriftChart(
                    listOf(
                        ClockDriftData(timestamp, 5000),
                        ClockDriftData(timestamp.plusSeconds(36_000), 1200),
                        ClockDriftData(timestamp.plusSeconds(36_005), 1500),
                    ),
                )
                val plot = chart.xyPlot
                val drawn = plot.rangeAxis.range

                // ChartPanel.zoom turns notifications off, then zooms the domain axis and the range axis in that order
                plot.isNotify = false
                plot.zoomDomainAxes(0.9, 1.0, null, null)
                plot.zoomRangeAxes(0.0, 2000.0 / drawn.upperBound, null, null)

                plot.rangeAxis.range.upperBound shouldBe (2000.0 plusOrMinus 1.0)
            }

            test("A lone drift opens on a one-minute window") {
                val chart = clockDriftChart(listOf(ClockDriftData(timestamp, 1500)))

                chart.xyPlot.domainAxis.range.length shouldBe (60_000.0 plusOrMinus 10.0)
            }

            test("Zoom stops at a one-second window") {
                val chart = clockDriftChart(
                    listOf(
                        ClockDriftData(timestamp, 1500),
                        ClockDriftData(timestamp.plusSeconds(3600), 1500),
                    ),
                )

                val base = timestamp.toEpochMilli().toDouble()
                chart.xyPlot.domainAxis.range = Range(base - 2.0, base + 2.0)

                chart.xyPlot.domainAxis.range.length shouldBe (1000.0 plusOrMinus 1.0)
            }

            test("Metric samples convert to percent points") {
                MetricSeries.cpu(
                    listOf(
                        MetricData(25.0, Date(0L)),
                        MetricData(50.0, Date(10_000L)),
                    ),
                ).shouldNotBeNull().toPercentSeries("CPU").asClue { line ->
                    line.itemCount shouldBe 2
                    line.getY(0) shouldBe 25.0
                    line.getY(1) shouldBe 50.0
                }
            }

            test("Gaps wider than the tolerance break the line") {
                // 10s cadence, then a 5-minute hole - far beyond the 30s tolerance floor
                val samples = List(6) { MetricData(50.0, Date(it * 10_000L)) } + MetricData(80.0, Date(350_000L))
                MetricSeries.cpu(samples).shouldNotBeNull().toPercentSeries("CPU").asClue { line ->
                    line.itemCount shouldBe samples.size + 1
                    (0 until line.itemCount).count { line.getY(it) == null } shouldBe 1
                }
            }

            test("Metric lines ride a secondary percent axis") {
                val halfDay = 43_200L
                val cpu = MetricSeries.cpu(
                    listOf(
                        MetricData(25.0, Date(timestamp.minusSeconds(halfDay).toEpochMilli())),
                        MetricData(75.0, Date(timestamp.plusSeconds(halfDay).toEpochMilli())),
                    ),
                )
                val memory = MetricSeries.heap(
                    used = listOf(
                        MetricData(1_000_000.0, Date(timestamp.toEpochMilli())),
                        MetricData(2_000_000.0, Date(timestamp.plusSeconds(60).toEpochMilli())),
                    ),
                    max = emptyList(),
                )
                val metrics = MetricsStripeState(wallClockTimestamps = false) { 0L..0L }.apply {
                    source = MetricsSource(Path.of("metrics.idb"), emptyList(), cpu = cpu, memory = memory)
                }

                val chart = clockDriftChart(listOf(ClockDriftData(timestamp, 1500)), metrics)

                chart.legend.shouldNotBeNull()
                chart.xyPlot.asClue { plot ->
                    plot.getRangeAxis(1).range shouldBe Range(0.0, 100.0)
                    plot.getDataset(1).seriesCount shouldBe 2
                    plot.getDataset(1).getSeriesKey(0) shouldBe "CPU"
                    plot.getDataset(1).getSeriesKey(1) shouldBe "Memory (% of peak)"
                    // the domain auto-ranges over the union of both datasets - the metric
                    // extent here is a full day, the drift extent a single instant
                    plot.domainAxis.range.length shouldBeGreaterThan 86_400_000.0
                }
            }

            test("Without metrics the chart is unchanged") {
                val chart = clockDriftChart(listOf(ClockDriftData(timestamp, 1500)))
                chart.legend.shouldBeNull()
                chart.xyPlot.datasetCount shouldBe 1
                chart.xyPlot.getRangeAxis(1).shouldBeNull()
            }
        },
    )
