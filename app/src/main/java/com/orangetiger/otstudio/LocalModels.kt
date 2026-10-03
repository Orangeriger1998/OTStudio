package com.orangetiger.otstudio

import java.net.URI
import java.security.MessageDigest

// Portable logic for on-device chat models: no Android types, so a desktop build can reuse it.

/** One GGUF file on Hugging Face. [sha256] is empty until Hugging Face reports it for a custom link. */
data class LocalModelInfo(
    val repo: String, val file: String, val revision: String = "main", val bytes: Long = 0,
    val sha256: String = "", val title: String = file.removeSuffix(".gguf"), val license: String = "", val note: String = ""
) {
    val id get() = "$repo/$file"
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$file"
    /** Private file name: a hash of the source, so two repositories with the same file name never collide. */
    val localName get() = MessageDigest.getInstance("SHA-256").digest("$repo/$revision/$file".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(24) + ".gguf"
}

object LocalModelCatalog {
    // Names, sizes, and SHA-256 hashes checked against the Hugging Face API on 2026-10-03.
    // Q4_0 comes first: on a Pixel 10 Pro XL it read prompts at 97 tokens per second against 65 for Q4_K_M,
    // because llama.cpp has faster ARM code for it. The imatrix (i1) version keeps its quality close to Q4_K_M.
    val entries = listOf(
        LocalModelInfo("mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-i1-GGUF", "Huihui-Qwen3-4B-Instruct-2507-abliterated.i1-Q4_0.gguf",
            bytes = 2_375_773_792, sha256 = "351bc144fcc8fefff3f8aa54bcc4912b1bb426496912fad3ea20843cada404af",
            title = "Qwen3 4B Instruct, abliterated", license = "Apache 2.0", note = "Suggested first model. The fastest choice on recent phones, and it answers without refusals."),
        LocalModelInfo("mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-GGUF", "Huihui-Qwen3-4B-Instruct-2507-abliterated.Q4_K_M.gguf",
            bytes = 2_497_281_312, sha256 = "b1324e2f1bd2e0610d591f54f93e38d90b187f69d1f215f4cace349eade678d7",
            title = "Qwen3 4B Instruct, abliterated, Q4_K_M", license = "Apache 2.0", note = "The same model in a slightly more exact format. It reads long messages about a third slower."),
        LocalModelInfo("unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            bytes = 2_497_281_120, sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
            title = "Qwen3 4B Instruct", license = "Apache 2.0", note = "The original Qwen model. It follows structured requests most reliably."),
        LocalModelInfo("unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf",
            bytes = 2_740_937_888, sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            title = "Qwen3.5 4B", license = "Apache 2.0", note = "Newer Qwen generation of the same size."),
        LocalModelInfo("bartowski/mlabonne_Qwen3-8B-abliterated-GGUF", "mlabonne_Qwen3-8B-abliterated-Q4_K_M.gguf",
            bytes = 5_027_784_288, sha256 = "361557e69ad101ee22b1baf427283b7ddcf81bc7532b8cee8ac2c6b4d1b81ead",
            title = "Qwen3 8B, abliterated", license = "Apache 2.0", note = "Writes better than the 4B models but answers about half as fast. Needs a phone with 12 GB of memory."),
        LocalModelInfo("unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf",
            bytes = 4_977_171_584, sha256 = "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
            title = "Gemma 4 E4B", license = "Apache 2.0", note = "Google's phone-sized model."),
        LocalModelInfo("mradermacher/Huihui-gemma-4-E4B-it-abliterated-GGUF", "Huihui-gemma-4-E4B-it-abliterated.Q4_K_M.gguf",
            bytes = 5_335_286_272, sha256 = "d793116ec11cacac0228e7c907a40e9765969d76dd45708f0f638df008718f87",
            title = "Gemma 4 E4B, abliterated", license = "Apache 2.0", note = "Gemma 4 E4B without refusals.")
    )
    val default get() = entries.first()
}

object HfLink {
    private val name = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    private val pathPart = Regex("[A-Za-z0-9_.+-]{1,200}")
    private val split = Regex("-\\d{5}-of-\\d{5}\\.gguf$")

    /** Accepts only https://huggingface.co/{owner}/{repo}/resolve/{revision}/{path}.gguf, with an optional download=true. */
    fun parse(raw: String): LocalModelInfo {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw UserError("Paste a Hugging Face file link that ends in .gguf.") }
        if (uri.scheme != "https" || uri.host?.lowercase() != "huggingface.co" || uri.port !in listOf(-1, 443) || uri.userInfo != null || uri.fragment != null) {
            throw UserError("Only https://huggingface.co links are accepted.")
        }
        if (uri.rawQuery != null && uri.rawQuery != "download=true") throw UserError("Remove the extra text after the question mark in the link.")
        val raw0 = uri.rawPath.orEmpty()
        if (raw0.contains('%') || raw0.contains("..")) throw UserError("Use the plain file link from the model's Files page.")
        val parts = raw0.trim('/').split('/')
        if (parts.size < 5 || (parts[2] != "resolve" && parts[2] != "blob")) throw UserError("Open the .gguf file on Hugging Face and copy its download link (it contains /resolve/).")
        val (owner, repo, _, revision) = parts
        val path = parts.drop(4)
        if (!name.matches(owner) || !name.matches(repo) || !name.matches(revision) || path.any { !pathPart.matches(it) }) throw UserError("This link has characters OT Studio does not accept.")
        val file = path.joinToString("/")
        if (!file.endsWith(".gguf", ignoreCase = true)) throw UserError("The link must point to a .gguf file.")
        if (split.containsMatchIn(file)) throw UserError("This model is split into several files. Choose a single-file version.")
        if (file.contains("mmproj", ignoreCase = true)) throw UserError("This is a vision add-on file, not a chat model.")
        return LocalModelInfo("$owner/$repo", file, revision)
    }
}

