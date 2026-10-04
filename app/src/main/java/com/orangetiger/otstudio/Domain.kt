package com.orangetiger.otstudio

import java.net.URI

enum class Mode(val title: String) { CHAT("Chat"), IMAGE("Image"), VIDEO("Video") }
enum class Protocol { CHAT_COMPLETIONS, REPLICATE }
enum class KeySource { OWN, TEXT, IMAGE }

data class ModelSettings(
    val mode: Mode,
    val protocol: Protocol,
    val baseUrl: String,
    val model: String,
    val keySource: KeySource = KeySource.OWN,
    val schema: InputSchema? = ModelCatalog.find(mode, model)?.schema ?: if (mode == Mode.CHAT) ModelCatalog.chatSchema else null,
    val options: List<InputValue> = ModelOptions.initial(mode, model, schema)
)

data class ResolvedModel(val settings: ModelSettings, val credentialMode: Mode, val key: String)
data class Turn(val role: String, val content: String)
data class Prediction(val id: String, val state: String, val outputUrls: List<String>, val failed: Boolean, val problem: String = "")
data class Entry(
    val id: Long, val mode: Mode, val prompt: String, val response: String,
    val state: String, val model: String, val baseUrl: String, val credentialMode: Mode,
    val remoteId: String, val outputUrl: String, val localFile: String, val created: Long,
    val requestSummary: String = "",
    /** For an edit, the entry it was made from. 0 when the entry has no source. */
    val parentId: Long = 0,
    /** For a chat turn: the job plan as JSON, or empty. */
    val plan: String = "",
    /** For a chat turn with a plan: the media entry it started, -1 when the user canceled it, 0 while it waits. */
    val jobEntry: Long = 0,
    /** For a chat turn: the web searches behind the reply, as JSON, or empty. */
    val sources: String = ""
)
data class MediaOutput(val position: Int, val url: String, val localFile: String)

open class UserError(message: String) : Exception(message)
/** The provider answered with a definite rejection, so no job was created. */
class ProviderRejected(message: String) : UserError(message)

object Rules {
    const val MAX_PROMPT = 4000
    const val MAX_JSON_BYTES = 1_048_576
    const val MAX_MEDIA_BYTES = 128L * 1024 * 1024
    const val MAX_STORAGE_BYTES = 512L * 1024 * 1024
    const val MAX_UPLOAD_BYTES = 20 * 1024 * 1024
    val UPLOAD_MIMES = setOf("image/jpeg", "image/png", "image/webp")
    private val modelId = Regex("[A-Za-z0-9_.:/+-]{1,180}")
    private val replicateId = Regex("[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+")
    private val predictionId = Regex("[A-Za-z0-9_-]{1,160}")

