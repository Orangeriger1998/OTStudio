package com.orangetiger.otstudio

import net.ladenthin.llama.LlamaIterator
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.args.LogFormat
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import org.json.JSONArray
import org.json.JSONObject

/** [LocalLlm] on llama.cpp through java-llama.cpp. The same library runs on desktop JVMs. */
class LlamaEngine : LocalLlm {
    private var model: LlamaModel? = null
    @Volatile private var running: LlamaIterator? = null
    @Volatile private var cancelled = false
    override var loadedPath: String? = null
        private set
    override var lastStats: ChatStats? = null
        private set

    private var stateDir: String? = null

    override fun load(path: String, contextTokens: Int, threads: Int, stateDir: String?, progress: (Float) -> Boolean) {
        unload()
        var aborted = false
        try {
            // Silence native logging: llama.cpp can echo prompts into the system log.
            LlamaModel.setLogger(LogFormat.TEXT) { _, _ -> }
            val params = ModelParameters().setModel(path).setCtxSize(contextTokens).setThreads(threads).setThreadsBatch(threads)
                .setParallel(1).enableJinja()
            if (stateDir != null) params.setSlotSavePath(stateDir)
            model = LlamaModel(params) { p -> progress(p).also { if (!it) aborted = true } }
            loadedPath = path
            this.stateDir = stateDir
        } catch (e: UnsatisfiedLinkError) {
            throw UserError("This phone's processor cannot run the on-device engine. Use OpenRouter for chat instead.")
        } catch (e: Exception) {
            if (aborted) throw UserError("Stopped while loading the model.")
            throw UserError("The model could not be loaded. The file may be damaged, or this engine may not support it.")
        }
    }

    override fun unload() {
        cancel()
        model?.close()
        model = null
        loadedPath = null
    }

    override fun saveState(): Boolean = try {
        val m = model
        // java-llama.cpp passes the name through as the path, without the slot-save folder, so give it the full path.
        if (m == null || stateDir == null) false else { m.saveSlot(0, java.io.File(stateDir, STATE_FILE).path); true }
    } catch (_: Exception) { false }

    override fun restoreState(): Boolean {
        val m = model ?: return false
        val file = java.io.File(stateDir ?: return false, STATE_FILE)
        if (!file.isFile) return false
        return try { m.restoreSlot(0, file.path); true } catch (_: Exception) { file.delete(); false }
    }

    override fun countTokens(text: String): Int = model?.encode(text)?.size ?: (text.length / 3 + 1)

    override fun chat(messages: List<Turn>, maxTokens: Int, responseFormat: String?, thinking: Boolean, onText: (String) -> Unit): String {
        val m = model ?: throw UserError("Load a model first.")
        cancelled = false
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }
        var params = InferenceParameters.empty().withMessagesJson(array.toString()).withNPredict(maxTokens).withCachePrompt(true)
            // Hybrid models such as Qwen3 think before answering unless told not to. Thinking is off unless the user turns it on.
            .withChatTemplateKwargs(mapOf("enable_thinking" to thinking.toString()))
        if (responseFormat != null) params = params.withResponseFormat(responseFormat)
        val out = StringBuilder()
        val start = System.nanoTime()
        var first = 0L
        lastStats = null
        m.generateChat(params).use { iterable ->
            val iterator = iterable.iterator()
            running = iterator
            try {
                while (!cancelled && iterator.hasNext()) {
                    val piece = iterator.next().text
                    if (piece.isNotEmpty()) { if (first == 0L) first = System.nanoTime(); out.append(piece); onText(out.toString()) }
                }
            } finally { running = null }
        }
        if (first > 0) lastStats = ChatStats((first - start) / 1_000_000, countTokens(out.toString()), (System.nanoTime() - first) / 1_000_000)
        return out.toString()
    }

    override fun cancel() { cancelled = true; running?.cancel() }

    companion object { const val STATE_FILE = "chat.bin" }
}
