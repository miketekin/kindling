package io.github.inductiveautomation.kindling.utils

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.jidesoft.comparator.AlphanumComparator
import com.jidesoft.swing.CheckBoxList
import com.jidesoft.swing.ListSearchable
import io.github.inductiveautomation.kindling.utils.FilterComparator.ByCountDescending
import java.awt.Point
import java.awt.event.MouseEvent
import java.text.DecimalFormat
import javax.swing.AbstractListModel
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JToggleButton
import javax.swing.ListModel
import javax.swing.SwingUtilities

data class FilterModelEntry(
    val key: String?,
    val count: Int,
)

enum class FilterComparator(
    val tooltip: String,
    val icon: FlatSVGIcon,
    val comparator: Comparator<FilterModelEntry>,
) : Comparator<FilterModelEntry> by comparator {
    ByNameAscending(
        tooltip = "Sort A-Z",
        icon = FlatActionIcon("icons/bx-sort-a-z.svg"),
        comparator = compareBy(nullsFirst(AlphanumComparator(false))) { it.key },
    ),
    ByNameDescending(
        tooltip = "Sort Z-A",
        icon = FlatActionIcon("icons/bx-sort-z-a.svg"),
        comparator = ByNameAscending.reversed(),
    ),
    ByCountAscending(
        tooltip = "Sort by Count",
        icon = FlatActionIcon("icons/bx-sort-up.svg"),
        comparator = compareBy(FilterModelEntry::count),
    ),
    ByCountDescending(
        tooltip = "Sort by Count (descending)",
        icon = FlatActionIcon("icons/bx-sort-down.svg"),
        comparator = ByCountAscending.reversed(),
    ),
}

fun FilterModel(data: Map<String?, Int>) = FilterModel(data) { it }

class FilterModel<T>(
    val data: Map<T, Int>,
    val sortKey: (T) -> String?,
) : AbstractListModel<Any>() {
    private val total = data.values.sum()
    val percentages = data.mapValues { (_, count) ->
        val percentage = count.toFloat() / total
        percentFormat.format(percentage)
    }

    internal var values: List<*> = data.keys.toList()

    override fun getSize(): Int = values.size + 1
    override fun getElementAt(index: Int): Any? = if (index == 0) {
        CheckBoxList.ALL_ENTRY
    } else {
        values[index - 1]
    }

    fun indexOf(value: Any?): Int {
        val indexOf: Int = values.indexOf(value)
        return if (indexOf >= 0) {
            indexOf + 1
        } else {
            -1
        }
    }

    fun copy(comparator: FilterComparator): FilterModel<T> = FilterModel(
        data.entries
            .sortedWith(
                compareBy(comparator) { (key, value) ->
                    FilterModelEntry(sortKey(key), value)
                },
            )
            .associate(Map.Entry<T, Int>::toPair),
        sortKey,
    )

    companion object {
        private val percentFormat = DecimalFormat.getPercentInstance()

        fun <T, R> fromRawData(
            data: List<T>,
            comparator: FilterComparator,
            sortKey: (R) -> String = Any?::toString,
            transform: (T) -> R,
        ): FilterModel<R> {
            val sortedData: Map<R, Int> = data.groupingBy(transform).eachCount().entries
                .sortedWith(
                    compareBy(comparator) { (key, value) ->
                        FilterModelEntry(sortKey(key), value)
                    },
                )
                .associate(Map.Entry<R, Int>::toPair)
            return FilterModel(sortedData, sortKey)
        }
    }
}

typealias Stringifier = (Any?) -> String?

