package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.core.Kindling.Preferences.UI.Theme
import io.github.inductiveautomation.kindling.core.Theme.Companion.theme
import io.github.inductiveautomation.kindling.core.Timezone
import org.jfree.chart.ChartFactory
import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.renderer.xy.StandardXYBarPainter
import org.jfree.chart.renderer.xy.XYBarRenderer
import org.jfree.chart.ui.RectangleInsets
import org.jfree.data.xy.XYBarDataset
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.time.Instant

data class ClockDriftData(
    val timestamp: Instant,
    val deviationMs: Long,
)

private val driftPattern = """actual\s+deviation\s*=\s*(\d+)\s*ms""".toRegex(RegexOption.IGNORE_CASE)

fun LogEvent.toClockDriftData(): ClockDriftData? {
    if (!logger.endsWith("ClockDriftDetector")) return null
    // in wrapper logs, the deviation arrives on a continuation line, which the parser
    // attaches as a stacktrace line; in system logs it is part of the message itself
    val deviation = (sequenceOf(message) + stacktrace.asSequence())
        .firstNotNullOfOrNull { driftPattern.find(it) }
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull() ?: return null
    return ClockDriftData(timestamp, deviation)
}

fun clockDriftChart(data: List<ClockDriftData>): JFreeChart {
    val dataset = XYBarDataset(
        XYSeriesCollection(
            XYSeries("Clock Drift").apply {
                for ((timestamp, deviationMs) in data) {
                    add(timestamp.toEpochMilli().toDouble(), deviationMs.toDouble(), false)
                }
            },
        ),
        1.0,
    )

    return ChartFactory.createXYBarChart(
        /* title = */
        null,
        /* xAxisLabel = */
        null,
        /* dateAxis = */
        true,
        /* yAxisLabel = */
        "Deviation (ms)",
        /* dataset = */
        dataset,
        /* orientation = */
        PlotOrientation.VERTICAL,
        /* legend = */
        false,
        /* tooltips = */
        true,
        /* urls = */
        false,
    ).apply {
        xyPlot.apply {
            (renderer as XYBarRenderer).apply {
                barPainter = StandardXYBarPainter()
                setShadowVisible(false)
                isDrawBarOutline = false
                margin = 0.0
                // the bar-width listener below fires a dataset change in the middle of a drag-zoom,
                // and a deviation range fitted to the visible drifts would shrink under the zoom
                dataBoundsIncludesVisibleSeriesOnly = false
            }

            domainAxis.autoRangeMinimumSize = MIN_WINDOW_MS

            // keep stems ~1px wide at any zoom level (assumes the ~800px popout width) - below a one-second
            // window the date axis rounds to whole milliseconds and would widen them again, so stop there
            fun updateBarWidth() {
                val range = domainAxis.range
                if (range.length < MIN_ZOOM_MS) {
                    val center = range.centralValue
                    domainAxis.setRange(center - MIN_ZOOM_MS / 2, center + MIN_ZOOM_MS / 2)
                    return
                }
                dataset.barWidth = range.length / 800.0
            }
            domainAxis.addChangeListener { updateBarWidth() }
            updateBarWidth()

            domainAxis.isPositiveArrowVisible = true
            rangeAxis.isPositiveArrowVisible = true

            val updateTooltipGenerator = {
                renderer.setDefaultToolTipGenerator { dataset, series, item ->
                    val time = Instant.ofEpochMilli(dataset.getXValue(series, item).toLong())
                    "${Timezone.Default.format(time)} - ${dataset.getYValue(series, item).toLong()} ms"
                }
            }

            updateTooltipGenerator()

            Timezone.Default.addChangeListener {
                updateTooltipGenerator()
            }

            isDomainGridlinesVisible = false
            isRangeGridlinesVisible = false
            isOutlineVisible = false
        }

        padding = RectangleInsets(10.0, 10.0, 10.0, 10.0)
        isBorderVisible = false

        theme = Theme.currentValue
        Theme.addChangeListener { newTheme ->
            theme = newTheme
        }
    }
}

private const val MIN_WINDOW_MS = 60_000.0
private const val MIN_ZOOM_MS = 1_000.0
