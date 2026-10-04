package com.orangetiger.otstudio

import org.json.JSONArray
import org.json.JSONObject

// Chat-led creation, portable: no Android types, so a desktop build can reuse it.
//
// The chat model answers every message with one JSON object, a job plan. The model never chooses a provider model,
// never writes a URL, and never starts a job. It only describes: the reply text, a complete media prompt, a plain
// shape, a length in seconds, and which listed image it means. The app maps that onto the user's current model,
// validates it with the same rules as manual requests, and shows a job card. Nothing is paid for until the user
// taps Generate on that card.

/** An image the chat may refer to, newest first. */
data class ImageRef(val id: Long, val prompt: String)

data class JobPlan(
    val action: String, val reply: String, val prompt: String = "",
    /** The entry ID of the image this plan works on, already resolved by the app. */
    val reference: Long? = null, val shape: String = "default", val seconds: Int? = null
) {
    fun toJson(): String = JSONObject().put("action", action).put("reply", reply).put("prompt", prompt)
        .put("reference", reference ?: 0).put("shape", shape).put("seconds", seconds ?: 0).toString()

    companion object {
        fun fromJson(raw: String): JobPlan? = try {
            val o = JSONObject(raw)
            JobPlan(o.getString("action"), o.optString("reply"), o.optString("prompt"),
                o.optLong("reference").takeIf { it > 0 }, o.optString("shape", "default"), o.optInt("seconds").takeIf { it > 0 })
        } catch (_: Exception) { null }
    }
}

sealed class PlanResult {
    data class Ok(val plan: JobPlan) : PlanResult()
    /** No usable plan. [reply] is any readable text the model gave, shown as its answer. */
    data class Invalid(val reason: String, val reply: String) : PlanResult()
}

object Planner {
    val MEDIA_ACTIONS = listOf("image", "edit", "expand", "video")
    val SHAPES = listOf("default", "square", "portrait", "landscape")
    const val MAX_IMAGES = 6
    const val REPLY_TOKENS = 1024

    private fun refName(id: Long) = "image_$id"
    private fun references(images: List<ImageRef>) = listOf("none", "last_image") + images.take(MAX_IMAGES).map { refName(it.id) }

    /**
     * The fixed instructions. They change only with memory and the search switch, so the phone's engine can reuse the
     * work it already did on the conversation. The picture list changes often, so it goes with the message ([userMessage]).
     */
    fun systemPrompt(memory: String, search: Boolean = false, loras: List<SavedLora> = emptyList()): String = buildString {
        if (memory.isNotBlank()) append(memory).append("\n\n")
        append("""You are the assistant in OT Studio, a phone app that can also make pictures and short videos through the user's own Replicate account.
Answer every message with one JSON object and nothing else.
- "action": "answer" for questions and conversation. Put the whole reply in "reply".
- "action": "image" when the user wants a new picture. Write "prompt" as a complete, vivid description for an image model: subject, setting, style, lighting, and framing. The image model remembers nothing, so describe everything again each time.
- "action": "edit" when the user wants to change an existing picture. Write "prompt" as one short, direct instruction, such as "change the border to black". Set "reference" to that picture.
- "action": "expand" when the user wants the same picture in another shape, such as wider, taller, square, or 16:9, with more of the scene added around it. Set "reference" to that picture and "shape" to "landscape", "portrait", or "square". Write "prompt" as what the added area should show, such as "more of the beach and the sky", or "more of the same scene".
- "action": "video" when the user wants a moving clip. Write "prompt" as the scene and its motion. Set "reference" to a picture to start from it, or "none". Set "seconds" from 2 to 15, normally 5.
- "shape": "square", "portrait", or "landscape" when the user asks for one, otherwise "default".
- For image, edit, and video, "reply" is one short sentence saying what the plan does. Put the details in "prompt", not in "reply".
- For "answer", set "prompt" to "", "reference" to "none", "shape" to "default", and "seconds" to 5. Most messages are conversation: only plan a picture, edit, or video when the user asks for one.
""")
        if (search) append("""- "action": "search" when the answer needs current facts, news, prices, or details you are not sure of. Put a short web search query in "prompt". The app runs the search and shows you the results, then you answer.
""")
        append("""You cannot start a job yourself. The user reviews the plan and taps Generate. Never claim a picture or video already exists. Never write a web address.
Each user message starts with an app note that lists the pictures you may name in "reference". The note comes from the app, not the user.""")
        val named = loras.filter { it.trigger.length >= 3 }.take(20)
        if (named.isNotEmpty()) {
            append("\nSaved LoRAs, each a trained person or style, as trigger word (name): ")
            append(named.joinToString("; ") { "${it.trigger} (${it.name.take(60).replace('\n', ' ')})" })
            append(". When the user asks for a picture or video of one of them, by trigger word or by name, begin \"prompt\" with its trigger word exactly as written.")
        }
    }

