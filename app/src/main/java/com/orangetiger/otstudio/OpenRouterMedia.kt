package com.orangetiger.otstudio

import org.json.JSONArray
import org.json.JSONObject

// Portable (no Android types). OpenRouter pictures and videos, as in OT Studio for Windows (src/core/builtins.js).
//
// OpenRouter models appear in the Image and Video lists under the "openrouter/" prefix. They use the OpenRouter key
// already saved for Chat, and only while Chat's address is OpenRouter's, so the key never reaches another host.
// Pictures go to OpenRouter inside the request (as data: addresses), because OpenRouter cannot read Replicate's private
// upload links. Settings and prices come from OpenRouter's model lists, checked 2026-10-09.

object OpenRouterMedia {
    const val BASE = "https://openrouter.ai/api/v1"
    const val PREFIX = "openrouter/"
    /** A picture kept on this phone, named by its file in private media. Sent to OpenRouter as a data: address. */
    val PHONE_PICTURE = Regex("phone:[A-Za-z0-9-]{1,64}\\.(png|jpg|webp)")

    fun isModel(model: String) = model.startsWith(PREFIX)
    fun providerId(model: String) = model.removePrefix(PREFIX)

    private fun choice(n: String, values: List<String>, default: String?, note: String = "") = InputDefinition(n, InputKind.TEXT, default, values, description = note)
    private fun intChoice(n: String, values: List<Int>, default: Int, note: String = "") = InputDefinition(n, InputKind.INTEGER, default.toString(), values.map { it.toString() }, description = note)
    private fun bool(n: String, default: Boolean, note: String = "") = InputDefinition(n, InputKind.BOOLEAN, default.toString(), description = note)
    private fun number(n: String, default: String? = null, min: Double? = null, max: Double? = null, note: String = "") = InputDefinition(n, InputKind.INTEGER, default, minimum = min, maximum = max, description = note)
    private fun picture(n: String, note: String, list: Boolean = false) = InputDefinition(n, InputKind.TEXT, description = note, uri = true, list = list)

    private class Video(val id: String, val title: String, val resolutions: List<String>, val shapes: List<String>, val seconds: List<Int>,
                        val lastFrame: Boolean, val audio: Boolean, val seed: Boolean,
                        /** Dollars a second by resolution ("*" for any), silent and with sound. Empty when OpenRouter bills by tokens. */
                        val rates: Map<String, Pair<Double, Double>>)
    private class Image(val id: String, val title: String, val shapes: List<String>, val resolutions: List<String>, val count: Int, val references: Int, val seed: Boolean, val perPicture: Double)

