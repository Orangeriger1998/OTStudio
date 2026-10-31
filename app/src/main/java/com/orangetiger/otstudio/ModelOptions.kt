package com.orangetiger.otstudio

import java.net.URI

enum class InputKind { BOOLEAN, INTEGER, NUMBER, TEXT }
data class InputValue(val name: String, val kind: InputKind, val value: String)
data class InputDefinition(
    val name: String, val kind: InputKind, val defaultValue: String? = null,
    val choices: List<String> = emptyList(), val minimum: Double? = null, val maximum: Double? = null,
    val required: Boolean = false, val description: String = "", val uri: Boolean = false,
    /** The provider takes a list of URLs here. The app fills one entry and sends it as a one-item array. */
    val list: Boolean = false
)
data class InputSchema(val fields: List<InputDefinition>, val unsupported: List<String> = emptyList(), val unsupportedRequired: List<String> = emptyList())
data class ModelPreset(val mode: Mode, val model: String, val schema: InputSchema, val fixedResolution: String? = null)
data class SetupSuggestion(val model: String, val options: List<InputValue>, val reason: String)

/**
 * Several pictures in one list input (such as Seedream's image_input), kept as one link per line. The model calls them
 * image 1, image 2, and so on, in this order; the first is the picture being worked on.
 */
object PictureList {
    fun split(value: String): List<String> = value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun join(links: List<String>): String = links.joinToString("\n")
    fun add(value: String?, link: String): String = join(split(value.orEmpty()) + link)
    fun remove(value: String, index: Int): String = join(split(value).filterIndexed { i, _ -> i != index })

    /** How many pictures a model's list input takes, from Replicate's model pages (2026-10-05) and SpicyAPI's docs (2026-10-30). */
    fun max(model: String): Int = OpenRouterMedia.maxPictures(model) ?: when (model) {
        "bytedance/seedream-4.5" -> 14
        "spicyapi/spicy-motion-3" -> 10
        "spicyapi/spicy-cinema-1-character" -> 9
        "black-forest-labs/flux-2-dev", "black-forest-labs/flux-2-klein-9b", "black-forest-labs/flux-2-klein-4b" -> 5
        else -> 3
    }
}

object ModelOptions {
    private val fieldName = Regex("[A-Za-z][A-Za-z0-9_]{0,79}")
    private val reserved = setOf("prompt", "model", "messages", "stream", "tools", "tool_choice", "api_key", "authorization", "webhook", "webhook_events_filter")
    val featureNames = listOf("Editing", "Starting frame", "Ending frame", "Frame rate", "Fast generation", "LoRA", "Image guidance", "Audio input", "Negative prompt", "Seed", "Safety controls")
    fun credentialField(name: String): Boolean = Regex("(^|_)(token|password|secret|api_key|authorization|credential)(_|$)").containsMatchIn(name.lowercase())
    fun editableName(name: String): Boolean = fieldName.matches(name) && name.lowercase() !in reserved && !credentialField(name)

    fun validateSchema(schema: InputSchema) {
        if (schema.fields.size > 64 || schema.fields.map { it.name }.distinct().size != schema.fields.size) throw UserError("Model schema contains too many or duplicate fields.")
        schema.fields.forEach { d ->
            if (!editableName(d.name)) throw UserError("Model schema contains a reserved, credential, or invalid input name.")
            if (d.choices.size > 100 || d.choices.any { it.length > 500 || it.contains('\n') }) throw UserError("Model schema has unsupported choices.")
            if (d.minimum?.isFinite() == false || d.maximum?.isFinite() == false || (d.minimum != null && d.maximum != null && d.minimum > d.maximum)) throw UserError("Model schema has invalid bounds.")
        }
    }

    fun validateForModel(model: String, schema: InputSchema, values: List<InputValue>) {
        validate(schema, values, true)
        fun has(name: String) = values.any { it.name == name && it.value.isNotBlank() }
        // A photo kept on the phone goes to OpenRouter, or to SpicyAPI's reference model, inside the request. Replicate models need an uploaded copy.
        if (!OpenRouterMedia.isModel(model) && !ModelCatalog.spicyPhonePhotos(model) && values.any { v -> schema.fields.any { it.name == v.name && it.uri } && PictureList.split(v.value).any { it.startsWith("phone:") } })
            throw UserError("A photo chosen for an OpenRouter model cannot go to this model. Choose the photo again in API CALL.")
        schema.fields.filter { it.list && it.uri }.forEach { d ->
            val count = values.firstOrNull { it.name == d.name }?.value?.let(PictureList::split)?.size ?: 0
            if (count > PictureList.max(model)) throw UserError("This model takes at most ${PictureList.max(model)} pictures.")
        }
        if (model == "wan-video/wan-2.7-i2v") {
            if (has("first_frame") == has("first_clip")) throw UserError("WAN 2.7 I2V needs either a first frame or a first clip, with only one selected.")
            if (has("last_frame") && !has("first_frame")) throw UserError("An ending frame requires a starting frame.")
        }
        if (model == "wan-video/wan-2.2-i2v-fast" && !has("image")) throw UserError("WAN 2.2 I2V needs a starting image URL.")
        if (model == "spicyapi/spicy-motion-3") {
            if (has("image_url") && has("reference_image_urls")) throw UserError("Spicy Motion 3 takes either a starting picture or reference pictures, not both.")
            if (has("last_frame_url") && !has("image_url")) throw UserError("An ending picture requires a starting picture.")
            if (has("image_url") && has("aspect_ratio")) throw UserError("With a starting picture, the clip keeps the picture's shape. Remove the shape setting.")
        }
        ModelCatalog.edit(model)?.let { e -> if (!has(e.imageField)) throw UserError("This editing model needs the image to edit. Use Edit on an image card, or Choose photo in API CALL.") }
        if (model == "bytedance/seedream-4.5" && values.any { it.name == "size" && it.value == "custom" } && (!has("width") || !has("height"))) throw UserError("A custom size needs a width and a height.")
    }

