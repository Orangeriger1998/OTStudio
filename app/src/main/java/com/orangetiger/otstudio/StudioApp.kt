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
        if (mode == Mode.CHAT && local.engine == ChatEngine.LOCAL) return sendLocal(p)
        val model = settings.resolve(mode)
        ModelOptions.validateForModel(model.settings.model, model.settings.schema ?: throw UserError("Open API CALL and load this model's inputs first."), model.settings.options)
        if (mode != Mode.CHAT && history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) {
            throw UserError("Finish or cancel the pending media job before starting another. You can still use text chat.")
        }
        run {
            // Memory goes first. With OpenRouter chat, these lines are sent to OpenRouter with each message.
            val memory = if (mode == Mode.CHAT) memoryPrompt() else ""
            val turns = if (mode == Mode.CHAT) (if (memory.isEmpty()) emptyList() else listOf(Turn("system", memory))) + Rules.boundedTurns(history.turns(), p) else emptyList()
            val id = history.insert(mode, p, model)
            changed()
            if (mode == Mode.CHAT) {
                try { history.update(id, "succeeded", api.chat(model, turns)) }
                catch (e: Exception) { history.update(id, "failed", friendly(e)) }
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
            stopRequested = false
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
        val first = history.outputs(listOf(id))[id]?.firstOrNull()?.localFile.orEmpty()
        history.update(id, "succeeded", "${outputs.size} output(s) ready to preview or save.", localFile = first)
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