    /** The app's note on the pictures, then the user's own words. */
    fun userMessage(prompt: String, images: List<ImageRef>, selected: Long?): String = pictureNote(images, selected) + "\n\nThe user's message:\n" + prompt

    fun pictureNote(images: List<ImageRef>, selected: Long?): String = buildString {
        append("[App note] ")
        if (images.isNotEmpty()) {
            append("Pictures in this conversation, newest first. \"last_image\" means the first one.")
            if (selected != null) append(" The user selected ${refName(selected)} to work on, so \"that picture\", \"it\", \"this\", \"her\", and \"him\" mean it." +
                " A request to change it, such as \"make it black and white\", \"make her jacket red\", or \"add a hat\", is an \"edit\" with \"reference\" set to ${refName(selected)}, never a new \"image\"." +
                " A request for the same picture in another shape (wider, taller, square) is an \"expand\" of it." +
                " A request to make it move is a \"video\" from it, and so is any description of motion or action over time, such as \"she walks toward the camera\", \"he turns and waves\", or \"the camera pans left\", even without the word video. You cannot see the picture, so never describe what is in it.")
            images.take(MAX_IMAGES).forEach { append("\n").append(refName(it.id)).append(": ").append(it.prompt.take(200).replace('\n', ' ')) }
        } else append("No pictures exist yet, so edits and videos from a picture are not possible. Use \"reference\": \"none\".")
    }

    /**
     * JSON Schema for the plan. Grammar-constrained decoding makes the local model follow it token by token, in the
     * order the properties are written. The action therefore comes first, so the model decides what to do before it
     * writes anything else. The text is built by hand because some JSON libraries do not keep key order.
     */
    fun schemaText(images: List<ImageRef>, search: Boolean = false): String {
        fun list(values: List<String>) = values.joinToString(",") { JSONObject.quote(it) }
        return "{\"type\":\"object\",\"properties\":{" +
            "\"action\":{\"type\":\"string\",\"enum\":[${list(listOf("answer") + MEDIA_ACTIONS + if (search) listOf("search") else emptyList())}]}," +
            "\"reply\":{\"type\":\"string\"}," +
            "\"prompt\":{\"type\":\"string\"}," +
            "\"reference\":{\"type\":\"string\",\"enum\":[${list(references(images))}]}," +
            "\"shape\":{\"type\":\"string\",\"enum\":[${list(SHAPES)}]}," +
            "\"seconds\":{\"type\":\"integer\",\"minimum\":2,\"maximum\":15}}," +
            "\"required\":[\"action\",\"reply\",\"prompt\",\"reference\",\"shape\",\"seconds\"],\"additionalProperties\":false}"
    }

    fun schema(images: List<ImageRef>, search: Boolean = false): JSONObject = JSONObject(schemaText(images, search))

    /** The OpenAI-style response_format both engines accept, with the properties in schema order. */
    fun responseFormat(images: List<ImageRef>, search: Boolean = false): String =
        "{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"job_plan\",\"strict\":true,\"schema\":${schemaText(images, search)}}}"

