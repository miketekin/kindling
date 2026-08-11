package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.core.Kindling.Preferences.General.HomeLocation
import io.github.inductiveautomation.kindling.core.Timezone
import io.github.inductiveautomation.kindling.idb.metrics.MetricData
import io.github.inductiveautomation.kindling.utils.EDT_SCOPE
import io.github.inductiveautomation.kindling.utils.FileFilter
import io.github.inductiveautomation.kindling.utils.FlatActionIcon
import io.github.inductiveautomation.kindling.utils.ReifiedJXTable
import io.github.inductiveautomation.kindling.utils.SQLiteConnection
import io.github.inductiveautomation.kindling.utils.add
import io.github.inductiveautomation.kindling.utils.configureCellRenderer
import io.github.inductiveautomation.kindling.utils.executeQuery
import io.github.inductiveautomation.kindling.utils.get
import io.github.inductiveautomation.kindling.utils.getAll
import io.github.inductiveautomation.kindling.utils.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JViewport
import javax.swing.RowSorter
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager
import javax.swing.event.EventListenerList
import javax.swing.event.RowSorterListener
import javax.swing.event.TableModelListener
import javax.swing.table.TableModel
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.math.roundToInt

enum class MetricsMode(val displayName: String) {
    Off("Off"),
    Cpu("CPU"),
    Memory("Memory"),
}

data class MetricsSource(
    val path: Path,
    val metricNames: List<String>,
    val gatewayInfo: GatewayInfo? = null,
    val cpu: MetricSeries? = null,
    val memory: MetricSeries? = null,
)

class MetricsStripeState(
    internal val wallClockTimestamps: Boolean,
    internal val logRange: () -> LongRange,
) {
    private val listeners = EventListenerList()

    var mode: MetricsMode = MetricsMode.Off
        set(value) {
            if (field != value) {
                field = value
                fireChanged()
            }
        }

    var source: MetricsSource? = null
        set(value) {
            field = value
            fireChanged()
        }

    fun series(mode: MetricsMode): MetricSeries? = when (mode) {
        MetricsMode.Cpu -> source?.cpu
        MetricsMode.Memory -> source?.memory
        MetricsMode.Off -> null
    }

    fun addChangeListener(listener: StripeListener) = listeners.add(listener)

    private fun fireChanged() = listeners.getAll<StripeListener>().forEach(StripeListener::stripeChanged)
}

@Suppress("EnumValuesSoftDeprecate") // not a performance sensitive enum.values() call
internal fun metricsModeSelector(metrics: MetricsStripeState): JComboBox<MetricsMode> = JComboBox(MetricsMode.values()).apply {
    configureCellRenderer { _, value, _, _, _ ->
        text = value?.displayName.orEmpty()
    }
    fun refresh() {
        selectedItem = metrics.mode
        val source = metrics.source
        val series = metrics.series(metrics.mode)
        toolTipText = when {
            series != null -> buildString {
                append("<html>${source?.path?.name} covers ${formatRange(series.firstTimestamp, series.lastTimestamp)}")
                if (metrics.wallClockTimestamps && source?.gatewayInfo?.zone == null) {
                    val reason = if (source?.gatewayInfo == null) "no gateway-info.json" else "unreadable gateway-info.json"
                    append("<br>Gateway timezone unknown ($reason) - the stripe may be misaligned if the gateway is in another timezone")
                }
            }
            source != null -> "${source.path.name} loaded"
            else -> "Show gateway CPU or heap usage beside the scroll bar"
        }
    }
    addActionListener {
        val target = selectedItem as? MetricsMode ?: return@addActionListener
        when {
            target == metrics.mode -> {}
            target == MetricsMode.Off -> metrics.mode = MetricsMode.Off
            metrics.series(target) != null -> metrics.mode = target
            else -> {
                // revert until the load succeeds - the change listener reselects on success
                selectedItem = metrics.mode
                loadAndActivate(metrics, target, this, chooseNewFile = metrics.source == null)
            }
        }
    }
    refresh()
    metrics.addChangeListener { refresh() }
    Timezone.Default.addChangeListener { refresh() }
}

