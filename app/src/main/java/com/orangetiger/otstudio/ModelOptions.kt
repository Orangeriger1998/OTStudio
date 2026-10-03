package com.orangetiger.otstudio

import java.net.URI

enum class InputKind { BOOLEAN, INTEGER, NUMBER, TEXT }
data class InputValue(val name: String, val kind: InputKind, val value: String)
data class InputDefinition(
    val name: String, val kind: InputKind, val defaultValue: String? = null,
    val choices: List<String> = emptyList(), val minimum: Double? = null, val maximum: Double? = null,
    val required: Boolean = false, val description: String = "", val uri: Boolean = false
)
data class InputSchema(val fields: List<InputDefinition>, val unsupported: List<String> = emptyList(), val unsupportedRequired: List<String> = emptyList())
data class ModelPreset(val mode: Mode, val model: String, val schema: InputSchema, val fixedResolution: String? = null)
data class SetupSuggestion(val model: String, val options: List<InputValue>, val reason: String)

object ModelOptions {
    private val fieldName = Regex("[A-Za-z][A-Za-z0-9_]{0,79}")
    private val reserved = setOf("prompt", "model", "messages", "stream", "tools", "tool_choice", "api_key", "authorization", "webhook", "webhook_events_filter")
    val featureNames = listOf("Starting frame", "Ending frame", "Frame rate", "Fast generation", "LoRA", "Image guidance", "Audio input", "Negative prompt", "Seed", "Safety controls")
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
        if (model == "wan-video/wan-2.7-i2v") {
            if (has("first_frame") == has("first_clip")) throw UserError("WAN 2.7 I2V needs either a first frame or a first clip, with only one selected.")
            if (has("last_frame") && !has("first_frame")) throw UserError("An ending frame requires a starting frame.")
        }
        if (model == "wan-video/wan-2.2-i2v-fast" && !has("image")) throw UserError("WAN 2.2 I2V needs a starting image URL.")
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
            val u = try { URI(v) } catch (_: Exception) { throw UserError("${def.name}: enter an HTTPS media URL.") }
            if (u.scheme != "https" || u.host.isNullOrBlank() || u.userInfo != null) throw UserError("${def.name}: enter an HTTPS media URL without embedded credentials.")
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
        val included = if (mode == Mode.CHAT) setOf("max_tokens") else setOf("aspect_ratio", "resolution", "duration", "num_outputs")
        return schema.fields.filter { it.name in included && it.defaultValue != null }.map {
            value(it, if (model.startsWith("wan-video/wan-2.7-") && it.name == "resolution") "720p" else it.defaultValue.orEmpty())
        }
    }

    fun supports(preset: ModelPreset, features: Set<String>, resolution: String? = null): Boolean {
        val names = preset.schema.fields.map { it.name }.toSet()
        if (preset.schema.unsupportedRequired.isNotEmpty()) return false
        if (resolution != null && preset.fixedResolution != resolution && preset.schema.fields.none { it.name == "resolution" && resolution in it.choices }) return false
        return features.all { feature -> when (feature) {
            "Starting frame" -> preset.mode == Mode.VIDEO && names.any { it in setOf("image", "first_frame", "start_image", "first_frame_image") }
            "Ending frame" -> preset.mode == Mode.VIDEO && names.any { it in setOf("last_image", "last_frame", "end_image") }
            "Frame rate" -> names.any { it in setOf("frames_per_second", "frame_per_second", "fps") }
            "Fast generation" -> "fast_mode" in names || "go_fast" in names
            "LoRA" -> names.any { it.startsWith("lora_weights") }
            "Image guidance" -> names.any { it in setOf("image", "image_prompt", "start_image", "first_frame_image", "first_frame") }
            "Audio input" -> names.any { it in setOf("audio", "audio_url") }
            "Negative prompt" -> "negative_prompt" in names
            "Seed" -> "seed" in names
            "Safety controls" -> "disable_safety_checker" in names || "safety_tolerance" in names
            else -> false
        } }
    }

    fun routedModel(model: String, resolution: String): String {
        if (model in listOf("wavespeedai/wan-2.1-t2v-480p", "wavespeedai/wan-2.1-t2v-720p")) {
            if (resolution !in listOf("480p", "720p")) throw UserError("WAN 2.1 supports the 480p or 720p model route.")
            return "wavespeedai/wan-2.1-t2v-$resolution"
        }
        return model
    }

    fun summary(model: String, values: List<InputValue>): String = buildString {
        ModelCatalog.find(Mode.VIDEO, model)?.fixedResolution?.let { append("$it model; ") }
        append(values.joinToString("; ") { "${it.name}=${if (it.value.length > 60) it.value.take(57) + "..." else it.value}" }.ifEmpty { "Provider defaults" })
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
    private val waveSchema = InputSchema(listOf(
        choice("aspect_ratio", videoAspects, "16:9"), choice("fast_mode", listOf("Off", "Balanced", "Fast"), "Balanced", note = "Faster modes may reduce quality."),
        number("seed"), number("sample_shift", "3", 0.0, 10.0), number("sample_steps", "30", 1.0, 40.0),
        number("sample_guide_scale", "5", 1.0, 10.0, false), text("negative_prompt", ""),
        text("lora_weights", note = "Provider-supported Hugging Face/CivitAI or HTTPS safetensors location."), number("lora_scale", "1", 0.0, 4.0, false),
        bool("disable_safety_checker", false)
    ))
    private val loraSchema = InputSchema(flux(true).fields.map { if (it.name == "guidance") it.copy(defaultValue = "3") else it } + listOf(
        text("lora_weights", note = "Choose a saved FLUX dev LoRA below or enter an owner/model/version reference or HTTPS safetensors URL."),
        number("lora_scale", "1", -1.0, 3.0, false, "With go_fast, the provider applies a 1.5 scale multiplier."),
        text("extra_lora", note = "Optional second FLUX dev LoRA."), number("extra_lora_scale", "1", -1.0, 3.0, false)
    ), listOf("hf_api_token", "civitai_api_token"))
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
    val presets: List<ModelPreset> = listOf(
        ModelPreset(Mode.CHAT, "gryphe/mythomax-l2-13b", chatSchema), ModelPreset(Mode.CHAT, "qwen/qwen3-8b", chatSchema),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-schnell", flux(false)), ModelPreset(Mode.IMAGE, "black-forest-labs/flux-dev", flux(true)),
        ModelPreset(Mode.IMAGE, "black-forest-labs/flux-dev-lora", loraSchema),
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
        ModelPreset(Mode.VIDEO, "wavespeedai/wan-2.1-t2v-480p", waveSchema, "480p"), ModelPreset(Mode.VIDEO, "wavespeedai/wan-2.1-t2v-720p", waveSchema, "720p"),
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
    fun find(mode: Mode, model: String): ModelPreset? = presets.firstOrNull { it.mode == mode && it.model == model } ?: upscaler.takeIf { mode == it.mode && model == it.model }

    /** Real-ESRGAN upscaler, used only by the Upscale action (it takes an image, not a prompt), so it is not in the model lists. */
    val upscaler = ModelPreset(Mode.IMAGE, "nightmareai/real-esrgan", InputSchema(listOf(
        text("image", note = "Image to upscale.", uri = true).copy(required = true),
        number("scale", "4", 0.0, 10.0, false, "Factor to scale the image by."),
        bool("face_enhance", false, "Run GFPGAN face enhancement.")
    )))
}

/** Where a photo can be used: the target mode, the model inputs that accept it, and a model to switch to if needed. */
enum class MediaRole(val title: String, val mode: Mode, val fields: List<String>, val fallbackModel: String) {
    REFERENCE("Reference for the next image", Mode.IMAGE, listOf("image", "image_prompt"), "black-forest-labs/flux-dev"),
    START_FRAME("Starting frame for a video", Mode.VIDEO, listOf("first_frame", "image", "start_image", "first_frame_image"), "wan-video/wan-2.7-i2v"),
    END_FRAME("Ending frame for a video", Mode.VIDEO, listOf("last_frame", "last_image", "end_image"), "wan-video/wan-2.7-i2v");

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
