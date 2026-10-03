package com.orangetiger.otstudio

import android.content.Context
import android.net.ConnectivityManager
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** The model manager: download, pause, resume, choose, and delete on-device chat models. */
class ModelsScreen(private val activity: MainActivity, private val ui: Ui, private val app: StudioApp, private val onBack: () -> Unit) {
    private lateinit var list: LinearLayout
    private lateinit var space: android.widget.TextView

    fun show(root: LinearLayout) {
        val bar = ui.row().apply { setPadding(0, ui.dp(4), 0, ui.dp(6)) }
        bar.addView(ui.iconButton(R.drawable.ic_arrow_back, "Back") { onBack() })
        bar.addView(ui.text("On-device models", Ui.Type.TITLE), ui.weight())
        root.addView(bar)
        val scroll = ScrollView(activity)
        val content = ui.column()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.text("Models download from Hugging Face into this app's private storage. Chat with them works offline, and nothing you type leaves the phone. Each download is large, so use Wi-Fi.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, ui.dp(4)) })
        space = ui.text("", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, ui.dp(10)) }
        content.addView(space)
        list = ui.column()
        content.addView(list)
        val add = ui.card(content)
        ui.sectionTitle(add, "Add a model from Hugging Face", "Open the model's Files page, tap the .gguf file, and copy its download link. Choose a Q4_K_M file of about 2 to 5 GB.")
        val link = ui.field(add, "Link to a .gguf file", "", helper = "Starts with https://huggingface.co/")
        val note = ui.text("", Ui.Type.BODY, ui.orange)
        add.addView(ui.tonal("Add to the list", R.drawable.ic_download) {
            try { app.local.addCustom(link.text.toString()); link.setText(""); note.text = "Added. Tap Download on its card."; render() }
            catch (e: UserError) { note.text = e.message }
        }, LinearLayout.LayoutParams(-2, -2))
        add.addView(note)
        render()
    }

    fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        space.text = "Free space on this phone: ${ModelDownloader.gb(app.local.freeBytes())}"
        val selected = app.local.selected()
        val running = app.download?.running == true
        if (running) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        app.local.all().forEach { m -> modelCard(m, m.localName == selected?.localName) }
    }

    private fun modelCard(m: LocalModelInfo, inUse: Boolean) {
        val card = ui.card(list, 14)
        val head = ui.row()
        head.addView(ui.bold(ui.text(m.title, Ui.Type.SUBTITLE)), ui.weight())
        if (inUse) head.addView(ui.pill("In use", R.color.ot_surface_high, R.color.ot_success))
        card.addView(head)
        val facts = listOfNotNull(if (m.bytes > 0) ModelDownloader.gb(m.bytes) else "Size shown when the download starts", m.license.ifBlank { null }, m.repo).joinToString("  |  ")
        card.addView(ui.text(facts, Ui.Type.CAPTION, ui.muted).apply { maxLines = 2 })
        if (m.note.isNotBlank()) card.addView(ui.text(m.note, Ui.Type.BODY).apply { setPadding(0, ui.dp(4), 0, 0) })
        val d = app.download?.takeIf { it.model.localName == m.localName }
        val partial = app.local.part(m).let { if (it.exists()) it.length() else 0L }
        val total = d?.total?.takeIf { it > 0 } ?: m.bytes
        fun amount(done: Long) = if (total > 0) "${ModelDownloader.gb(done)} of ${ModelDownloader.gb(total)}" else ModelDownloader.gb(done)
        val buttons = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
        when {
            app.local.installed(m) -> {
                if (!inUse) buttons.addView(ui.tonal("Use this model") { choose(m) })
                buttons.addView(ui.danger("Delete", R.drawable.ic_delete) { confirmDelete(m) })
            }
            d?.running == true -> {
                val bar = LinearProgressIndicator(activity).apply {
                    setIndicatorColor(ui.orange); trackColor = ui.color(R.color.ot_surface_high)
                    if (d.verifying || total <= 0) isIndeterminate = true else { max = 1000; progress = (d.done * 1000 / total).toInt() }
                }
                card.addView(bar, ui.matchWrap().apply { topMargin = ui.dp(10) })
                card.addView(ui.text(if (d.verifying) "Checking the file. This takes about a minute." else "Downloading ${amount(d.done)}", Ui.Type.SMALL, ui.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
                if (!d.verifying) buttons.addView(ui.outlined("Pause") { app.pauseDownload() })
            }
            partial > 0 || d != null -> {
                card.addView(ui.text("Paused at ${amount(maxOf(partial, d?.done ?: 0))}", Ui.Type.SMALL, ui.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
                d?.error?.takeIf { it.isNotBlank() }?.let { card.addView(ui.text(it, Ui.Type.BODY, ui.color(R.color.ot_error))) }
                buttons.addView(ui.tonal("Resume", R.drawable.ic_download) { start(m) })
                buttons.addView(ui.danger("Delete", R.drawable.ic_delete) { confirmDelete(m) })
            }
            else -> {
                buttons.addView(ui.tonal(if (m.bytes > 0) "Download (${ModelDownloader.gb(m.bytes)})" else "Download", R.drawable.ic_download) { start(m) })
                if (m.sha256.isEmpty()) buttons.addView(ui.danger("Remove") { app.local.removeCustom(m); render() })
            }
        }
        card.addView(buttons)
    }

    private fun choose(m: LocalModelInfo) {
        try { app.selectModel(m) } catch (e: UserError) { error(e.message.orEmpty()) }
    }

    private fun start(m: LocalModelInfo) {
        val metered = (activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: true
        val go = { try { app.startDownload(m) } catch (e: UserError) { error(e.message.orEmpty()) } }
        if (!metered) { go(); return }
        MaterialAlertDialogBuilder(activity).setTitle("Download on mobile data?")
            .setMessage("This phone is not on Wi-Fi. The model is ${if (m.bytes > 0) ModelDownloader.gb(m.bytes) else "several gigabytes"}, which can use up a data plan. Keep OT Studio open until the download finishes.")
            .setPositiveButton("Download anyway") { _, _ -> go() }.setNegativeButton("Wait for Wi-Fi", null).show()
    }

    private fun confirmDelete(m: LocalModelInfo) {
        MaterialAlertDialogBuilder(activity).setTitle("Delete ${m.title}?")
            .setMessage("This frees ${if (m.bytes > 0) ModelDownloader.gb(m.bytes) else "its space"} on the phone. You can download it again later.")
            .setPositiveButton("Delete") { _, _ -> try { app.deleteModel(m) } catch (e: UserError) { error(e.message.orEmpty()) } }
            .setNegativeButton("Keep", null).show()
    }

    private fun error(message: String) {
        MaterialAlertDialogBuilder(activity).setTitle("OT Studio").setMessage(message).setPositiveButton("OK", null).show()
    }

    fun leave() { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}