internal fun metricsFileButton(metrics: MetricsStripeState): JButton = JButton(FlatActionIcon("icons/bx-folder-open.svg")).apply {
    toolTipText = "Choose metrics database..."
    addActionListener {
        loadAndActivate(metrics, metrics.mode, this, chooseNewFile = true)
    }
}

internal fun loadMissingSeries(metrics: MetricsStripeState, parent: JComponent) {
    if (metrics.source == null) return
    EDT_SCOPE.launch {
        // sequential, re-reading the source each pass - two concurrent loads would each
        // copy() a stale base and drop the other's freshly loaded series
        for (mode in listOf(MetricsMode.Cpu, MetricsMode.Memory)) {
            val base = metrics.source ?: return@launch
            if (metrics.series(mode) != null) continue
            try {
                val series = loadSeries(metrics, base, mode, parent) ?: continue
                metrics.source = when (mode) {
                    MetricsMode.Memory -> base.copy(memory = series)
                    else -> base.copy(cpu = series)
                }
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(parent, e.message, "Gateway Metrics", JOptionPane.ERROR_MESSAGE)
            }
        }
    }
}

private fun formatRange(startMillis: Long, endMillis: Long): String = "${Timezone.Default.format(Instant.ofEpochMilli(startMillis))} - ${Timezone.Default.format(Instant.ofEpochMilli(endMillis))}"

private fun loadAndActivate(metrics: MetricsStripeState, mode: MetricsMode, parent: JComponent, chooseNewFile: Boolean) {
    val existing = metrics.source?.takeUnless { chooseNewFile }
    val path = existing?.path ?: chooseMetricsFile(parent, metrics.source?.path?.parent) ?: return

    EDT_SCOPE.launch {
        try {
            val base = existing ?: withContext(Dispatchers.IO) {
                MetricsSource(path, readMetricNames(path), readGatewayInfo(path))
            }
            if (mode == MetricsMode.Off) {
                metrics.source = base
                return@launch
            }
            val series = loadSeries(metrics, base, mode, parent) ?: return@launch
            metrics.source = when (mode) {
                MetricsMode.Memory -> base.copy(memory = series)
                else -> base.copy(cpu = series)
            }
            metrics.mode = mode
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(parent, e.message, "Gateway Metrics", JOptionPane.ERROR_MESSAGE)
        }
    }
}

private suspend fun loadSeries(metrics: MetricsStripeState, base: MetricsSource, mode: MetricsMode, parent: JComponent): MetricSeries? {
    val choices = when (mode) {
        MetricsMode.Memory -> heapUsedCandidates(base.metricNames)
        else -> metricCandidates(base.metricNames, "cpu")
    }.ifEmpty { base.metricNames.sorted() }
    val metricName = choices.singleOrNull() ?: pickMetric(parent, choices, mode) ?: return null
    val maxName = if (mode == MetricsMode.Memory) heapMaxFor(metricName, base.metricNames) else null

    val gatewayZone = base.gatewayInfo?.zone
    val heapMax = base.gatewayInfo?.heapMax
    val series = withContext(Dispatchers.IO) {
        // Wrapper logs parse their zone-less wall-clock text in the viewer's system zone
        // (WrapperLogPanel.DEFAULT_WRAPPER_LOG_TIME_FORMAT), so absolute metric samples are
        // re-mapped into that same frame to keep the stripe aligned with the table. Remove
        // this remap if wrapper log or metrics parsing ever becomes zone-aware.
        val data = readSeries(base.path, listOfNotNull(metricName, maxName)).mapValues { (_, samples) ->
            if (metrics.wallClockTimestamps && gatewayZone != null) {
                remapWallClock(samples, gatewayZone, ZoneId.systemDefault())
            } else {
                samples
            }
        }
        when (mode) {
            MetricsMode.Memory -> {
                val used = data[metricName].orEmpty()
                val max = when {
                    maxName != null -> data[maxName].orEmpty()
                    heapMax != null -> used.take(1).map { MetricData(heapMax, it.timestamp) }
                    else -> emptyList()
                }
                MetricSeries.heap(used, max)
            }
            else -> MetricSeries.cpu(data[metricName].orEmpty())
        }
    }
    checkNotNull(series) { "$metricName has no data points" }

    val logRange = metrics.logRange()
    check(series.overlaps(logRange.first, logRange.last)) {
        buildString {
            append("${base.path.name} covers ${formatRange(series.firstTimestamp, series.lastTimestamp)}, but the log covers ${formatRange(logRange.first, logRange.last)}")
            if (metrics.wallClockTimestamps && gatewayZone == null) {
                val reason = if (base.gatewayInfo == null) "No gateway-info.json was found beside ${base.path.name}" else "No gateway timezone could be read from the gateway-info.json beside ${base.path.name}"
                append("\n\n$reason - if the gateway is in another timezone, these ranges may be shifted apart.")
            }
        }
    }
    return series
}