    fun defaults(mode: Mode): ModelSettings = when (mode) {
        Mode.CHAT -> ModelSettings(mode, Protocol.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", "mistralai/mistral-nemo")
        Mode.IMAGE -> ModelSettings(mode, Protocol.REPLICATE, "https://api.replicate.com/v1", "black-forest-labs/flux-schnell")
        Mode.VIDEO -> ModelSettings(mode, Protocol.REPLICATE, "https://api.replicate.com/v1", "wan-video/wan-2.7-t2v", KeySource.IMAGE)
    }

    /** True when the first bytes of a file are those of a [ext] file (png, jpg, webp, or mp4). */
    fun mediaMatches(ext: String, head: ByteArray): Boolean {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it] == b[it].toByte() }
        return when (ext) {
            "png" -> at(0, 0x89, 0x50, 0x4E, 0x47)
            "jpg" -> at(0, 0xFF, 0xD8, 0xFF)
            "webp" -> at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)
            "mp4" -> at(4, 0x66, 0x74, 0x79, 0x70)
            else -> false
        }
    }

    fun origin(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw UserError("Enter a valid HTTPS API URL.") }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.rawQuery != null || uri.fragment != null || uri.port == 0 || uri.port > 65535) {
            throw UserError("API URLs must use HTTPS, without embedded credentials, query parameters, or fragments.")
        }
        if (uri.rawPath.orEmpty().contains("..") || uri.rawPath.orEmpty().contains('%')) throw UserError("Use a plain API base path without encoded segments.")
        return "https://${uri.host.lowercase()}:${if (uri.port == -1) 443 else uri.port}"
    }

    fun validate(s: ModelSettings) {
        origin(s.baseUrl)
        if (!modelId.matches(s.model) || s.model.contains("..")) throw UserError("Enter a model ID using letters, numbers, slashes, dashes, periods, colons, or underscores.")
        if (s.mode == Mode.CHAT && s.protocol != Protocol.CHAT_COMPLETIONS) throw UserError("Text chat requires the Chat Completions protocol.")
        if (s.mode != Mode.CHAT && s.protocol != Protocol.REPLICATE) throw UserError("This build supports the Replicate protocol for image and video generation.")
        if (s.protocol == Protocol.REPLICATE && !replicateId.matches(s.model)) throw UserError("Use an official Replicate model ID in owner/model format. Versioned community models need another adapter.")
        if (s.mode == Mode.CHAT && s.keySource != KeySource.OWN) throw UserError("The text LLM needs its own API key.")
        if (s.mode != Mode.VIDEO && s.keySource == KeySource.IMAGE) throw UserError("Only video can reuse the image API key.")
        if (s.schema != null) ModelOptions.validate(s.schema, s.options)
        else if (s.options.isNotEmpty()) throw UserError("Load this model's inputs in API CALL before configuring modifiers.")
    }

    fun resolve(mode: Mode, settings: (Mode) -> ModelSettings, key: (Mode) -> String): ResolvedModel {
        val s = settings(mode).also(::validate)
        val source = when (s.keySource) {
            KeySource.OWN -> mode
            KeySource.TEXT -> Mode.CHAT
            KeySource.IMAGE -> when (settings(Mode.IMAGE).keySource) {
                KeySource.TEXT -> Mode.CHAT
                KeySource.OWN -> Mode.IMAGE
                KeySource.IMAGE -> throw UserError("Circular credential reference.")
            }
        }
        if (origin(s.baseUrl) != origin(settings(source).baseUrl)) throw UserError("These providers use different API origins. Enter a separate key instead of reusing it.")
        val value = key(source).trim()
        if (value.isEmpty()) throw UserError("Add the ${source.title.lowercase()} API key in Settings.")
        if (value.length > 4096 || value.any { it.isWhitespace() || it.code < 32 || it.code > 126 }) throw UserError("The API key contains invalid characters.")
        return ResolvedModel(s, source, value)
    }

    fun prompt(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) throw UserError("Write a prompt first.")
        if (trimmed.length > MAX_PROMPT) throw UserError("Keep the prompt under $MAX_PROMPT characters.")
        return trimmed
    }

    fun boundedTurns(history: List<Turn>, prompt: String): List<Turn> {
        val kept = ArrayList<Turn>()
        var length = prompt.length
        for (turn in history.asReversed().take(12)) {
            if (length + turn.content.length > 8000) break
            if (turn.role !in listOf("user", "assistant")) continue
            kept.add(turn)
            length += turn.content.length
        }
        return kept.asReversed() + Turn("user", prompt)
    }

    fun remoteId(id: String): String = id.takeIf { predictionId.matches(it) } ?: throw UserError("The provider returned an invalid job ID.")

    private fun replicateDelivery(host: String) = host == "replicate.delivery" || host.endsWith(".replicate.delivery")
    // Observed 2026-10-02: api.replicate.com returned outputs as presigned links on this Cloudflare R2 bucket.
    // Pinned to the exact account so a look-alike bucket in another account is refused.
    private const val REPLICATE_R2_OUTPUTS = "ai-gateway-outputs.0d37909e38d3e99c29fa2cd343ac421a.r2.cloudflarestorage.com"

    fun outputUrl(url: String): String {
        val uri = try { URI(url) } catch (_: Exception) { throw UserError("The provider returned an invalid media URL.") }
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || uri.userInfo != null || (!replicateDelivery(host) && host != REPLICATE_R2_OUTPUTS) || (uri.port != -1 && uri.port != 443)) {
            // Naming the scheme and host (never the full URL or its query) makes a provider change diagnosable.
            throw UserError("Media must be hosted on Replicate's HTTPS delivery service (received ${uri.scheme ?: "no scheme"}://${host.ifEmpty { "no host" }.take(80)}${if (uri.port != -1) ":${uri.port}" else ""}). A different delivery host needs an adapter update.")
        }
        return url
    }

    // The API key may follow a 401/403 only to replicate.delivery. Presigned storage links answer 403 when they
    // expire, and the key must never be sent to a storage host.
    fun authorizeDelivery(baseUrl: String, rawUrl: String): Boolean {
        outputUrl(rawUrl)
        return origin(baseUrl) == "https://api.replicate.com:443" && replicateDelivery(URI(rawUrl).host.lowercase())
    }

    fun afterProcessRestart(state: String, id: String): String = when {
        state == "submitting" && id.isEmpty() -> "uncertain"
        state == "chatting" -> "interrupted"
        state in listOf("submitting", "processing", "canceling") && id.isNotEmpty() -> "processing"
        else -> state
    }
}
