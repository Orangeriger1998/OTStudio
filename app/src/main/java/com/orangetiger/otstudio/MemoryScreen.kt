package com.orangetiger.otstudio

import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Memory: standing instructions that open every chat. Each one can be switched off without deleting it. */
class MemoryScreen(private val activity: MainActivity, private val ui: Ui, private val app: StudioApp, private val onBack: () -> Unit) {
    private lateinit var list: LinearLayout

    fun show(root: LinearLayout) {
        val bar = ui.row().apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) }
        bar.addView(ui.iconButton(R.drawable.ic_arrow_back, "Back") { onBack() })
        bar.addView(ui.text("Memory", Ui.Type.TITLE), ui.weight())
        root.addView(bar)
        val scroll = ScrollView(activity)
        val content = ui.column()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.text("Memory holds standing instructions. Every chat starts with the ones that are switched on, so you never have to repeat them.", Ui.Type.BODY, ui.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
        content.addView(ui.text("With On this phone, memory stays on the phone. When Chat uses OpenRouter, the lines that are on go to OpenRouter with every message.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, ui.dp(12)) })
        content.addView(ui.filled("Add instruction", R.drawable.ic_auto_awesome) { edit(null) }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = ui.dp(12) })
        list = ui.column()
        content.addView(list)
        render()
    }

    fun render() {
        list.removeAllViews()
        val lines = app.history.memory.lines()
        if (lines.isEmpty()) {
            val card = ui.card(list, 16, R.color.ot_surface_container)
            card.addView(ui.text("No instructions yet. Two examples:", Ui.Type.BODY))
            card.addView(ui.text("Call the user Mark.\nDo not use em dashes in your responses.", Ui.Type.BODY, ui.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
            return
        }
        lines.forEach { line ->
            val card = ui.card(list, 14, if (line.enabled) R.color.ot_surface_container else R.color.ot_surface_high, 8)
            val row = ui.row()
            row.addView(ui.text(line.text, Ui.Type.BODY_LARGE, if (line.enabled) ui.onSurface else ui.muted).apply { setTextIsSelectable(true) }, ui.weight())
            row.addView(MaterialSwitch(activity).apply {
                isChecked = line.enabled
                contentDescription = if (line.enabled) "Instruction on" else "Instruction off"
                setOnCheckedChangeListener { _, on -> app.history.memory.setEnabled(line.id, on); render() }
            })
            card.addView(row)
            card.addView(activity.actions(
                ui.textButton("Edit") { edit(line) },
                ui.danger("Delete", R.drawable.ic_delete) {
                    MaterialAlertDialogBuilder(activity).setTitle("Delete this instruction?").setMessage(line.text)
                        .setPositiveButton("Delete") { _, _ -> app.history.memory.delete(line.id); render() }.setNegativeButton("Keep", null).show()
                }))
        }
    }

    private fun edit(line: MemoryLine?) {
        val content = ui.column(20)
        val field = ui.field(content, "Instruction", line?.text.orEmpty(), multiline = true, helper = "For example: Do not use em dashes in your responses.")
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (line == null) "Add instruction" else "Edit instruction").setView(content)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    if (line == null) app.history.memory.add(field.text.toString()) else app.history.memory.edit(line.id, field.text.toString())
                    dialog.dismiss(); render()
                } catch (e: UserError) { ui.layoutOf(field)?.error = e.message }
            }
        }
        dialog.show()
    }
}