    /** Reads a model reply as a plan. Anything unexpected makes the plan invalid; nothing is guessed. */
    fun parse(raw: String, images: List<ImageRef>, selected: Long?, search: Boolean = false): PlanResult {
        // A model that ignores the format and writes plain prose has still answered; Use my words remains under it.
        // A broken object is no plan, and only the reply text it holds is shown.
        if ('{' !in raw) return if (raw.isBlank()) PlanResult.Invalid("The model returned no text.", "") else PlanResult.Ok(JobPlan("answer", raw.trim().take(4000)))
        val text = SchemaCodec.firstJsonObject(raw) ?: run {
            // A long answer can reach the length limit before its JSON closes. Its text so far is still the answer.
            val partial = streamingReply(raw).trim()
            return if (Regex("\"action\"\\s*:\\s*\"answer\"").containsMatchIn(raw) && partial.isNotEmpty()) PlanResult.Ok(JobPlan("answer", partial.take(4000) + "..."))
            else PlanResult.Invalid("The model did not return a plan.", partial.take(4000))
        }
        val o = try { JSONObject(text) } catch (_: Exception) { return PlanResult.Invalid("The model returned a broken plan.", "") }
        val action = o.optString("action")
        val reply = o.optString("reply").trim().take(4000)
        if (action == "search") {
            val q = o.optString("prompt").trim()
            return if (search && q.isNotEmpty()) PlanResult.Ok(JobPlan("search", reply, q.take(300))) else PlanResult.Invalid("The model asked for a web search that is not available now.", reply)
        }
        if (action !in listOf("answer") + MEDIA_ACTIONS) return PlanResult.Invalid("The plan named an unknown action.", reply)
        if (action == "answer") return if (reply.isEmpty()) PlanResult.Invalid("The model returned an empty answer.", "") else PlanResult.Ok(JobPlan("answer", reply))
        val prompt = o.optString("prompt").trim()
        if (prompt.isEmpty()) return PlanResult.Invalid("The plan has no prompt.", reply)
        if (prompt.length > Rules.MAX_PROMPT) return PlanResult.Invalid("The plan's prompt is too long.", reply)
        if (Regex("(?i)https?://|www\\.").containsMatchIn(prompt)) return PlanResult.Invalid("The plan's prompt contains a web address.", reply)
        val refText = o.optString("reference", "none")
        val reference: Long? = when {
            refText == "none" || refText.isEmpty() -> null
            refText == "last_image" -> selected ?: images.firstOrNull()?.id
            refText.startsWith("image_") -> refText.removePrefix("image_").toLongOrNull()?.takeIf { id -> images.take(MAX_IMAGES).any { it.id == id } }
                ?: return PlanResult.Invalid("The plan pointed at a picture that is not in this conversation.", reply)
            else -> return PlanResult.Invalid("The plan pointed at an unknown picture.", reply)
        }
        if (action == "edit" && reference == null) return PlanResult.Invalid("An edit needs a picture to work on, and none was found.", reply)
        if (action == "expand" && reference == null) return PlanResult.Invalid("Expanding needs a picture to work on, and none was found.", reply)
        // An expansion always changes the shape; wide is the usual wish when the model names none.
        val shape = (o.optString("shape", "default").takeIf { it in SHAPES } ?: "default").let { if (action == "expand" && it == "default") "landscape" else it }
        val seconds = if (action == "video") o.optInt("seconds", 5).takeIf { it in 2..15 } ?: 5 else null
        // A plan's reply is one line above the card; small models sometimes repeat the whole prompt there.
        val short = Regex("^.{1,200}?[.!?](\\s|$)").find(reply)?.value?.trim() ?: if (reply.length > 200) reply.take(197).trimEnd() + "..." else reply
        return PlanResult.Ok(JobPlan(action, short.ifEmpty { "Here is a plan." }, prompt, if (action == "image") null else reference, shape, seconds))
    }

    /** The reply text so far, read out of a JSON object that is still streaming. */
    fun streamingReply(partial: String): String {
        val key = Regex("\"reply\"\\s*:\\s*\"").find(partial) ?: return ""
        val out = StringBuilder()
        var i = key.range.last + 1
        while (i < partial.length) {
            val c = partial[i]
            if (c == '"') break
            if (c == '\\') {
                if (i + 1 >= partial.length) break
                when (val e = partial[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> { }
                    'u' -> { if (i + 5 >= partial.length) break; partial.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }; i += 4 }
                    else -> out.append(e)
                }
                i += 2; continue
            }
            out.append(c); i++
        }
        return out.toString()
    }

    /** The Expand shape a plan's plain shape asks for. */
    fun expandRatio(shape: String): String = when (shape) { "portrait" -> "9:16"; "square" -> "1:1"; else -> "16:9" }

    /** The line the conversation keeps after a job, so the model knows what exists. */
    fun note(kind: String, entryId: Long, state: String, prompt: String): String = when (state) {
        "succeeded" -> "(The app made ${kind} ${refName(entryId)} from this prompt: ${prompt.take(300)})"
        "failed", "uncertain", "canceled", "untracked", "unavailable" -> "(The ${kind} job for this plan did not finish.)"
        else -> "(The ${kind} job for this plan is still running.)"
    }
}

/** Maps a plan's plain wishes onto a model's real inputs. A wish the model cannot take is left out and reported. */
object JobBuilder {
    private val preferred = mapOf(
        "square" to listOf("1:1"),
        "portrait" to listOf("9:16", "2:3", "3:4", "4:5"),
        "landscape" to listOf("16:9", "3:2", "4:3", "5:4")
    )

