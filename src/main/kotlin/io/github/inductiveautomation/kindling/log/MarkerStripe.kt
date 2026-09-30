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

enum class StripeMode { Off, Levels }

fun interface StripeListener : EventListener {
    fun stripeChanged()
}

class StripeState {
    private val listeners = EventListenerList()

    var mode: StripeMode = StripeMode.Off
        set(value) {
            if (field != value) {
                field = value
                fireChanged()
            }
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
