package io.github.inductiveautomation.kindling.log

import io.kotest.assertions.asClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jfree.data.Range
import org.jfree.data.xy.XYBarDataset
import java.time.Instant

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
        },
    )