    fun apply(settings: ModelSettings, plan: JobPlan): Pair<ModelSettings, List<String>> {
        val schema = settings.schema ?: return settings to listOf("Load this model's inputs in API CALL first.")
        val notes = ArrayList<String>()
        var options = settings.options
        fun set(name: String, value: String) {
            val def = schema.fields.firstOrNull { it.name == name } ?: return
            val v = try { ModelOptions.value(def, value) } catch (_: UserError) { return }
            options = options.filter { it.name != name } + v
        }
        // An edit keeps the picture's own shape; chat models sometimes add a shape nobody asked for.
        if (plan.shape != "default" && plan.action != "edit") {
            val aspect = schema.fields.firstOrNull { it.name == "aspect_ratio" }
            val pick = preferred[plan.shape]?.firstOrNull { aspect != null && it in aspect.choices }
            if (pick != null) set("aspect_ratio", pick) else notes.add("This model has no ${plan.shape} shape, so it keeps its own.")
        }
        plan.seconds?.let { seconds ->
            val duration = schema.fields.firstOrNull { it.name == "duration" }
            val frames = schema.fields.firstOrNull { it.name == "num_frames" }
            when {
                duration != null -> set("duration", seconds.toDouble().coerceIn(duration.minimum ?: 1.0, duration.maximum ?: 15.0).toInt().toString())
                frames != null -> set("num_frames", (seconds * 16).toDouble().coerceIn(frames.minimum ?: 81.0, frames.maximum ?: 121.0).toInt().toString())
                else -> notes.add("This model sets its own length.")
            }
        }
        return settings.copy(options = options) to notes
    }
}

/** Plain words for a model's settings, in place of raw names such as aspect_ratio=1:1. */
object PlainSummary {
    private fun shape(value: String) = when (value) {
        "1:1" -> "square"; "16:9" -> "wide"; "9:16" -> "tall"; "match_input_image" -> "same shape as the picture"
        else -> "$value frame"
    }

    fun describe(mode: Mode, model: String, options: List<InputValue>): String {
        fun v(name: String) = options.firstOrNull { it.name == name }?.value?.takeIf { it.isNotBlank() }
        val parts = ArrayList<String>()
        val edit = ModelCatalog.edit(model)
        when {
            edit != null -> parts.add("Edit of your picture")
            model == ModelCatalog.expander.model -> parts.add("Your picture, expanded")
            mode == Mode.VIDEO -> {
                val seconds = v("duration")?.toIntOrNull() ?: v("num_frames")?.toIntOrNull()?.let { it / 16 }
                    ?: v("frame_num")?.toIntOrNull()?.let { it / 16 }
                parts.add(if (seconds != null) "$seconds-second clip" else "Video clip")
                if (v("first_frame") != null || v("image") != null) parts.add("from your picture")
                if (v("last_frame") != null || v("last_image") != null) parts.add("ending on your picture")
                if (v("first_clip") != null) parts.add("continuing your clip")
            }
            else -> {
                val count = v("num_outputs")?.toIntOrNull() ?: 1
                parts.add(if (count > 1) "$count images" else "Image")
                if (v("image") != null || v("image_prompt") != null) parts.add("guided by your picture")
            }
        }
        v("aspect_ratio")?.let { parts.add(shape(it)) }
        v("resolution")?.let { parts.add(it) }
        v("size")?.let { parts.add(it) }
        if (v("lora_weights") != null) parts.add("with your LoRA")
        if (v("disable_safety_checker") == "true") parts.add("safety checker off")
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }
}

/** Likely cost of one request, from the providers' price pages in October 2026. Null when unknown. */
object Prices {
    private fun cents(dollars: Double): String = when {
        dollars < 0.01 -> "less than 1 cent"
        dollars < 1.0 -> "about ${"%.1f".format(dollars * 100).removeSuffix(".0")} cents"
        else -> "about $${"%.2f".format(dollars)}"
    }

    fun estimate(model: String, options: List<InputValue>): String? {
        fun v(name: String) = options.firstOrNull { it.name == name }?.value
        val count = v("num_outputs")?.toIntOrNull() ?: 1
        ModelCatalog.edit(model)?.let { return it.price }
        if (model == ModelCatalog.expander.model) return "about 4 cents"
        val dollars = when (model) {
            "black-forest-labs/flux-schnell" -> 0.003 * count
            "black-forest-labs/flux-dev" -> 0.025 * count
            "black-forest-labs/flux-dev-lora" -> 0.032 * count
            "black-forest-labs/flux-1.1-pro" -> 0.04
            "wan-video/wan-2.7-t2v", "wan-video/wan-2.7-i2v" -> (v("duration")?.toDoubleOrNull() ?: 5.0) * (if (v("resolution") == "1080p") 0.15 else 0.10)
            "wan-video/wan-2.2-i2v-fast", "wan-video/wan-2.2-t2v-fast" -> if (v("resolution") == "720p") 0.11 else 0.05
            else -> return null
        }
        return cents(dollars)
    }
}
