package io.github.inductiveautomation.kindling.log

import io.github.inductiveautomation.kindling.utils.FlatActionIcon
import io.github.inductiveautomation.kindling.utils.ReifiedJXTable
import io.github.inductiveautomation.kindling.utils.add
import io.github.inductiveautomation.kindling.utils.getAll
import java.awt.Graphics
import java.awt.Rectangle
import java.util.EventListener
import javax.swing.JComponent
import javax.swing.JToggleButton
import javax.swing.JViewport
import javax.swing.RowSorter
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.EventListenerList
import javax.swing.event.RowSorterListener
import javax.swing.event.TableModelListener
import javax.swing.table.TableModel
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class StripeMode { Off, Levels, Loggers, Threads }

fun interface StripeListener : EventListener {
    fun stripeChanged()
}

class StripeState {
    private val listeners = EventListenerList()

    val loggerColors = CategoryColors(LogEvent::logger, ::fireChanged)
    val threadColors = CategoryColors({ (it as? SystemLogEvent)?.thread }, ::fireChanged)

    var mode: StripeMode = StripeMode.Off
        set(value) {
            if (field != value) {
                field = value
                colors(value)?.refreshAuto(latestData)
                fireChanged()
            }
        }

    private var latestData: List<LogEvent> = emptyList()

    fun colors(mode: StripeMode): CategoryColors? = when (mode) {
        StripeMode.Loggers -> loggerColors
        StripeMode.Threads -> threadColors
        else -> null
    }

    fun updateAuto(data: List<LogEvent>) {
        latestData = data
        colors(mode)?.refreshAuto(data)
    }

    fun resetToAuto(mode: StripeMode) {
        colors(mode)?.resetToAuto(latestData)
    }

    fun addChangeListener(listener: StripeListener) = listeners.add(listener)

    private fun fireChanged() = listeners.getAll<StripeListener>().forEach(StripeListener::stripeChanged)
}

internal fun stripeToggleButton(stripe: StripeState, mode: StripeMode, tooltip: String): JToggleButton = JToggleButton(FlatActionIcon("icons/bx-palette.svg")).apply {
    toolTipText = tooltip
    isSelected = stripe.mode == mode
    addActionListener {
        stripe.mode = if (isSelected) mode else StripeMode.Off
    }
    stripe.addChangeListener { isSelected = stripe.mode == mode }
}

/**
 * A thin vertical stripe beside the log table's scroll bar. While a coloring mode is active, each pixel
 * row draws a bar whose length is proportional to the rate of notable events at that position in the
 * table's current view. Marked events are ticked along the outer edge in every mode.
 */
