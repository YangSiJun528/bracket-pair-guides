package com.sijunyang.bracketpairguides.settings

import com.intellij.ui.ColorChooserService
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.AbstractCellEditor
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

/** A compact palette editor following the platform File Colors table pattern. */
internal class ColorPaletteTable(
    private val disabledReason: (PaletteComponent) -> String?,
    private val onColorChanged: (level: Int, component: PaletteComponent, color: Color) -> Unit,
    private val chooseColor: (Component, String, Color) -> Color? =
        { parent, title, currentColor ->
            ColorChooserService.instance.showDialog(
                parent,
                title,
                currentColor,
                false,
            )
        },
) : JPanel(BorderLayout()) {
    private val colors = Array(BracketColorPalette.COLOR_COUNT) {
        Array(PaletteComponent.entries.size) { Color.BLACK }
    }
    private val model = PaletteTableModel()
    internal val table = JBTable(model)

    init {
        table.apply {
            setShowGrid(false)
            intercellSpacing = JBUI.emptySize()
            rowHeight = maxOf(rowHeight, JBUI.scale(24))
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            tableHeader.reorderingAllowed = false
            setDefaultRenderer(Color::class.java, ColorRenderer())
            setDefaultEditor(Color::class.java, ColorEditor())
            putClientProperty("terminateEditOnFocusLost", true)
            accessibleContext.apply {
                accessibleName = "Bracket level colors"
                accessibleDescription =
                    "Six repeating levels with Base, Guide, Border, and Background colors"
            }
        }
        configureColumnWidths()

        val scrollPane = JBScrollPane(
            table,
            JBScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
        )
        val tableHeight = table.rowHeight * BracketColorPalette.COLOR_COUNT +
            table.tableHeader.preferredSize.height + JBUI.scale(2)
        preferredSize = Dimension(JBUI.scale(PREFERRED_WIDTH), tableHeight)
        minimumSize = Dimension(JBUI.scale(MINIMUM_WIDTH), tableHeight)
        maximumSize = Dimension(JBUI.scale(MAXIMUM_WIDTH), tableHeight)
        add(scrollPane, BorderLayout.CENTER)
    }

    fun setColor(level: Int, component: PaletteComponent, color: Color) {
        require(level in colors.indices)
        colors[level][component.ordinal] = color
        model.fireTableCellUpdated(level, component.ordinal + 1)
    }

    fun color(level: Int, component: PaletteComponent): Color {
        require(level in colors.indices)
        return colors[level][component.ordinal]
    }

    fun refreshAvailability() {
        model.fireTableDataChanged()
        table.repaint()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        table.isEnabled = enabled
        table.tableHeader.isEnabled = enabled
        table.repaint()
    }

    private fun configureColumnWidths() {
        val widths = intArrayOf(LEVEL_COLUMN_WIDTH, 58, 62, 62, 86)
        for (index in widths.indices) {
            val width = widths[index]
            table.columnModel.getColumn(index).apply {
                preferredWidth = JBUI.scale(width)
                minWidth = JBUI.scale(if (index == 0) width else 46)
            }
        }
    }

    private inner class PaletteTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = BracketColorPalette.COLOR_COUNT

        override fun getColumnCount(): Int = PaletteComponent.entries.size + 1

        override fun getColumnName(column: Int): String = if (column == 0) {
            "Level"
        } else {
            PaletteComponent.entries[column - 1].displayName
        }

        override fun getColumnClass(column: Int): Class<*> = if (column == 0) {
            String::class.java
        } else {
            Color::class.java
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            return if (columnIndex == 0) {
                "${rowIndex + 1}"
            } else {
                colors[rowIndex][columnIndex - 1]
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            return columnIndex != 0 &&
                disabledReason(PaletteComponent.entries[columnIndex - 1]) == null
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val color = value as? Color ?: return
            val component = PaletteComponent.entries.getOrNull(columnIndex - 1) ?: return
            colors[rowIndex][component.ordinal] = color
            fireTableCellUpdated(rowIndex, columnIndex)
            onColorChanged(rowIndex, component, color)
        }
    }

    private inner class ColorRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            focused: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(
                table,
                "",
                selected,
                focused,
                row,
                column,
            )
            val color = value as? Color ?: return this
            val component = PaletteComponent.entries[column - 1]
            val reason = disabledReason(component)
            val editable = reason == null && table.isEnabled
            val swatchColor = if (editable) {
                color
            } else {
                blend(color, background, DISABLED_COLOR_PERCENT)
            }
            horizontalAlignment = CENTER
            icon = ColorIcon(
                JBUI.scale(SWATCH_WIDTH),
                JBUI.scale(SWATCH_HEIGHT),
                swatchColor,
                true,
            )
            // A disabled JLabel drops ColorIcon entirely on some platform LAFs.
            // Editability is enforced by the model; a muted icon keeps the
            // inherited effective color visible, matching disabled IDE controls.
            isEnabled = true
            toolTipText = buildString {
                append("Level ${row + 1} ${component.displayName.lowercase()} color: ")
                append(color.toHex())
                if (reason != null) {
                    append(" — $reason")
                } else {
                    append(" — Click to edit")
                }
            }
            getAccessibleContext()?.apply {
                accessibleName =
                    "Level ${row + 1} ${component.displayName} color ${color.toHex()}"
                accessibleDescription = this@ColorRenderer.toolTipText
            }
            return this
        }
    }

    private inner class ColorEditor : AbstractCellEditor(), TableCellEditor {
        private val editorComponent = JBLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        private var selectedColor = Color.BLACK

        override fun getCellEditorValue(): Any = selectedColor

        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            selected: Boolean,
            row: Int,
            column: Int,
        ): Component {
            selectedColor = value as? Color ?: Color.BLACK
            val paletteComponent = PaletteComponent.entries[column - 1]
            editorComponent.icon = ColorIcon(
                JBUI.scale(SWATCH_WIDTH),
                JBUI.scale(SWATCH_HEIGHT),
                selectedColor,
                true,
            )
            SwingUtilities.invokeLater {
                if (!table.isEditing || table.editingRow != row || table.editingColumn != column) {
                    return@invokeLater
                }
                val title = "Choose Level ${row + 1} " +
                    "${paletteComponent.displayName.lowercase()} color"
                val result = chooseColor(table, title, selectedColor)
                if (result == null) {
                    cancelCellEditing()
                } else {
                    selectedColor = result
                    stopCellEditing()
                }
            }
            return editorComponent
        }
    }

    companion object {
        private const val MINIMUM_WIDTH = 350
        private const val PREFERRED_WIDTH = 380
        private const val MAXIMUM_WIDTH = 420
        private const val LEVEL_COLUMN_WIDTH = 54
        private const val SWATCH_WIDTH = 24
        private const val SWATCH_HEIGHT = 12
        private const val DISABLED_COLOR_PERCENT = 45

        private fun Color.toHex(): String = "#%02X%02X%02X".format(red, green, blue)

        private fun blend(foreground: Color, background: Color, percent: Int): Color {
            val foregroundWeight = percent.coerceIn(0, 100)
            val backgroundWeight = 100 - foregroundWeight
            return Color(
                (foreground.red * foregroundWeight +
                    background.red * backgroundWeight) / 100,
                (foreground.green * foregroundWeight +
                    background.green * backgroundWeight) / 100,
                (foreground.blue * foregroundWeight +
                    background.blue * backgroundWeight) / 100,
            )
        }
    }
}

internal enum class PaletteComponent(val displayName: String) {
    BASE("Base"),
    GUIDE("Guide"),
    BORDER("Border"),
    BACKGROUND("Background"),
}
