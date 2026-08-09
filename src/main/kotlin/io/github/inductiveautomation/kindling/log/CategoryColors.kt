package io.github.inductiveautomation.kindling.log

import com.formdev.flatlaf.icons.FlatAbstractIcon
import io.github.inductiveautomation.kindling.core.Kindling.Preferences.UI.Theme
import io.github.inductiveautomation.kindling.utils.Action
import io.github.inductiveautomation.kindling.utils.attachPopupMenu
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import javax.swing.Icon
import javax.swing.JColorChooser
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.UIManager

/**
 * Session-local color assignments for one category dimension (logger or thread) of the marker stripe.
 *
 * Starts in auto mode: the five most frequent keys of the current filtered view, colored by rank.
 * The first manual change freezes the current assignments into a stable identity map and stops auto
 * updates until [resetToAuto].
 */
class CategoryColors(
    val keyFn: (LogEvent) -> String?,
    private val onChange: () -> Unit,
) {
    private var autoRanks: Map<String, Int> = emptyMap()

    private var countedData: List<LogEvent>? = null

    private var manual: LinkedHashMap<String, Color>? = null

    // iteration order is the stripe's segment draw order
    val assignments: Map<String, Color>
        get() = manual ?: autoRanks.entries.sortedBy { it.value }.associate { (key, rank) -> key to palette[rank] }

    fun colorOf(key: String): Color? = when (val frozen = manual) {
        null -> autoRanks[key]?.let { palette[it] }
        else -> frozen[key]
    }

    fun refreshAuto(data: List<LogEvent>) {
        if (manual != null || data === countedData) return
        countedData = data
        val ranks = topKeys(data, keyFn)
        if (ranks != autoRanks) {
            autoRanks = ranks
            onChange()
        }
    }

    fun assign(key: String, color: Color) = freezeAnd { it[key] = color }

    fun remove(key: String) = freezeAnd { it.remove(key) }

    fun clear() {
        manual = LinkedHashMap()
        onChange()
    }

    fun resetToAuto(data: List<LogEvent>) {
        manual = null
        countedData = data
        autoRanks = topKeys(data, keyFn)
        onChange()
    }

    private fun freezeAnd(change: (LinkedHashMap<String, Color>) -> Unit) {
        val frozen = manual ?: LinkedHashMap(assignments).also { manual = it }
        change(frozen)
        onChange()
    }

    companion object {
        val paletteNames = listOf("Red", "Orange", "Yellow", "Green", "Blue")

        val palette: List<Color>
            get() = if (Theme.currentValue.isDark) DARK_COLORS else LIGHT_COLORS

        internal fun topKeys(data: List<LogEvent>, keyFn: (LogEvent) -> String?): Map<String, Int> = data
            .asSequence().mapNotNull(keyFn)
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(LIGHT_COLORS.size)
            .withIndex()
            .associate { (rank, entry) -> entry.key to rank }

        private val LIGHT_COLORS = listOf(
            Color(0xD32F2F),
            Color(0xEF6C00),
            Color(0xC0A800),
            Color(0x2E7D32),
            Color(0x1565C0),
        )
        private val DARK_COLORS = listOf(
            Color(0xE57373),
            Color(0xFFB74D),
            Color(0xFFF176),
            Color(0x81C784),
            Color(0x64B5F6),
        )
    }
}

class SwatchIcon(private val color: Color?) : FlatAbstractIcon(12, 12, null) {
    override fun paintIcon(c: Component, g: Graphics2D) {
        if (color != null) {
            g.color = color
            g.fillRoundRect(1, 1, 10, 10, 3, 3)
        } else {
            g.color = UIManager.getColor("Component.borderColor")
            g.drawRoundRect(1, 1, 9, 9, 3, 3)
        }
    }
}

internal fun StripeState.swatchIcon(mode: StripeMode, key: String): Icon? = if (this.mode == mode) SwatchIcon(checkNotNull(colors(mode)).colorOf(key)) else null

internal fun StripeState.swatchPopup(mode: StripeMode, key: String, parent: Component): JPopupMenu {
    val colors = checkNotNull(colors(mode))
    return JPopupMenu().apply {
        for ((name, color) in CategoryColors.paletteNames.zip(CategoryColors.palette)) {
            add(
                JMenuItem(
                    Action(name, icon = SwatchIcon(color)) {
                        colors.assign(key, color)
                    },
                ),
            )
        }
        add(
            JMenuItem(
                Action("Choose...") {
                    JColorChooser.showDialog(parent, "Stripe Color", colors.colorOf(key))?.let {
                        colors.assign(key, it)
                    }
                },
            ),
        )
        add(
            JMenuItem(
                Action("Remove") {
                    colors.remove(key)
                },
            ),
        )
    }
}

internal fun categoryToggleButton(stripe: StripeState, mode: StripeMode, tooltip: String): JToggleButton = stripeToggleButton(stripe, mode, tooltip).apply {
    attachPopupMenu {
        JPopupMenu().apply {
            add(Action("Auto (Top 5)") { stripe.resetToAuto(mode) })
            add(Action("Clear Colors") { stripe.colors(mode)?.clear() })
        }
    }
}
