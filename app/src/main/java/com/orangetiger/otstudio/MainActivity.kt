@file:Suppress("DEPRECATION")

package com.orangetiger.otstudio

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    companion object {
        const val REPO = "github.com/Orangeriger1998/OTStudio"
        // While the repository is private, the wiki lives in its wiki folder. Point this at /wiki when it goes public.
        const val WIKI = "https://github.com/Orangeriger1998/OTStudio/tree/main/wiki"
        const val OPENROUTER_KEYS = "https://openrouter.ai/settings/keys"
        const val REPLICATE_TOKENS = "https://replicate.com/account/api-tokens"
    }
    private val app get() = application as StudioApp
    private lateinit var ui: Ui
    private lateinit var host: FrameLayout
    private lateinit var screen: FrameLayout
    private lateinit var feed: LinearLayout
    private lateinit var statusArea: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var prompt: TextInputEditText
    private lateinit var send: MaterialButton
    private lateinit var setupTitle: TextView
    private lateinit var setupSummary: TextView
    private var mode = Mode.CHAT
    private var settingsOpen = false
    private var settingsMode: Mode? = null
    private var helpOpen = false
    private var callPanel: CallPanel? = null
    private var lastSent = ""
    private val drafts = mutableMapOf<Mode, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var renderGeneration = 0
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private var pendingPhoto: ((Uri?) -> Unit)? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pendingPhoto?.invoke(uri); pendingPhoto = null }
    private var pendingFolder: ((Uri?) -> Unit)? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pendingFolder?.invoke(uri); pendingFolder = null }
    private val loraPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importLora) }

    private val poll = object : Runnable {
        override fun run() {
            if (!settingsOpen && !app.isBusy() && app.lastError.isEmpty()) {
                app.history.list(pendingOnly = true).firstOrNull { it.state == "processing" && it.remoteId.isNotBlank() }?.let { app.refresh(it) }
            }
            handler.postDelayed(this, 6000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        host = FrameLayout(this).apply { setBackgroundColor(ui.color(R.color.ot_black)) }
        screen = FrameLayout(this)
        host.addView(screen, FrameLayout.LayoutParams(-1, -1))
        setContentView(host)
        // Draw edge to edge on every Android version, then pad once for the status bar, navigation bar, and keyboard.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(screen) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        mode = savedInstanceState?.getString("mode")?.let { Mode.valueOf(it) } ?: Mode.CHAT
        lastSent = savedInstanceState?.getString("lastSent").orEmpty()
        Mode.entries.forEach { drafts[it] = savedInstanceState?.getString("draft.${it.name}").orEmpty() }
        val reopen = savedInstanceState?.getString("settings")?.let { Mode.valueOf(it) }
        when {
            reopen != null -> showSettings(reopen)
            savedInstanceState?.getBoolean("help") == true -> showHelp()
            else -> showChat()
        }
        if (savedInstanceState == null) showSplash()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsOpen || helpOpen) showChat() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        app.listener = { if (!settingsOpen) renderFeed() }
        handler.post(poll)
        if (!settingsOpen) renderFeed()
    }
    override fun onPause() { super.onPause(); app.listener = null; handler.removeCallbacks(poll) }
    override fun onDestroy() { callPanel?.dismiss(); callPanel = null; io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (!settingsOpen && ::prompt.isInitialized) drafts[mode] = prompt.text.toString()
        outState.putString("mode", mode.name)
        outState.putString("lastSent", lastSent)
        Mode.entries.forEach { outState.putString("draft.${it.name}", drafts[it]) }
        settingsMode?.let { outState.putString("settings", it.name) }
        outState.putBoolean("help", helpOpen)
        super.onSaveInstanceState(outState)
    }

    private fun dp(n: Int) = ui.dp(n)
    private fun appVersion(): String = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
    private fun page(): LinearLayout {
        val root = ui.column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        screen.removeAllViews(); screen.addView(root, FrameLayout.LayoutParams(-1, -1))
        return root
    }
    private fun snack(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return
        Snackbar.make(screen, message, Snackbar.LENGTH_LONG).apply { if (action != null && onAction != null) setAction(action) { onAction() } }.show()
    }

    /** Full OT Studio logo for a moment after the system splash (Android 12+ only allows an icon there). */
    private fun showSplash() {
        val splash = FrameLayout(this).apply { setBackgroundColor(ui.color(R.color.ot_black)); isClickable = true; contentDescription = "OT Studio" }
        val logo = ImageView(this).apply { setImageResource(R.drawable.splash_logo); adjustViewBounds = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val width = minOf(dp(340), (resources.displayMetrics.widthPixels * 0.82).toInt())
        splash.addView(logo, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        host.addView(splash, FrameLayout.LayoutParams(-1, -1))
        splash.postDelayed({ splash.animate().alpha(0f).setDuration(350).withEndAction { host.removeView(splash) }.start() }, 900)
    }

    private fun showChat() {
        settingsOpen = false
        settingsMode = null
        helpOpen = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = page()
        val bar = ui.row().apply { setPadding(0, dp(4), 0, dp(10)) }
        bar.addView(ui.avatar(36))
        bar.addView(ui.bold(ui.text("OT Studio", Ui.Type.TITLE, ui.orange)).apply { setPadding(dp(12), 0, 0, 0) }, ui.weight())
        bar.addView(ui.iconButton(R.drawable.ic_help, "Help") { drafts[mode] = prompt.text.toString(); showHelp() })
        bar.addView(ui.iconButton(R.drawable.ic_collections_bookmark, "LoRA library") { drafts[mode] = prompt.text.toString(); showLoraLibrary() })
        bar.addView(ui.iconButton(R.drawable.ic_settings, "Settings") { drafts[mode] = prompt.text.toString(); showSettings(mode) })
        root.addView(bar)
        ui.segmented(root, listOf("Chat" to R.drawable.ic_chat, "Image" to R.drawable.ic_image, "Video" to R.drawable.ic_movie), mode.ordinal) { i ->
            drafts[mode] = prompt.text.toString(); mode = Mode.entries[i]; showChat()
        }
        val setup = ui.card(root, padding = 14, bottom = 8)
        val setupRow = ui.row()
        val names = ui.column()
        setupTitle = ui.text("", Ui.Type.SUBTITLE).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        setupSummary = ui.text("", Ui.Type.SMALL, ui.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        names.addView(setupTitle); names.addView(setupSummary)
        setupRow.addView(names, ui.weight())
        setupRow.addView(ui.tonal("API CALL", R.drawable.ic_tune) { openCallPanel() }.apply { contentDescription = "API CALL: choose models and generation inputs" })
        setup.addView(setupRow)
        statusArea = ui.column()
        root.addView(statusArea)
        scroller = ScrollView(this).apply { isFillViewport = true }
        feed = ui.column()
        scroller.addView(feed)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val composer = ui.row().apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.BOTTOM }
        val field = ui.column()
        prompt = ui.field(field, when (mode) { Mode.CHAT -> "Message"; Mode.IMAGE -> "Describe an image"; Mode.VIDEO -> "Describe the scene and its motion" }, drafts[mode].orEmpty(), multiline = true)
        prompt.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        prompt.maxLines = 5
        ui.layoutOf(prompt)?.setBoxCornerRadii(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat())
        (field.getChildAt(0).layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        composer.addView(field, ui.weight())
        send = ui.iconButton(R.drawable.ic_send, if (mode == Mode.CHAT) "Send" else "Generate ${mode.title.lowercase()}", filled = true) {
            try {
                val value = prompt.text.toString()
                if (app.send(mode, value)) { lastSent = value.trim(); renderFeed() }
            } catch (e: UserError) { showError(e.message.orEmpty()) }
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(8); bottomMargin = dp(2) } }
        composer.addView(send)
        root.addView(composer)
        renderFeed()
    }

    private fun openCallPanel() {
        drafts[mode] = prompt.text.toString()
        settingsOpen = true
        callPanel = CallPanel(this, app, mode, prompt.text.toString(),
            { selected -> mode = selected; showChat() }, { selected -> showSettings(selected) },
            { settingsOpen = false; renderFeed() }).also { it.show() }
    }

    private fun stateLabel(state: String) = when (state) {
        "succeeded" -> "Done"; "processing" -> "Working"; "submitting" -> "Sending"; "chatting" -> "Thinking"; "download" -> "Saving"
        "canceling" -> "Canceling"; "failed" -> "Failed"; "uncertain" -> "Check dashboard"; "interrupted" -> "Interrupted"
        "untracked" -> "Not tracked"; "canceled" -> "Canceled"; "unavailable" -> "Download skipped"; else -> state
    }
    private fun statePill(state: String) = when (state) {
        "succeeded" -> ui.pill(stateLabel(state), R.color.ot_surface_high, R.color.ot_success)
        "processing", "submitting", "chatting", "download", "canceling" -> ui.pill(stateLabel(state), R.color.ot_orange_container, R.color.ot_on_orange_container)
        "failed", "uncertain", "interrupted", "untracked" -> ui.pill(stateLabel(state), R.color.ot_error_container, R.color.ot_on_error_container)
        else -> ui.pill(stateLabel(state), R.color.ot_surface_high, R.color.ot_on_surface_variant)
    }
    private fun actionRow(vararg buttons: View?): View {
        val row = ui.row()
        buttons.filterNotNull().forEach { row.addView(it) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun renderFeed() {
        if (!::feed.isInitialized || settingsOpen || isFinishing || isDestroyed) return
        renderGeneration++
        val generation = renderGeneration
        val previousY = scroller.scrollY
        val entries = app.history.list(mode)
        val outputMap = app.history.outputs(entries.map { it.id })
        val s = app.settings.get(mode)
        setupTitle.text = s.model
        setupSummary.text = if (app.isBusy()) "Working..." else ModelOptions.summary(s.model, s.options).ifBlank { "Provider defaults" }
        send.isEnabled = !app.isBusy()
        if (!app.isBusy() && lastSent.isNotEmpty() && entries.lastOrNull()?.let { it.prompt == lastSent && it.state in listOf("succeeded", "processing", "download") } == true) {
            if (prompt.text.toString().trim() == lastSent) prompt.setText("")
            lastSent = ""
        }
        statusArea.removeAllViews()
        if (app.isBusy()) {
            statusArea.addView(ui.progress(), ui.matchWrap(2))
            val r = ui.row(); r.addView(ui.text("Working...", Ui.Type.SMALL, ui.muted), ui.weight())
            r.addView(ui.textButton("Stop waiting", R.drawable.ic_stop_circle) { app.stopWaiting() }); statusArea.addView(r)
        }
        if (app.lastError.isNotEmpty()) ui.card(statusArea, 12, R.color.ot_error_container, 8).addView(ui.text(app.lastError, Ui.Type.BODY, ui.color(R.color.ot_on_error_container)))
        feed.removeAllViews()
        if (entries.isEmpty()) emptyState()
        entries.forEach { e -> if (e.mode == Mode.CHAT) chatEntry(e) else mediaEntry(e, outputMap[e.id].orEmpty(), entries.takeLast(3).any { it.id == e.id }, generation) }
        scroller.post { if (lastSent.isNotEmpty()) scroller.fullScroll(View.FOCUS_DOWN) else scroller.scrollTo(0, previousY) }
    }

    private fun emptyState() {
        val box = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(36), dp(8), dp(8)) }
        box.addView(ui.avatar(96))
        ui.gap(box, 16)
        box.addView(ui.text(when (mode) { Mode.CHAT -> "Start with an idea."; Mode.IMAGE -> "Make something worth keeping."; Mode.VIDEO -> "Give your idea a little motion." }, Ui.Type.HEADLINE).apply { gravity = Gravity.CENTER })
        ui.gap(box, 8)
        box.addView(ui.text("Pick a model with API CALL and add its key in Settings. Then write a prompt below.", Ui.Type.BODY, ui.muted).apply { gravity = Gravity.CENTER })
        if (mode != Mode.CHAT) { ui.gap(box, 8); box.addView(ui.text("Each Generate is one request to your provider at its normal price.", Ui.Type.SMALL, ui.muted).apply { gravity = Gravity.CENTER }) }
        if (!app.settings.hasKey(Mode.CHAT) && !app.settings.hasKey(Mode.IMAGE)) { ui.gap(box, 12); box.addView(ui.tonal("How to get started", R.drawable.ic_help) { showHelp() }) }
        feed.addView(box, ui.matchWrap())
    }

    private fun bubble(text: String, mine: Boolean, error: Boolean = false): View {
        val holder = LinearLayout(this).apply { gravity = if (mine) Gravity.END else Gravity.START }
        val tone = when { error -> R.color.ot_error_container; mine -> R.color.ot_orange_container; else -> R.color.ot_surface_high }
        val fg = when { error -> R.color.ot_on_error_container; mine -> R.color.ot_on_orange_container; else -> R.color.ot_on_surface }
        val t = ui.text(text, Ui.Type.BODY_LARGE, ui.color(fg)).apply {
            setTextIsSelectable(true); setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ui.color(tone)); val r = dp(20).toFloat(); val s = dp(6).toFloat()
                cornerRadii = if (mine) floatArrayOf(r, r, s, s, r, r, r, r) else floatArrayOf(s, s, r, r, r, r, r, r)
            }
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
        }
        holder.addView(t)
        return holder
    }

    private fun chatEntry(e: Entry) {
        feed.addView(bubble(e.prompt, mine = true), ui.matchWrap(6))
        val failed = e.state in listOf("failed", "uncertain", "interrupted")
        if (e.response.isNotEmpty()) feed.addView(bubble(e.response, mine = false, error = failed), ui.matchWrap(4))
        val meta = ui.row()
        meta.addView(statePill(e.state)); meta.addView(ui.text("  ${e.model}", Ui.Type.CAPTION, ui.muted), ui.weight())
        meta.addView(ui.textButton("Use prompt") { prompt.setText(e.prompt); prompt.requestFocus() })
        feed.addView(meta, ui.matchWrap(14))
    }

    private fun mediaEntry(e: Entry, outputs: List<MediaOutput>, recent: Boolean, generation: Int) {
        val card = ui.card(feed, padding = 14)
        card.addView(ui.text(e.prompt, Ui.Type.BODY_LARGE).apply { setTextIsSelectable(true) })
        ui.gap(card, 6)
        val meta = ui.row(); meta.addView(statePill(e.state)); meta.addView(ui.text("  ${e.model}", Ui.Type.CAPTION, ui.muted).apply { maxLines = 1 }, ui.weight())
        card.addView(meta)
        if (e.requestSummary.isNotEmpty()) card.addView(ui.text(e.requestSummary, Ui.Type.SMALL, ui.muted).apply { setPadding(0, dp(6), 0, 0) })
        if (e.response.isNotEmpty()) {
            val bad = e.state in listOf("failed", "uncertain", "interrupted", "untracked")
            card.addView(ui.text(e.response, Ui.Type.BODY, if (bad) ui.color(R.color.ot_error) else ui.onSurface).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, 0) })
        }
        if (e.remoteId.isNotEmpty()) card.addView(ui.text("Job ${e.remoteId}", Ui.Type.CAPTION, ui.muted).apply { setTextIsSelectable(true); setPadding(0, dp(4), 0, 0) })
        outputs.filter { it.localFile.isNotEmpty() }.forEach { output ->
            val mediaEntry = e.copy(localFile = output.localFile)
            val file = localMedia(mediaEntry)
            if (outputs.size > 1) card.addView(ui.text("Output ${output.position + 1}", Ui.Type.CAPTION, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
            if (file.exists() && e.mode == Mode.IMAGE && recent) {
                val image = ShapeableImageView(this).apply {
                    contentDescription = "Generated image: ${e.prompt}"; adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(dp(16).toFloat()).build()
                    setOnClickListener { previewImage(file) }
                }
                card.addView(image, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(10) })
                io.execute {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 768 || bounds.outHeight / inSampleSize > 768) inSampleSize *= 2 }
                    val bitmap = BitmapFactory.decodeFile(file.path, options)
                    runOnUiThread { if (generation == renderGeneration && !isFinishing && !isDestroyed) image.setImageBitmap(bitmap) else bitmap?.recycle() }
                }
            }
            card.addView(actionRow(
                if (e.mode == Mode.IMAGE) ui.textButton("View", R.drawable.ic_visibility) { previewImage(file) } else ui.textButton("Play", R.drawable.ic_play_arrow) { previewVideo(file) },
                ui.textButton("Save", R.drawable.ic_download) { saveMedia(mediaEntry) },
                ui.textButton("Share", R.drawable.ic_share) { shareMedia(mediaEntry) },
                if (e.mode == Mode.IMAGE) ui.textButton("Use as...", R.drawable.ic_auto_awesome) { useAs(file) } else null,
                if (e.mode == Mode.IMAGE) ui.textButton("Upscale 2x", R.drawable.ic_zoom_in) { upscale(e, file) } else null
            ))
        }
        val busy = app.isBusy()
        card.addView(actionRow(
            ui.textButton("Use prompt") { prompt.setText(e.prompt); prompt.requestFocus() },
            if (e.state == "processing") ui.textButton("Refresh", R.drawable.ic_refresh) { app.refresh(e) }.apply { isEnabled = !busy } else null,
            if (e.state == "processing") ui.textButton("Cancel") { app.refresh(e, true) }.apply { isEnabled = !busy } else null,
            if (e.state == "processing") ui.textButton("Stop tracking") { confirmStopTracking(e) }.apply { isEnabled = !busy } else null,
            if (e.state in listOf("download", "unavailable")) ui.textButton("Download again", R.drawable.ic_refresh) { app.refresh(e) }.apply { isEnabled = !busy } else null,
            if (e.state == "download") ui.textButton("Skip download") { app.skipDownload(e) }.apply { isEnabled = !busy } else null
        ))
    }

    private fun confirmStopTracking(e: Entry) {
        MaterialAlertDialogBuilder(this).setTitle("Stop tracking this job?")
            .setMessage("Use this when a job cannot be refreshed or canceled. It frees the app for new media jobs. It does not cancel the job at the provider, which may still run and charge.")
            .setPositiveButton("Stop tracking") { _, _ -> app.stopTracking(e) }.setNegativeButton("Keep", null).show()
    }

    private fun showSettings(selected: Mode) {
        settingsOpen = true
        settingsMode = selected
        // Release builds block screenshots of Settings so keys cannot be captured; debug builds allow them for documentation.
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = page()
        val bar = ui.row().apply { setPadding(0, dp(4), 0, dp(6)) }
        bar.addView(ui.iconButton(R.drawable.ic_arrow_back, "Back") { showChat() })
        bar.addView(ui.text("Settings", Ui.Type.TITLE), ui.weight())
        bar.addView(ui.textButton("Done") { showChat() })
        root.addView(bar)
        val scroll = ScrollView(this)
        val content = ui.column()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.text("Models and keys stay on this device.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, dp(10)) })
        ui.segmented(content, listOf("Chat" to R.drawable.ic_chat, "Image" to R.drawable.ic_image, "Video" to R.drawable.ic_movie), selected.ordinal) { i -> showSettings(Mode.entries[i]) }
        val s = app.settings.get(selected)
        val provider = ui.card(content)
        ui.sectionTitle(provider, if (selected == Mode.CHAT) "OpenRouter, Chat Completions" else "Replicate, Predictions", "A new provider must speak this API protocol. Changing a URL alone does not add a different protocol.")
        val url = ui.field(provider, "API base URL", s.baseUrl)
        val model = ui.field(provider, "Model ID", s.model, helper = if (selected == Mode.CHAT) "Default: gryphe/mythomax-l2-13b. Enter the exact provider ID for another model." else null)
        ui.dropdown(provider, "Saved models", listOf("Keep current") + app.settings.models(selected, s.baseUrl).map { it.model }, "Keep current") { if (it != "Keep current") model.setText(it) }
        val keyCard = ui.card(content)
        ui.sectionTitle(keyCard, "API key", "OpenRouter and Replicate keys are different. Reuse works only when the API origins match.")
        var source = s.keySource
        val useText = if (selected != Mode.CHAT) ui.checkbox("Use text LLM API key", source == KeySource.TEXT).also { keyCard.addView(it) } else null
        val useImage = if (selected == Mode.VIDEO) ui.checkbox("Use image API key", source == KeySource.IMAGE).also { keyCard.addView(it) } else null
        val savedHelper = { if (app.settings.hasKey(selected)) "Saved securely. Leave blank to keep." else "Enter this provider's API key." }
        val key = ui.field(keyCard, "API key", "", secret = true, helper = savedHelper())
        fun keyEnabled() { key.isEnabled = source == KeySource.OWN; ui.layoutOf(key)?.isEnabled = source == KeySource.OWN }
        keyEnabled()
        useText?.setOnCheckedChangeListener { _, checked ->
            if (checked) { source = KeySource.TEXT; useImage?.isChecked = false } else if (source == KeySource.TEXT) source = KeySource.OWN
            keyEnabled()
        }
        useImage?.setOnCheckedChangeListener { _, checked ->
            if (checked) { source = KeySource.IMAGE; useText?.isChecked = false } else if (source == KeySource.IMAGE) source = KeySource.OWN
            keyEnabled()
        }
        val note = ui.text("", Ui.Type.BODY, ui.orange).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val actions = ui.row()
        actions.addView(ui.filled("Save ${selected.title.lowercase()} settings") {
            try {
                val updated = app.settings.forModel(s.copy(baseUrl = url.text.toString().trim().trimEnd('/'), keySource = source), model.text.toString().trim())
                Rules.validate(updated)
                if (source != KeySource.OWN) {
                    val target = if (source == KeySource.TEXT) Mode.CHAT else Mode.IMAGE
                    if (Rules.origin(updated.baseUrl) != Rules.origin(app.settings.get(target).baseUrl)) throw UserError("The selected providers use different keys. Uncheck reuse and enter this provider's key.")
                }
                val enteredKey = key.text.toString().takeIf { it.isNotBlank() && source == KeySource.OWN }
                app.settings.save(updated, enteredKey)
                key.setText(""); ui.layoutOf(key)?.helperText = savedHelper()
                note.text = "Saved."
            } catch (e: UserError) { note.text = e.message }
        }, ui.weight())
        actions.addView(ui.danger("Remove key", R.drawable.ic_delete) {
            MaterialAlertDialogBuilder(this).setTitle("Remove API key?").setMessage("This removes the device's saved key. It does not revoke the key at your provider.")
                .setPositiveButton("Remove") { _, _ ->
                    try { app.settings.save(app.settings.get(selected), ""); key.setText(""); ui.layoutOf(key)?.helperText = savedHelper(); note.text = "Key removed." }
                    catch (e: UserError) { note.text = e.message }
                }.setNegativeButton("Keep", null).show()
        }.apply { contentDescription = "Remove saved ${selected.title.lowercase()} key" })
        content.addView(actions, ui.matchWrap(4))
        content.addView(note, ui.matchWrap(12))
        val downloads = ui.card(content)
        ui.sectionTitle(downloads, "Save folder", "Save puts images and videos here. Android remembers the folder, so you don't have to browse from the top each time.")
        downloads.addView(ui.text(savedTree()?.let(::folderName) ?: "Not chosen yet. You'll pick one the first time you tap Save.", Ui.Type.BODY))
        downloads.addView(ui.outlined("Choose folder", R.drawable.ic_folder_open) { pickFolder { uri -> if (uri != null) { rememberTree(uri); showSettings(selected) } } }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val about = ui.card(content)
        val header = ui.row()
        header.addView(ui.avatar(56))
        val who = ui.column().apply { setPadding(dp(14), 0, 0, 0) }
        who.addView(ui.bold(ui.text("OT Studio ${appVersion()}", Ui.Type.SUBTITLE)))
        who.addView(ui.text("Created by OrangeTiger", Ui.Type.BODY, ui.muted))
        who.addView(ui.text(REPO, Ui.Type.SMALL, ui.color(R.color.ot_blue)).apply {
            setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://${REPO}"))) } catch (_: Exception) { snack("No browser is available.") } }
        })
        header.addView(who, ui.weight())
        about.addView(header)
        ui.gap(about, 12)
        about.addView(ui.text("Free software under the GNU GPL, version 3. It comes with no warranty.\nAndroid 10 or later. No bundled model weights, ads, or analytics.\nHistory and generated media stay in private app storage until you save them.\nProvider generation has normal API costs. Video jobs refresh while the app is open and recover by job ID after reopening.\nPrivate media limit: 512 MB; per-file limit: 128 MB.", Ui.Type.SMALL, ui.muted))
    }

    private fun showLoraLibrary() {
        val entries = app.loras.list()
        val content = ui.column(20)
        content.addView(ui.text("Train on your computer with the included script, then import its .otstudio-lora.json file. The weights backup stays on your computer; this library keeps the provider reference used for generation.", Ui.Type.SMALL, ui.muted))
        ui.gap(content, 12)
        if (entries.isEmpty()) content.addView(ui.text("No saved LoRAs yet.", Ui.Type.BODY))
        entries.forEach { lora ->
            val c = ui.card(content, 12, R.color.ot_surface_high, 8)
            c.addView(ui.bold(ui.text(lora.name, Ui.Type.SUBTITLE)))
            c.addView(ui.text("Trigger: ${lora.trigger.ifBlank { "none" }}  |  scale ${lora.scale}", Ui.Type.SMALL, ui.muted))
            c.addView(ui.text(lora.reference, Ui.Type.CAPTION, ui.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        }
        MaterialAlertDialogBuilder(this).setTitle("LoRA library (${entries.size})").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Import file") { _, _ -> try { loraPicker.launch(arrayOf("*/*")) } catch (_: Exception) { showError("No Android file picker is available.") } }
            .setNeutralButton("Add manually") { _, _ -> editLora(null) }.setNegativeButton("Close", null).show()
    }

    private fun editLora(imported: SavedLora?) {
        val content = ui.column(20)
        val name = ui.field(content, "LoRA name", imported?.name.orEmpty())
        val reference = ui.field(content, "Replicate owner/model/version or HTTPS weights URL", imported?.reference.orEmpty())
        val trigger = ui.field(content, "Trigger word to use in your prompt", imported?.trigger.orEmpty())
        val scale = ui.field(content, "Default scale (-1 to 3)", (imported?.scale ?: 1.0).toString(), numeric = true, decimal = true)
        content.addView(ui.text("For FLUX dev models. Saving an existing reference updates its name, trigger, and scale. No provider call is made here.", Ui.Type.SMALL, ui.muted))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (imported == null) "Add LoRA" else "Review LoRA import").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val parsed = scale.text.toString().replace(',', '.').toDoubleOrNull() ?: throw UserError("Enter a numeric scale.")
                app.loras.save(SavedLora(name.text.toString().trim(), reference.text.toString().trim(), trigger.text.toString().trim(), parsed))
                dialog.dismiss(); showLoraLibrary()
            } catch (e: Exception) { showError((e as? UserError)?.message ?: "Could not save the library. Check device storage.") }
        } }
        dialog.show()
    }

    private fun importLora(uri: Uri) {
        io.execute {
            try {
                val raw = contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(2048)
                    while (true) {
                        val count = stream.read(chunk); if (count < 0) break
                        if (output.size() + count > LoraCodec.MAX_BYTES) throw UserError("Select the small .otstudio-lora.json file, not the weights archive.")
                        output.write(chunk, 0, count)
                    }; output.toString("UTF-8")
                } ?: throw UserError("Could not open the selected file.")
                val item = LoraCodec.parse(raw)
                runOnUiThread { if (!isFinishing && !isDestroyed) editLora(item) }
            } catch (e: Exception) { runOnUiThread { if (!isFinishing && !isDestroyed) showError((e as? UserError)?.message ?: "Could not import this LoRA library file.") } }
        }
    }

    /** Opens Android's photo picker; [done] receives the chosen photo or null. */
    fun pickPhoto(done: (Uri?) -> Unit) {
        pendingPhoto = done
        try { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { pendingPhoto = null; showError("No Android photo picker is available.") }
    }
    /** Reads a picked photo (off the main thread) as bytes, MIME type, and display name, within the upload limit. */
    fun readPhoto(uri: Uri): Triple<ByteArray, String, String> {
        val mime = contentResolver.getType(uri) ?: throw UserError("Android could not tell this photo's type.")
        if (mime !in Rules.UPLOAD_MIMES) throw UserError("Choose a JPG, PNG, or WebP photo.")
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "photo"
        val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it) } ?: throw UserError("Could not open the selected photo.")
        return Triple(bytes, mime, name)
    }
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16384)
        while (true) {
            val n = input.read(chunk); if (n < 0) break
            if (output.size() + n > Rules.MAX_UPLOAD_BYTES) throw UserError("Photos must be smaller than 20 MB.")
            output.write(chunk, 0, n)
        }
        return output.toByteArray()
    }

    /** Uploads a generated image and puts it into the chosen model input (reference, starting frame, or ending frame). */
    private fun useAs(file: File) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val roles = MediaRole.entries
        MaterialAlertDialogBuilder(this).setTitle("Use this image as...").setItems(roles.map { it.title }.toTypedArray()) { _, which ->
            val role = roles[which]
            val mime = when (file.extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/webp" }
            app.uploadPhoto(role.mode, { Triple(file.inputStream().use { readLimited(it) }, mime, file.name) }) { url, error ->
                if (isFinishing || isDestroyed) return@uploadPhoto
                if (url == null) { showError(error ?: "Could not upload the image."); return@uploadPhoto }
                try {
                    val current = app.settings.get(role.mode)
                    val updated = role.apply(current, url) { app.settings.forModel(current, it) }
                    app.settings.save(updated, null)
                    snack("${role.title} set on ${updated.model}.", "Open ${role.mode.title}") { drafts[mode] = prompt.text.toString(); mode = role.mode; showChat() }
                    renderFeed()
                } catch (e: UserError) { showError(e.message.orEmpty()) }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun upscale(e: Entry, file: File) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val mime = when (file.extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/webp" }
        try {
            if (app.upscale(e) { Triple(file.inputStream().use { readLimited(it) }, mime, file.name) }) { snack("Upscaling to twice the size. The result appears as a new card."); renderFeed() }
        } catch (ex: UserError) { showError(ex.message.orEmpty()) }
    }

    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { snack("No browser is available.") }
    }

    /** In-app help, read from assets/help.txt (the same text as the README quick start). */
    private fun showHelp() {
        settingsOpen = true
        settingsMode = null
        helpOpen = true
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = page()
        val bar = ui.row().apply { setPadding(0, dp(4), 0, dp(6)) }
        bar.addView(ui.iconButton(R.drawable.ic_arrow_back, "Back") { showChat() })
        bar.addView(ui.text("Help", Ui.Type.TITLE), ui.weight())
        root.addView(bar)
        val scroll = ScrollView(this)
        val content = ui.column()
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val logo = ImageView(this).apply { setImageResource(R.drawable.splash_logo); adjustViewBounds = true; contentDescription = "OT Studio" }
        content.addView(logo, LinearLayout.LayoutParams(dp(220), -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        val text = try { assets.open("help.txt").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        var card: LinearLayout? = null
        var title = ""
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            val lines = block.trim().lines()
            if (lines.isEmpty() || lines.first().isBlank()) continue
            var body = lines
            if (lines.first().startsWith("# ")) {
                title = lines.first().removePrefix("# ").trim()
                val fresh = ui.card(content)
                fresh.addView(ui.bold(ui.text(title, Ui.Type.SUBTITLE)).apply { setPadding(0, 0, 0, dp(6)) })
                card = fresh
                body = lines.drop(1)
            }
            val target = card ?: ui.card(content)
            card = target
            if (body.isNotEmpty()) target.addView(ui.text(body.joinToString("\n"), Ui.Type.BODY).apply { setPadding(0, 0, 0, dp(10)); setLineSpacing(dp(2).toFloat(), 1f) })
            if (title == "Get your keys" && body.any { it.contains("r8_") }) {
                val links = ui.row()
                links.addView(ui.tonal("OpenRouter keys", R.drawable.ic_open_in_new) { openLink(OPENROUTER_KEYS) }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) } })
                links.addView(ui.tonal("Replicate tokens", R.drawable.ic_open_in_new) { openLink(REPLICATE_TOKENS) })
                target.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(links) })
            }
            if (title == "Full guide" && body.isNotEmpty()) target.addView(ui.filled("Open the OT Studio wiki", R.drawable.ic_open_in_new) { openLink(WIKI) }, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun localMedia(e: Entry): File {
        val folder = File(filesDir, "media")
        val file = File(folder, e.localFile)
        if (file.parentFile?.canonicalPath != folder.canonicalPath) throw UserError("Invalid stored media path.")
        return file
    }
    private fun mimeOf(file: File) = when (file.extension) { "mp4" -> "video/mp4"; "png" -> "image/png"; "jpg" -> "image/jpeg"; else -> "image/webp" }

    private fun previewVideo(file: File) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val video = VideoView(this)
        val controls = MediaController(this)
        video.setMediaController(controls); controls.setAnchorView(video)
        video.setVideoURI(Uri.fromFile(file))
        video.setOnPreparedListener { video.start() }
        video.setOnErrorListener { _, _, _ -> showError("Android could not play this video. Save it to try another player."); true }
        MaterialAlertDialogBuilder(this).setTitle("Video preview").setView(video).setPositiveButton("Close", null).create().apply {
            setOnDismissListener { video.stopPlayback() }; show()
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(480))
        }
    }
    private fun previewImage(file: File) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        io.execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 1280 || bounds.outHeight / inSampleSize > 1280) inSampleSize *= 2 }
            val bitmap = BitmapFactory.decodeFile(file.path, options)
            runOnUiThread {
                if (isFinishing || isDestroyed) { bitmap?.recycle(); return@runOnUiThread }
                if (bitmap == null) { showError("Android could not decode this image."); return@runOnUiThread }
                val view = ImageView(this).apply { contentDescription = "Generated image preview"; adjustViewBounds = true; setImageBitmap(bitmap) }
                MaterialAlertDialogBuilder(this).setView(view).setPositiveButton("Close", null).create().apply {
                    setOnDismissListener { view.setImageDrawable(null); bitmap.recycle() }; show()
                }
            }
        }
    }

    // Save folder: chosen once with Android's folder picker, then remembered (with a persisted permission) for every Save.
    private fun savedTree(): Uri? = prefs.getString("save_tree", null)?.let(Uri::parse)
        ?.takeIf { u -> contentResolver.persistedUriPermissions.any { it.uri == u && it.isWritePermission } }
    private fun rememberTree(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.getString("save_tree", null)?.let(Uri::parse)?.takeIf { it != uri }?.let { old ->
            try { contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: SecurityException) { }
        }
        prefs.edit().putString("save_tree", uri.toString()).apply()
    }
    private fun pickFolder(done: (Uri?) -> Unit) {
        pendingFolder = done
        // The picker opens at the last folder, so changing it never starts from the top.
        try { folderPicker.launch(prefs.getString("save_tree", null)?.let(Uri::parse)) }
        catch (_: Exception) { pendingFolder = null; showError("No Android folder picker is available.") }
    }
    private fun folderName(tree: Uri): String = try { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifBlank { "Device storage" } } catch (_: Exception) { "Chosen folder" }

    private fun saveMedia(e: Entry) {
        val file = localMedia(e)
        if (!file.exists()) { showError("The private media file is missing."); return }
        val tree = savedTree() ?: run { pickFolder { uri -> if (uri != null) { rememberTree(uri); saveMedia(e) } }; return }
        io.execute {
            var created: Uri? = null
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                created = DocumentsContract.createDocument(contentResolver, parent, mimeOf(file), "OTStudio-${e.id}-${file.name}") ?: throw UserError("Android could not create the saved file.")
                contentResolver.openOutputStream(created)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw UserError("Android could not write the saved file.")
                runOnUiThread { snack("Saved to ${folderName(tree)}", "Change") { pickFolder { uri -> if (uri != null) { rememberTree(uri); snack("New save folder: ${folderName(uri)}") } } } }
            } catch (ex: Exception) {
                created?.let { try { DocumentsContract.deleteDocument(contentResolver, it) } catch (_: Exception) { } }
                runOnUiThread {
                    if (ex is SecurityException) { prefs.edit().remove("save_tree").apply(); showError("OT Studio lost access to the save folder. Tap Save again to choose it.") }
                    else showError((ex as? UserError)?.message ?: "Could not save the media. Check the folder and available storage.")
                }
            }
        }
    }
    private fun shareMedia(e: Entry) {
        val file = localMedia(e)
        if (!file.exists()) { showError("The private media file is missing."); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = mimeOf(file); putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share generated media"))
        } catch (_: Exception) { showError("No app is available to share this media.") }
    }
    private fun showError(message: String) { if (isFinishing || isDestroyed) return; MaterialAlertDialogBuilder(this).setTitle("OT Studio").setMessage(message).setPositiveButton("OK", null).show() }
}