/** Fits a conversation into the model's context: the system prompt and the new message stay, older turns drop first. */
object ChatWindow {
    fun fit(system: String, history: List<Turn>, prompt: String, contextTokens: Int, replyTokens: Int, count: (String) -> Int): List<Turn> {
        // Chat templates add a few tokens per message for role markers.
        val overhead = 8
        var budget = contextTokens - replyTokens - (if (system.isBlank()) 0 else count(system) + overhead) - count(prompt) - overhead
        if (budget < 0) throw UserError("This message is too long for the model's context. Shorten it or raise the context size in Settings.")
        val kept = ArrayList<Turn>()
        for (turn in history.asReversed()) {
            if (turn.role !in listOf("user", "assistant")) continue
            val cost = count(turn.content) + overhead
            if (cost > budget) break
            kept.add(turn); budget -= cost
        }
        // A reply without its question confuses the model, so the window never starts with an assistant turn.
        while (kept.isNotEmpty() && kept.last().role == "assistant") kept.removeAt(kept.lastIndex)
        return (if (system.isBlank()) emptyList() else listOf(Turn("system", system))) + kept.asReversed() + Turn("user", prompt)
    }
}

/** Separates a reasoning model's thinking from its answer. While a think block is still open, the answer is empty. */
object ReplyText {
    private val closed = Regex("(?s)<think>(.*?)</think>")
    /** The thinking text, including a block that is still open. */
    fun thinking(raw: String): String {
        val parts = closed.findAll(raw).map { it.groupValues[1].trim() }.toMutableList()
        val rest = raw.replace(closed, "")
        rest.indexOf("<think>").takeIf { it >= 0 }?.let { parts += rest.substring(it + 7).trim() }
        return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    fun visible(raw: String): String {
        val text = raw.replace(closed, "")
        val open = text.indexOf("<think>")
        return (if (open >= 0) text.substring(0, open) else text).trimStart()
    }
}

/** Timing of the last reply: [firstMs] until the first word, then [tokens] written in [writeMs]. */
data class ChatStats(val firstMs: Long, val tokens: Int, val writeMs: Long) {
    val tokensPerSecond get() = if (writeMs > 0 && tokens > 1) (tokens - 1) * 1000.0 / writeMs else 0.0
    fun summary() = "First word in %.1f s, %.1f tokens per second".format(firstMs / 1000.0, tokensPerSecond)
}

/** The on-device chat engine. A desktop build supplies its own implementation. */
interface LocalLlm {
    val loadedPath: String?
    val lastStats: ChatStats?
    /**
     * Loads [path], replacing any loaded model. Blocks; call off the main thread. [progress] receives 0 to 1
     * and returns false to abort, which throws [UserError].
     */
    fun load(path: String, contextTokens: Int, threads: Int, stateDir: String? = null, progress: (Float) -> Boolean = { true })
    fun unload()
    /**
     * Saves the processed conversation into [load]'s stateDir, so a later load can skip reading it again.
     * The file is a cache tied to one model, context size, and engine build. Returns false when unavailable.
     */
    fun saveState(): Boolean
    /** Restores the state saved by [saveState]. Returns false when there is none or it does not fit. */
    fun restoreState(): Boolean
    fun countTokens(text: String): Int
    /** Streams a reply to [messages]. Returns the full text. [responseFormat] is an OpenAI response_format JSON object or null. */
    fun chat(messages: List<Turn>, maxTokens: Int, responseFormat: String?, thinking: Boolean, onText: (String) -> Unit): String
    /** Stops the running [chat] from another thread. */
    fun cancel()
}