internal class MarkerStripe(
    private val table: ReifiedJXTable<out LogsModel<out LogEvent>>,
    private val stripe: StripeState,
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
        stripe.addChangeListener { repaint() }
    }

    override fun isOpaque(): Boolean = false

    override fun paintComponent(g: Graphics) {
        val rowCount = table.rowCount
        if (rowCount == 0) return

        // align with the scroll bar track, i.e. the vertical extent of the scroll pane's viewport
        val viewport = table.parent as? JViewport
        val track = if (viewport != null) {
            SwingUtilities.convertRectangle(viewport.parent, viewport.bounds, this)
        } else {
            Rectangle(0, 0, width, height)
        }
        if (track.height <= 0) return

        val marks = BooleanArray(track.height)
        val barSpan = width - MARK_LANE_WIDTH - 1

        when (stripe.mode) {
            StripeMode.Off -> {
                for (viewRow in 0 until rowCount) {
                    val event = table.model[table.convertRowIndexToModel(viewRow)]
                    if (event.marked) {
                        marks[(viewRow.toLong() * track.height / rowCount).toInt()] = true
                    }
                }
            }

            StripeMode.Levels -> {
                val errors = IntArray(track.height)
                val warns = IntArray(track.height)
                val totals = IntArray(track.height)
                for (viewRow in 0 until rowCount) {
                    val event = table.model[table.convertRowIndexToModel(viewRow)]
                    val bucket = (viewRow.toLong() * track.height / rowCount).toInt()
                    totals[bucket]++
                    when (event.level) {
                        Level.ERROR -> errors[bucket]++
                        Level.WARN -> warns[bucket]++
                        else -> {}
                    }
                    if (event.marked) {
                        marks[bucket] = true
                    }
                }

                val errorColor = UIManager.getColor("Actions.Red")
                val warnColor = UIManager.getColor("Actions.Yellow")
                for (bucket in totals.indices) {
                    val problems = errors[bucket] + warns[bucket]
                    if (problems == 0) continue
                    // sqrt keeps low rates distinguishable - the bar is split by error/warn share
                    val length = maxOf(MIN_BAR_LENGTH, (barSpan * sqrt(problems.toFloat() / totals[bucket])).roundToInt())
                    var errorLength = (length.toFloat() * errors[bucket] / problems).roundToInt()
                    if (errors[bucket] > 0) errorLength = errorLength.coerceAtLeast(1)
                    if (warns[bucket] > 0) errorLength = errorLength.coerceAtMost(length - 1)
                    val y = track.y + bucket
                    g.color = errorColor
                    g.fillRect(0, y, errorLength, 1)
                    g.color = warnColor
                    g.fillRect(errorLength, y, length - errorLength, 1)
                }
            }

            StripeMode.Loggers, StripeMode.Threads -> {
                val category = checkNotNull(stripe.colors(stripe.mode))
                val assignments = category.assignments
                val segmentColors = assignments.values.toList()
                val indexByKey = assignments.keys.withIndex().associate { (index, key) -> key to index }
                val totals = IntArray(track.height)
                val counts = Array(track.height) { IntArray(segmentColors.size) }
                for (viewRow in 0 until rowCount) {
                    val event = table.model[table.convertRowIndexToModel(viewRow)]
                    val bucket = (viewRow.toLong() * track.height / rowCount).toInt()
                    totals[bucket]++
                    category.keyFn(event)?.let { key -> indexByKey[key]?.let { counts[bucket][it]++ } }
                    if (event.marked) {
                        marks[bucket] = true
                    }
                }

                for (bucket in totals.indices) {
                    val colored = counts[bucket].sum()
                    if (colored == 0) continue
                    var remaining = counts[bucket].count { it > 0 }
                    val length = maxOf(MIN_BAR_LENGTH, remaining, (barSpan * sqrt(colored.toFloat() / totals[bucket])).roundToInt())
                        .coerceAtMost(barSpan)
                    val y = track.y + bucket
                    var x = 0
                    var remainingLength = length
                    var remainingCount = colored
                    for (segment in segmentColors.indices) {
                        val count = counts[bucket][segment]
                        if (count == 0) continue
                        remaining--
                        // proportional share, clamped so every remaining segment keeps >= 1px
                        val segmentLength = if (remaining == 0) {
                            remainingLength
                        } else {
                            (remainingLength.toFloat() * count / remainingCount).roundToInt()
                                .coerceIn(1, (remainingLength - remaining).coerceAtLeast(1))
                        }
                        g.color = segmentColors[segment]
                        g.fillRect(x, y, segmentLength, 1)
                        x += segmentLength
                        remainingLength -= segmentLength
                        remainingCount -= count
                        if (remainingLength <= 0) break
                    }
                }
            }
        }

        g.color = UIManager.getColor("Table.cellFocusColor")
        for (bucket in marks.indices) {
            if (marks[bucket]) {
                g.fillRect(width - MARK_LANE_WIDTH, track.y + bucket, MARK_LANE_WIDTH, 2)
            }
        }
    }

    companion object {
        private const val MARK_LANE_WIDTH = 3
        private const val MIN_BAR_LENGTH = 2
    }
}