private fun chooseMetricsFile(parent: JComponent, startDirectory: Path?): Path? {
    val chooser = JFileChooser((startDirectory ?: HomeLocation.currentValue).toFile()).apply {
        isMultiSelectionEnabled = false
        isAcceptAllFileFilterUsed = false
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileFilter("Metrics Database (.idb)", "idb", "db", "sqlite")
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

private fun pickMetric(parent: JComponent, choices: List<String>, mode: MetricsMode): String? = JOptionPane.showInputDialog(
    parent,
    "Select the ${mode.displayName} metric",
    "Gateway Metrics",
    JOptionPane.QUESTION_MESSAGE,
    null,
    choices.toTypedArray(),
    choices.first(),
) as String?

@Suppress("SqlResolve")
private fun readMetricNames(path: Path): List<String> = SQLiteConnection(path).use { connection ->
    val tables = connection.metaData.getTables("", "", "", null).toList<String> { rs -> rs["TABLE_NAME"] }
    check("SYSTEM_METRICS" in tables) { "${path.name} is not an Ignition metrics database" }
    connection.executeQuery(
        //language=sql
        """
        SELECT DISTINCT
            metric_name
        FROM
            system_metrics
        """.trimIndent(),
    ).toList { rs -> rs.getString(1) }
        .also { check(it.isNotEmpty()) { "${path.name} contains no metrics" } }
}

@Suppress("SqlResolve")
private fun readSeries(path: Path, names: List<String>): Map<String, List<MetricData>> = SQLiteConnection(path).use { connection ->
    val query = connection.prepareStatement(
        //language=sql
        """
        SELECT DISTINCT
            value,
            timestamp
        FROM
            system_metrics
        WHERE
            metric_name = ?
        ORDER BY
            timestamp
        """.trimIndent(),
    )
    names.associateWith { name ->
        query.apply { setString(1, name) }
            .executeQuery()
            .toList { rs -> MetricData(rs.getDouble(1), rs.getTimestamp(2)) }
    }
}

data class GatewayInfo(val zone: ZoneId?, val heapMax: Double?)

// null = no file beside the idb; GatewayInfo(null, null) = a file that couldn't be read
private fun readGatewayInfo(idbPath: Path): GatewayInfo? = idbPath.resolveSibling("gateway-info.json")
    .takeIf { it.isRegularFile() }
    ?.let { file ->
        runCatching { parseGatewayInfo(file.readText()) }.getOrNull() ?: GatewayInfo(null, null)
    }

internal fun parseGatewayInfo(text: String): GatewayInfo? = runCatching {
    val system = Json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject["system"]?.jsonObject
    val zone = system?.get("user")?.jsonObject?.get("timezone")?.jsonPrimitive?.contentOrNull
        ?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() }
    val heapMax = system?.get("memory")?.jsonObject?.get("heap")?.jsonObject?.get("max")?.jsonPrimitive?.doubleOrNull
    GatewayInfo(zone, heapMax)
}.getOrNull()

/**
 * A thin vertical stripe beside the marker stripe. While a metric is active, each pixel row draws a
 * bar whose length is proportional to the gateway's usage at that position in the table's current
 * view. Rows with no nearby metric sample draw nothing, and the stripe collapses entirely while off.
 */
internal class MetricsStripe(
    private val table: ReifiedJXTable<out LogsModel<out LogEvent>>,
    private val metrics: MetricsStripeState,
) : JComponent() {
    private val modelListener = TableModelListener { repaint() }
    private val sorterListener = RowSorterListener { repaint() }

    init {
        table.model.addTableModelListener(modelListener)
        table.rowSorter?.addRowSorterListener(sorterListener)
        table.addPropertyChangeListener("model") { event ->
            (event.oldValue as? TableModel)?.removeTableModelListener(modelListener)
            (event.newValue as? TableModel)?.addTableModelListener(modelListener)
            repaint()
        }
        table.addPropertyChangeListener("rowSorter") { event ->
            (event.oldValue as? RowSorter<*>)?.removeRowSorterListener(sorterListener)
            (event.newValue as? RowSorter<*>)?.addRowSorterListener(sorterListener)
            repaint()
        }
        isVisible = false
        metrics.addChangeListener {
            isVisible = metrics.mode != MetricsMode.Off
            repaint()
        }
        // plain JComponents don't show tooltips unless registered
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun isOpaque(): Boolean = false

    override fun paintComponent(g: Graphics) {
        val series = metrics.series(metrics.mode) ?: return
        val rowCount = table.rowCount
        if (rowCount == 0) return
        val track = trackBounds()
        if (track.height <= 0) return

        val fractions = DoubleArray(track.height) { -1.0 }
        for (viewRow in 0 until rowCount) {
            val event = table.model[table.convertRowIndexToModel(viewRow)]
            val sample = series.sampleNear(event.timestamp.toEpochMilli()) ?: continue
            val bucket = (viewRow.toLong() * track.height / rowCount).toInt()
            if (sample.fraction > fractions[bucket]) {
                fractions[bucket] = sample.fraction
            }
        }

        g.color = UIManager.getColor(if (metrics.mode == MetricsMode.Cpu) "Actions.Blue" else "Actions.Green")
        val barSpan = width - 1
        for (bucket in fractions.indices) {
            if (fractions[bucket] < 0) continue
            val length = (barSpan * fractions[bucket]).roundToInt().coerceAtLeast(MIN_BAR_LENGTH)
            g.fillRect(0, track.y + bucket, length, 1)
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val series = metrics.series(metrics.mode) ?: return null
        val rowCount = table.rowCount
        if (rowCount == 0) return null
        val track = trackBounds()
        val bucket = event.y - track.y
        if (track.height <= 0 || bucket !in 0 until track.height) return null

        val viewRow = (bucket.toLong() * rowCount / track.height).toInt().coerceIn(0, rowCount - 1)
        val timestamp = table.model[table.convertRowIndexToModel(viewRow)].timestamp
        val sample = series.sampleNear(timestamp.toEpochMilli()) ?: return "No metrics data"
        val time = Timezone.Default.format(Instant.ofEpochMilli(sample.timestampMillis))
        val percent = (sample.fraction * 100).roundToInt()
        return when (metrics.mode) {
            MetricsMode.Memory -> {
                val share = if (series.peakNormalized) "$percent% of peak" else "$percent%"
                "Heap %.1f mB (%s) - %s".format(sample.raw / 1_000_000, share, time)
            }
            else -> "CPU $percent% - $time"
        }
    }

    // align with the scroll bar track, i.e. the vertical extent of the scroll pane's viewport
    private fun trackBounds(): Rectangle {
        val viewport = table.parent as? JViewport
        return if (viewport != null) {
            SwingUtilities.convertRectangle(viewport.parent, viewport.bounds, this)
        } else {
            Rectangle(0, 0, width, height)
        }
    }

    companion object {
        private const val MIN_BAR_LENGTH = 1
    }
}
