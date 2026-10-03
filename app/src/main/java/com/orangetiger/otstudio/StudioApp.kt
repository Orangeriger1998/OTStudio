package com.orangetiger.otstudio

import android.app.Application
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
        history.recover()
    }

    fun isBusy() = busy.get()
    fun stopWaiting() { transport.cancel(); activeDownload?.disconnect() }
    private fun changed() { main.post { listener?.invoke() } }
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
        val model = settings.resolve(mode)
        ModelOptions.validateForModel(model.settings.model, model.settings.schema ?: throw UserError("Open API CALL and load this model's inputs first."), model.settings.options)
        if (mode != Mode.CHAT && history.list(pendingOnly = true).any { it.mode != Mode.CHAT }) {
            throw UserError("Finish or cancel the pending media job before starting another. You can still use text chat.")
        }
        run {
            val turns = if (mode == Mode.CHAT) Rules.boundedTurns(history.turns(), p) else emptyList()
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
}
