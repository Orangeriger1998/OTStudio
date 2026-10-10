package com.orangetiger.otstudio

import org.json.JSONObject
import java.net.URI

data class SavedLora(val name: String, val reference: String, val trigger: String, val scale: Double, val baseModel: String = "flux-dev")

object LoraCodec {
    const val MODEL = "black-forest-labs/flux-dev-lora"

    /** The saved LoRA whose trigger word appears as a whole word in [text], ignoring case. Short triggers never match. */
    fun matching(loras: List<SavedLora>, text: String): SavedLora? = loras.firstOrNull { l ->
        l.trigger.length >= 3 && Regex("(?i)(^|[^A-Za-z0-9_])" + Regex.escape(l.trigger) + "($|[^A-Za-z0-9_])").containsMatchIn(text)
    }

    /** The base model a saved LoRA runs on. */
    fun base(lora: SavedLora): LoraBase = LoraBases.of(lora.baseModel) ?: throw UserError("This LoRA has an unknown base model.")

    /** [settings] for the LoRA's own picture model with [lora] filled in; other inputs stay as they were. */
    fun apply(settings: ModelSettings, lora: SavedLora): ModelSettings {
        val b = base(lora)
        if (settings.model != b.model) throw UserError("${lora.name} is a ${b.title} LoRA. It runs only on ${b.title} with a LoRA.")
        return settings.copy(options = settings.options.filter { it.name !in listOf(b.field, "lora_scale") } +
            InputValue(b.field, InputKind.TEXT, lora.reference) + InputValue("lora_scale", InputKind.NUMBER, lora.scale.toString()))
    }

    const val MAX_BYTES = 16384
    fun validate(item: SavedLora): SavedLora {
        if (item.name.isBlank() || item.name.length > 80 || item.name.any { it.isISOControl() }) throw UserError("Give the LoRA a name of up to 80 characters.")
        if (item.trigger.length > 120 || item.trigger.any { it.isISOControl() }) throw UserError("Keep the trigger word below 120 characters on one line.")
        if (LoraBases.of(item.baseModel) == null) throw UserError("This library takes FLUX dev and Qwen Image LoRAs. Video LoRAs are not interchangeable.")
        if (item.baseModel == LoraBases.QWEN.id && item.scale < 0) throw UserError("A Qwen LoRA's strength runs from 0 to 3.")
        if (!item.scale.isFinite() || item.scale !in -1.0..3.0) throw UserError("LoRA scale must be between -1 and 3.")
        val reference = item.reference
        if (reference.length > 4000) throw UserError("LoRA reference is too long.")
        if (reference.startsWith("https://")) {
            val uri = try { URI(reference) } catch (_: Exception) { throw UserError("Invalid LoRA URL.") }
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)) throw UserError("Use an HTTPS LoRA URL without embedded credentials.")
        } else if (!Regex("[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+(?:/[a-f0-9]{64})?").matches(reference) || reference.contains("..")) {
            throw UserError("Use owner/model, owner/model/version, or an HTTPS weights URL.")
        }
        return item
    }
    fun parse(raw: String): SavedLora {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw UserError("LoRA library file is too large.")
        try {
            val root = JSONObject(raw)
            val expected = setOf("format", "name", "reference", "trigger_word", "scale", "base_model")
            if (root.keys().asSequence().toSet() != expected || root.get("format") != "otstudio-lora-v1") throw UserError("Select an OT Studio LoRA library JSON file.")
            if (listOf("name", "reference", "trigger_word", "base_model").any { root.get(it) !is String } || root.get("scale") !is Number) throw UserError("Invalid LoRA library field types.")
            return validate(SavedLora(root.getString("name"), root.getString("reference"), root.getString("trigger_word"), root.getDouble("scale"), root.getString("base_model")))
        } catch (e: UserError) { throw e }
        catch (_: Exception) { throw UserError("Could not read this LoRA library file.") }
    }
}