    fun value(def: InputDefinition, raw: String): InputValue {
        val v = if (def.kind == InputKind.TEXT) raw else raw.trim()
        if (v.length > 4000 || v.any { it.code == 0 }) throw UserError("${def.name}: keep input below 4000 characters.")
        if (def.choices.isNotEmpty() && v !in def.choices) throw UserError("${def.name}: select one of this model's choices.")
        val number = when (def.kind) {
            InputKind.BOOLEAN -> { if (v !in listOf("true", "false")) throw UserError("${def.name}: choose true or false."); null }
            InputKind.INTEGER -> v.toLongOrNull()?.toDouble() ?: throw UserError("${def.name}: enter a whole number.")
            InputKind.NUMBER -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw UserError("${def.name}: enter a finite number.")
            InputKind.TEXT -> null
        }
        if (number != null && ((def.minimum != null && number < def.minimum) || (def.maximum != null && number > def.maximum))) throw UserError("${def.name}: value is outside the model's supported range.")
        if (def.uri && v.isNotBlank()) {
            // A list input may hold several links, one per line; a single input takes exactly one.
            val links = if (def.list) PictureList.split(v) else listOf(v)
            if (!def.list && '\n' in v.trim()) throw UserError("${def.name}: this input takes one picture.")
            links.forEach { link ->
                if (OpenRouterMedia.PHONE_PICTURE.matches(link)) return@forEach
                val u = try { URI(link) } catch (_: Exception) { throw UserError("${def.name}: enter an HTTPS media URL.") }
                if (u.scheme != "https" || u.host.isNullOrBlank() || u.userInfo != null) throw UserError("${def.name}: enter an HTTPS media URL without embedded credentials.")
            }
        }
        if (def.required && v.isBlank()) throw UserError("${def.name} is required by this model.")
        return InputValue(def.name, def.kind, v)
    }

    fun validate(schema: InputSchema, values: List<InputValue>, forCall: Boolean = false) {
        validateSchema(schema)
        if (values.size > 64 || values.map { it.name }.distinct().size != values.size) throw UserError("Duplicate or excessive model options.")
        values.forEach { v ->
            val def = schema.fields.firstOrNull { it.name == v.name } ?: throw UserError("${v.name} is not supported by this model. Review API CALL.")
            if (v.kind != def.kind) throw UserError("${v.name}: incorrect input type.")
            value(def, v.value)
        }
        if (forCall) {
            if (schema.unsupportedRequired.isNotEmpty()) throw UserError("This model needs unsupported inputs: ${schema.unsupportedRequired.joinToString()}. Choose another model.")
            schema.fields.filter { it.required && it.defaultValue == null }.forEach { d -> if (values.none { it.name == d.name }) throw UserError("${d.name} is required. Set it in API CALL.") }
            if (values.any { it.name == "aspect_ratio" && it.value == "custom" }) {
                if (values.none { it.name == "width" } || values.none { it.name == "height" }) throw UserError("Custom aspect ratio requires width and height.")
            }
        }
    }

    fun initial(mode: Mode, model: String, schema: InputSchema?): List<InputValue> {
        if (schema == null) return emptyList()
        // Editing models start with the safety checker off (owner decision) and the input image's shape.
        val included = if (mode == Mode.CHAT) setOf("max_tokens") else setOf("aspect_ratio", "resolution", "duration", "num_outputs") + if (ModelCatalog.edit(model) != null) setOf("disable_safety_checker") else emptySet()
        return schema.fields.filter { it.name in included && it.defaultValue != null }.map {
            value(it, if (model.startsWith("wan-video/wan-2.7-") && it.name == "resolution") "720p" else it.defaultValue.orEmpty())
        }
    }

    fun supports(preset: ModelPreset, features: Set<String>, resolution: String? = null): Boolean {
        val names = preset.schema.fields.map { it.name }.toSet()
        if (preset.schema.unsupportedRequired.isNotEmpty()) return false
        if (resolution != null && preset.fixedResolution != resolution && preset.schema.fields.none { it.name == "resolution" && resolution in it.choices }) return false
        return features.all { feature -> when (feature) {
            "Editing" -> ModelCatalog.edit(preset.model) != null
            "Starting frame" -> preset.mode == Mode.VIDEO && names.any { it in setOf("image", "image_url", "first_frame", "start_image", "first_frame_image") }
            "Ending frame" -> preset.mode == Mode.VIDEO && names.any { it in setOf("last_image", "last_frame", "last_frame_url", "end_image") }
            "Frame rate" -> names.any { it in setOf("frames_per_second", "frame_per_second", "fps") }
            "Fast generation" -> "fast_mode" in names || "go_fast" in names
            "LoRA" -> names.any { it.startsWith("lora_weights") || it == "replicate_weights" }
            "Image guidance" -> names.any { it in setOf("image", "image_url", "image_urls", "image_prompt", "start_image", "first_frame_image", "first_frame", "input_image", "input_images", "images", "image_input", "input_references", "reference_image", "reference_image_urls") }
            "Audio input" -> names.any { it in setOf("audio", "audio_url") }
            "Negative prompt" -> "negative_prompt" in names
            "Seed" -> "seed" in names
            "Safety controls" -> "disable_safety_checker" in names || "safety_tolerance" in names
            else -> false
        } }
    }

    fun summary(model: String, values: List<InputValue>): String = buildString {
        ModelCatalog.find(Mode.VIDEO, model)?.fixedResolution?.let { append("$it model; ") }
        append(values.joinToString("; ") { v ->
            val links = PictureList.split(v.value)
            "${v.name}=${if (links.size > 1) "${links.size} pictures" else if (v.value.length > 60) v.value.take(57) + "..." else v.value}"
        }.ifEmpty { "Provider defaults" })
    }
}

