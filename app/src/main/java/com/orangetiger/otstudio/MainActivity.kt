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
import androidx.core.widget.doAfterTextChanged
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
        const val TAVILY_SIGNUP = "https://app.tavily.com/home"
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
    private var models: ModelsScreen? = null
    private var memoryScreen: MemoryScreen? = null
    private var streamId = -1L
    private var streamView: TextView? = null
    private var streamThinking: TextView? = null
    // Job cards keep the user's prompt edits and model choice across redraws of the feed.
    private val cardPrompts = mutableMapOf<Long, String>()
    private val cardModels = mutableMapOf<Long, String>()
    private val cardRatios = mutableMapOf<Long, String>()
    private lateinit var selectionRow: LinearLayout
    private var lastSent = ""
    private val drafts = mutableMapOf<Mode, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var renderGeneration = 0
    // A freshly built feed opens at the newest entry; later redraws keep the reader's place.
    private var scrollToEnd = true
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private var pendingPhoto: ((Uri?) -> Unit)? = null
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pendingPhoto?.invoke(uri); pendingPhoto = null }
    private var pendingFolder: ((Uri?) -> Unit)? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pendingFolder?.invoke(uri); pendingFolder = null }
    private val loraPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importLora) }
    private var pendingPhotos: ((List<Uri>) -> Unit)? = null
    private val photosPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(LoraTraining.MAX_PHOTOS)) { uris -> pendingPhotos?.invoke(uris); pendingPhotos = null }

    private var pollTick = 0
    private val poll = object : Runnable {
        override fun run() {
            // OpenRouter asks for a check about every 30 seconds, so its videos are checked on every fifth round.
            val openRouter = Rules.origin(OpenRouterMedia.BASE)
            pollTick++
            if (!settingsOpen && !app.isBusy() && app.lastError.isEmpty()) {
                app.history.list(pendingOnly = true).firstOrNull { it.state == "processing" && it.remoteId.isNotBlank() && (pollTick % 5 == 0 || runCatching { Rules.origin(it.baseUrl) }.getOrNull() != openRouter) }?.let { app.refresh(it) }
                // Trainings are checked about every 30 seconds; each check is a free request.
                app.checkTrainings()
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
            savedInstanceState?.getBoolean("models") == true -> showModels()
            savedInstanceState?.getBoolean("memory") == true -> showMemory()
            reopen != null -> showSettings(reopen)
            savedInstanceState?.getBoolean("help") == true -> showHelp()
            else -> showChat()
        }
        if (savedInstanceState == null) showSplash()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (models != null || memoryScreen != null) showSettings(Mode.CHAT) else if (settingsOpen || helpOpen) showChat() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        app.listener = { if (!settingsOpen) renderFeed(); libraryContent?.let(::fillLibrary) }
        app.modelsListener = { models?.render() }
        app.streamListener = { id, raw -> if (id == streamId) streamView?.let {
            val thought = ReplyText.thinking(raw)
            streamThinking?.let { t -> t.text = thought; (t.parent as View).visibility = if (thought.isEmpty()) View.GONE else View.VISIBLE }
            it.text = Markdown.styled(ReplyText.visible(raw).ifEmpty { if (thought.isEmpty()) "..." else "Thinking..." })
            if (!scroller.canScrollVertically(1) || scroller.scrollY + scroller.height >= feed.height - dp(240)) scroller.post { scroller.fullScroll(View.FOCUS_DOWN) } } }
        models?.render()
        handler.post(poll)
        if (!settingsOpen) renderFeed()
    }
    override fun onPause() { super.onPause(); app.listener = null; app.modelsListener = null; app.streamListener = null; handler.removeCallbacks(poll) }
    override fun onDestroy() { callPanel?.dismiss(); callPanel = null; io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (!settingsOpen && ::prompt.isInitialized) drafts[mode] = prompt.text.toString()
        outState.putString("mode", mode.name)
        outState.putString("lastSent", lastSent)
        Mode.entries.forEach { outState.putString("draft.${it.name}", drafts[it]) }
        settingsMode?.let { outState.putString("settings", it.name) }
        outState.putBoolean("help", helpOpen)
        outState.putBoolean("models", models != null)
        outState.putBoolean("memory", memoryScreen != null)
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

    private fun closeModels() { models?.leave(); models = null; memoryScreen = null }

    private fun showMemory() {
        closeModels()
        settingsOpen = true
        settingsMode = null
        helpOpen = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        memoryScreen = MemoryScreen(this, ui, app) { showSettings(Mode.CHAT) }.also { it.show(page()) }
    }

    private fun showModels() {
        closeModels()
        settingsOpen = true
        settingsMode = null
        helpOpen = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        models = ModelsScreen(this, ui, app) { showSettings(Mode.CHAT) }.also { it.show(page()) }
    }

    private fun localChat() = mode == Mode.CHAT && app.local.engine == ChatEngine.LOCAL

    /** A crossed-out eye for a model on this phone, a cloud for OpenRouter, so it is always clear where messages go. */
    private fun privacyBadge(local: Boolean): View = (if (local) ui.pill("Private: runs on this phone", R.color.ot_surface_high, R.color.ot_success)
        else ui.pill("OpenRouter: your messages go online", R.color.ot_blue_container, R.color.ot_on_blue_container)).apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(if (local) R.drawable.ic_private else R.drawable.ic_online, 0, 0, 0)
        compoundDrawablePadding = dp(6)
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = if (local) "Private chat model: your messages stay on this phone" else "Online chat model at OpenRouter: your messages go online"
    }
    private fun localStatus(): String {
        val m = app.local.selected() ?: return "No model on this phone yet. Download one in Manage models."
        return when (app.localState) {
            "loading" -> "Loading ${ModelDownloader.gb(app.local.file(m).length())} into memory, ${app.loadPercent}%"
            "ready" -> "Loaded and ready. Replies stay on this phone."
            else -> "Loads with your first message. Replies stay on this phone."
        }
    }

    private fun showChat() {
        closeModels()
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
        if (localChat()) setupRow.addView(ui.tonal("Models", R.drawable.ic_memory) { drafts[mode] = prompt.text.toString(); showModels() }.apply { contentDescription = "Manage on-device models" })
        else setupRow.addView(ui.tonal("API CALL", R.drawable.ic_tune) { openCallPanel() }.apply { contentDescription = "API CALL: choose models and generation inputs" })
        setup.addView(setupRow)
        if (mode == Mode.CHAT) setup.addView(privacyBadge(localChat()), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        // A saved picture shapes every request on this model, so it is shown here with a way to remove it.
        // One line per picture, each with its own Remove, so one can go and the other stay.
        if (mode != Mode.CHAT) app.attachedPictures(mode).forEach { name ->
            val label = InputLabels.title(name, mode)
            val row = ui.row().apply { setPadding(0, dp(6), 0, 0) }
            row.addView(ui.text("$label attached. Every request uses it.", Ui.Type.SMALL, ui.orange), ui.weight())
            row.addView(ui.textButton("Remove") {
                try { app.removePictures(mode, listOf(name)); showChat(); snack("$label removed.") } catch (e: UserError) { showError(e.message.orEmpty()) }
            }.apply { contentDescription = "Remove the ${label.lowercase()}" })
            setup.addView(row)
        }
        statusArea = ui.column()
        root.addView(statusArea)
        scroller = ScrollView(this).apply { isFillViewport = true }
        scrollToEnd = true
        feed = ui.column()
        scroller.addView(feed)
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        selectionRow = ui.row()
        root.addView(selectionRow)
        val composer = ui.row().apply { setPadding(0, dp(8), 0, 0); gravity = Gravity.BOTTOM }
        val field = ui.column()
        prompt = ui.field(field, when (mode) { Mode.CHAT -> "Message"; Mode.IMAGE -> "Describe an image"; Mode.VIDEO -> "Describe the scene and its motion" }, drafts[mode].orEmpty(), multiline = true)
        prompt.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        prompt.maxLines = 5
        ui.layoutOf(prompt)?.setBoxCornerRadii(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat())
        (field.getChildAt(0).layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        composer.addView(ui.iconButton(R.drawable.ic_add_photo_alternate, "Add a photo from this phone") { addPhoto() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginEnd = dp(4); bottomMargin = dp(2) }
        })
        composer.addView(field, ui.weight())
        send = ui.iconButton(R.drawable.ic_send, if (mode == Mode.CHAT) "Send" else "Generate ${mode.title.lowercase()}", filled = true) {
            try {
                val value = prompt.text.toString()
                val lora = if (mode == Mode.IMAGE) LoraCodec.matching(app.loras.list(), value) else null
                val s = app.settings.get(Mode.IMAGE)
                val loraBase = lora?.let { LoraBases.of(it.baseModel) }
                if (lora != null && loraBase != null && (s.model != loraBase.model || s.options.none { it.name == loraBase.field && it.value == lora.reference })) {
                    MaterialAlertDialogBuilder(this).setTitle("Use your LoRA?")
                        .setMessage("This prompt names ${lora.trigger}, but Image is set to ${s.model}, which does not use that LoRA. Switch Image to ${loraBase.title} with ${lora.name} and generate?")
                        .setPositiveButton("Switch and generate") { _, _ ->
                            try { app.useLora(lora); if (app.send(mode, value)) { lastSent = value.trim(); renderFeed() } } catch (e: UserError) { showError(e.message.orEmpty()) }
                        }
                        .setNeutralButton("Generate as set") { _, _ -> try { if (app.send(mode, value)) { lastSent = value.trim(); renderFeed() } } catch (e: UserError) { showError(e.message.orEmpty()) } }
                        .setNegativeButton("Cancel", null).show()
                    return@iconButton
                }
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
        "untracked" -> "Not tracked"; "canceled" -> "Canceled"; "unavailable" -> "Download skipped"; "stopped" -> "Stopped"; else -> state
    }
    private fun statePill(state: String) = when (state) {
        "succeeded" -> ui.pill(stateLabel(state), R.color.ot_surface_high, R.color.ot_success)
        "processing", "submitting", "chatting", "download", "canceling" -> ui.pill(stateLabel(state), R.color.ot_orange_container, R.color.ot_on_orange_container)
        "failed", "uncertain", "interrupted", "untracked" -> ui.pill(stateLabel(state), R.color.ot_error_container, R.color.ot_on_error_container)
        else -> ui.pill(stateLabel(state), R.color.ot_surface_high, R.color.ot_on_surface_variant)
    }
    /** A sideways-scrolling row of buttons, for screens built outside this activity. */
    fun actions(vararg buttons: View?): View = actionRow(*buttons)
    private fun actionRow(vararg buttons: View?): View {
        val row = ui.row()
        buttons.filterNotNull().forEach { row.addView(it) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun renderFeed() {
        if (!::feed.isInitialized || settingsOpen || isFinishing || isDestroyed) return
        renderGeneration++
        val generation = renderGeneration
        streamId = -1L; streamView = null; streamThinking = null
        val previousY = scroller.scrollY
        // A reader already at the end keeps following the conversation when it grows.
        val atEnd = !scroller.canScrollVertically(1)
        val entries = app.history.list(mode)
        val outputMap = app.history.outputs(entries.map { it.id })
        val s = app.settings.get(mode)
        if (localChat()) {
            setupTitle.text = app.local.selected()?.title ?: "On this phone"
            setupSummary.text = localStatus()
        } else {
            setupTitle.text = s.model
            setupSummary.text = when {
                app.isBusy() -> "Working..."
                mode == Mode.CHAT -> "Online chat through OpenRouter"
                else -> PlainSummary.describe(mode, s.model, s.options)
            }
        }
        send.isEnabled = !app.isBusy()
        if (!app.isBusy() && lastSent.isNotEmpty() && entries.lastOrNull()?.let { it.prompt == lastSent && it.state in listOf("succeeded", "processing", "download") } == true) {
            if (prompt.text.toString().trim() == lastSent) prompt.setText("")
            lastSent = ""
        }
        statusArea.removeAllViews()
        if (app.isBusy()) {
            statusArea.addView(ui.progress(), ui.matchWrap(2))
            val writing = app.streaming != null
            val label = when { app.localState == "loading" && localChat() -> "Loading the model..."; writing -> "Writing on this phone..."; else -> "Working..." }
            val r = ui.row(); r.addView(ui.text(label, Ui.Type.SMALL, ui.muted), ui.weight())
            r.addView(ui.textButton(if (writing || (localChat() && app.localState == "loading")) "Stop" else "Stop waiting", R.drawable.ic_stop_circle) { app.stopWaiting() }); statusArea.addView(r)
        }
        if (app.lastError.isNotEmpty()) ui.card(statusArea, 12, R.color.ot_error_container, 8).addView(ui.text(app.lastError, Ui.Type.BODY, ui.color(R.color.ot_on_error_container)))
        autoSaveNew()
        feed.removeAllViews()
        if (entries.isEmpty()) emptyState()
        val prompts = entries.associate { it.id to it.prompt }
        selectionRow.removeAllViews()
        if (mode == Mode.CHAT) app.selectedImage?.let { id ->
            val picked = app.history.entry(id)
            if (picked == null) app.selectedImage = null
            else {
                val file = try { localMedia(picked) } catch (_: UserError) { null }
                if (file != null && file.isFile) {
                    val thumb = ShapeableImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Picture being talked about"
                        shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(dp(8).toFloat()).build()
                        setOnClickListener { previewImage(file) }
                    }
                    selectionRow.addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10); topMargin = dp(6) })
                    io.execute {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (bounds.outWidth / inSampleSize > 160 || bounds.outHeight / inSampleSize > 160) inSampleSize *= 2 }
                        val bitmap = BitmapFactory.decodeFile(file.path, options)
                        runOnUiThread { if (generation == renderGeneration && !isFinishing && !isDestroyed) thumb.setImageBitmap(bitmap) else bitmap?.recycle() }
                    }
                }
                selectionRow.addView(ui.text("Talking about: ${picked.prompt.take(60)}", Ui.Type.SMALL, ui.orange).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, ui.weight())
                selectionRow.addView(ui.textButton("Clear") { app.selectedImage = null; renderFeed() })
            }
        }
        entries.forEach { e -> if (e.mode == Mode.CHAT) chatEntry(e, generation) else mediaEntry(e, outputMap[e.id].orEmpty(), entries.takeLast(3).any { it.id == e.id }, generation, prompts[e.parentId]) }
        val toEnd = scrollToEnd || lastSent.isNotEmpty() || atEnd
        scrollToEnd = false
        scroller.post { if (toEnd) scroller.fullScroll(View.FOCUS_DOWN) else scroller.scrollTo(0, previousY) }
    }

    private fun emptyState() {
        val box = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), dp(36), dp(8), dp(8)) }
        box.addView(ui.avatar(96))
        ui.gap(box, 16)
        box.addView(ui.text(when (mode) { Mode.CHAT -> "Start with an idea."; Mode.IMAGE -> "Make something worth keeping."; Mode.VIDEO -> "Give your idea a little motion." }, Ui.Type.HEADLINE).apply { gravity = Gravity.CENTER })
        ui.gap(box, 8)
        box.addView(ui.text("Pick a model with API CALL and add its key in Settings. Then write a prompt below.", Ui.Type.BODY, ui.muted).apply { gravity = Gravity.CENTER })
        if (mode == Mode.CHAT && !app.local.engineChosen) {
            ui.gap(box, 16)
            val choice = ui.card(box, 16)
            ui.sectionTitle(choice, "Where should Chat run?", "You can change this later in Settings.")
            choice.addView(ui.filled("On this phone", R.drawable.ic_memory) { app.setEngine(ChatEngine.LOCAL); showModels() }, ui.matchWrap(4))
            choice.addView(ui.text("Free and private. Downloads a 2.4 GB model once, over Wi-Fi. Works best on phones with 8 GB of memory or more.", Ui.Type.SMALL, ui.muted), ui.matchWrap(12))
            choice.addView(ui.tonal("OpenRouter, with your key", R.drawable.ic_key) { app.setEngine(ChatEngine.OPENROUTER); showSettings(Mode.CHAT) }, ui.matchWrap(4))
            choice.addView(ui.text("Larger models online. Each message costs a fraction of a cent from your OpenRouter credit.", Ui.Type.SMALL, ui.muted))
        }
        if (mode != Mode.CHAT) { ui.gap(box, 8); box.addView(ui.text("Each Generate is one request to your provider at its normal price.", Ui.Type.SMALL, ui.muted).apply { gravity = Gravity.CENTER }) }
        if (!app.settings.hasKey(Mode.CHAT) && !app.settings.hasKey(Mode.IMAGE)) { ui.gap(box, 12); box.addView(ui.tonal("How to get started", R.drawable.ic_help) { showHelp() }) }
        feed.addView(box, ui.matchWrap())
    }

    private fun bubble(text: String, mine: Boolean, error: Boolean = false): View {
        val holder = LinearLayout(this).apply { gravity = if (mine) Gravity.END else Gravity.START }
        val tone = when { error -> R.color.ot_error_container; mine -> R.color.ot_orange_container; else -> R.color.ot_surface_high }
        val fg = when { error -> R.color.ot_on_error_container; mine -> R.color.ot_on_orange_container; else -> R.color.ot_on_surface }
        val t = ui.text(if (mine) text else Markdown.styled(text), Ui.Type.BODY_LARGE, ui.color(fg)).apply {
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

    /**
     * A model's thinking, kept visibly apart from its answer: small muted italic text under a "Thinking" label.
     * It stays open while the model writes and starts folded once the reply is done.
     */
    private fun thinkingBlock(text: String, live: Boolean): View {
        val box = ui.column().apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(ui.color(R.color.ot_surface_container)); cornerRadius = dp(14).toFloat() }
        }
        val label = ui.text(if (live) "Thinking" else "Thinking, tap to show", Ui.Type.CAPTION, ui.muted)
        val body = ui.text(text, Ui.Type.SMALL, ui.muted).apply {
            setTypeface(typeface, android.graphics.Typeface.ITALIC); setTextIsSelectable(true); setPadding(0, dp(4), 0, 0)
            visibility = if (live) View.VISIBLE else View.GONE
        }
        box.addView(label); box.addView(body)
        if (!live) box.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            label.text = if (open) "Thinking, tap to hide" else "Thinking, tap to show"
        }
        box.contentDescription = "The model's thinking, not its answer"
        return box
    }

    private fun chatEntry(e: Entry, generation: Int) {
        feed.addView(bubble(e.prompt, mine = true), ui.matchWrap(6))
        val failed = e.state in listOf("failed", "uncertain", "interrupted")
        val thought = ReplyText.thinking(e.response)
        if (thought.isNotEmpty()) feed.addView(thinkingBlock(thought, live = false), ui.matchWrap(4))
        val answer = if (thought.isEmpty()) e.response else ReplyText.visible(e.response)
        if (answer.isNotEmpty()) feed.addView(bubble(answer, mine = false, error = failed), ui.matchWrap(4))
        val live = app.streaming?.takeIf { it.first == e.id && e.state == "chatting" }
        if (live != null) {
            val liveThought = ReplyText.thinking(live.second)
            val block = thinkingBlock(liveThought, live = true).apply { if (liveThought.isEmpty()) visibility = View.GONE }
            feed.addView(block, ui.matchWrap(4))
            val holder = bubble(ReplyText.visible(live.second).ifEmpty { if (liveThought.isEmpty()) "..." else "Thinking..." }, mine = false)
            streamId = e.id; streamView = (holder as LinearLayout).getChildAt(0) as TextView
            streamThinking = (block as LinearLayout).getChildAt(1) as TextView
            feed.addView(holder, ui.matchWrap(4))
        }
        if (e.sources.isNotEmpty()) sourcesBlock(SearchResult.listFromJson(e.sources))
        val plan = JobPlan.fromJson(e.plan)
        when {
            plan == null -> { }
            plan.action == "invalid" -> ownWordsCard(e, "No usable plan came back. ${plan.reply}")
            e.jobEntry == 0L -> jobCard(e, plan)
            e.jobEntry < 0 -> feed.addView(ui.text("Plan canceled. Nothing was sent.", Ui.Type.CAPTION, ui.muted), ui.matchWrap(6))
            else -> app.history.entry(e.jobEntry)?.let { job ->
                mediaEntry(job, app.history.outputs(listOf(job.id))[job.id].orEmpty(), true, generation, job.parentId.takeIf { it > 0 }?.let { app.history.entry(it)?.prompt })
            }
        }
        val meta = ui.row()
        meta.addView(statePill(e.state)); meta.addView(ui.text("  ${e.model}", Ui.Type.CAPTION, ui.muted), ui.weight())
        // Also after a job that failed, so the same message can try another kind of job.
        val jobFailed = e.jobEntry > 0 && app.history.entry(e.jobEntry)?.state in listOf("failed", "canceled", "unavailable")
        if ((plan == null || jobFailed) && app.createFromChat && e.state == "succeeded") meta.addView(ui.textButton(if (jobFailed) "Try again" else "Use my words") { ownWordsChoice(e) })
        meta.addView(ui.textButton("Use prompt") { prompt.setText(e.prompt); prompt.requestFocus() })
        // On-device replies carry a speed line; online replies would only repeat raw settings, so they show none.
        val speed = e.requestSummary.takeIf { e.baseUrl == History.LOCAL_BASE && it.isNotEmpty() }
        feed.addView(meta, ui.matchWrap(if (speed == null) 14 else 2))
        if (speed != null) feed.addView(ui.text(speed, Ui.Type.CAPTION, ui.muted), ui.matchWrap(14))
    }

    /** The web searches behind a reply: each query, its service, and links the user can open. */
    private fun sourcesBlock(results: List<SearchResult>) {
        val box = ui.column().apply { setPadding(dp(14), 0, dp(14), dp(4)) }
        results.forEach { r ->
            box.addView(ui.text(if (r.source == "failed") "Search failed: ${r.query}" else "Searched ${r.source}: ${r.query}", Ui.Type.CAPTION, ui.muted))
            r.hits.take(5).forEach { h ->
                val host = try { Uri.parse(h.url).host.orEmpty() } catch (_: Exception) { "" }
                box.addView(ui.text("${h.title} ($host)", Ui.Type.SMALL, ui.color(R.color.ot_blue)).apply {
                    setPadding(0, dp(2), 0, dp(2)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = "Open ${h.title} in the browser"
                    setOnClickListener { if (h.url.startsWith("https://")) openLink(h.url) }
                })
            }
        }
        if (results.any { it.source == "Wikipedia" && it.hits.isNotEmpty() }) box.addView(ui.text("Text from Wikipedia, available under CC BY-SA 4.0.", Ui.Type.CAPTION, ui.muted).apply { setPadding(0, dp(2), 0, 0) })
        feed.addView(box, ui.matchWrap(4))
    }

    /** Ways to use a message as typed: a new picture, an edit of the chosen picture (only when one is chosen), or a video. */
    private fun ownWordsActions(): List<Pair<String, String>> =
        listOfNotNull("New picture" to "image", if (app.selectedImage != null) "Edit the chosen picture" to "edit" else null, "Video" to "video")

    private fun ownWords(e: Entry, action: String) { try { app.planFromWords(e, action) } catch (ex: UserError) { showError(ex.message.orEmpty()) } }

    /** Offers to send the user's own message, as typed, to an image or video model. Only a job card follows; nothing is paid yet. */
    private fun ownWordsChoice(e: Entry) {
        val actions = ownWordsActions()
        // A list instead of a message, so it can hold every choice. You still review the job card and tap Generate.
        MaterialAlertDialogBuilder(this).setTitle("Make a job card from your exact words")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> ownWords(e, actions[which].second) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun ownWordsCard(e: Entry, reason: String) {
        val card = ui.card(feed, 14, R.color.ot_surface_high, 6)
        card.addView(ui.text(reason, Ui.Type.BODY))
        card.addView(ui.text("You can still use your own words, exactly as you typed them.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, dp(4), 0, 0) })
        val editing = app.selectedImage != null
        card.addView(actionRow(
            if (editing) ui.tonal("Edit the picture with my words", R.drawable.ic_edit) { ownWords(e, "edit") } else null,
            if (editing) ui.textButton("Picture from my words", R.drawable.ic_image) { ownWords(e, "image") } else ui.tonal("Picture from my words", R.drawable.ic_image) { ownWords(e, "image") },
            ui.textButton("Video from my words", R.drawable.ic_movie) { ownWords(e, "video") }
        ))
    }

    /**
     * A job card: what the plan will make, in plain words, with the exact prompt (editable), the model, and the likely cost.
     * Generate is the only way a plan becomes a paid request.
     */
    private fun jobCard(e: Entry, plan: JobPlan) {
        val card = ui.card(feed, 14, R.color.ot_surface_container, 6)
        val models = app.planModels(plan)
        var modelId = cardModels[e.id]?.takeIf { it in models } ?: app.defaultPlanModel(plan)
        val title = ui.bold(ui.text("", Ui.Type.SUBTITLE))
        card.addView(title)
        plan.reference?.let { id -> app.history.entry(id)?.let { ref ->
            card.addView(ui.text("${when (plan.action) { "edit" -> "Edits"; "expand" -> "Expands"; else -> "Starts from" }} the picture: ${app.describe(ref).take(120)}", Ui.Type.SMALL, ui.muted).apply { setPadding(0, dp(2), 0, dp(6)) })
            if (plan.action == "video") card.addView(ui.textButton("Without the picture") { cardModels.remove(e.id); app.dropPlanPicture(e, plan) }, LinearLayout.LayoutParams(-2, -2))
        } }
        // A small chat model sometimes picks the wrong kind of job for the chosen picture. These switch it, with the user's own words.
        if (app.selectedImage != null && plan.action in listOf("image", "edit", "expand")) card.addView(actionRow(
            if (plan.action != "edit") ui.textButton("Edit the chosen picture instead", R.drawable.ic_edit) { cardModels.remove(e.id); cardPrompts.remove(e.id); ownWords(e, "edit") } else null,
            ui.textButton("Make a video from it instead", R.drawable.ic_movie) { cardModels.remove(e.id); cardPrompts.remove(e.id); ownWords(e, "video") }
        ))
        ui.gap(card, 6)
        val field = ui.field(card, when (plan.action) { "edit" -> "Instruction"; "expand" -> "What the new area should show"; else -> "Prompt the model receives" }, cardPrompts[e.id] ?: plan.prompt, multiline = true)
        field.maxLines = 8
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: android.text.Editable?) { cardPrompts[e.id] = s.toString() }
        })
        val details = ui.text("", Ui.Type.SMALL, ui.muted)
        fun refresh() {
            try {
                val (s, notes) = app.planSettings(plan, modelId, cardRatios[e.id], e.prompt)
                title.text = PlainSummary.describe(if (plan.action == "video") Mode.VIDEO else Mode.IMAGE, s.model, s.options)
                details.text = (listOf("Model: ${s.model}", "Likely cost: ${Prices.estimate(s.model, s.options) ?: "see the model's page at Replicate"}") + notes).joinToString("\n")
            } catch (ex: UserError) { title.text = "This plan cannot run with this model"; details.text = ex.message }
        }
        if (plan.action == "expand") {
            val labels = EXPAND_RATIOS.map { "${it.second} ${it.first}" }
            val current = cardRatios[e.id] ?: Planner.expandRatio(plan.shape)
            ui.dropdown(card, "New shape", labels, labels[EXPAND_RATIOS.indexOfFirst { it.first == current }]) { label -> cardRatios[e.id] = EXPAND_RATIOS[labels.indexOf(label)].first; refresh() }
        } else if (models.isNotEmpty()) ui.dropdown(card, "Model", models, modelId) { modelId = it; cardModels[e.id] = it; refresh() }
        card.addView(details)
        refresh()
        val busy = app.isBusy()
        card.addView(actionRow(
            ui.filled("Generate", R.drawable.ic_send) {
                try {
                    if (app.runPlan(e, plan, field.text.toString(), modelId, cardRatios[e.id])) { cardPrompts.remove(e.id); cardModels.remove(e.id); cardRatios.remove(e.id); snack("Started. The result appears under this plan."); renderFeed() }
                } catch (ex: UserError) { showError(ex.message.orEmpty()) }
            }.apply { isEnabled = !busy },
            ui.textButton("Cancel") { app.cancelPlan(e) }
        ).also { (it as View).setPadding(0, dp(8), 0, 0) })
    }

    private fun mediaEntry(e: Entry, outputs: List<MediaOutput>, recent: Boolean, generation: Int, parentPrompt: String? = null) {
        val card = ui.card(feed, padding = 14)
        if (e.parentId > 0) card.addView(ui.text("Edit of: ${parentPrompt?.let { if (it.length > 70) it.take(67) + "..." else it } ?: "an earlier image"}", Ui.Type.CAPTION, ui.muted).apply { setPadding(0, 0, 0, dp(4)) })
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
                    contentDescription = if (e.baseUrl == History.PHONE_BASE) e.prompt else "Generated image: ${e.prompt}"; adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP
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
                if (e.mode == Mode.VIDEO && e.state == "succeeded") ui.textButton("Make it longer", R.drawable.ic_movie) { makeLonger(e) } else null,
                ui.textButton(if (prefs.getString(savedKey(mediaEntry, file), null) != null) "Saved" else "Save", R.drawable.ic_download) { saveMedia(mediaEntry) },
                ui.textButton("Share", R.drawable.ic_share) { shareMedia(mediaEntry) },
                if (e.mode == Mode.IMAGE && mode == Mode.CHAT) ui.textButton("Talk about this", R.drawable.ic_chat) { app.selectedImage = e.id; renderFeed(); prompt.requestFocus() } else null,
                if (e.mode == Mode.IMAGE) ui.textButton("Edit...", R.drawable.ic_edit) { editImage(e, file) } else null,
                if (e.mode == Mode.IMAGE) ui.textButton("Use as...", R.drawable.ic_auto_awesome) { useAs(file) } else null,
                if (e.mode == Mode.IMAGE) ui.textButton("Expand...", R.drawable.ic_open_in_full) { expandImage(e, file) } else null,
                if (e.mode == Mode.IMAGE) ui.textButton("Upscale 2x", R.drawable.ic_zoom_in) { upscale(e, file) } else null
            ))
        }
        val busy = app.isBusy()
        card.addView(actionRow(
            if (e.baseUrl != History.PHONE_BASE && e.model != ModelCatalog.upscaler.model && e.state !in listOf("processing", "submitting", "canceling"))
                ui.textButton("Repeat", R.drawable.ic_refresh) { repeat(e) }.apply { isEnabled = !busy; contentDescription = "Repeat with the same settings" } else null,
            if (e.state == "processing") ui.textButton("Refresh", R.drawable.ic_refresh) { app.refresh(e) }.apply { isEnabled = !busy } else null,
            if (e.state == "processing") ui.textButton("Cancel") { app.refresh(e, true) }.apply { isEnabled = !busy } else null,
            if (e.state == "processing") ui.textButton("Stop tracking") { confirmStopTracking(e) }.apply { isEnabled = !busy } else null,
            if (e.state in listOf("download", "unavailable")) ui.textButton("Download again", R.drawable.ic_refresh) { app.refresh(e) }.apply { isEnabled = !busy } else null,
            if (e.state == "download") ui.textButton("Skip download") { app.skipDownload(e) }.apply { isEnabled = !busy } else null,
            if (e.state !in listOf("processing", "submitting", "canceling", "download")) ui.textButton("Delete", R.drawable.ic_delete) {
                MaterialAlertDialogBuilder(this).setTitle("Delete this ${e.mode.title.lowercase()}?").setMessage("The card and its file in OT Studio's private storage are deleted. A copy you saved with Save stays.")
                    .setPositiveButton("Delete") { _, _ -> try { app.clearMedia(e.mode, listOf(e.id)); renderFeed() } catch (ex: UserError) { showError(ex.message.orEmpty()) } }
                    .setNegativeButton("Keep", null).show()
            }.apply { isEnabled = !busy } else null
        ))
    }

    private fun confirmStopTracking(e: Entry) {
        MaterialAlertDialogBuilder(this).setTitle("Stop tracking this job?")
            .setMessage("Use this when a job cannot be refreshed or canceled. It frees the app for new media jobs. It does not cancel the job at the provider, which may still run and charge.")
            .setPositiveButton("Stop tracking") { _, _ -> app.stopTracking(e) }.setNegativeButton("Keep", null).show()
    }

    private fun showSettings(selected: Mode) {
        closeModels()
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
        if (selected == Mode.CHAT) { localChatCard(content); memoryCard(content); searchCard(content) }
        val s = app.settings.get(selected)
        val provider = ui.card(content)
        ui.sectionTitle(provider, if (selected == Mode.CHAT) "OpenRouter, Chat Completions" else "Replicate, Predictions", "A new provider must speak this API protocol. Changing a URL alone does not add a different protocol.")
        val url = ui.field(provider, "API base URL", s.baseUrl)
        val model = ui.field(provider, "Model ID", s.model, helper = if (selected == Mode.CHAT) "Default: mistralai/mistral-nemo. Enter the exact provider ID for another model." else null)
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
        if (selected != Mode.CHAT) {
            val spicy = ui.card(content)
            ui.sectionTitle(spicy, "SpicyAPI", "Adult pictures and videos from words, with SpicyAPI's four text-only models in the model list. Its key is kept apart from your Replicate key and goes only to api.spicyapi.com. These models take no photo.")
            val spicyHelper = { if (app.settings.hasSpicyKey()) "Saved securely. Leave blank to keep." else "Starts with sk-spicy-." }
            val spicyKey = ui.field(spicy, "SpicyAPI key", "", secret = true, helper = spicyHelper())
            val spicyNote = ui.text("", Ui.Type.BODY, ui.orange)
            spicy.addView(actionRow(
                ui.tonal("Save key") { try { if (spicyKey.text.toString().isNotBlank()) { app.settings.saveSpicyKey(spicyKey.text.toString()); spicyKey.setText(""); ui.layoutOf(spicyKey)?.helperText = spicyHelper(); spicyNote.text = "Saved." } } catch (e: UserError) { spicyNote.text = e.message } },
                ui.danger("Remove key", R.drawable.ic_delete) { try { app.settings.saveSpicyKey(""); ui.layoutOf(spicyKey)?.helperText = spicyHelper(); spicyNote.text = "Key removed." } catch (e: UserError) { spicyNote.text = e.message } }
            ))
            spicy.addView(spicyNote)
            val openRouter = ui.card(content)
            val chatOnOpenRouter = Rules.origin(app.settings.get(Mode.CHAT).baseUrl) == Rules.origin(OpenRouterMedia.BASE)
            ui.sectionTitle(openRouter, "OpenRouter", "The model list also offers ${if (selected == Mode.IMAGE) "Seedream 4.5, Nano Banana, FLUX.2 pro, and Qwen Image 3" else "Wan 2.7, Kling v3.0 Pro, Seedance 1.5 Pro, and Veo 3.1 Lite"} on OpenRouter. They use the OpenRouter key saved for Chat, which goes only to openrouter.ai. Photos for them stay on this phone and travel inside each request.")
            openRouter.addView(ui.text(when {
                chatOnOpenRouter && app.settings.hasKey(Mode.CHAT) -> "Ready: your OpenRouter key is saved."
                chatOnOpenRouter -> "Save your OpenRouter key under Chat to use them."
                else -> "Chat uses another address. Set Chat to OpenRouter and save its key to use them."
            }, Ui.Type.BODY, ui.orange))
            val kind = selected.title.lowercase()
            val history = ui.card(content)
            val count = app.history.list(selected).size
            ui.sectionTitle(history, "${selected.title} history", if (count == 0) "No ${kind}s yet." else "$count ${kind}${if (count == 1) "" else "s"} in the ${selected.title} list.")
            history.addView(ui.danger("Clear $kind history", R.drawable.ic_delete) {
                MaterialAlertDialogBuilder(this).setTitle("Clear $kind history?")
                    .setMessage("This deletes every ${kind} card and its file in OT Studio's private storage${if (selected == Mode.IMAGE) ", including photos added from your phone" else ""}. Copies you saved with Save stay where you saved them, and the originals in your phone's gallery are not touched. Chat and memory stay.")
                    .setPositiveButton("Clear") { _, _ -> try { app.clearMedia(selected); snack("${selected.title} history cleared."); showSettings(selected) } catch (e: UserError) { showError(e.message.orEmpty()) } }
                    .setNegativeButton("Keep", null).show()
            }, LinearLayout.LayoutParams(-2, -2))
        }
        val downloads = ui.card(content)
        ui.sectionTitle(downloads, "Save folder", "Save puts images and videos here. Android remembers the folder, so you don't have to browse from the top each time.")
        downloads.addView(ui.text(savedTree()?.let(::folderName) ?: "Not chosen yet. You'll pick one the first time you tap Save.", Ui.Type.BODY))
        downloads.addView(ui.switch("Save every finished picture and video here automatically", prefs.getBoolean("auto_save", true)).apply {
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("auto_save", on).apply() }
        })
        if (savedTree() == null) downloads.addView(ui.text("Automatic saving starts once you choose a folder.", Ui.Type.SMALL, ui.muted))
        downloads.addView(actionRow(
            ui.outlined("Choose folder", R.drawable.ic_folder_open) { pickFolder { uri -> if (uri != null) { rememberTree(uri); showSettings(selected) } } },
            savedTree()?.let { tree -> ui.textButton("Open folder", R.drawable.ic_open_in_new) { openFolder(tree) } }
        ).also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8) })
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

    private fun localChatCard(content: LinearLayout) {
        val card = ui.card(content)
        ui.sectionTitle(card, "Chat runs", "On this phone answers offline at no cost per message. OpenRouter runs larger models online with your key.")
        val local = app.local.engine == ChatEngine.LOCAL
        ui.segmented(card, listOf("On this phone" to R.drawable.ic_memory, "OpenRouter" to null), if (local) 0 else 1) { i ->
            app.setEngine(if (i == 0) ChatEngine.LOCAL else ChatEngine.OPENROUTER); showSettings(Mode.CHAT)
        }
        if (!local) { card.addView(ui.text("OpenRouter settings follow below.", Ui.Type.SMALL, ui.muted)); return }
        card.addView(ui.bold(ui.text(app.local.selected()?.title ?: "No model chosen", Ui.Type.BODY_LARGE)))
        card.addView(ui.text(localStatus(), Ui.Type.SMALL, ui.muted).apply { setPadding(0, dp(2), 0, dp(8)) })
        card.addView(ui.text(if (NativeEngine.chosen == NativeEngine.FAST) "Engine: the faster build for this processor" else "Engine: the standard build", Ui.Type.CAPTION, ui.muted).apply { setPadding(0, 0, 0, dp(8)) })
        card.addView(ui.tonal("Manage models", R.drawable.ic_memory) { showModels() }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12) })
        val contexts = LocalModelStore.CONTEXT_CHOICES.map { "$it tokens" }
        ui.dropdown(card, "Context size", contexts, "${app.local.contextTokens} tokens") { app.local.contextTokens = it.substringBefore(' ').toInt() }
        val threads = LocalModelStore.THREAD_CHOICES.map { if (it == 0) "Automatic" else "$it" }
        ui.dropdown(card, "Processor threads", threads, if (app.local.threads == 0) "Automatic" else "${app.local.threads}") { app.local.threads = it.toIntOrNull() ?: 0 }
        val think = ui.checkbox("Show the model's thinking", app.local.showThinking).apply { setOnCheckedChangeListener { _, c -> app.local.showThinking = c } }
        card.addView(think)
        card.addView(ui.text("Some models, such as Qwen3.5 and Gemma 4, can think before they answer. Answers then come slower but may be better. The thinking appears in small gray type above the answer, and the model does not see it again later. Qwen3 4B Instruct never thinks.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, dp(8)) })
        card.addView(ui.text("A larger context remembers more of the conversation and uses more memory. 8192 suits phones with 8 GB or more. Change threads only to test speed. Changes apply to the next message.", Ui.Type.SMALL, ui.muted))
        content.addView(ui.text("OpenRouter settings below stay saved. Suggest setup in API CALL still uses them.", Ui.Type.SMALL, ui.muted).apply { setPadding(dp(4), 0, dp(4), dp(10)) })
    }

    private fun searchCard(content: LinearLayout) {
        val card = ui.card(content)
        ui.sectionTitle(card, "Web search", "The chat can look up current facts before it answers. Without a key it searches Wikipedia. With your own free Tavily key it searches the whole web.")
        card.addView(ui.checkbox("Allow web search", app.settings.allowSearch).apply { setOnCheckedChangeListener { _, on -> app.settings.allowSearch = on } })
        card.addView(ui.text("Search words leave the phone and go to Wikipedia or Tavily. Each reply that searched lists its searches and sources.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, dp(8)) })
        val helper = { if (app.settings.hasSearchKey()) "Saved securely. Leave blank to keep." else "Optional. Starts with tvly-." }
        val key = ui.field(card, "Tavily API key", "", secret = true, helper = helper())
        val note = ui.text("", Ui.Type.BODY, ui.orange)
        card.addView(actionRow(
            ui.tonal("Save key") { try { if (key.text.toString().isNotBlank()) { app.settings.saveSearchKey(key.text.toString()); key.setText(""); ui.layoutOf(key)?.helperText = helper(); note.text = "Saved." } } catch (e: UserError) { note.text = e.message } },
            ui.danger("Remove key", R.drawable.ic_delete) { try { app.settings.saveSearchKey(""); ui.layoutOf(key)?.helperText = helper(); note.text = "Key removed." } catch (e: UserError) { note.text = e.message } },
            ui.textButton("Get a key", R.drawable.ic_open_in_new) { openLink(TAVILY_SIGNUP) }
        ))
        card.addView(note)
    }

    private fun memoryCard(content: LinearLayout) {
        val create = ui.card(content)
        ui.sectionTitle(create, "Create from chat", "Ask for a picture, an edit, or a video in plain words. The chat answers with a job card that shows the exact prompt, the model, and the likely cost. Nothing is paid for until you tap Generate on the card.")
        create.addView(ui.checkbox("Create images and videos from chat", app.createFromChat).apply { setOnCheckedChangeListener { _, on -> app.createFromChat = on } })
        create.addView(ui.text("While this is on, the model's thinking is not shown, because each reply must be a plan.", Ui.Type.SMALL, ui.muted))
        val card = ui.card(content)
        val lines = app.history.memory.lines()
        ui.sectionTitle(card, "Memory and history", if (lines.isEmpty()) "No memory instructions yet." else "${lines.count { it.enabled }} of ${lines.size} memory instructions are on.")
        card.addView(ui.tonal("Edit memory", R.drawable.ic_auto_awesome) { showMemory() }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val save = ui.checkbox("Save chat history", app.saveChatHistory)
        card.addView(save)
        card.addView(ui.text("When this is off, new chat messages stay only until the app closes. Images and videos are still saved, so their files can be kept. Turning it back on hides the unsaved chat and shows the saved one again.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, dp(8)) })
        save.setOnCheckedChangeListener { _, on -> try { app.saveChatHistory = on } catch (e: UserError) { showError(e.message.orEmpty()) } }
        card.addView(ui.danger("Clear chat history", R.drawable.ic_delete) {
            MaterialAlertDialogBuilder(this).setTitle("Clear chat history?").setMessage("This deletes every chat message on this phone, saved or not. Images, videos, and memory stay.")
                .setPositiveButton("Clear") { _, _ -> try { app.clearChat(); snack("Chat history cleared.") } catch (e: UserError) { showError(e.message.orEmpty()) } }
                .setNegativeButton("Keep", null).show()
        }, LinearLayout.LayoutParams(-2, -2))
    }

    private fun showLoraLibrary() {
        val content = ui.column(20)
        libraryContent = content
        fillLibrary(content)
        library = MaterialAlertDialogBuilder(this).setTitle("LoRA library").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Import file") { _, _ -> try { loraPicker.launch(arrayOf("*/*")) } catch (_: Exception) { showError("No Android file picker is available.") } }
            .setNeutralButton("Add manually") { _, _ -> editLora(null) }.setNegativeButton("Close", null)
            .setOnDismissListener { if (libraryContent === content) libraryContent = null }.show()
        app.checkTrainings(now = true)
    }
    private var library: androidx.appcompat.app.AlertDialog? = null
    /** The open LoRA library's content, redrawn when a training changes. */
    private var libraryContent: LinearLayout? = null

    private fun fillLibrary(content: LinearLayout) {
        content.removeAllViews()
        val entries = app.loras.list()
        val trainings = app.loras.trainings()
        content.addView(ui.text("Train a LoRA from your photos here, or import a .otstudio-lora.json file from OT Studio for Windows or the LoRA Trainer. Each LoRA runs only on its own base model: FLUX dev or Qwen Image.", Ui.Type.SMALL, ui.muted))
        content.addView(ui.tonal("Train a LoRA", R.drawable.ic_auto_awesome) { library?.dismiss(); trainLoraDialog() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        if (trainings.isNotEmpty()) {
            ui.gap(content, 12)
            content.addView(ui.bold(ui.text("Trainings", Ui.Type.SUBTITLE)))
            trainings.forEach { trainingCard(content, it) }
        }
        ui.gap(content, 12)
        content.addView(ui.bold(ui.text("Saved LoRAs (${entries.size})", Ui.Type.SUBTITLE)))
        if (entries.isEmpty()) content.addView(ui.text("No saved LoRAs yet.", Ui.Type.BODY))
        entries.forEach { lora ->
            val c = ui.card(content, 12, R.color.ot_surface_high, 8)
            c.addView(ui.bold(ui.text(lora.name, Ui.Type.SUBTITLE)))
            c.addView(ui.text("${LoraBases.of(lora.baseModel)?.title ?: lora.baseModel}  |  ${if (lora.baseModel == LoraBases.QWEN.id) "words" else "trigger"}: ${lora.trigger.ifBlank { "none" }}  |  strength ${lora.scale}", Ui.Type.SMALL, ui.muted))
            c.addView(ui.text(lora.reference, Ui.Type.CAPTION, ui.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            c.addView(actionRow(
                ui.tonal("Use for pictures", R.drawable.ic_image) { library?.dismiss(); useLora(lora) },
                ui.danger("Remove", R.drawable.ic_delete) {
                    MaterialAlertDialogBuilder(this).setTitle("Remove ${lora.name}?").setMessage("This removes the LoRA from this library only. Its model stays in your Replicate account, and you can add it again with its reference.")
                        .setPositiveButton("Remove") { _, _ -> app.loras.remove(lora.reference); fillLibrary(content) }.setNegativeButton("Keep", null).show()
                }.apply { contentDescription = "Remove ${lora.name} from the library" }
            ))
        }
    }

    /** One training: its stage, progress, and time left while it runs; its result or reason when it ends. */
    private fun trainingCard(parent: LinearLayout, t: LoraTrainingRecord) {
        val c = ui.card(parent, 12, R.color.ot_surface_high, 8)
        c.addView(ui.bold(ui.text(t.name, Ui.Type.SUBTITLE)))
        c.addView(ui.text("${LoraBases.of(t.base)?.title ?: t.base} trainer  |  ${t.steps} steps", Ui.Type.SMALL, ui.muted))
        if (t.running || t.state == "sending") c.addView(ui.progress().apply {
            t.progress?.let { isIndeterminate = false; max = 100; progress = (it * 100).toInt() }
        }, ui.matchWrap(4).apply { topMargin = dp(8) })
        val status = when (t.state) {
            "sending" -> "Sending the photos to Replicate..."
            "starting", "processing" -> listOfNotNull(LoraTraining.phaseText(t.phase), t.progress?.let { "${(it * 100).toInt()}%" },
                t.minutesLeft?.let { "about $it minute${if (it == 1) "" else "s"} left" }).joinToString("  |  ")
            "succeeded" -> "Ready. It is in your saved LoRAs."
            "failed" -> "Failed: ${t.error}"
            "canceled" -> "Canceled. Replicate bills the time it ran."
            else -> t.error.ifEmpty { "The outcome is unknown. Check replicate.com/trainings." }
        }
        c.addView(ui.text(status, Ui.Type.BODY, if (t.state in listOf("failed", "uncertain")) ui.orange else ui.onSurface))
        if (t.running) c.addView(actionRow(
            ui.textButton("Check now", R.drawable.ic_refresh) { app.checkTrainings(now = true) },
            ui.danger("Cancel training") {
                MaterialAlertDialogBuilder(this).setTitle("Stop this training?").setMessage("Replicate stops the training and bills the time it already ran. No LoRA is made.")
                    .setPositiveButton("Stop training") { _, _ -> try { app.cancelTraining(t) } catch (e: UserError) { showError(e.message.orEmpty()) } }.setNegativeButton("Keep training", null).show()
            }
        )) else if (t.state != "sending") c.addView(ui.textButton("Remove from list") { try { app.forgetTraining(t) } catch (e: UserError) { showError(e.message.orEmpty()) } }, LinearLayout.LayoutParams(-2, -2))
    }

    /**
     * Train a LoRA: a name, a trainer, the trigger word (or plain words for Qwen), steps, and 1 to 20 photos. The Train
     * tap, after a cost confirmation, starts one billed training at Replicate.
     */
    private fun trainLoraDialog() {
        val content = ui.column(20)
        var base = LoraBases.FLUX
        var triggerEdited = false
        val chosen = ArrayList<Uri>()
        fun trainerTitle(b: LoraBase) = if (b == LoraBases.QWEN) "Qwen Image (alternate)" else "FLUX dev (standard)"
        val name = ui.field(content, "LoRA name", "", helper = "For example: Anna face")
        var refreshAll: () -> Unit = {}
        ui.dropdown(content, "Trainer", LoraBases.all.map(::trainerTitle), trainerTitle(base)) { title -> base = LoraBases.all.first { trainerTitle(it) == title }; triggerEdited = false; refreshAll() }
        val trigger = ui.field(content, "Trigger word", "")
        var settingTrigger = false
        fun setTrigger(value: String) { settingTrigger = true; trigger.setText(value); settingTrigger = false }
        // Nothing is suggested until the LoRA has a name.
        fun suggestion() = name.text.toString().trim().let { if (it.isEmpty()) "" else LoraTraining.suggest(base, it) }
        val steps = ui.field(content, "Steps", "1000", numeric = true)
        val note = ui.text("", Ui.Type.SMALL, ui.muted)
        val photoCount = ui.text("", Ui.Type.BODY)
        fun refresh() {
            val qwen = base == LoraBases.QWEN
            ui.layoutOf(trigger)?.hint = if (qwen) "How the photos describe the subject" else "Trigger word"
            ui.layoutOf(trigger)?.helperText = if (qwen) "Plain words, not a made-up word: Qwen learns from familiar words. Every photo gets the caption \"A photo of\" these words, and you use them in your prompts."
                else "A made-up word you put in prompts to call up the LoRA."
            ui.layoutOf(steps)?.helperText = if (qwen) "100 to 6000. 1000 to 2000 suit most faces." else "3 to 6000. 1000 suits most faces."
            note.text = if (qwen) "Training runs on Replicate with qwen/qwen-image-lora-trainer, billed by the second (about 9 cents a minute). A run usually takes 15 to 30 minutes, so about \$1.40 to \$2.75. The LoRA runs only on Qwen Image with a LoRA."
                else "Training runs on Replicate with ostris/flux-dev-lora-trainer. 1,000 steps cost about \$1.50 to \$2 and take about 20 to 30 minutes. The LoRA runs on FLUX dev with a LoRA, here and in OT Studio for Windows."
            photoCount.text = if (chosen.isEmpty()) "No photos chosen. Choose 1 to 20; 10 or more work best, each at least 512 pixels on its short side." else "${chosen.size} photo${if (chosen.size == 1) "" else "s"} chosen."
            if (!triggerEdited) setTrigger(suggestion())
        }
        refreshAll = ::refresh
        name.doAfterTextChanged { if (!triggerEdited) setTrigger(suggestion()) }
        trigger.doAfterTextChanged { if (!settingTrigger) triggerEdited = true }
        content.addView(photoCount, ui.matchWrap(4).apply { topMargin = dp(8) })
        content.addView(ui.tonal("Choose photos", R.drawable.ic_add_photo_alternate) {
            pickPhotos { uris -> chosen.clear(); chosen.addAll(uris.take(LoraTraining.MAX_PHOTOS)); refresh() }
        }, LinearLayout.LayoutParams(-2, -2))
        val agree = ui.checkbox("I agree to upload these photos to my Replicate account and pay for the training there.", false)
        content.addView(agree, ui.matchWrap(4).apply { topMargin = dp(8) })
        content.addView(note)
        content.addView(ui.text("Use photos of yourself, or of someone who agreed to be trained.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
        refresh()
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Train a LoRA").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Train", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val n = name.text.toString().trim(); val t = trigger.text.toString().trim()
                val s = steps.text.toString().trim().toIntOrNull() ?: throw UserError("Enter the number of steps.")
                LoraTraining.check(n, t, base, s, LoraTraining.defaultRank(base), chosen.size)
                if (!agree.isChecked) throw UserError("Tick the box to agree to the upload and the charge first.")
                val cost = if (base == LoraBases.QWEN) "about \$1.40 to \$2.75, billed by the second" else "about \$1.50 to \$2 for 1,000 steps"
                MaterialAlertDialogBuilder(this).setTitle("Start this training?")
                    .setMessage("$n: ${chosen.size} photos, $s steps, on the ${base.title} trainer. Replicate bills $cost. The photos go to your Replicate account, and the LoRA is kept in a private model there.")
                    .setPositiveButton("Start training") { _, _ ->
                        try {
                            val photos = chosen.map { uri -> { readPhoto(uri) } }
                            if (app.trainLora(n, t, base, s, photos)) { dialog.dismiss(); snack("Training is starting. The LoRA library shows its progress.", "Open") { showLoraLibrary() } }
                            else showError("Wait for the current request to finish.")
                        } catch (e: UserError) { showError(e.message.orEmpty()) }
                    }.setNegativeButton("Not yet", null).show()
            } catch (e: UserError) { showError(e.message.orEmpty()) }
        } }
        dialog.show()
    }

    /** Switches Image to the LoRA's own model with [lora] and starts the prompt with its trigger word. */
    private fun useLora(lora: SavedLora) {
        try {
            app.useLora(lora)
            drafts[mode] = prompt.text.toString()
            mode = Mode.IMAGE
            val draft = drafts[Mode.IMAGE].orEmpty()
            if (LoraCodec.matching(listOf(lora), draft) == null) drafts[Mode.IMAGE] = "${lora.trigger} " + draft
            showChat()
            prompt.setSelection(prompt.text?.length ?: 0); prompt.requestFocus()
            snack("Image now uses ${LoraCodec.base(lora).title} with ${lora.name}. Describe the picture after ${lora.trigger}, then tap Generate.")
        } catch (e: UserError) { showError(e.message.orEmpty()) }
    }

    private fun editLora(imported: SavedLora?) {
        val content = ui.column(20)
        val name = ui.field(content, "LoRA name", imported?.name.orEmpty())
        val reference = ui.field(content, "Replicate owner/model/version or HTTPS weights URL", imported?.reference.orEmpty())
        val trigger = ui.field(content, "Trigger word to use in your prompt", imported?.trigger.orEmpty())
        val scale = ui.field(content, "Default scale (-1 to 3)", (imported?.scale ?: 1.0).toString(), numeric = true, decimal = true)
        var base = LoraBases.of(imported?.baseModel ?: "") ?: LoraBases.FLUX
        ui.dropdown(content, "Base model", LoraBases.all.map { it.title }, base.title) { title -> base = LoraBases.all.first { it.title == title } }
        content.addView(ui.text("FLUX dev LoRAs run on FLUX dev with a LoRA; Qwen Image LoRAs run on Qwen Image with a LoRA. Saving an existing reference updates its name, trigger, and scale. No provider call is made here.", Ui.Type.SMALL, ui.muted))
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (imported == null) "Add LoRA" else "Review LoRA import").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val parsed = scale.text.toString().replace(',', '.').toDoubleOrNull() ?: throw UserError("Enter a numeric scale.")
                val saved = SavedLora(name.text.toString().trim(), reference.text.toString().trim(), trigger.text.toString().trim(), parsed, base.id)
                app.loras.save(saved)
                dialog.dismiss()
                MaterialAlertDialogBuilder(this).setTitle("LoRA saved").setMessage("Use ${saved.name} for pictures now? Image switches to ${base.title} with a LoRA, and your prompt starts with ${saved.trigger.ifBlank { "its trigger word" }}.")
                    .setPositiveButton("Use for pictures") { _, _ -> useLora(saved) }.setNegativeButton("Not now") { _, _ -> showLoraLibrary() }.show()
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

    /** Opens Android's photo picker for up to 20 photos; [done] receives them, or an empty list. */
    private fun pickPhotos(done: (List<Uri>) -> Unit) {
        pendingPhotos = done
        try { photosPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { pendingPhotos = null; showError("No Android photo picker is available.") }
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

    /** The message bar's photo button: brings a phone photo in as a picture, then offers what this mode can do with it. */
    private fun addPhoto() {
        val target = mode
        pickPhoto { uri ->
            if (uri == null) return@pickPhoto
            io.execute {
                try {
                    val (bytes, mime) = readPhoto(uri)
                    val id = app.importPhoto(bytes, mime)
                    runOnUiThread { if (!isFinishing && !isDestroyed) photoAdded(id, target) }
                } catch (e: Exception) { runOnUiThread { if (!isFinishing && !isDestroyed) showError((e as? UserError)?.message ?: "Could not add this photo.") } }
            }
        }
    }

    private fun photoAdded(id: Long, target: Mode) {
        val entry = app.history.entry(id) ?: return
        val file = localMedia(entry)
        if (target != mode) { renderFeed(); snack("Photo added to the Image feed."); return }
        when (target) {
            Mode.CHAT -> {
                app.selectedImage = id; scrollToEnd = true; renderFeed(); prompt.requestFocus()
                snack(if (app.createFromChat) "Photo added. Say what to do with it, such as make it black and white or turn it into a video."
                    else "Photo added to the Image feed. Turn on Create from chat in Settings to edit or animate it from here.")
            }
            Mode.IMAGE -> { scrollToEnd = true; renderFeed(); useAs(file, listOf(MediaRole.REFERENCE), "Use this photo to...", listOf("Edit it" to { editImage(entry, file) }, "Expand it to a new shape" to { expandImage(entry, file) })) }
            Mode.VIDEO -> { renderFeed(); useAs(file, listOf(MediaRole.START_FRAME, MediaRole.END_FRAME), "Use this photo as...") }
        }
    }

    /** Uploads a generated image and puts it into the chosen model input (reference, starting frame, or ending frame). */
    private fun useAs(file: File, roles: List<MediaRole> = MediaRole.entries, title: String = "Use this image as...", extras: List<Pair<String, () -> Unit>> = emptyList()) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val labels = extras.map { it.first } + roles.map { it.title }
        MaterialAlertDialogBuilder(this).setTitle(title).setItems(labels.toTypedArray()) { _, which ->
            if (which < extras.size) { extras[which].second(); return@setItems }
            val role = roles[which - extras.size]
            val mime = when (file.extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/webp" }
            // The model that will receive the photo: an OpenRouter model keeps it on the phone instead of uploading it.
            val target = try { app.settings.get(role.mode).let { current -> role.apply(current, "https://api.replicate.com/v1/files/check") { app.settings.forModel(current, it) }.model } } catch (_: UserError) { null }
            app.uploadPhoto(role.mode, { Triple(file.inputStream().use { readLimited(it) }, mime, file.name) }, target, file) { url, error ->
                if (isFinishing || isDestroyed) return@uploadPhoto
                if (url == null) { showError(error ?: "Could not upload the image."); return@uploadPhoto }
                try {
                    val current = app.settings.get(role.mode)
                    val updated = role.apply(current, url) { app.settings.forModel(current, it) }
                    app.settings.save(updated, null)
                    if (role.mode == mode) snack("${role.title} set on ${updated.model}. Describe what you want, then tap Generate.")
                    else snack("${role.title} set on ${updated.model}.", "Open ${role.mode.title}") { drafts[mode] = prompt.text.toString(); mode = role.mode; showChat() }
                    renderFeed()
                } catch (e: UserError) { showError(e.message.orEmpty()) }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    /** Asks for a plain instruction and an editing model, then edits this image. The Edit tap starts one paid request. */
    private fun editImage(e: Entry, file: File, words: String = "", model: String? = null) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val content = ui.column(20)
        val instruction = ui.field(content, "What should change?", words, multiline = true, helper = "For example: change her jacket to a red sweater. Keep her face the same.")
        val models = ModelCatalog.edits
        var chosen = models.firstOrNull { it.model == (model ?: app.settings.editModel) } ?: ModelCatalog.defaultEdit
        val cost = ui.text("", Ui.Type.SMALL, ui.muted)
        fun showCost() { cost.text = "One edit with ${chosen.title} costs ${chosen.price} at Replicate. The edited image appears as a new card. Replicate's usage policy still applies, whatever the safety setting says." }
        // Reference photos from the phone, such as an outfit or a place, follow the picture being edited.
        val references = ArrayList<Uri>()
        val referenceNote = ui.text("", Ui.Type.SMALL, ui.muted)
        val referenceButtons = ui.row()
        lateinit var showReferences: () -> Unit
        showReferences = {
            val takes = PictureList.max(chosen.model) - 1
            val listInput = ModelCatalog.find(Mode.IMAGE, chosen.model)?.schema?.fields?.firstOrNull { it.name == chosen.imageField }?.list == true
            referenceButtons.removeAllViews()
            if (!listInput) {
                referenceNote.text = if (references.isEmpty()) "${chosen.title} edits one picture. Seedream 4.5, Qwen Image Edit Plus, FLUX.2, and P-Image Edit also take reference photos." else "${chosen.title} takes one picture, so the ${references.size} reference photo(s) would be left out. Choose another model, or clear them."
            } else {
                referenceNote.text = if (references.isEmpty()) "You can add up to $takes reference photos, such as an outfit or a place. This picture is @img1, and they become @img2, @img3, and so on." else "This picture is @img1; ${references.indices.joinToString(" and ") { "@img${it + 2}" }} ${if (references.size == 1) "comes" else "come"} from your phone. Name them in your words, for example: dress her in the outfit from @img2. The model reads image 1, image 2."
                if (references.size < takes) referenceButtons.addView(ui.textButton("Add a reference photo", R.drawable.ic_add_photo_alternate) { pickPhoto { uri -> if (uri != null) { references.add(uri); showReferences() } } })
            }
            if (references.isNotEmpty()) referenceButtons.addView(ui.textButton("Clear references") { references.clear(); showReferences() })
        }
        ui.dropdown(content, "Editing model", models.map { it.title }, chosen.title) { title -> chosen = models.first { it.title == title }; showCost(); showReferences() }
        showCost()
        content.addView(cost)
        content.addView(referenceNote.apply { setPadding(0, dp(8), 0, 0) })
        content.addView(referenceButtons)
        showReferences()
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Edit this image").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Edit", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val mime = when (file.extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/webp" }
            try {
                if (app.edit(e, instruction.text.toString(), chosen.model, { Triple(file.inputStream().use { readLimited(it) }, mime, file.name) }, extras = references.map { uri -> { readPhoto(uri) } })) {
                    dialog.dismiss(); snack("Editing with ${chosen.title}${if (references.isEmpty()) "" else " and ${references.size} reference photo(s)"}. The result appears as a new card."); renderFeed()
                } else ui.layoutOf(instruction)?.error = "Wait for the current operation to finish."
            } catch (ex: UserError) { ui.layoutOf(instruction)?.error = ex.message }
        } }
        dialog.show()
    }

    /** Asks for a new shape and, optionally, what the new area should show. The Expand tap starts one paid request. */
    private fun expandImage(e: Entry, file: File, startRatio: String? = null, startWords: String = "") {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val content = ui.column(20)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth > 0) content.addView(ui.text("This picture is ${bounds.outWidth} by ${bounds.outHeight} pixels. It stays as it is, in the middle, and new picture fills the rest of the new shape.", Ui.Type.SMALL, ui.muted).apply { setPadding(0, 0, 0, dp(8)) })
        val labels = EXPAND_RATIOS.map { "${it.second} ${it.first}" }
        var ratio = startRatio?.takeIf { r -> EXPAND_RATIOS.any { it.first == r } } ?: EXPAND_RATIOS.first().first
        ui.dropdown(content, "New shape", labels, labels[EXPAND_RATIOS.indexOfFirst { it.first == ratio }]) { label -> ratio = EXPAND_RATIOS[labels.indexOf(label)].first }
        val words = ui.field(content, "What should the new area show? (optional)", startWords, multiline = true, helper = "For example: more of the beach and the sky.")
        content.addView(ui.text("One expansion with FLUX Fill dev costs about 4 cents at Replicate. The phone then puts the full-size result together, so the picture keeps its own quality. The result appears as a new card.", Ui.Type.SMALL, ui.muted))
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Expand this image").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Expand", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                if (app.expand(e, ratio, words.text.toString())) {
                    dialog.dismiss(); snack("Expanding. The result appears as a new card."); renderFeed()
                } else ui.layoutOf(words)?.error = "Wait for the current operation to finish."
            } catch (ex: UserError) { ui.layoutOf(words)?.error = ex.message }
        } }
        dialog.show()
    }

    /**
     * Repeat: the same job again, with the words ready to change. An edit, an expansion, or a longer video opens its
     * dialog filled in; any other picture or video gets its model and inputs back, pictures included, and its words
     * in the message box. Nothing is sent until the user taps the button.
     */
    private fun repeat(e: Entry) {
        val parent = e.parentId.takeIf { it > 0 }?.let { app.history.entry(it) }
        val parentFile = parent?.let { p -> try { localMedia(p).takeIf { it.isFile } } catch (_: UserError) { null } }
        when {
            ModelCatalog.edit(e.model) != null && parent != null -> parentFile?.let { editImage(parent, it, e.prompt, e.model) } ?: showError("The picture this edit started from is gone.")
            e.model == ModelCatalog.expander.model && parent != null -> {
                val match = Regex("Expanded to \\S+ (\\d+:\\d+)(?:: (.*))?").find(e.prompt)
                parentFile?.let { expandImage(parent, it, match?.groupValues?.get(1), match?.groupValues?.get(2).orEmpty()) } ?: showError("The picture this expansion started from is gone.")
            }
            e.mode == Mode.VIDEO && parent?.mode == Mode.VIDEO -> makeLonger(parent, e.prompt)
            else -> try {
                app.restoreSettings(e)
                drafts[mode] = prompt.text.toString()
                mode = e.mode
                drafts[e.mode] = e.prompt
                showChat()
                prompt.setSelection(prompt.text?.length ?: 0); prompt.requestFocus()
                snack("Same model and settings as that ${e.mode.title.lowercase()}, pictures included. Change the words, then send.")
            } catch (ex: UserError) { showError(ex.message.orEmpty()) }
        }
    }

    /** Asks what happens next, then starts the next part of this video from its last frame. One paid request. */
    private fun makeLonger(e: Entry, words: String? = null) {
        val content = ui.column(20)
        val previous = words ?: e.prompt.removePrefix("Continues: ")
        val next = ui.field(content, "What happens next?", previous, multiline = true, helper = "For example: she keeps dancing and turns toward the sea.")
        content.addView(ui.text("The next part starts from this video's last frame and runs about 5 seconds. With WAN 2.2 fast it costs about 5 to 11 cents. The phone joins it to this video, so the new card holds the whole video.", Ui.Type.SMALL, ui.muted))
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Make this video longer").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Make it longer", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                if (app.extendVideo(e, next.text.toString())) { dialog.dismiss(); snack("Making the next part. The joined video appears as a new card."); renderFeed() }
                else ui.layoutOf(next)?.error = "Wait for the current operation to finish."
            } catch (ex: UserError) { ui.layoutOf(next)?.error = ex.message }
        } }
        dialog.show()
    }

    private fun upscale(e: Entry, file: File) {
        if (!file.exists()) { showError("The private media file is missing."); return }
        val mime = when (file.extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/webp" }
        val (w, h) = try { Outpainter.size(file) } catch (ex: UserError) { showError(ex.message.orEmpty()); return }
        // The upscaler refuses pictures above about 2 megapixels. Those are already large, so nothing is sent.
        if (w.toLong() * h > Outpainter.UPSCALE_MAX_PIXELS) {
            MaterialAlertDialogBuilder(this).setTitle("Already large").setMessage("This picture is $w by $h pixels. Upscale 2x works on pictures up to about 1400 by 1400 and makes them about 2800 by 2800. This one is already large enough, so it does not need upscaling. Nothing was sent.")
                .setPositiveButton("OK", null).show()
            return
        }
        try {
            if (app.upscale(e) { Triple(file.inputStream().use { readLimited(it) }, mime, file.name) }) { snack("Upscaling to twice the size. The result appears as a new card."); renderFeed() }
        } catch (ex: UserError) { showError(ex.message.orEmpty()) }
    }

    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { snack("No browser is available.") }
    }

    /** In-app help, read from assets/help.txt (the same text as the README quick start). */
    private fun showHelp() {
        closeModels()
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

    /** "Saved to <folder>" with two actions: Open folder and Change. */
    private fun savedSnack(tree: Uri) {
        if (isFinishing || isDestroyed) return
        val bar = Snackbar.make(screen, "Saved to ${folderName(tree)}", Snackbar.LENGTH_LONG).setAction("Change") { pickFolder { uri -> if (uri != null) { rememberTree(uri); snack("New save folder: ${folderName(uri)}") } } }
        // A Snackbar holds one action; the second one is added beside it.
        ((bar.view as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.let { row ->
            row.addView(ui.textButton("Open folder") { bar.dismiss(); openFolder(tree) }, row.childCount - 1)
        }
        bar.show()
    }

    /** Opens the save folder in the phone's file manager. Android decides which app shows it. */
    private fun openFolder(tree: Uri) {
        val folder = try { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) } catch (_: Exception) { null }
        val tries = listOfNotNull(
            folder?.let { Intent(Intent.ACTION_VIEW).setDataAndType(it, DocumentsContract.Document.MIME_TYPE_DIR) },
            folder?.let { Intent(Intent.ACTION_VIEW).setData(it) }
        ).map { it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        for (intent in tries) {
            try { startActivity(intent); return } catch (_: android.content.ActivityNotFoundException) { } catch (_: SecurityException) { }
        }
        showError("No app on this phone can open folders directly. Open the Files app and go to ${folderName(tree)}.")
    }

    private fun savedKey(e: Entry, file: File) = "saved:${e.id}:${file.name}"
    private fun documentExists(uri: Uri): Boolean = try {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) { false }
    private val saving = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Saves every finished picture and video that is new since automatic saving began, once each. It needs a save
     * folder; until one is chosen, Save asks for it. Photos added from the phone are not copied back.
     */
    private fun autoSaveNew() {
        if (!prefs.getBoolean("auto_save", true)) return
        // The history and file checks run on the background thread, never on the screen's thread.
        io.execute { try { autoSaveScan() } catch (_: Exception) { } }
    }

    private fun autoSaveScan() {
        savedTree() ?: return
        val entries = listOf(Mode.IMAGE, Mode.VIDEO).flatMap { app.history.list(it) }
        val floor = prefs.getLong("auto_save_from", -1L).let { if (it >= 0) it else (entries.maxOfOrNull { e -> e.id } ?: 0L).also { max -> prefs.edit().putLong("auto_save_from", max).apply() } }
        val fresh = entries.filter { it.id > floor && it.state == "succeeded" && it.baseUrl != History.PHONE_BASE }
        if (fresh.isEmpty()) return
        val outputs = app.history.outputs(fresh.map { it.id })
        fresh.forEach { e -> outputs[e.id].orEmpty().filter { it.localFile.isNotEmpty() }.forEach { o ->
            val each = e.copy(localFile = o.localFile)
            val file = localMedia(each)
            if (file.exists() && prefs.getString(savedKey(each, file), null) == null) saveMedia(each, quiet = true)
        } }
    }

    private fun saveMedia(e: Entry, quiet: Boolean = false) {
        val file = localMedia(e)
        if (!file.exists()) { if (!quiet) showError("The private media file is missing."); return }
        val tree = savedTree() ?: run { if (!quiet) pickFolder { uri -> if (uri != null) { rememberTree(uri); saveMedia(e) } }; return }
        val key = savedKey(e, file)
        if (!saving.add(key)) return
        io.execute {
            var created: Uri? = null
            try {
                // A copy saved earlier (by hand or automatically) that is still there is not made again.
                prefs.getString(key, null)?.let(Uri::parse)?.takeIf(::documentExists)?.let {
                    if (!quiet) runOnUiThread { alreadySavedSnack(tree) }
                    return@execute
                }
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                created = DocumentsContract.createDocument(contentResolver, parent, mimeOf(file), "OTStudio-${e.id}-${file.name}") ?: throw UserError("Android could not create the saved file.")
                contentResolver.openOutputStream(created)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: throw UserError("Android could not write the saved file.")
                prefs.edit().putString(key, created.toString()).apply()
                if (!quiet) runOnUiThread { savedSnack(tree) }
            } catch (ex: Exception) {
                created?.let { try { DocumentsContract.deleteDocument(contentResolver, it) } catch (_: Exception) { } }
                if (!quiet) runOnUiThread {
                    if (ex is SecurityException) { prefs.edit().remove("save_tree").apply(); showError("OT Studio lost access to the save folder. Tap Save again to choose it.") }
                    else showError((ex as? UserError)?.message ?: "Could not save the media. Check the folder and available storage.")
                }
            } finally { saving.remove(key) }
        }
    }

    private fun alreadySavedSnack(tree: Uri) {
        if (isFinishing || isDestroyed) return
        Snackbar.make(screen, "Already saved in ${folderName(tree)}", Snackbar.LENGTH_LONG).setAction("Open folder") { openFolder(tree) }.show()
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
