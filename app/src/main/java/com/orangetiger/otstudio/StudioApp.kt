package com.orangetiger.otstudio

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class StudioApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var history: History
        private set
    lateinit var loras: LoraStore
        private set
    lateinit var local: LocalModelStore
        private set
    /** The on-device chat engine. Loading and replies run on [executor], one at a time, like every other request. */
    val engine: LocalLlm = LlamaEngine()
    /** "idle", "loading", or "ready" for the on-device model. */
    @Volatile var localState = "idle"
        private set
    /** Load progress from 0 to 100 while [localState] is "loading". */
    @Volatile var loadPercent = 0
        private set
    @Volatile private var loadedKey: String? = null
    @Volatile private var stopRequested = false
    /** The chat entry whose reply is streaming, with the text so far. */
    @Volatile var streaming: Pair<Long, String>? = null
        private set
    var streamListener: ((Long, String) -> Unit)? = null
    @Volatile private var lastStreamPost = 0L
    private var startedActivities = 0
    private val unloadLater = Runnable { unloadLocal() }

    private val downloads = Executors.newSingleThreadExecutor()
    private val fetcher = HttpFetcher()
    @Volatile private var downloader: ModelDownloader? = null
    /** The model download in progress, paused, or failed in this session. */
    @Volatile var download: DownloadStatus? = null
        private set
    var modelsListener: (() -> Unit)? = null
    private val transport = HttpsTransport()
    private val api = ProviderApi(transport)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var activeDownload: HttpURLConnection? = null
    var listener: (() -> Unit)? = null
    var lastError: String = ""
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        history = History(this)
        loras = LoraStore(this)
        local = LocalModelStore(this)
        history.saveChat = saveChatHistory
        try { NativeEngine.prepare(this) } catch (_: Exception) { }
        history.recover()
        // Free the model's memory when the app stays in the background for five minutes.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++; main.removeCallbacks(unloadLater) }
            override fun onActivityStopped(activity: Activity) { startedActivities--; if (startedActivities <= 0) main.postDelayed(unloadLater, 5 * 60_000L) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { }
            override fun onActivityResumed(activity: Activity) { }
            override fun onActivityPaused(activity: Activity) { }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) { }
            override fun onActivityDestroyed(activity: Activity) { }
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && startedActivities <= 0) unloadLocal()
    }

    /** Unloads the on-device model after any running reply finishes. */
    fun unloadLocal() {
        executor.execute { if (engine.loadedPath != null) { engine.unload(); loadedKey = null; localState = "idle"; changed() } }
    }

    private val chatPrefs by lazy { getSharedPreferences("chat", MODE_PRIVATE) }
    private fun chatStateDir() = File(cacheDir, "chat-state")

    /**
     * Save chat history (default on). Off keeps new chat turns in memory only, and the on-device engine stops saving
     * its conversation state. Image and video entries are saved either way.
     */
    var saveChatHistory: Boolean
        get() = chatPrefs.getBoolean("save", true)
        set(v) {
            chatPrefs.edit().putBoolean("save", v).apply()
            history.dropUnsaved()
            history.saveChat = v
            chatStateDir().deleteRecursively()
            unloadLocal()
            changed()
        }

    /** Create images and videos from chat (default on): every chat reply is a job plan, shown as a job card when it describes media. */
    var createFromChat: Boolean
        get() = chatPrefs.getBoolean("create", true)
        set(v) { chatPrefs.edit().putBoolean("create", v).apply(); changed() }

    /** The picture the user pointed at with "Talk about this". "That picture" in the next message means it. */
    // Kept across restarts and updates, so an attached photo stays attached.
    var selectedImage: Long?
        get() = chatPrefs.getLong("selected", 0L).takeIf { it > 0 }
        set(v) { chatPrefs.edit().putLong("selected", v ?: 0L).apply() }

    /** Finished pictures the chat may refer to, newest first, with the selected one always included. */
    fun imageRefs(): List<ImageRef> {
        val done = history.list(Mode.IMAGE).filter { it.state == "succeeded" && it.localFile.isNotEmpty() }.asReversed()
        val selected = selectedImage?.let { id -> done.firstOrNull { it.id == id } }
        return (listOfNotNull(selected) + done.filter { it.id != selected?.id }).take(Planner.MAX_IMAGES).map { ImageRef(it.id, describe(it)) }
    }

    /** What a picture shows, in words. An edit's own prompt is only its instruction, so it is its source plus that instruction. */
    fun describe(e: Entry): String = if (e.parentId > 0) (history.entry(e.parentId)?.let { "${describe(it)}, edited: ${e.prompt}" } ?: e.prompt) else e.prompt

    /** Stores a model's reply as a plan, an answer, or an invalid plan the user can replace with their own words. */
    private fun storePlan(id: Long, raw: String, refs: List<ImageRef>, selected: Long?, summary: String? = null, found: List<SearchResult> = emptyList()) {
        val sources = SearchResult.listToJson(found).ifEmpty { null }
        when (val r = Planner.parse(raw, refs, selected)) {
            is PlanResult.Ok -> history.update(id, "succeeded", r.plan.reply, summary = summary, plan = if (r.plan.action == "answer") null else r.plan.toJson(), sources = sources)
            is PlanResult.Invalid -> history.update(id, "succeeded", r.reply.ifEmpty { "No plan came back." }, summary = summary, plan = JobPlan("invalid", r.reason).toJson(), sources = sources)
        }
    }

    private val web = WebSearch(HttpWebFetch())

    /**
     * Runs the planner, answering up to three web searches in between. Each search result goes back to the model as
     * quoted, untrusted data. [fits] tells whether another round of results still fits the model's context.
     * Returns the final reply and the searches made.
     */
    private fun planWithSearch(id: Long, start: List<Turn>, refs: List<ImageRef>, selected: Long?, fits: (List<Turn>) -> Boolean, ask: (List<Turn>, Boolean) -> String): Pair<String, List<SearchResult>> {
        var messages = start
        val found = ArrayList<SearchResult>()
        var last = ""
        while (true) {
            // Stop during a search ends the turn; nothing more is asked of the model.
            if (stopRequested && found.isNotEmpty()) return last to found
            val allow = settings.allowSearch && found.size < WebSearch.MAX_SEARCHES && !stopRequested && fits(messages)
            val raw = ask(messages, allow)
            last = raw
            val r = Planner.parse(raw, refs, selected, allow)
            if (r !is PlanResult.Ok || r.plan.action != "search") return raw to found
            postStream(id, "Searching the web for: ${r.plan.prompt}")
            val key = try { settings.searchKey().ifBlank { null } } catch (_: UserError) { null }
            val result = try { web.search(r.plan.prompt, key) }
                catch (e: Exception) { SearchResult(r.plan.prompt, "failed", (e as? UserError)?.message ?: "The search could not reach the service.", emptyList()) }
            found.add(result)
            if (stopRequested) return raw to found
            messages = messages + Turn("assistant", raw.trim()) +
                Turn("user", if (result.source == "failed") "The web search failed: ${result.answer} Answer without it, and say that the search failed." else WebSearch.context(result))
        }
    }

    /** Enabled memory lines, as the start of every chat's system prompt. */
    fun memoryPrompt(): String = MemoryPrompt.build(history.memory.lines())

    /** Deletes every chat turn, saved or not, and the engine's saved conversation state. */
    fun clearChat() {
        if (busy.get()) throw UserError("Wait for the current reply to finish.")
        history.clearChat()
        chatStateDir().deleteRecursively()
        unloadLocal()
        changed()
    }

    fun isBusy() = busy.get()
    fun stopWaiting() { stopRequested = true; engine.cancel(); transport.cancel(); activeDownload?.disconnect() }
    private fun changed() { main.post { listener?.invoke() } }
    private fun modelsChanged() { main.post { modelsListener?.invoke(); listener?.invoke() } }
    private fun friendly(e: Exception): String = (e as? UserError)?.message ?: "Connection or device storage failed. Check the provider dashboard before repeating a paid request."

    private fun run(task: () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        lastError = ""
        changed()
        executor.execute {
            try { task() } catch (e: Exception) { lastError = friendly(e) }
            finally { busy.set(false); changed() }
        }
    }

    fun send(mode: Mode, prompt: String): Boolean {
        if (busy.get()) return false
        val p = Rules.prompt(prompt)
        // Reset here, before the task is queued, so a Stop tapped while it waits is kept.
        stopRequested = false
        if (mode == Mode.CHAT && local.engine == ChatEngine.LOCAL) return sendLocal(p)
        val model = settings.resolve(mode)
        ModelOptions.validateForModel(model.settings.model, model.settings.schema ?: throw UserError("Open API CALL and load this model's inputs first."), model.settings.options)
        if (mode != Mode.CHAT && history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) {
            throw UserError("Finish or cancel the pending media job before starting another. You can still use text chat.")
        }
        run {
            // Memory goes first. With OpenRouter chat, these lines are sent to OpenRouter with each message.
            val memory = if (mode == Mode.CHAT) memoryPrompt() else ""
            val plan = mode == Mode.CHAT && createFromChat
            val refs = if (plan) imageRefs() else emptyList()
            val selected = selectedImage
            val system = if (plan) Planner.systemPrompt(memory, settings.allowSearch) else memory
            val bounded = if (mode == Mode.CHAT) Rules.boundedTurns(history.turns(), p) else emptyList()
            // With a plan, the newest message carries the app's note on the pictures.
            val withNote = if (plan && bounded.lastOrNull()?.role == "user") bounded.dropLast(1) + Turn("user", Planner.userMessage(bounded.last().content, refs, selected)) else bounded
            val turns = if (mode == Mode.CHAT) (if (system.isEmpty()) emptyList() else listOf(Turn("system", system))) + withNote else emptyList()
            val id = history.insert(mode, p, model)
            changed()
            if (mode == Mode.CHAT) {
                try {
                    streaming = null
                    if (plan) {
                        val (raw, found) = planWithSearch(id, turns,
                            refs, selected, { m -> m.sumOf { it.content.length } < 60_000 }) { m, allow -> api.chat(model, m, Planner.responseFormat(refs, allow)) }
                        storePlan(id, raw, refs, selected, found = found)
                    }
                    else history.update(id, "succeeded", api.chat(model, turns))
                }
                catch (e: Exception) { history.update(id, "failed", friendly(e)) }
                finally { streaming = null }
            } else {
                try { applyPrediction(id, mode, api.create(model, p)) }
                catch (e: ProviderRejected) { history.update(id, "failed", "${e.message} No job was created.") }
                catch (e: Exception) {
                    history.update(id, "uncertain", "${friendly(e)} No job ID was saved. The provider may have accepted the request; inspect its dashboard before retrying.")
                }
            }
        }
        return true
    }

    private fun threads(): Int = local.threads.takeIf { it > 0 } ?: (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 6)

    private fun sendLocal(p: String): Boolean {
        val m = local.selected() ?: throw UserError("Download a model first: Settings, Chat, Manage models. Or switch Chat to OpenRouter.")
        run {
            val turns = history.turns()
            val id = history.insertLocal(p, "${m.title} (on this phone)")
            engine.clearCancel()
            if (stopRequested) engine.cancel()
            changed()
            NativeEngine.prepare(this)
            NativeEngine.begin(this)
            try {
                val path = local.file(m).path
                val key = "$path|${local.contextTokens}|${threads()}"
                if (loadedKey != key || engine.loadedPath != path) {
                    loadedKey = null; localState = "loading"; loadPercent = 0; changed()
                    val stateDir = chatStateDir().apply { mkdirs() }
                    engine.load(path, local.contextTokens, threads(), stateDir.path) { f ->
                        val pct = (f * 100).toInt().coerceIn(0, 100)
                        if (pct >= loadPercent + 5) { loadPercent = pct; changed() }
                        !stopRequested
                    }
                    loadedKey = key
                    // The saved conversation state only fits the same model, context size, and engine build.
                    val stateKey = File(stateDir, "key.txt")
                    val wanted = "$key|${NativeEngine.chosen}|${m.localName}"
                    val restored = stateKey.isFile && stateKey.readText() == wanted && engine.restoreState()
                    if (!restored) { File(stateDir, LlamaEngine.STATE_FILE).delete(); stateKey.writeText(wanted) }
                }
                localState = "ready"; changed()
                if (stopRequested) { history.update(id, "canceled", "Stopped before the reply began."); return@run }
                if (createFromChat) {
                    // Planning: the grammar from the plan's JSON schema shapes every token, and thinking stays off.
                    val refs = imageRefs()
                    val selected = selectedImage
                    val messages = ChatWindow.fit(Planner.systemPrompt(memoryPrompt(), settings.allowSearch), turns, Planner.userMessage(p, refs, selected), local.contextTokens, Planner.REPLY_TOKENS, engine::countTokens)
                    streaming = id to ""
                    // One round of results takes up to about 1,500 tokens, plus the reply.
                    val fits = { m: List<Turn> -> m.sumOf { engine.countTokens(it.content) + 8 } + 1500 + Planner.REPLY_TOKENS < local.contextTokens }
                    val (raw, found) = planWithSearch(id, messages, refs, selected, fits) { m, allow ->
                        engine.chat(m, Planner.REPLY_TOKENS, Planner.responseFormat(refs, allow), false) { postStream(id, Planner.streamingReply(it)) }
                    }
                    val stats = engine.lastStats?.summary()
                    if (raw.isNotBlank() && history.saveChat) engine.saveState()
                    if (stopRequested) history.update(id, if (raw.isBlank()) "canceled" else "stopped", Planner.streamingReply(raw).ifEmpty { "Stopped before the reply began." }, summary = stats)
                    else if (raw.isBlank()) history.update(id, "failed", "The model returned no text. Try again or choose another model.", summary = stats)
                    else storePlan(id, raw, refs, selected, stats, found)
                    return@run
                }
                val messages = ChatWindow.fit(memoryPrompt(), turns, p, local.contextTokens, LOCAL_REPLY_TOKENS, engine::countTokens)
                streaming = id to ""
                val thinking = local.showThinking
                // Thinking uses tokens before the answer starts, so it gets a larger budget.
                val raw = engine.chat(messages, if (thinking) LOCAL_REPLY_TOKENS * 3 else LOCAL_REPLY_TOKENS, null, thinking) { postStream(id, it) }.trim()
                val answer = ReplyText.visible(raw)
                val text = if (thinking && answer.isNotEmpty()) raw else answer
                val stats = engine.lastStats?.summary()
                // Keeps the conversation ready for the next load, so reopening the app does not reread it.
                // Not while chat history is off: then nothing of the conversation may outlive the app.
                if (text.isNotEmpty() && history.saveChat) engine.saveState()
                if (stopRequested) history.update(id, if (text.isEmpty()) "canceled" else "stopped", text.ifEmpty { "Stopped before the reply began." }, summary = stats)
                else history.update(id, if (text.isEmpty()) "failed" else "succeeded", text.ifEmpty { "The model returned no text. Try again or choose another model." }, summary = stats)
            } catch (e: Exception) {
                if (engine.loadedPath == null) localState = "idle"
                if (stopRequested && engine.loadedPath == null) { history.update(id, "canceled", "Stopped while loading the model."); return@run }
                history.update(id, "failed", (e as? UserError)?.message ?: "The on-device model stopped with an error. Try again, or choose a smaller model.")
            } finally { streaming = null; NativeEngine.end(this) }
        }
        return true
    }

    private fun postStream(id: Long, text: String) {
        streaming = id to text
        val now = System.nanoTime()
        if (now - lastStreamPost < 60_000_000L) return
        lastStreamPost = now
        main.post { streaming?.let { (sid, t) -> if (sid == id) streamListener?.invoke(sid, t) } }
    }

    fun selectModel(m: LocalModelInfo) {
        if (!local.installed(m)) throw UserError("Download this model first.")
        local.select(m)
        if (engine.loadedPath != null && engine.loadedPath != local.file(m).path) unloadLocal()
        modelsChanged()
    }

    fun setEngine(e: ChatEngine) {
        local.engine = e
        if (e == ChatEngine.OPENROUTER) unloadLocal()
        changed()
    }

    fun startDownload(m: LocalModelInfo) {
        if (download?.running == true) throw UserError("Another model is downloading. Pause it first.")
        if (local.installed(m)) return
        val d = ModelDownloader(local.folder, fetcher)
        downloader = d
        download = DownloadStatus(m, local.part(m).length(), m.bytes, verifying = false, running = true)
        modelsChanged()
        downloads.execute {
            try {
                d.download(m, local::freeBytes) { p -> download = DownloadStatus(m, p.done, p.total, p.verifying, true); modelsChanged() }
                download = null
                if (local.selected() == null) local.select(m)
            } catch (e: Exception) {
                val before = download ?: DownloadStatus(m, 0, m.bytes, verifying = false, running = false)
                val paused = e is DownloadPaused || d.stopping
                download = before.copy(done = local.part(m).length(), verifying = false, running = false,
                    error = if (paused) "" else (e as? UserError)?.message ?: "The download stopped. Check the connection, then tap Resume.")
            } finally { downloader = null; modelsChanged() }
        }
    }

    fun pauseDownload() { downloader?.let { it.requestStop(); fetcher.cancel() } }

    fun deleteModel(m: LocalModelInfo) {
        if (download?.running == true && download?.model?.localName == m.localName) throw UserError("Pause the download before deleting it.")
        if (busy.get()) throw UserError("Wait for the current reply to finish.")
        executor.execute {
            if (engine.loadedPath == local.file(m).path) { engine.unload(); loadedKey = null; localState = "idle" }
            local.delete(m)
            if (download?.model?.localName == m.localName) download = null
            modelsChanged()
        }
    }

    /**
     * Edits a saved image by instruction: uploads it with the Image mode's Replicate key, then starts one prediction
     * on [editModel] with the image in that model's image input. The result appears as a new Image entry linked to [source].
     * The user's tap on Edit in the dialog is the confirmation for this paid request. No automatic retry.
     */
    fun edit(source: Entry, instruction: String, editModel: String, read: () -> Triple<ByteArray, String, String>, chatId: Long = 0): Boolean {
        if (busy.get()) return false
        val text = Rules.prompt(instruction)
        val preset = ModelCatalog.edit(editModel) ?: throw UserError("Choose an editing model.")
        if (history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) throw UserError("Finish or cancel the pending media job before starting another.")
        val image = settings.resolve(Mode.IMAGE)
        // Checked before the upload, so a settings problem never leaves an orphan file at the provider.
        val chosen = keepShape(settings.forModel(image.settings, preset.model))
        ModelOptions.validateForModel(preset.model, chosen.schema ?: throw UserError("This editing model has no input list."), chosen.options.filter { it.name != preset.imageField } + InputValue(preset.imageField, InputKind.TEXT, "https://api.replicate.com/v1/files/check"))
        settings.editModel = preset.model
        run {
            val (bytes, mime, name) = read()
            val url = api.uploadFile(image.settings.baseUrl, image.key, name, mime, bytes)
            val model = image.copy(settings = chosen.copy(options = chosen.options.filter { it.name != preset.imageField } + InputValue(preset.imageField, InputKind.TEXT, url)))
            val id = history.insert(Mode.IMAGE, text, model, parentId = source.id)
            if (chatId != 0L) history.setJob(chatId, id)
            changed()
            try { applyPrediction(id, Mode.IMAGE, api.create(model, text)) }
            catch (e: ProviderRejected) { history.update(id, "failed", "${e.message} No job was created.") }
            catch (e: Exception) { history.update(id, "uncertain", "${friendly(e)} No job ID was saved. The provider may have accepted the request; inspect its dashboard before retrying.") }
        }
        return true
    }

    /**
     * An edit keeps the picture's own shape. A shape saved in the model's profile, such as 9:16, is replaced by
     * match_input_image where the model offers it; some models refuse a fixed shape together with an input picture.
     */
    private fun keepShape(s: ModelSettings): ModelSettings {
        val aspect = s.schema?.fields?.firstOrNull { it.name == "aspect_ratio" } ?: return s
        if ("match_input_image" !in aspect.choices) return s
        return s.copy(options = s.options.filter { it.name != "aspect_ratio" } + InputValue("aspect_ratio", InputKind.TEXT, "match_input_image"))
    }

    /** A placeholder that passes URL checks, for validating settings before the real upload exists. */
    private val uploadCheck = "https://api.replicate.com/v1/files/check"

    /** Reads a finished image's first output from private storage: bytes, MIME type, and file name. */
    fun mediaBytes(entry: Entry): Triple<ByteArray, String, String> {
        val file = mediaFile(entry)
        if (file.length() > Rules.MAX_UPLOAD_BYTES) throw UserError("Pictures must be smaller than 20 MB to upload.")
        val mime = when (file.extension) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> "image/webp" }
        return Triple(file.readBytes(), mime, file.name)
    }

    /** Models a plan may use: edits use editing models; a video from a picture needs a starting-frame model. */
    fun planModels(plan: JobPlan): List<String> = when (plan.action) {
        "edit" -> ModelCatalog.edits.map { it.model }
        "expand" -> listOf(ModelCatalog.expander.model)
        "video" -> settings.models(Mode.VIDEO, settings.get(Mode.VIDEO).baseUrl)
            .filter { if (plan.reference != null) ModelOptions.supports(it, setOf("Starting frame")) else !ModelCatalog.needsStartPicture(it.model) && it.schema.fields.none { f -> f.required && f.uri } }.map { it.model }
        else -> settings.models(Mode.IMAGE, settings.get(Mode.IMAGE).baseUrl).filter { ModelCatalog.edit(it.model) == null }.map { it.model }
    }

    /** The model a plan's card starts with: the user's current one when it fits, else the first that does. */
    fun defaultPlanModel(plan: JobPlan): String {
        val allowed = planModels(plan)
        val current = when (plan.action) { "edit" -> settings.editModel; "video" -> settings.get(Mode.VIDEO).model; else -> settings.get(Mode.IMAGE).model }
        return current.takeIf { it in allowed } ?: allowed.firstOrNull() ?: current
    }

    /**
     * The settings a plan's job would use with [modelId], as the card shows them. A picture to start from or edit
     * is a placeholder here; the real upload happens on Generate.
     */
    fun planSettings(plan: JobPlan, modelId: String, ratio: String? = null): Pair<ModelSettings, List<String>> {
        if (plan.action == "expand") return expandSettings(settings.get(Mode.IMAGE), uploadCheck, uploadCheck) to emptyList()
        val mode = if (plan.action == "video") Mode.VIDEO else Mode.IMAGE
        val current = settings.get(mode)
        // A plan attaches only the picture it names. Pictures saved in a model's profile (a reference, frames, a clip) stay out.
        fun withoutPictures(s: ModelSettings) = s.copy(options = s.options.filter { o -> s.schema?.fields?.firstOrNull { it.name == o.name }?.uri != true })
        val chosen = withoutPictures(if (modelId == current.model) current else settings.forModel(current, modelId))
        val withPicture = when {
            plan.action == "edit" -> ModelCatalog.edit(modelId)?.let { e -> keepShape(chosen).let { k -> k.copy(options = k.options.filter { it.name != e.imageField } + InputValue(e.imageField, InputKind.TEXT, uploadCheck)) } } ?: chosen
            plan.action == "video" && plan.reference != null -> MediaRole.START_FRAME.apply(chosen, uploadCheck) { withoutPictures(settings.forModel(chosen, it)) }
            else -> chosen
        }
        return JobBuilder.apply(withPicture, plan)
    }

    /**
     * Starts the job a chat plan describes. Called only from the Generate button on its job card, which is the user's
     * confirmation for this one paid request. [prompt] is the card's prompt, which the user may have changed.
     */
    fun runPlan(chat: Entry, plan: JobPlan, prompt: String, modelId: String, ratio: String? = null): Boolean {
        if (busy.get()) return false
        if (chat.jobEntry != 0L) throw UserError("This plan already ran or was canceled.")
        val text = Rules.prompt(prompt)
        if (modelId !in planModels(plan)) throw UserError("Choose a model from the list on the card.")
        if (history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) throw UserError("Finish or cancel the pending media job before starting another.")
        val source = plan.reference?.let { history.entry(it) }
        if (plan.reference != null && source == null) throw UserError("The picture this plan uses is gone.")
        if (plan.action == "edit") return edit(source ?: throw UserError("This edit has no picture."), text, modelId, { mediaBytes(source) }, chat.id)
        if (plan.action == "expand") return expand(source ?: throw UserError("This plan has no picture."), ratio ?: Planner.expandRatio(plan.shape), text, chat.id)
        val mode = if (plan.action == "video") Mode.VIDEO else Mode.IMAGE
        val (planned, _) = planSettings(plan, modelId)
        val resolved = Rules.resolve(mode, { if (it == mode) planned else settings.get(it) }, settings::key)
        ModelOptions.validateForModel(planned.model, planned.schema ?: throw UserError("Load this model's inputs in API CALL first."), planned.options)
        run {
            var model = resolved
            if (mode == Mode.VIDEO && source != null) {
                val (bytes, mime, name) = mediaBytes(source)
                val url = api.uploadFile(resolved.settings.baseUrl, resolved.key, name, mime, bytes)
                model = resolved.copy(settings = MediaRole.START_FRAME.apply(resolved.settings, url) { error("The planned model already starts from a picture.") })
            }
            val id = history.insert(mode, text, model, parentId = source?.id ?: 0)
            history.setJob(chat.id, id)
            changed()
            try { applyPrediction(id, mode, api.create(model, text)) }
            catch (e: ProviderRejected) { history.update(id, "failed", "${e.message} No job was created.") }
            catch (e: Exception) { history.update(id, "uncertain", "${friendly(e)} No job ID was saved. The provider may have accepted the request; inspect its dashboard before retrying.") }
        }
        return true
    }

    /** Makes a video plan start without its picture, when the model attached one the user did not want. */
    fun dropPlanPicture(chat: Entry, plan: JobPlan) {
        if (chat.jobEntry != 0L || plan.action != "video") return
        history.update(chat.id, chat.state, plan = plan.copy(reference = null).toJson())
        changed()
    }

    fun cancelPlan(chat: Entry) { if (chat.jobEntry == 0L) { history.setJob(chat.id, -1); changed() } }

    /** Replaces a missing or declined plan with the user's own words, as typed, for [action] "image" or "video". */
    fun planFromWords(chat: Entry, action: String) {
        if (busy.get()) throw UserError("Wait for the current reply to finish.")
        val reference = when (action) {
            "video" -> selectedImage ?: imageRefs().firstOrNull()?.id
            // An edit needs the picture the user chose, never a guess.
            "edit" -> selectedImage?.takeIf { id -> imageRefs().any { it.id == id } } ?: throw UserError("Choose the picture first: tap Talk about this on it, or add a photo.")
            else -> null
        }
        history.update(chat.id, chat.state, plan = JobPlan(action, "Your own words, as you typed them.", chat.prompt, reference).toJson())
        history.setJob(chat.id, 0)
        changed()
    }

    /**
     * Upscales a saved image 2x with Real-ESRGAN: uploads it, then starts one prediction that appears as a new Image entry.
     * Uses the Image mode's Replicate key. Returns false if another operation is running.
     */
    fun upscale(source: Entry, read: () -> Triple<ByteArray, String, String>): Boolean {
        if (busy.get()) return false
        if (history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) throw UserError("Finish or cancel the pending media job before starting another.")
        val image = settings.resolve(Mode.IMAGE)
        run {
            val (bytes, mime, name) = read()
            val url = api.uploadFile(image.settings.baseUrl, image.key, name, mime, bytes)
            val preset = ModelCatalog.upscaler
            val model = image.copy(settings = image.settings.copy(model = preset.model, schema = preset.schema,
                options = listOf(InputValue("image", InputKind.TEXT, url), InputValue("scale", InputKind.NUMBER, "2"))))
            val id = history.insert(Mode.IMAGE, Rules.prompt("Upscale 2x: ${source.prompt}".take(Rules.MAX_PROMPT)), model)
            changed()
            try { applyPrediction(id, Mode.IMAGE, api.create(model, null)) }
            catch (e: ProviderRejected) { history.update(id, "failed", "${e.message} No job was created.") }
            catch (e: Exception) { history.update(id, "uncertain", "${friendly(e)} No job ID was saved. The provider may have accepted the request; inspect its dashboard before retrying.") }
        }
        return true
    }

    /** Fill settings: canvas and mask, the canvas's own size, PNG out, and the safety checker off like the other models. */
    private fun expandSettings(base: ModelSettings, imageUrl: String, maskUrl: String): ModelSettings {
        val preset = ModelCatalog.expander
        val options = listOf(InputValue("image", InputKind.TEXT, imageUrl), InputValue("mask", InputKind.TEXT, maskUrl),
            InputValue("megapixels", InputKind.TEXT, "match_input"), InputValue("output_format", InputKind.TEXT, "png"),
            InputValue("num_inference_steps", InputKind.INTEGER, "40"), InputValue("disable_safety_checker", InputKind.BOOLEAN, "true"))
        ModelOptions.validate(preset.schema, options)
        return base.copy(model = preset.model, schema = preset.schema, options = options)
    }

    /** A finished picture's file in private storage. */
    private fun mediaFile(entry: Entry): File {
        val name = history.outputs(listOf(entry.id))[entry.id]?.firstOrNull { it.localFile.isNotEmpty() }?.localFile ?: entry.localFile
        val folder = File(filesDir, "media")
        val file = File(folder, name)
        if (name.isEmpty() || file.parentFile?.canonicalPath != folder.canonicalPath || !file.isFile) throw UserError("The picture's file is missing on this phone.")
        return file
    }

    /**
     * Expands a saved image to [ratio] with FLUX Fill dev: the picture stays as it is, and new picture fills the rest.
     * Uploads the image, then starts one prediction that appears as a new Image entry. Returns false while busy.
     */
    fun expand(source: Entry, ratio: String, prompt: String, chatId: Long = 0): Boolean {
        if (busy.get()) return false
        if (ratio !in EXPAND_RATIOS.map { it.first }) throw UserError("Choose one of the listed shapes.")
        if (history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) throw UserError("Finish or cancel the pending media job before starting another.")
        val image = settings.resolve(Mode.IMAGE)
        val words = if (prompt.isBlank()) "" else Rules.prompt(prompt)
        val file = mediaFile(source)
        // Fails here, before any upload, when the picture already has this shape.
        Outpainter.check(file, ratio)
        expandSettings(image.settings, uploadCheck, uploadCheck)
        run {
            val (canvas, mask) = Outpainter.prepare(file, ratio)
            val imageUrl = api.uploadFile(image.settings.baseUrl, image.key, "expand-canvas.jpg", "image/jpeg", canvas)
            val maskUrl = api.uploadFile(image.settings.baseUrl, image.key, "expand-mask.png", "image/png", mask)
            val model = image.copy(settings = expandSettings(image.settings, imageUrl, maskUrl))
            val label = EXPAND_RATIOS.first { it.first == ratio }.let { "${it.second} ${it.first}" }
            val id = history.insert(Mode.IMAGE, Rules.prompt("Expanded to $label${if (words.isEmpty()) "" else ": $words"}".take(Rules.MAX_PROMPT)), model, parentId = source.id)
            history.setFinish(id, "expand|${source.id}|$ratio")
            if (chatId != 0L) history.setJob(chatId, id)
            changed()
            try { applyPrediction(id, Mode.IMAGE, api.create(model, Outpaint.prompt(words))) }
            catch (e: ProviderRejected) { history.update(id, "failed", "${e.message} No job was created.") }
            catch (e: Exception) { history.update(id, "uncertain", "${friendly(e)} No job ID was saved. The provider may have accepted the request; inspect its dashboard before retrying.") }
        }
        return true
    }

    fun loadInputs(draft: ModelSettings, complete: (InputSchema?, String?) -> Unit) {
        if (isBusy()) { complete(null, "Wait for the current operation to finish."); return }
        val model = try { Rules.resolve(draft.mode, { if (it == draft.mode) draft else settings.get(it) }, settings::key) }
        catch (e: UserError) { complete(null, e.message); return }
        run {
            try { val schema = api.loadSchema(model); main.post { complete(schema, null) } }
            catch (e: Exception) { main.post { complete(null, friendly(e)) } }
        }
    }

    fun suggestSetup(prompt: String, candidates: List<ModelPreset>, requirements: String, complete: (SetupSuggestion?, String?) -> Unit) {
        if (isBusy()) { complete(null, "Wait for the current operation to finish."); return }
        val model = try { settings.resolve(Mode.CHAT) } catch (e: UserError) { complete(null, e.message); return }
        run {
            try { val suggestion = api.suggest(model, prompt, candidates, requirements); main.post { complete(suggestion, null) } }
            catch (e: Exception) { main.post { complete(null, friendly(e)) } }
        }
    }

    /** Copies a picked photo into private media and adds it to the Image feed. Runs off the main thread. Nothing is uploaded. */
    fun importPhoto(bytes: ByteArray, mime: String): Long {
        val folder = File(filesDir, "media").apply { mkdirs() }
        if (folder.listFiles().orEmpty().sumOf { it.length() } + bytes.size > Rules.MAX_STORAGE_BYTES) throw UserError("Private media storage is full (512 MB). Save your files and clear app storage in Android Settings before adding more.")
        val ext = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> throw UserError("Choose a JPG, PNG, or WebP photo.") }
        val file = File(folder, "${UUID.randomUUID()}.$ext")
        file.writeBytes(bytes)
        // The photo picker hides real file names, so the time it was added tells photos apart in the chat's picture list.
        val added = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date())
        val id = try { history.insertPhoto("Your photo, added $added", file.name) } catch (e: Exception) { file.delete(); throw e }
        changed()
        return id
    }

    /** Uploads a photo with [mode]'s Replicate key. [read] runs off the main thread and returns bytes, MIME type, and a file name. */
    fun uploadPhoto(mode: Mode, read: () -> Triple<ByteArray, String, String>, complete: (String?, String?) -> Unit) {
        if (isBusy()) { complete(null, "Wait for the current operation to finish."); return }
        val model = try { settings.resolve(mode) } catch (e: UserError) { complete(null, e.message); return }
        run {
            try {
                val (bytes, mime, name) = read()
                val url = api.uploadFile(model.settings.baseUrl, model.key, name, mime, bytes)
                main.post { complete(url, null) }
            } catch (e: Exception) { main.post { complete(null, friendly(e)) } }
        }
    }

    fun refresh(entry: Entry, cancel: Boolean = false) {
        run {
            if (entry.state in listOf("download", "unavailable") && !cancel) {
                downloadAll(entry.id, entry.mode)
                return@run
            }
            val key = settings.keyForEntry(entry)
            val result = if (cancel) api.cancel(entry.baseUrl, key, entry.remoteId) else api.status(entry.baseUrl, key, entry.remoteId)
            if (result.id != entry.remoteId) throw UserError("The provider returned a different job ID. The result was not applied.")
            applyPrediction(entry.id, entry.mode, result)
        }
    }

    fun stopTracking(entry: Entry) {
        if (busy.get() || entry.state != "processing") return
        history.update(entry.id, "untracked", "No longer tracked in this app. The provider job may still run and charge; check its dashboard.")
        lastError = ""
        changed()
    }

    fun skipDownload(entry: Entry) {
        if (busy.get() || entry.state != "download") return
        history.update(entry.id, "unavailable", "Local download skipped. You can try Download again while the provider link is still available.")
        changed()
    }

    private fun applyPrediction(id: Long, mode: Mode, p: Prediction) {
        when (p.state) {
            "succeeded" -> {
                history.completePrediction(id, p)
                changed()
                try { downloadAll(id, mode) }
                catch (e: Exception) { history.update(id, "download", "${friendly(e)} Tap Download again to retry saving this completed output.") }
            }
            "failed" -> history.update(id, "failed", "Generation failed at the provider. Check its dashboard for the cause. No automatic retry was made.", p.id)
            "canceled" -> history.update(id, "canceled", "Canceled by the provider.", p.id)
            "unusable" -> history.update(id, "failed", "${p.problem} The job finished, but this app cannot save its output. Check the provider dashboard for it.", p.id)
            else -> history.update(id, "processing", "Generation is ${p.state}. Progress refreshes while this screen is open.", p.id)
        }
    }

    private fun downloadAll(id: Long, mode: Mode) {
        val outputs = history.outputs(listOf(id))[id].orEmpty()
        if (outputs.isEmpty()) throw UserError("No saved output links are available.")
        outputs.forEach { output ->
            if (output.localFile.isEmpty() || !File(File(filesDir, "media"), output.localFile).exists()) download(id, mode, output.url, output.position)
        }
        val note = finishExpand(id)
        val first = history.outputs(listOf(id))[id]?.firstOrNull()?.localFile.orEmpty()
        history.update(id, "succeeded", note ?: "${outputs.size} output(s) ready to preview or save.", localFile = first)
    }

    /** For an Expand job: rebuilds the result at full size with the original pixels in the middle. Runs once. Returns a note, or null. */
    private fun finishExpand(id: Long): String? {
        val parts = history.finishOf(id).split('|')
        if (parts.size != 3 || parts[0] != "expand") return null
        val folder = File(filesDir, "media")
        val output = history.outputs(listOf(id))[id]?.firstOrNull() ?: return null
        val made = File(folder, output.localFile)
        try {
            val source = history.entry(parts[1].toLongOrNull() ?: 0) ?: throw UserError("The original picture is gone.")
            val out = File(folder, "${UUID.randomUUID()}.jpg")
            Outpainter.finish(mediaFile(source), parts[2], made, out)
            history.savedOutput(id, output.position, out.name)
            made.delete()
            return "Ready at full size. The original picture sits unchanged in the middle."
        } catch (e: Throwable) {
            // The model's own, smaller picture stays; nothing is charged again.
            return "The full-size version could not be made (${(e as? UserError)?.message ?: e.javaClass.simpleName}). This is the model's smaller picture."
        } finally { history.setFinish(id, "") }
    }

    private fun download(id: Long, mode: Mode, rawUrl: String, position: Int) {
        val folder = File(filesDir, "media").apply { mkdirs() }
        if (folder.listFiles().orEmpty().sumOf { it.length() } >= Rules.MAX_STORAGE_BYTES) throw UserError("Private media storage is full (512 MB). Save your files and clear app storage in Android Settings before generating more.")
        var url = Rules.outputUrl(rawUrl)
        var connection: HttpURLConnection? = null
        var part: File? = null
        var deliveryKey: String? = null
        try {
            for (attempt in 0..4) {
                val c = URL(url).openConnection() as HttpURLConnection
                connection = c
                activeDownload = c
                c.connectTimeout = 15_000; c.readTimeout = 30_000; c.instanceFollowRedirects = false
                deliveryKey?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                val code = c.responseCode
                if (code in listOf(401, 403) && deliveryKey == null && attempt < 4) {
                    val entry = history.credentialEntry(id)
                    if (Rules.authorizeDelivery(entry.baseUrl, url)) {
                        deliveryKey = settings.keyForEntry(entry); c.disconnect(); continue
                    }
                }
                if (code in 300..399 && attempt < 4) {
                    url = Rules.outputUrl(URL(URL(url), c.getHeaderField("Location") ?: "").toString())
                    c.disconnect()
                    continue
                }
                if (code != 200) throw UserError("The media download is unavailable (HTTP $code). The provider link may have expired.")
                val type = c.contentType.orEmpty().substringBefore(';').lowercase()
                val ext = when {
                    mode == Mode.VIDEO && type == "video/mp4" -> "mp4"
                    mode == Mode.IMAGE && type == "image/png" -> "png"
                    mode == Mode.IMAGE && type in listOf("image/jpeg", "image/jpg") -> "jpg"
                    mode == Mode.IMAGE && type == "image/webp" -> "webp"
                    else -> throw UserError("The media server returned an unsupported content type.")
                }
                val existingBytes = folder.listFiles().orEmpty().sumOf { it.length() }
                val limit = minOf(Rules.MAX_MEDIA_BYTES, Rules.MAX_STORAGE_BYTES - existingBytes)
                if (c.contentLengthLong > limit) throw UserError("This output exceeds the available media storage limit.")
                val file = File(folder, "${UUID.randomUUID()}.$ext")
                val temp = File(folder, "${file.name}.part")
                part = temp
                val deadline = System.nanoTime() + 180_000_000_000L
                var total = 0L
                c.inputStream.use { input -> temp.outputStream().use { output ->
                    val buf = ByteArray(16_384)
                    while (true) {
                        if (System.nanoTime() > deadline) throw UserError("Media download timed out. It can be retried without generating again.")
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw UserError("Media exceeded the storage limit.")
                        output.write(buf, 0, n)
                    }
                } }
                if (total == 0L || (c.contentLengthLong >= 0 && total != c.contentLengthLong)) throw UserError("The media download was incomplete.")
                if (!temp.renameTo(file)) throw UserError("The generated file could not be saved.")
                try { history.savedOutput(id, position, file.name) }
                catch (e: Exception) { file.delete(); throw e }
                return
            }
        } finally { activeDownload = null; connection?.disconnect(); part?.delete() }
    }

    companion object { const val LOCAL_REPLY_TOKENS = 1024 }
}

data class DownloadStatus(val model: LocalModelInfo, val done: Long, val total: Long, val verifying: Boolean, val running: Boolean, val error: String = "")
