package com.orangetiger.otstudio

import org.json.JSONArray
import org.json.JSONObject

object SchemaCodec {
    private fun resolve(raw: JSONObject, document: JSONObject): JSONObject {
        val ref = raw.optString("\$ref")
        if (ref.isEmpty()) return raw
        if (!ref.startsWith("#/components/schemas/") || ref.substringAfterLast('/').contains('~')) throw UserError("Unsupported schema reference.")
        return document.optJSONObject("components")?.optJSONObject("schemas")?.optJSONObject(ref.substringAfterLast('/')) ?: throw UserError("Missing model schema reference.")
    }

    fun parse(document: JSONObject): InputSchema {
        val input = document.optJSONObject("components")?.optJSONObject("schemas")?.optJSONObject("Input") ?: throw UserError("The provider did not return an input schema.")
        val properties = input.optJSONObject("properties") ?: throw UserError("The provider did not list model inputs.")
        if (properties.length() > 100) throw UserError("This model has too many inputs for this preview.")
        val requiredJson = input.optJSONArray("required") ?: JSONArray()
        val required = (0 until requiredJson.length()).map { requiredJson.optString(it) }.toSet()
        val fields = ArrayList<InputDefinition>()
        val unsupported = ArrayList<String>()
        val unsupportedRequired = ArrayList<String>()
        properties.keys().asSequence().toList().forEach { name ->
            if (name == "prompt") return@forEach
            val raw = properties.optJSONObject(name) ?: throw UserError("Malformed model field.")
            val all = raw.optJSONArray("allOf")
            val p = resolve(if (all?.length() == 1) all.optJSONObject(0) ?: raw else raw, document)
            // A list of URLs (such as several reference images) becomes one URL field sent as a one-item array.
            val items = p.optJSONObject("items")
            val list = p.optString("type") == "array" && items?.optString("type") == "string" && items.optString("format") == "uri"
            val kind = when (p.optString("type")) { "boolean" -> InputKind.BOOLEAN; "integer" -> InputKind.INTEGER; "number" -> InputKind.NUMBER; "string" -> InputKind.TEXT; "array" -> if (list) InputKind.TEXT else null; else -> null }
            if (kind == null || p.has("oneOf") || p.has("anyOf") || p.has("allOf") || !ModelOptions.editableName(name) || p.optString("format") == "password" || raw.optString("format") == "password" || raw.optBoolean("writeOnly") || p.optBoolean("writeOnly")) {
                unsupported.add(name.take(80)); if (name in required) unsupportedRequired.add(name.take(80)); return@forEach
            }
            val choicesJson = p.optJSONArray("enum")
            val choices = if (choicesJson == null) emptyList() else (0 until choicesJson.length()).map { choicesJson.get(it).toString() }
            val default = if (list) null else if (raw.has("default") && !raw.isNull("default")) raw.get("default").toString() else if (p.has("default") && !p.isNull("default")) p.get("default").toString() else null
            fields.add(InputDefinition(name, kind, default, choices,
                if (p.has("minimum")) p.getDouble("minimum") else null, if (p.has("maximum")) p.getDouble("maximum") else null,
                name in required, raw.optString("description", p.optString("description")).take(600).replace('\u2014', '-'), list || p.optString("format") == "uri", list))
        }
        return InputSchema(fields, unsupported, unsupportedRequired).also(ModelOptions::validateSchema)
    }

    fun append(target: JSONObject, schema: InputSchema, values: List<InputValue>) {
        ModelOptions.validate(schema, values, true)
        // A checked but empty optional text field means "no value", the same as unchecked. A setting that does not
        // count with the other choices (Seedream's width without a custom size) is left out.
        LinkedSettings.keep(schema, values).filterNot { it.kind == InputKind.TEXT && it.value.isBlank() }.forEach { v -> target.put(v.name, when (v.kind) {
            InputKind.BOOLEAN -> v.value.toBooleanStrict()
            InputKind.INTEGER -> v.value.toLong()
            InputKind.NUMBER -> v.value.toDouble()
            InputKind.TEXT -> if (schema.fields.first { it.name == v.name }.list) JSONArray(PictureList.split(v.value)) else v.value
        }) }
    }

    fun recommendationContext(candidates: List<ModelPreset>): String = JSONArray().apply {
        candidates.take(30).forEach { m -> put(JSONObject().put("model", m.model).put("fixed_resolution", m.fixedResolution ?: JSONObject.NULL).put("inputs", JSONArray().apply {
            m.schema.fields.forEach { d -> put(JSONObject().put("name", d.name).put("type", d.kind.name).put("choices", JSONArray(d.choices)).put("minimum", d.minimum ?: JSONObject.NULL).put("maximum", d.maximum ?: JSONObject.NULL).put("default", d.defaultValue ?: JSONObject.NULL).put("description", d.description.take(160))) }
        })) }
    }.toString()

    // Chat models often wrap the JSON in a sentence or a code fence. Take the first balanced {...} object, honoring
    // quoted strings; everything after parsing is validated exactly as before.
    internal fun firstJsonObject(raw: String): String? {
        val start = raw.indexOf('{').takeIf { it >= 0 } ?: return null
        var depth = 0; var quoted = false; var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false; continue }
            when (c) { '"' -> quoted = true; '{' -> depth++; '}' -> if (--depth == 0) return raw.substring(start, i + 1) }
        }
        return null
    }

    fun suggestion(raw: String, candidates: List<ModelPreset>): SetupSuggestion {
        val root = try { JSONObject(firstJsonObject(raw) ?: throw UserError("")) } catch (_: Exception) { throw UserError("The text model did not return a usable setup. Choose settings manually or try again.") }
        val id = root.optString("model")
        val candidate = candidates.firstOrNull { it.model == id } ?: throw UserError("The text model selected a model outside the matching catalog. Nothing was applied.")
        val input = root.optJSONObject("options") ?: throw UserError("The suggestion did not include typed options.")
        if (input.length() > 64) throw UserError("Too many suggested inputs.")
        val values = input.keys().asSequence().toList().map { name ->
            val d = candidate.schema.fields.firstOrNull { it.name == name } ?: throw UserError("The suggestion used an unsupported modifier: $name.")
            val v = input.get(name)
            val correctType = when (d.kind) { InputKind.BOOLEAN -> v is Boolean; InputKind.INTEGER -> v is Int || v is Long; InputKind.NUMBER -> v is Number; InputKind.TEXT -> v is String }
            if (!correctType) throw UserError("The suggestion used an incorrect type for $name.")
            ModelOptions.value(d, v.toString())
        }
        ModelOptions.validate(candidate.schema, values)
        return SetupSuggestion(id, values, root.optString("reason").take(1200))
    }
}