    private val videos = listOf(
        Video("alibaba/wan-2.7", "Wan 2.7 on OpenRouter, 10 cents a second", listOf("720p", "1080p"), listOf("16:9", "9:16", "1:1", "4:3", "3:4"), (2..10).toList(), true, true, true, mapOf("*" to (0.10 to 0.10))),
        Video("kwaivgi/kling-v3.0-pro", "Kling v3.0 Pro on OpenRouter, 11 cents a second", listOf("720p"), listOf("16:9", "9:16", "1:1"), (3..15).toList(), true, true, false, mapOf("*" to (0.112 to 0.168))),
        Video("bytedance/seedance-1-5-pro", "Seedance 1.5 Pro on OpenRouter", listOf("480p", "720p", "1080p"), listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9", "9:21"), (4..12).toList(), true, true, true, emptyMap()),
        Video("google/veo-3.1-lite", "Veo 3.1 Lite on OpenRouter, from 3 cents a second", listOf("720p", "1080p"), listOf("16:9", "9:16"), listOf(4, 6, 8), true, true, true, mapOf("1080p" to (0.05 to 0.08), "720p" to (0.03 to 0.05))),
    )
    private val images = listOf(
        Image("bytedance-seed/seedream-4.5", "Seedream 4.5 on OpenRouter, 4 cents", listOf("1:1", "4:3", "3:4", "3:2", "2:3", "16:9", "9:16", "21:9", "9:21", "4:5", "5:4"), listOf("1K", "2K", "4K"), 4, 14, true, 0.04),
        Image("google/gemini-2.5-flash-image", "Nano Banana on OpenRouter, about 4 cents", listOf("1:1", "4:3", "3:4", "3:2", "2:3", "16:9", "9:16", "21:9", "4:5", "5:4"), emptyList(), 1, 3, false, 0.039),
        Image("black-forest-labs/flux.2-pro", "FLUX.2 pro on OpenRouter, about 3 cents", listOf("1:1", "4:3", "3:4", "3:2", "2:3", "16:9", "9:16", "21:9"), emptyList(), 1, 8, true, 0.03),
        Image("qwen/qwen-image-3", "Qwen Image 3 on OpenRouter, 3 cents", listOf("1:1", "4:3", "3:4", "3:2", "2:3", "16:9", "9:16", "4:5", "5:4"), listOf("1K", "2K"), 4, 4, true, 0.03),
    )

    private fun videoSchema(v: Video) = InputSchema(listOfNotNull(
        picture("first_frame", "Starting picture: the video begins with it. From this phone."),
        if (v.lastFrame) picture("last_frame", "Ending picture: the video ends on it. Needs a starting picture.") else null,
        intChoice("duration", v.seconds, if (5 in v.seconds) 5 else v.seconds.first(), "Length in seconds."),
        choice("resolution", v.resolutions, v.resolutions.first()),
        choice("aspect_ratio", v.shapes, v.shapes.first()),
        if (v.audio) bool("generate_audio", false, "Sound can add to the price.") else null,
        if (v.seed) number("seed", min = 0.0, max = 2147483647.0) else null,
    ))
    private fun imageSchema(i: Image) = InputSchema(listOfNotNull(
        picture("input_references", "Reference pictures, from this phone. Call them @img1, @img2 in your words.", list = true),
        choice("aspect_ratio", i.shapes, i.shapes.first()),
        if (i.resolutions.isNotEmpty()) choice("resolution", i.resolutions, i.resolutions.first()) else null,
        if (i.count > 1) number("n", "1", 1.0, i.count.toDouble(), "Pictures per request, each billed.") else null,
        if (i.seed) number("seed", min = 0.0, max = 2147483647.0) else null,
    ))

    val presets: List<ModelPreset> = images.map { ModelPreset(Mode.IMAGE, PREFIX + it.id, imageSchema(it)) } + videos.map { ModelPreset(Mode.VIDEO, PREFIX + it.id, videoSchema(it)) }
    val titles: Map<String, String> = (images.map { PREFIX + it.id to it.title } + videos.map { PREFIX + it.id to it.title }).toMap()
    /** How many reference pictures each picture model takes. */
    fun maxPictures(model: String): Int? = images.firstOrNull { PREFIX + it.id == model }?.references

    /** Likely cost in dollars, from OpenRouter's own prices; null when it bills by tokens. */
    fun dollars(model: String, options: List<InputValue>): Double? {
        fun v(n: String) = options.firstOrNull { it.name == n }?.value
        images.firstOrNull { PREFIX + it.id == model }?.let { return it.perPicture * (v("n")?.toIntOrNull() ?: 1) }
        val video = videos.firstOrNull { PREFIX + it.id == model } ?: return null
        val seconds = v("duration")?.toIntOrNull() ?: (if (5 in video.seconds) 5 else video.seconds.first())
        val rate = video.rates[v("resolution") ?: video.resolutions.first()] ?: video.rates["*"] ?: return null
        return seconds * if (v("generate_audio") == "true") rate.second else rate.first
    }

    /**
     * The request for one job. [pictures] maps a picture input to data: addresses, already read from this phone.
     * Only the settings this model lists are sent; picture inputs become OpenRouter's own shapes.
     */
    fun request(mode: Mode, model: String, prompt: String, schema: InputSchema, options: List<InputValue>, pictures: Map<String, List<String>>): Pair<String, JSONObject> {
        if (!isModel(model)) throw UserError("This is not an OpenRouter model.")
        ModelOptions.validate(schema, options, true)
        val body = JSONObject().put("model", providerId(model)).put("prompt", prompt)
        SchemaCodec.append(body, schema.copy(fields = schema.fields.filter { !it.uri }), options.filter { o -> schema.fields.firstOrNull { it.name == o.name }?.uri != true })
        fun item(url: String) = JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url))
        if (mode == Mode.IMAGE) {
            val refs = pictures["input_references"].orEmpty()
            val max = maxPictures(model) ?: 0
            if (refs.size > max) throw UserError("This model takes at most $max reference pictures.")
            if (refs.isNotEmpty()) body.put("input_references", JSONArray(refs.map(::item)))
            return "$BASE/images" to body
        }
        val first = pictures["first_frame"].orEmpty().firstOrNull()
        val last = pictures["last_frame"].orEmpty().firstOrNull()
        if (last != null && first == null) throw UserError("An ending picture needs a starting picture.")
        val frames = JSONArray()
        first?.let { frames.put(item(it).put("frame_type", "first_frame")) }
        last?.let { frames.put(item(it).put("frame_type", "last_frame")) }
        if (frames.length() > 0) body.put("frame_images", frames)
        return "$BASE/videos" to body
    }

    /** The pictures from an image answer, as bytes. OpenRouter returns them inline, in base64. */
    fun pictures(root: JSONObject): List<ByteArray> {
        val data = root.optJSONArray("data") ?: throw UserError("OpenRouter returned no pictures.")
        if (data.length() > 10) throw UserError("OpenRouter returned too many pictures.")
        val out = (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.optString("b64_json")?.takeIf { it.isNotEmpty() } }
            .map { b64 -> try { java.util.Base64.getMimeDecoder().decode(b64) } catch (_: IllegalArgumentException) { throw UserError("OpenRouter returned a picture that could not be read.") } }
        if (out.isEmpty()) throw UserError("OpenRouter returned no pictures.")
        return out
    }

    /** A video task as a job state. Finished videos are fetched from openrouter.ai with the same key. */
    fun videoTask(root: JSONObject): Prediction {
        val id = Rules.remoteId(root.optString("id"))
        val clean = { s: String -> s.replace(Regex("[\\p{Cntrl}]+"), " ").trim().take(300) }
        return when (val status = root.optString("status")) {
            "pending", "queued" -> Prediction(id, "starting", emptyList(), false)
            "in_progress", "processing" -> Prediction(id, "processing", emptyList(), false)
            "completed" -> {
                val urls = root.optJSONArray("unsigned_urls")?.let { a -> (0 until minOf(a.length(), 4)).mapNotNull { a.optString(it).takeIf { u -> u.isNotEmpty() }?.let(Rules::outputUrl) } }.orEmpty()
                if (urls.isEmpty()) Prediction(id, "unusable", emptyList(), true, "The finished video has no link.") else Prediction(id, "succeeded", urls, false)
            }
            "failed", "cancelled", "canceled", "expired" -> {
                val e = root.opt("error")
                val reason = (e as? String) ?: (e as? JSONObject)?.optString("message").orEmpty()
                Prediction(id, "failed", emptyList(), true, clean(reason.ifEmpty { "OpenRouter ended the task as $status." }))
            }
            else -> throw UserError("OpenRouter returned an unknown task state: ${clean(status).take(40)}.")
        }
    }
}
