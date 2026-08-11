package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.core.Kindling.Preferences.UI.Theme
import io.github.inductiveautomation.kindling.core.Theme.Companion.theme
import io.github.inductiveautomation.kindling.core.Timezone
import org.jfree.chart.ChartFactory
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.AxisLocation
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.StandardXYBarPainter
import org.jfree.chart.renderer.xy.XYBarRenderer
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.chart.ui.RectangleInsets
import org.jfree.data.xy.XYBarDataset
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.time.Instant
import javax.swing.UIManager
import kotlin.math.roundToInt

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

fun clockDriftChart(data: List<ClockDriftData>, metrics: MetricsStripeState? = null): JFreeChart {
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
        metrics?.source != null,
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

            // usage lines join only when a metrics file is already loaded; a source loaded
            // later is picked up by reopening the chart
            if (metrics?.source != null) {
                addUsageLines(metrics)
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

private fun XYPlot.addUsageLines(metrics: MetricsStripeState) {
    setRangeAxis(
        1,
        NumberAxis("Usage (%)").apply {
            setRange(0.0, 100.0)
            isPositiveArrowVisible = true
        },
    )
    setRangeAxisLocation(1, AxisLocation.BOTTOM_OR_RIGHT)
    mapDatasetToRangeAxis(1, 1)

    val lineRenderer = XYLineAndShapeRenderer(true, false)
    setRenderer(1, lineRenderer)

    fun updateLinePaints() {
        val lines = getDataset(1) ?: return
        for (series in 0 until lines.seriesCount) {
            lineRenderer.setSeriesPaint(
                series,
                UIManager.getColor(if (lines.getSeriesKey(series) == "CPU") "Actions.Blue" else "Actions.Green"),
            )
        }
    }

    fun updateLineDataset() {
        val source = metrics.source
        setDataset(
            1,
            XYSeriesCollection().apply {
                source?.cpu?.let { addSeries(it.toPercentSeries("CPU")) }
                source?.memory?.let { addSeries(it.toPercentSeries(if (it.peakNormalized) "Memory (% of peak)" else "Memory")) }
            },
        )
        updateLinePaints()
    }

    updateLineDataset()
    metrics.addChangeListener { updateLineDataset() }
    Theme.addChangeListener { updateLinePaints() }

    val updateLineTooltipGenerator = {
        lineRenderer.setDefaultToolTipGenerator { dataset, series, item ->
            val usage = dataset.getYValue(series, item)
            if (usage.isNaN()) {
                // the artificial items that break lines at coverage gaps carry no value
                null
            } else {
                val millis = dataset.getXValue(series, item).toLong()
                val time = Timezone.Default.format(Instant.ofEpochMilli(millis))
                val percent = usage.roundToInt()
                val memory = metrics.source?.memory?.takeUnless { dataset.getSeriesKey(series) == "CPU" }
                val sample = memory?.sampleNear(millis)
                if (memory != null && sample != null) {
                    val share = if (memory.peakNormalized) "$percent% of peak" else "$percent%"
                    "Heap %.1f mB (%s) - %s".format(sample.raw / 1_000_000, share, time)
                } else {
                    "${dataset.getSeriesKey(series)} $percent% - $time"
                }
            }
        }
    }
    updateLineTooltipGenerator()
    Timezone.Default.addChangeListener { updateLineTooltipGenerator() }
}

// null y-values break the polyline at coverage gaps, so a gateway-down window
// doesn't draw as a bridge between its endpoints
internal fun MetricSeries.toPercentSeries(key: String): XYSeries = XYSeries(key).apply {
    var previous = Long.MIN_VALUE
    forEachSample { sample ->
        if (previous != Long.MIN_VALUE && sample.timestampMillis - previous > gapToleranceMillis) {
            add((previous + sample.timestampMillis) / 2.0, null, false)
        }
        add(sample.timestampMillis.toDouble(), sample.fraction * 100, false)
        previous = sample.timestampMillis
    }
}

private const val MIN_WINDOW_MS = 60_000.0
private const val MIN_ZOOM_MS = 1_000.0