object ModelCatalog {
    private fun text(n: String, default: String? = null, note: String = "", uri: Boolean = false) = InputDefinition(n, InputKind.TEXT, defaultValue = default, description = note, uri = uri)
    private fun choice(n: String, values: List<String>, default: String, kind: InputKind = InputKind.TEXT, note: String = "") = InputDefinition(n, kind, default, values, description = note)
    private fun bool(n: String, default: Boolean, note: String = "") = InputDefinition(n, InputKind.BOOLEAN, default.toString(), description = note)
    private fun number(n: String, default: String? = null, min: Double? = null, max: Double? = null, integer: Boolean = true, note: String = "") = InputDefinition(n, if (integer) InputKind.INTEGER else InputKind.NUMBER, default, minimum = min, maximum = max, description = note)
    private val wideAspects = listOf("1:1", "16:9", "21:9", "3:2", "2:3", "4:5", "5:4", "3:4", "4:3", "9:16", "9:21")
    private val videoAspects = listOf("16:9", "9:16")
    val chatSchema = InputSchema(listOf(
        number("max_tokens", "1024", 1.0, 8192.0, note = "Maximum output tokens. App limit: 8192."),
        number("temperature", "1", 0.0, 2.0, false), number("top_p", "1", 0.0, 1.0, false),
        number("frequency_penalty", "0", -2.0, 2.0, false), number("presence_penalty", "0", -2.0, 2.0, false), number("seed")
    ))
    private fun flux(dev: Boolean) = InputSchema(listOf(
        choice("aspect_ratio", wideAspects, "1:1"), choice("megapixels", listOf("1", "0.25"), "1"),
        number("seed"), bool("go_fast", true, "Speed optimization may affect repeatability even with a seed."),
        number("num_outputs", "1", 1.0, 4.0), number("num_inference_steps", if (dev) "28" else "4", 1.0, if (dev) 50.0 else 4.0),
        choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "80", 0.0, 100.0),
        bool("disable_safety_checker", false, "Requests the provider's exposed setting. Provider restrictions still apply.")
    ) + if (dev) listOf(number("guidance", "3.5", 0.0, 10.0, false), text("image", note = "HTTPS input image URL for image guidance. Provider fetches this URL.", uri = true), number("prompt_strength", "0.8", 0.0, 1.0, false)) else emptyList())
    /** FLUX.1 Krea dev: FLUX dev's inputs with Krea's guidance default (schema checked 2026-10-30). */
    private val kreaSchema = InputSchema(flux(true).fields.map { if (it.name == "guidance") it.copy(defaultValue = "4.5") else it })
    private val safety = bool("disable_safety_checker", false, "Requests the provider's exposed setting. Provider restrictions still apply.")
    private val customAspects = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "custom")
    /** Qwen Image 2512, the December 2025 release with more natural people (schema checked 2026-10-30). */
    private val qwenImage2512 = InputSchema(listOf(
        choice("aspect_ratio", customAspects, "16:9"),
        number("width", min = 256.0, max = 2048.0, note = "Only used when aspect_ratio is custom. A multiple of 16."),
        number("height", min = 256.0, max = 2048.0, note = "Only used when aspect_ratio is custom. A multiple of 16."),
        text("image", note = "HTTPS starting picture for image to image. The result keeps its shape, not the person.", uri = true),
        number("strength", "0.8", 0.0, 1.0, false, "How far from the starting picture. 1 ignores it."),
        number("guidance", "4", 0.0, 10.0, false), number("num_inference_steps", "40", 20.0, 50.0), bool("go_fast", true),
        number("seed"), text("negative_prompt", ""), choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "95", 0.0, 100.0), safety
    ))
    /**
     * Z-Image Turbo at Pruna (schema checked 2026-10-30). The endpoint lists several compatibility inputs it ignores,
     * among them disable_safety_checker; its only real content control is enable_safety_filter, off by default.
     */
    private val zImageSchema = InputSchema(listOf(
        number("width", "1024", 64.0, 2048.0), number("height", "1024", 64.0, 2048.0),
        number("num_inference_steps", "8", 1.0, 50.0), number("guidance_scale", "0", 0.0, 20.0, false, "Leave at 0 for this turbo model."),
        number("seed"), choice("output_format", listOf("jpg", "png", "webp"), "jpg"), number("output_quality", "80", 0.0, 100.0),
        bool("enable_safety_filter", false, "Checks the prompt against the provider's content policy. Off at the provider by default.")
    ), listOf("num_outputs", "safety_tolerance", "model_version", "negative_prompt", "aspect_ratio", "megapixels", "go_fast", "disable_safety_checker", "enable_safety_checker"))
    /** P-Image at Pruna (schema checked 2026-10-30). Its LoRA inputs stay out: the base model is undisclosed, so no saved LoRA is known to fit. */
    private val pImageSchema = InputSchema(listOf(
        choice("aspect_ratio", customAspects, "16:9"),
        number("width", min = 256.0, max = 1440.0, note = "Only used when aspect_ratio is custom. A multiple of 16."),
        number("height", min = 256.0, max = 1440.0, note = "Only used when aspect_ratio is custom. A multiple of 16."),
        bool("prompt_upsampling", false, "Let a language model expand the prompt first."), number("seed"), safety,
        bool("enable_copyright_filter", false, "Rejects prompts that ask for copyrighted characters, trademarks, logos, or a real person's likeness.")
    ), listOf("lora_weights", "lora_scale", "hf_api_token"))
    private val loraSchema = InputSchema(flux(true).fields.map { if (it.name == "guidance") it.copy(defaultValue = "3") else it } + listOf(
        text("lora_weights", note = "Choose a saved FLUX dev LoRA below or enter an owner/model/version reference or HTTPS safetensors URL."),
        number("lora_scale", "1", -1.0, 3.0, false, "With go_fast, the provider applies a 1.5 scale multiplier."),
        text("extra_lora", note = "Optional second FLUX dev LoRA."), number("extra_lora_scale", "1", -1.0, 3.0, false)
    ), listOf("hf_api_token", "civitai_api_token"))
    /** Qwen Image with a LoRA: the trainer's own picture model (replicate/qwen-image-lora-trainer predict.py, checked 2026-10-09). */
    private val qwenLoraSchema = InputSchema(listOf(
        text("replicate_weights", note = "Choose a saved Qwen LoRA below. Qwen LoRAs run only on this model."),
        number("lora_scale", "1", 0.0, 3.0, false, "0 is the plain model; 1 is the full LoRA."),
        choice("aspect_ratio", listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3"), "16:9"),
        choice("image_size", listOf("optimize_for_quality", "optimize_for_speed"), "optimize_for_quality"),
        number("width", min = 512.0, max = 2048.0, note = "Custom width. Give both width and height to set the size yourself."),
        number("height", min = 512.0, max = 2048.0, note = "Custom height. Give both width and height to set the size yourself."),
        bool("go_fast", false, "Much faster, with lower quality."), number("num_inference_steps", "50", 0.0, 50.0),
        number("guidance", "4", 0.0, 10.0, false), number("seed"), text("negative_prompt", ""), bool("enhance_prompt", false),
        choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "80", 0.0, 100.0)
    ))
    private val i2v22 = InputSchema(listOf(
        text("image", note = "Required starting frame HTTPS image URL.", uri = true).copy(required = true),
        text("last_image", note = "Optional ending frame HTTPS image URL.", uri = true),
        choice("resolution", listOf("480p", "720p"), "480p"), number("num_frames", "81", 81.0, 121.0),
        number("frames_per_second", "16", 5.0, 30.0, note = "Pricing uses duration at 16 fps."),
        bool("interpolate_output", false, "Interpolate output to 30 fps."), bool("go_fast", true),
        number("sample_shift", "12", 1.0, 20.0, false), number("seed"), bool("disable_safety_checker", false),
        text("lora_weights_transformer"), text("lora_weights_transformer_2"),
        number("lora_scale_transformer", "1", integer = false), number("lora_scale_transformer_2", "1", integer = false)
    ))
    private val i2v27 = InputSchema(listOf(
        text("first_frame", note = "Starting frame: HTTPS JPG/PNG/BMP/WebP, up to 20 MB.", uri = true),
        text("last_frame", note = "Ending frame: up to 20 MB. Requires first_frame.", uri = true),
        text("first_clip", note = "Continue an HTTPS MP4/MOV clip, 2-10 seconds, up to 100 MB. Cannot combine with first_frame.", uri = true),
        choice("resolution", listOf("720p", "1080p"), "1080p"), number("duration", "5", 2.0, 15.0),
        number("seed", min = 0.0, max = 2147483647.0), text("negative_prompt", ""), bool("enable_prompt_expansion", true),
        text("audio", note = "HTTPS WAV/MP3, 3-30 seconds, up to 15 MB. Otherwise audio is generated.", uri = true)
    ))
    private val dsc = bool("disable_safety_checker", true, "Turns off the provider's safety checker. Replicate's usage policy still applies.")
    private fun image(name: String, list: Boolean, required: Boolean) = text(name, note = "The image to edit. Edit on an image card fills it.", uri = true).copy(required = required, list = list)
    private fun aspects(values: List<String>) = choice("aspect_ratio", values, "match_input_image")
    private val kontextAspects = listOf("match_input_image", "1:1", "16:9", "21:9", "3:2", "2:3", "4:5", "5:4", "3:4", "4:3", "9:16", "9:21")
    private val qwenAspects = listOf("match_input_image", "1:1", "16:9", "9:16", "4:3", "3:4")
    private fun qwenEdit(list: Boolean) = InputSchema(listOf(image("image", list, true), aspects(qwenAspects), bool("go_fast", true), number("seed"),
        choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "95", 0.0, 100.0), dsc))
    private fun klein() = InputSchema(listOf(image("images", true, false),
        aspects(listOf("match_input_image", "1:1", "16:9", "9:16", "3:2", "2:3", "4:3", "3:4", "5:4", "4:5", "21:9", "9:21")),
        choice("output_megapixels", listOf("0.25", "0.5", "1", "2", "4"), "1"), number("seed"), bool("go_fast", false),
        choice("output_format", listOf("webp", "jpg", "png"), "jpg"), number("output_quality", "95", 0.0, 100.0), dsc))

    /**
     * Editing models (Replicate list A, schemas checked 2026-10-03 and 2026-10-30): each Replicate model exposes
     * disable_safety_checker, which OT Studio turns on by default. LoRA versions (flux-kontext-dev-lora,
     * qwen-image-edit-plus-lora) wait until LoRA compatibility is tested, and Qwen 2511 is listed without its LoRA
     * inputs. Qwen Image Edit (the August 2025 model) left the list on 2026-10-30; 2511 does the same work better.
     * Spicy Image Edit 1 runs at SpicyAPI and edits only pictures made there.
     */
    val edits: List<EditModel> = listOf(
        EditModel("black-forest-labs/flux-kontext-dev", "FLUX Kontext dev", "input_image", "about 2.5 cents"),
        EditModel("qwen/qwen-image-edit-plus", "Qwen Image Edit Plus", "image", "about 3 cents"),
        EditModel("qwen/qwen-image-edit-2511", "Qwen Image Edit 2511", "image", "about 3 cents"),
        EditModel("qwen/qwen-edit-multiangle", "Qwen Edit Multi-angle", "image", "about 3 cents"),
        EditModel("black-forest-labs/flux-2-dev", "FLUX.2 dev", "input_images", "about 3 cents at 1 megapixel"),
        EditModel("black-forest-labs/flux-2-klein-9b", "FLUX.2 klein 9B", "images", "about 1.5 cents at 1 megapixel"),
        EditModel("black-forest-labs/flux-2-klein-4b", "FLUX.2 klein 4B", "images", "a fraction of a cent"),
        EditModel("bytedance/seedream-4.5", "Seedream 4.5", "image_input", "about 4 cents"),
        EditModel("prunaai/p-image-edit", "P-Image Edit", "images", "about 1 cent"),
        EditModel("spicyapi/spicy-image-edit-1", "Spicy Image Edit 1", "image_urls", "9 cents")
    )
    fun edit(model: String): EditModel? = edits.firstOrNull { it.model == model }
    /** Video models that cannot run without a starting picture or clip (see validateForModel). */
    fun needsStartPicture(model: String) = model in setOf("wan-video/wan-2.7-i2v", "wan-video/wan-2.2-i2v-fast")
    val defaultEdit get() = edits.first()

    private val editPresets = listOf(
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-kontext-dev", InputSchema(listOf(image("input_image", false, true), aspects(kontextAspects),
            number("num_inference_steps", "28", 4.0, 50.0), number("guidance", "2.5", 0.0, 10.0, false), number("seed"),
            choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "80", 0.0, 100.0), dsc, bool("go_fast", true)))),
        ModelPreset(Mode.IMAGE, "qwen/qwen-image-edit-plus", qwenEdit(true)),
        ModelPreset(Mode.IMAGE, "qwen/qwen-image-edit-2511", qwenEdit(true)),
        // Camera moves on one picture (schema checked 2026-10-30). The preloaded multi-angle LoRA is part of the model, so its LoRA inputs stay out.
        ModelPreset(Mode.IMAGE, "qwen/qwen-edit-multiangle", InputSchema(listOf(image("image", false, true),
            number("rotate_degrees", "0", -90.0, 90.0, note = "Turns the camera. Positive turns left, negative turns right."),
            number("move_forward", "0", 0.0, 10.0, note = "Moves the camera closer. 10 is a close-up."),
            number("vertical_tilt", "0", -1.0, 1.0, note = "-1 looks down from above, 0 stays level, 1 looks up from below."),
            bool("use_wide_angle", false, "Wide-angle lens."), aspects(listOf("match_input_image", "1:1", "16:9", "9:16", "4:3", "3:4")),
            bool("go_fast", true, "4 steps when on, 40 when off, unless steps are set."), number("num_inference_steps", min = 1.0, max = 40.0),
            number("true_guidance_scale", "1", 0.0, 10.0, false), number("seed"),
            choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "95", 0.0, 100.0), dsc), listOf("lora_weights", "lora_scale"))),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-2-dev", InputSchema(listOf(image("input_images", true, false), bool("go_fast", true),
            choice("aspect_ratio", listOf("match_input_image", "custom", "1:1", "16:9", "3:2", "2:3", "4:5", "5:4", "9:16", "3:4", "4:3"), "match_input_image"),
            number("width", min = 256.0, max = 1440.0, note = "Use with custom aspect ratio."), number("height", min = 256.0, max = 1440.0, note = "Use with custom aspect ratio."),
            number("seed"), choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "80", 0.0, 100.0), dsc))),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-2-klein-9b", klein()),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-2-klein-4b", klein()),
        ModelPreset(Mode.IMAGE, "bytedance/seedream-4.5", InputSchema(listOf(image("image_input", true, false), choice("size", listOf("2K", "4K", "custom"), "2K"),
            aspects(listOf("match_input_image", "1:1", "4:3", "3:4", "4:5", "5:4", "16:9", "9:16", "3:2", "2:3", "21:9", "9:21")),
            number("width", min = 1024.0, max = 4096.0, note = "Use with custom size."), number("height", min = 1024.0, max = 4096.0, note = "Use with custom size."),
            choice("sequential_image_generation", listOf("disabled", "auto"), "disabled"), number("max_images", "1", 1.0, 15.0), dsc))),
        ModelPreset(Mode.IMAGE, "prunaai/p-image-edit", InputSchema(listOf(image("images", true, false), bool("turbo", true),
            aspects(listOf("match_input_image", "1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3")), number("seed"), dsc)))
    )

    private val basePresets: List<ModelPreset> = listOf(
        // Mistral Nemo and Qwen3 30B-A3B returned valid job plans in testing (2026-10-03); MythoMax often did not.
        ModelPreset(Mode.CHAT, "mistralai/mistral-nemo", chatSchema), ModelPreset(Mode.CHAT, "qwen/qwen3-30b-a3b-instruct-2507", chatSchema),
        ModelPreset(Mode.CHAT, "gryphe/mythomax-l2-13b", chatSchema), ModelPreset(Mode.CHAT, "qwen/qwen3-8b", chatSchema),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-schnell", flux(false)), ModelPreset(Mode.IMAGE, "black-forest-labs/flux-dev", flux(true)),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-krea-dev", kreaSchema), ModelPreset(Mode.IMAGE, "qwen/qwen-image-2512", qwenImage2512),
        ModelPreset(Mode.IMAGE, "prunaai/z-image-turbo", zImageSchema), ModelPreset(Mode.IMAGE, "prunaai/p-image", pImageSchema),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-dev-lora", loraSchema), ModelPreset(Mode.IMAGE, "qwen/qwen-image-lora-trainer", qwenLoraSchema),
        ModelPreset(Mode.VIDEO, "wan-video/wan-2.2-i2v-fast", i2v22), ModelPreset(Mode.VIDEO, "wan-video/wan-2.7-i2v", i2v27),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-1.1-pro", InputSchema(listOf(
            choice("aspect_ratio", listOf("custom", "1:1", "16:9", "3:2", "2:3", "4:5", "5:4", "9:16", "3:4", "4:3"), "1:1"),
            number("width", min = 256.0, max = 1440.0, note = "Use with custom aspect ratio. Provider rounds to a multiple of 32."), number("height", min = 256.0, max = 1440.0, note = "Use with custom aspect ratio. Provider rounds to a multiple of 32."),
            number("seed"), text("image_prompt", note = "HTTPS image guidance URL. Provider fetches this URL.", uri = true),
            choice("output_format", listOf("webp", "jpg", "png"), "webp"), number("output_quality", "80", 0.0, 100.0),
            number("safety_tolerance", "2", 1.0, 6.0, note = "1 is most strict; 6 is most permissive."), bool("prompt_upsampling", false)
        ))),
        ModelPreset(Mode.VIDEO, "wan-video/wan-2.7-t2v", InputSchema(listOf(
            choice("resolution", listOf("720p", "1080p"), "1080p"), choice("aspect_ratio", videoAspects + listOf("1:1", "4:3", "3:4"), "16:9"),
            number("duration", "5", 2.0, 15.0), number("seed", min = 0.0, max = 2147483647.0), text("negative_prompt", ""),
            bool("enable_prompt_expansion", true), text("audio", note = "HTTPS WAV/MP3 URL, 3-30 seconds and up to 15 MB. Otherwise the provider generates audio.", uri = true)
        ))),
        ModelPreset(Mode.VIDEO, "wan-video/wan-2.1-1.3b", InputSchema(listOf(
            choice("resolution", listOf("480p"), "480p"), choice("aspect_ratio", videoAspects, "16:9"),
            choice("frame_num", listOf("17", "33", "49", "65", "81"), "81", InputKind.INTEGER, "Duration in frames at fixed 16 fps."),
            number("seed"), number("sample_shift", "8", 0.0, 20.0, false), number("sample_steps", "30", 10.0, 50.0), number("sample_guide_scale", "6", 0.0, 20.0, false)
        ))),
        ModelPreset(Mode.VIDEO, "wan-video/wan-2.2-t2v-fast", InputSchema(listOf(
            choice("resolution", listOf("480p", "720p"), "480p"), choice("aspect_ratio", videoAspects, "16:9"),
            number("num_frames", "81", 81.0, 121.0), number("frames_per_second", "16", 5.0, 30.0, note = "Provider pricing uses duration at 16 fps. Interpolation can change final playback to 30 fps."),
            bool("interpolate_output", true, "Interpolate output to 30 fps."), bool("go_fast", true), number("sample_shift", "12", 1.0, 20.0, false),
            number("seed"), bool("optimize_prompt", false, "Translate prompt to Chinese before generation."), bool("disable_safety_checker", false),
            text("lora_weights_transformer", note = "HTTPS safetensors URL."), text("lora_weights_transformer_2", note = "HTTPS safetensors URL for the second transformer."),
            number("lora_scale_transformer", "1", integer = false), number("lora_scale_transformer_2", "1", integer = false)
        )))
    )
    /**
     * SpicyAPI models, listed under the "spicyapi/" prefix and run against api.spicyapi.com with their own key. Owner
     * decision, 2026-10-30, replacing the text-only rule of 2026-10-05: the photo-based models are offered too. SpicyAPI
     * accepts as a picture input only a picture the same account made there (docs checked 2026-10-30), so the app sends
     * the address of a finished SpicyAPI picture and never a phone photo or a Replicate upload. The one exception is the
     * reference model, which takes any picture, describes it, and draws new people in its look.
     */
    const val SPICY_REFERENCE = "spicyapi/spicy-image-reference-1"
    const val SPICY_UPSCALER = "spicyapi/spicy-upscale-1"
    private val spicySizes = listOf("832*1216", "1216*832", "1024*1024", "1280*1280", "1440*1440", "1024*1536", "1536*1024", "1080*1920", "1920*1080", "1152*2048", "2048*1152")
    private val spicyStyles = listOf("photorealistic", "studio", "anime", "3d", "cartoon")
    private val spicyResolutions = listOf("480P", "720P", "1080P")
    private val spicyEnhance = bool("enhance_prompt", false, "Let SpicyAPI plan the shot from your words first.")
    private val spicyCount = number("n", "1", 1.0, 4.0, note = "Pictures per request, each billed.")
    private fun spicyImage() = InputSchema(listOf(choice("size", spicySizes, "832*1216", note = "Width by height in pixels."), spicyCount,
        choice("style", spicyStyles, "photorealistic"), text("negative_prompt", note = "What to keep out of the picture."), number("seed"), spicyEnhance))
    private fun spicyPicture(name: String, note: String, list: Boolean = false, required: Boolean = true) = text(name, note = note, uri = true).copy(required = required, list = list)
    val spicyPresets = listOf(
        ModelPreset(Mode.IMAGE, "spicyapi/spicy-image-1", spicyImage()),
        ModelPreset(Mode.IMAGE, "spicyapi/spicy-image-1-pro", spicyImage()),
        ModelPreset(Mode.IMAGE, "spicyapi/spicy-image-photo-1", spicyImage()),
        ModelPreset(Mode.IMAGE, "spicyapi/spicy-image-action-1", InputSchema(listOf(
            spicyPicture("image_url", "A SpicyAPI picture of the woman. Her face, hair, and skin tone go into the scene; the scene supplies the rest."),
            text("action", note = "The scene's id from SpicyAPI's image actions list (GET /v1/images/actions), such as pov-missionary.").copy(required = true),
            spicyCount, choice("style", spicyStyles, "photorealistic"), number("seed")))),
        ModelPreset(Mode.IMAGE, SPICY_REFERENCE, InputSchema(listOf(
            spicyPicture("reference_image", "The picture to recreate: a photo from this phone or a SpicyAPI picture. Its look is kept; the faces are not.")))),
        ModelPreset(Mode.IMAGE, "spicyapi/spicy-image-edit-1", InputSchema(listOf(
            spicyPicture("image_urls", "SpicyAPI pictures: the one to edit first, then up to two references.", list = true),
            spicyPicture("mask_url", "Optional black and white mask: white changes, black stays. Works with one picture only.", required = false),
            spicyCount, text("negative_prompt", note = "What to keep out of the picture."), number("seed"), spicyEnhance))),
        ModelPreset(Mode.VIDEO, "spicyapi/spicy-video-1", InputSchema(listOf(choice("aspect_ratio", listOf("16:9", "9:16", "1:1", "4:3", "3:4"), "16:9")))),
        ModelPreset(Mode.VIDEO, "spicyapi/spicy-cinema-1", InputSchema(listOf(number("duration", "5", 3.0, 15.0, note = "3 to 15 seconds."),
            choice("resolution", spicyResolutions, "720P"), choice("aspect_ratio", listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9", "9:21", "4:5"), "16:9")))),
        ModelPreset(Mode.VIDEO, "spicyapi/spicy-motion-draft-1", InputSchema(listOf(
            spicyPicture("image_url", "Starting picture: a SpicyAPI picture. The clip is silent."),
            number("duration", "5", 2.0, 15.0, note = "2 to 15 seconds."), choice("resolution", listOf("720P", "1080P"), "720P"), number("seed"), spicyEnhance))),
        ModelPreset(Mode.VIDEO, "spicyapi/spicy-motion-3", InputSchema(listOf(
            spicyPicture("image_url", "Optional starting picture: a SpicyAPI picture. Not together with reference pictures or a shape.", required = false),
            spicyPicture("last_frame_url", "Optional ending picture: a SpicyAPI picture. Needs a starting picture.", required = false),
            spicyPicture("reference_image_urls", "Up to 10 SpicyAPI pictures that set who is in the clip without fixing the first shot. Not together with a starting picture.", list = true, required = false),
            number("duration", "5", 2.0, 30.0, note = "2 to 30 seconds."), choice("resolution", spicyResolutions, "720P"),
            InputDefinition("aspect_ratio", InputKind.TEXT, null, listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9"), description = "Shape of a clip from words only. Left out, the model picks one."),
            number("seed"), spicyEnhance))),
        ModelPreset(Mode.VIDEO, "spicyapi/spicy-cinema-1-image", InputSchema(listOf(
            spicyPicture("image_url", "Starting picture: a SpicyAPI picture. The clip keeps its shape."),
            number("duration", "5", 3.0, 15.0, note = "3 to 15 seconds."), choice("resolution", spicyResolutions, "720P"), number("seed"), spicyEnhance))),
        ModelPreset(Mode.VIDEO, "spicyapi/spicy-cinema-1-character", InputSchema(listOf(
            spicyPicture("reference_image_urls", "Up to 9 SpicyAPI pictures of the people, outfits, or props to keep. In your words, call them @img1, @img2, and so on.", list = true),
            number("duration", "5", 3.0, 15.0, note = "3 to 15 seconds."), choice("resolution", spicyResolutions, "720P"),
            choice("aspect_ratio", listOf("16:9", "9:16", "1:1", "4:3", "3:4", "4:5", "5:4", "9:21", "21:9"), "16:9"), number("seed"), spicyEnhance)))
    )
    fun spicy(model: String) = model.startsWith("spicyapi/")
    /** The SpicyAPI model that takes a picture from this phone, sent inside the request as a data: address. */
    fun spicyPhonePhotos(model: String) = model == SPICY_REFERENCE
    /** SpicyAPI image models that run on the edits endpoint. */
    fun spicyEdits(model: String) = model == "spicyapi/spicy-image-edit-1" || model == SPICY_UPSCALER
    /** SpicyAPI models that take no prompt: the picture or the scene says everything. */
    fun spicyPromptless(model: String) = model == "spicyapi/spicy-image-action-1" || model == SPICY_REFERENCE || model == SPICY_UPSCALER

    val presets: List<ModelPreset> = basePresets + editPresets + spicyPresets + OpenRouterMedia.presets
    fun find(mode: Mode, model: String): ModelPreset? = presets.firstOrNull { it.mode == mode && it.model == model }
        ?: listOf(upscaler, spicyUpscaler, expander).firstOrNull { mode == it.mode && model == it.model }

    /**
     * P-Image Upscale, used only by the Upscale action (it takes an image, not a prompt), so it is not in the model lists.
     * It replaced Real-ESRGAN on 2026-10-30 (schema checked that day).
     */
    val upscaler = ModelPreset(Mode.IMAGE, "prunaai/p-image-upscale", InputSchema(listOf(
        text("image", note = "Image to upscale.", uri = true).copy(required = true),
        choice("upscale_mode", listOf("factor", "target"), "factor", note = "factor multiplies each side; target aims at a megapixel count."),
        number("factor", "2", 1.0, 8.0, false, "Each side is multiplied by this, when upscale_mode is factor."),
        number("target", "4", 1.0, 128.0, note = "Megapixels, when upscale_mode is target."),
        bool("enhance_details", false, "Sharpens fine textures. May change small details."), bool("enhance_realism", false, "Improves the realism of AI pictures. May change details."),
        choice("output_format", listOf("jpg", "png", "webp"), "jpg"), number("output_quality", "80", 0.0, 100.0), bool("disable_safety_checker", false)
    )))

    /** Spicy Upscale 1, used by the Upscale action for a picture SpicyAPI made (docs checked 2026-10-30). Not in the model lists. */
    val spicyUpscaler = ModelPreset(Mode.IMAGE, SPICY_UPSCALER, InputSchema(listOf(
        text("image_url", note = "The SpicyAPI picture to upscale.", uri = true).copy(required = true),
        choice("scale", listOf("2", "4"), "2", InputKind.INTEGER, "4 takes pictures of about 1 megapixel at most.")
    )))

    /**
     * FLUX.1 Fill dev, used only by Expand: it fills the white part of a mask. The app sends the picture on a larger
     * canvas of the new shape, then rebuilds the full-size result on the phone. Not in the model lists.
     */
    val expander = ModelPreset(Mode.IMAGE, "black-forest-labs/flux-fill-dev", InputSchema(listOf(
        text("image", note = "The canvas with the picture on it.", uri = true).copy(required = true),
        text("mask", note = "White areas are filled; black areas are kept.", uri = true),
        text("megapixels", note = "match_input keeps the canvas size, up to 1440 by 1440."),
        choice("output_format", listOf("webp", "jpg", "png"), "webp"),
        number("num_inference_steps", "28", 1.0, 50.0, note = "Denoising steps."),
        bool("disable_safety_checker", false, "Disable the safety checker.")
    )))
}

/** Plain names for model inputs, so the API CALL panel reads like a form instead of an API. The real name shows small below. */
object InputLabels {
    private val names = mapOf(
        "disable_safety_checker" to "Safety checker", "aspect_ratio" to "Shape", "num_outputs" to "Number of pictures",
        "go_fast" to "Fast mode", "seed" to "Seed", "megapixels" to "Size in megapixels", "output_format" to "File type",
        "output_quality" to "File quality", "num_inference_steps" to "Steps", "guidance" to "Prompt strength",
        "guidance_scale" to "Prompt strength", "prompt_strength" to "How far from your picture", "lora_scale" to "LoRA strength",
        "lora_weights" to "LoRA", "duration" to "Length in seconds", "resolution" to "Resolution", "negative_prompt" to "Avoid",
        "image" to "Picture", "input_image" to "Picture", "image_input" to "Pictures", "input_images" to "Pictures", "images" to "Pictures",
        "first_frame" to "Starting picture", "last_frame" to "Ending picture", "last_image" to "Ending picture", "end_image" to "Ending picture",
        "start_image" to "Starting picture", "first_frame_image" to "Starting picture", "first_clip" to "Clip to continue",
        "frames_per_second" to "Frames per second", "fps" to "Frames per second", "num_frames" to "Length in frames (81 is 5 s, 121 is 7.5 s)",
        "safety_tolerance" to "Safety tolerance", "size" to "Size", "width" to "Width", "height" to "Height",
        "n" to "Number of pictures", "style" to "Style", "enhance_prompt" to "Plan the shot first",
        "input_references" to "Pictures", "generate_audio" to "Sound", "replicate_weights" to "LoRA", "image_size" to "Size",
        "image_url" to "Picture", "image_urls" to "Pictures", "reference_image" to "Picture to recreate", "reference_image_urls" to "Reference pictures",
        "last_frame_url" to "Ending picture", "mask_url" to "Mask", "action" to "Scene", "scale" to "Scale factor", "strength" to "How far from your picture",
        "rotate_degrees" to "Turn the camera (degrees)", "move_forward" to "Move the camera closer", "vertical_tilt" to "Tilt the camera", "use_wide_angle" to "Wide-angle lens",
        "true_guidance_scale" to "Prompt strength", "upscale_mode" to "Upscale by", "factor" to "Factor", "target" to "Target megapixels",
        "enhance_details" to "Sharpen details", "enhance_realism" to "Improve realism", "enable_safety_filter" to "Prompt safety filter",
        "enable_copyright_filter" to "Copyright filter", "prompt_upsampling" to "Expand the prompt first"
    )

    fun title(name: String): String = names[name] ?: name.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

    /** A video model's plain "image" or "image_url" input is its starting picture. */
    fun title(name: String, mode: Mode): String = if (mode == Mode.VIDEO && (name == "image" || name == "image_url")) "Starting picture" else title(name)

    /** True for the switch that reads the other way round: on means the checker is off. */
    fun invertedSafety(d: InputDefinition) = d.name == "disable_safety_checker" && d.kind == InputKind.BOOLEAN

    /** "Model default (1:1)" for the first entry of a choice list. */
    fun defaultChoice(d: InputDefinition) = "Model default" + (d.defaultValue?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
}

/** Plain model names with what they are for and what they cost, for the simple view. Unknown models show their ID. */
object ModelNames {
    private val names = mapOf(
        "mistralai/mistral-nemo" to "Mistral Nemo, recommended", "qwen/qwen3-30b-a3b-instruct-2507" to "Qwen3 30B", "qwen/qwen3-8b" to "Qwen3 8B",
        "gryphe/mythomax-l2-13b" to "MythoMax",
        "black-forest-labs/flux-schnell" to "Quick draft (FLUX schnell), under 1 cent",
        "black-forest-labs/flux-dev" to "Detailed picture (FLUX dev), 2.5 cents",
        "black-forest-labs/flux-krea-dev" to "Natural photo look (FLUX Krea dev), 2.5 cents",
        "qwen/qwen-image-2512" to "Realistic people (Qwen Image 2512), 2 cents",
        "prunaai/z-image-turbo" to "Fast realistic picture (Z-Image Turbo), under 1 cent",
        "prunaai/p-image" to "Fast picture (P-Image), under 1 cent",
        "black-forest-labs/flux-dev-lora" to "Your trained face or style (FLUX dev LoRA), 3 cents",
        "qwen/qwen-image-lora-trainer" to "Your Qwen LoRA (Qwen Image with a LoRA), a few cents",
        "black-forest-labs/flux-1.1-pro" to "Best quality (FLUX 1.1 pro), 4 cents",
        "black-forest-labs/flux-2-dev" to "FLUX.2 dev, about 3 cents",
        "black-forest-labs/flux-2-klein-9b" to "FLUX.2 klein 9B, about 1.5 cents",
        "black-forest-labs/flux-2-klein-4b" to "FLUX.2 klein 4B, under 1 cent",
        "bytedance/seedream-4.5" to "Seedream 4.5, about 4 cents",
        "black-forest-labs/flux-kontext-dev" to "Edit a picture (FLUX Kontext dev), 2.5 cents",
        "qwen/qwen-image-edit-plus" to "Edit a picture (Qwen Image Edit Plus), 3 cents",
        "qwen/qwen-image-edit-2511" to "Edit a picture (Qwen Image Edit 2511), 3 cents",
        "qwen/qwen-edit-multiangle" to "New camera angle of a picture (Qwen Edit Multi-angle), 3 cents",
        "prunaai/p-image-edit" to "Edit a picture (P-Image Edit), 1 cent",
        "wan-video/wan-2.2-t2v-fast" to "Quick video from words (WAN 2.2 fast), 5 cents",
        "wan-video/wan-2.2-i2v-fast" to "Quick video from a picture (WAN 2.2 fast), 5 cents",
        "wan-video/wan-2.7-t2v" to "Video from words (WAN 2.7), about 50 cents",
        "wan-video/wan-2.7-i2v" to "Video from a picture (WAN 2.7), about 50 cents",
        "wan-video/wan-2.1-1.3b" to "Older small video model (WAN 2.1)",
        "spicyapi/spicy-image-1" to "Adult picture from words (Spicy Image 1), 6 cents",
        "spicyapi/spicy-image-1-pro" to "Adult picture from words, best (Spicy Image 1 Pro), 9 cents",
        "spicyapi/spicy-image-photo-1" to "Adult photo from words, natural light (Spicy Image Photo 1), 8 cents",
        "spicyapi/spicy-image-action-1" to "Adult scene with the woman from your Spicy picture (Spicy Image Action 1), 15 cents",
        "spicyapi/spicy-image-reference-1" to "Adult picture in the look of a picture, new people (Spicy Image Reference 1), 12 cents",
        "spicyapi/spicy-image-edit-1" to "Edit a Spicy picture (Spicy Image Edit 1), 9 cents",
        "spicyapi/spicy-video-1" to "Adult video from words (Spicy Video 1), from 20 cents a second",
        "spicyapi/spicy-cinema-1" to "Adult video from words, cinematic (Spicy Cinema 1), from 14 cents a second",
        "spicyapi/spicy-motion-draft-1" to "Adult silent draft video from a Spicy picture (Spicy Motion Draft 1), from 5 cents a second",
        "spicyapi/spicy-motion-3" to "Adult video with sound, best (Spicy Motion 3), from 10 cents a second",
        "spicyapi/spicy-cinema-1-image" to "Adult video from a Spicy picture, cinematic (Spicy Cinema 1 Image), from 14 cents a second",
        "spicyapi/spicy-cinema-1-character" to "Adult video with the people from your Spicy pictures (Spicy Cinema 1 Character), from 14 cents a second"
    )
    fun title(model: String): String = names[model] ?: OpenRouterMedia.titles[model] ?: model

    /** The settings the simple view shows: the ones most people change. Pictures and required inputs always show. */
    val simple = setOf("aspect_ratio", "num_outputs", "duration", "num_frames", "resolution", "disable_safety_checker", "size", "n", "style", "generate_audio")
}

/** The shapes Expand offers, with plain names. The first is the usual choice. */
val EXPAND_RATIOS = listOf("16:9" to "Wide", "9:16" to "Tall", "1:1" to "Square", "3:2" to "Landscape", "2:3" to "Portrait",
    "4:3" to "Landscape", "3:4" to "Portrait", "5:4" to "Landscape", "4:5" to "Portrait")

/** Geometry for Expand. Plain arithmetic, so a desktop build can share it. */
object Outpaint {
    /** The full-size canvas of the new shape, and where the picture ([w] by [h]) sits on it. */
    data class Layout(val canvasW: Int, val canvasH: Int, val x: Int, val y: Int, val w: Int, val h: Int)
    /** The reduced copy sent to the model: [w] by [h], multiples of 32. The canvas fills its top-left [contentW] by [contentH]. */
    data class Send(val w: Int, val h: Int, val contentW: Int, val contentH: Int, val scale: Double)

    /** FLUX Fill dev's largest size with match_input. */
    const val MAX_SEND = 1440
    /** The largest result side, which keeps a phone's memory use moderate. */
    const val MAX_CANVAS = 4096

    fun ratio(text: String): Double {
        val parts = text.split(':').mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 2 || parts.any { it <= 0 }) throw UserError("Choose one of the listed shapes.")
        return parts[0] / parts[1]
    }

    /** Only the new area grows: a wider shape keeps the picture's full height, and a taller shape keeps its full width. */
    fun layout(w: Int, h: Int, ratio: String): Layout {
        if (w <= 0 || h <= 0) throw UserError("This picture could not be read.")
        val r = ratio(ratio)
        val s = w.toDouble() / h
        if (Math.abs(r - s) / s < 0.02) throw UserError("The picture already has this shape. Choose another one.")
        val cw = if (r > s) Math.round(h * r).toInt() else w
        val ch = if (r > s) h else Math.round(w / r).toInt()
        return Layout(cw, ch, (cw - w) / 2, (ch - h) / 2, w, h)
    }

    fun send(layout: Layout): Send {
        // One step of 32 below the limit, so rounding up to a multiple of 32 stays inside it.
        val scale = minOf(1.0, (MAX_SEND - 32).toDouble() / maxOf(layout.canvasW, layout.canvasH))
        val cw = maxOf(32, Math.round(layout.canvasW * scale).toInt()); val ch = maxOf(32, Math.round(layout.canvasH * scale).toInt())
        return Send((cw + 31) / 32 * 32, (ch + 31) / 32 * 32, cw, ch, scale)
    }

    /** The width of the blend between the original and the new area, in full-size pixels. */
    fun feather(layout: Layout): Int = maxOf(8, minOf(layout.w, layout.h) / 12)

    /** The words sent to the fill model. Its prompt is required, so plain continuation is the default. */
    fun prompt(words: String): String {
        val base = "Continue the scene beyond the edges of the photo, with the same lighting, colors, and camera style. Photorealistic."
        return if (words.isBlank()) base else "${words.trim().trimEnd('.')}. $base"
    }
}

/** An editing model: [imageField] receives the image to edit, and [price] is a plain estimate per edit. */
data class EditModel(val model: String, val title: String, val imageField: String, val price: String)

/** Where a photo can be used: the target mode, the model inputs that accept it, and a model to switch to if needed. */
enum class MediaRole(val title: String, val mode: Mode, val fields: List<String>, val fallbackModel: String) {
    REFERENCE("Reference for the next image", Mode.IMAGE, listOf("image", "image_prompt", "input_image", "input_images", "images", "image_input", "input_references", "image_url", "image_urls", "reference_image"), "black-forest-labs/flux-dev"),
    START_FRAME("Starting frame for a video", Mode.VIDEO, listOf("first_frame", "image", "image_url", "start_image", "first_frame_image"), "wan-video/wan-2.7-i2v"),
    END_FRAME("Ending frame for a video", Mode.VIDEO, listOf("last_frame", "last_image", "last_frame_url", "end_image"), "wan-video/wan-2.7-i2v");

    /**
     * Returns settings with [url] in this role's input. Keeps the current model when it has a matching input;
     * otherwise switches to [fallbackModel]. A starting frame replaces any clip to continue (they cannot be combined).
     */
    fun apply(current: ModelSettings, url: String, forModel: (String) -> ModelSettings): ModelSettings {
        fun field(s: ModelSettings) = s.schema?.fields?.firstOrNull { it.name in fields && it.uri }
        val target = if (field(current) != null) current else forModel(fallbackModel)
        val input = field(target) ?: throw UserError("${target.model} has no input for this photo. Choose another model in API CALL.")
        val kept = target.options.filter { it.name != input.name && !(this == START_FRAME && it.name == "first_clip") }
        val updated = target.copy(options = kept + InputValue(input.name, InputKind.TEXT, url))
        ModelOptions.validate(updated.schema ?: throw UserError("Load this model's inputs first."), updated.options)
        return updated
    }
}