class FilterList(
    initialComparator: FilterComparator = ByCountDescending,
    private val tooltipToStringFn: Stringifier? = null,
    private val toStringFn: Stringifier = { it?.toString() },
) : CheckBoxList(FilterModel(emptyMap())) {
    private var lastSelection = arrayOf<Any>()

    var comparatorIsAdjusting = false

    /** Optional per-item icon, rendered between the checkbox and the label. */
    var iconFn: ((Any?) -> Icon?)? = null

    /** Left clicks on a visible item icon are swallowed (no checkbox toggle) and routed here. */
    var iconClickHandler: ((value: Any?, event: MouseEvent) -> Unit)? = null

    private val checkBoxHotspot = JCheckBox().preferredSize.width

    init {
        selectionModel = NoSelectionModel()
        isClickInCheckBoxOnly = false

        cellRenderer = listCellRenderer<Any?> { _, value, _, _, _ ->
            when (value) {
                ALL_ENTRY -> {
                    text = value.toString()
                }

                else -> {
                    text = "${toStringFn(value)} [${model.data[value]}] (${model.percentages[value]})"
                    icon = iconFn?.invoke(value)
                    toolTipText = tooltipToStringFn?.let { stringifier ->
                        "${stringifier(value)} [${model.data[value]}] (${model.percentages[value]})"
                    } ?: text
                }
            }
        }

        object : ListSearchable(this) {
            init {
                isCaseSensitive = false
                isRepeats = true
                isCountMatch = true
            }

            override fun convertElementToString(element: Any?): String = element.toString()

            override fun setSelectedIndex(index: Int, incremental: Boolean) {
                checkBoxListSelectedIndex = index
            }
        }
    }

    fun select(value: Any?) {
        val rowToSelect = model.indexOf(value)
        checkBoxListSelectionModel.setSelectionInterval(rowToSelect, rowToSelect)
    }

    // consume icon-zone clicks before JIDE's handler toggles the checkbox on mouse press
    override fun processMouseEvent(e: MouseEvent) {
        if (iconClickHandler != null && SwingUtilities.isLeftMouseButton(e) &&
            (e.id == MouseEvent.MOUSE_PRESSED || e.id == MouseEvent.MOUSE_RELEASED || e.id == MouseEvent.MOUSE_CLICKED)
        ) {
            val value = iconValueAt(e.point)
            if (value != null) {
                e.consume()
                if (e.id == MouseEvent.MOUSE_CLICKED) {
                    iconClickHandler?.invoke(value, e)
                }
            }
        }
        super.processMouseEvent(e)
    }

    private fun iconValueAt(point: Point): Any? {
        val index = locationToIndex(point)
        if (index <= 0) return null
        val cell = getCellBounds(index, index) ?: return null
        if (!cell.contains(point)) return null
        val value = model.getElementAt(index)
        if (iconFn?.invoke(value) == null) return null
        return value.takeIf { point.x - cell.x - checkBoxHotspot in 0 until ICON_HIT_WIDTH }
    }

    var comparator: FilterComparator = initialComparator
        set(newComparator) {
            comparatorIsAdjusting = true
            field = newComparator
            model = model.copy(newComparator)
            comparatorIsAdjusting = false
        }

    @Suppress("UNCHECKED_CAST")
    override fun getModel(): FilterModel<in Any?> = super.getModel() as FilterModel<in Any?>

    override fun setModel(model: ListModel<*>) {
        require(model is FilterModel<*>)
        val currentSelection = checkBoxListSelectedValues
        val allSelected = currentSelection.size + 1 == this.model.size

        lastSelection = if (currentSelection.isEmpty()) {
            lastSelection
        } else {
            currentSelection
        }

        super.setModel(model)

        for (sortAction in sortActions) {
            sortAction.selected = comparator == sortAction.comparator
        }

        if (allSelected) {
            selectAll()
        } else {
            addCheckBoxListSelectedValues(lastSelection)
        }
    }

    private val sortActions: List<SortAction> = FilterComparator.entries.map { filterComparator ->
        SortAction(filterComparator)
    }

    inner class SortAction(comparator: FilterComparator) :
        Action(
            description = comparator.tooltip,
            icon = comparator.icon,
            selected = this@FilterList.comparator == comparator,
            action = {
                this@FilterList.comparator = comparator
                selected = true
            },
        ) {
        var comparator: FilterComparator by actionValue("filterComparator", comparator)
    }

    fun createSortButtons(): ButtonGroup = ButtonGroup().apply {
        for (sortAction in sortActions) {
            add(
                JToggleButton(
                    Action(
                        description = sortAction.description,
                        icon = sortAction.icon,
                        selected = sortAction.selected,
                    ) { e ->
                        sortAction.actionPerformed(e)
                    },
                ),
            )
        }
    }

    companion object {
        private const val ICON_HIT_WIDTH = 20
    }
}
