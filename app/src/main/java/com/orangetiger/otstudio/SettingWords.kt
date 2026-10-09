package com.orangetiger.otstudio

// Portable (no Android types), shared in spirit with OT Studio for Windows (src/core/settingwords.js, refs.js).

/**
 * Picture labels in a description. The user writes @img1, @img2 (or @image1, @pic1, @1, /frame1); pictures are
 * numbered in the order they are sent. The models that take several pictures are trained on "image 1", "image 2", so
 * that is what the model receives. The history keeps the user's own wording.
 */
object PictureRefs {
    private val token = Regex("(^|[^\\w@/])(?:@(?:img|image|pic|picture|photo|frame)?\\s?(\\d{1,2})|/frame\\s?(\\d{1,2}))(?!\\w)", RegexOption.IGNORE_CASE)

    /** The picture numbers a description refers to, in order of first mention. */
    fun references(prompt: String): List<Int> = token.findAll(prompt).map { (it.groupValues[2].ifEmpty { it.groupValues[3] }).toInt() }.distinct().toList()

    /** The description as the model reads it ("@img1" becomes "image 1"); throws when a label has no picture. */
    fun rewrite(prompt: String, count: Int): String {
        val used = references(prompt)
        val missing = used.firstOrNull { it < 1 || it > count }
        if (missing != null) throw UserError("The description mentions @img$missing, but ${when (count) { 0 -> "no picture is"; 1 -> "only 1 picture is"; else -> "only $count pictures are" }} attached.")
        if (used.isEmpty()) return prompt
        return token.replace(prompt) { m -> "${m.groupValues[1]}image ${m.groupValues[2].ifEmpty { m.groupValues[3] }.toInt()}" }
    }

    /** How many pictures [values] send, in the order they are sent: one per link in every picture input. */
    fun count(schema: InputSchema, values: List<InputValue>): Int = values.sumOf { v ->
        if (schema.fields.firstOrNull { it.name == v.name }?.uri == true && v.value.isNotBlank()) PictureList.split(v.value).size else 0
    }
}

/** Shapes typed as ratios ("4:3", "16x9", "3 by 2", "9/16"), and the closest ratio a model offers. */
object Ratios {
    private val typed = Regex("(?:^|[^\\d.:])(\\d{1,2}(?:\\.\\d)?)\\s*(?::|x|\\u00d7|/|\\bby\\b)\\s*(\\d{1,2}(?:\\.\\d)?)(?![\\d:])", RegexOption.IGNORE_CASE)

    /** A ratio the user typed, as "4:3"; null for none or for a clock time such as "at 10:30 pm". */
    fun fromText(text: String): String? {
        val m = typed.find(text) ?: return null
        val after = text.substring(m.range.last + 1).take(8)
        if (Regex("^\\s*(am|pm|a\\.m|p\\.m|o.?clock|h\\b|hours)", RegexOption.IGNORE_CASE).containsMatchIn(after)) return null
        if (Regex("\\bat\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(text.substring(0, m.range.first + 1))) return null
        val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
        if (a <= 0 || b <= 0 || a / b > 4 || b / a > 4) return null
        return "${m.groupValues[1]}:${m.groupValues[2]}"
    }

    private fun value(r: String): Double? = Regex("^(\\d+(?:\\.\\d+)?)[:x*](\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(r)?.let { it.groupValues[1].toDouble() / it.groupValues[2].toDouble() }

    /** The entry of [list] whose ratio is closest to [ratio], or null when none is a ratio. */
    fun nearest(ratio: String, list: List<String>): String? {
        val want = value(ratio) ?: return null
        return list.mapNotNull { r -> value(r)?.let { r to kotlin.math.abs(kotlin.math.ln(it / want)) } }.minByOrNull { it.second }?.first
    }
}

/**
 * Settings that count only in some cases, such as Seedream's width and height ("only used when size='custom'") and its
 * shape ("only used when size is not 'custom'"). The rule comes from the model's own description, or, for width and
 * height, from a sibling list that offers "custom". A setting that does not count is hidden and never sent.
 */
object LinkedSettings {
    data class Rule(val owner: String, val value: String, val not: Boolean)

    fun rule(d: InputDefinition, schema: InputSchema): Rule? {
        val names = schema.fields.map { it.name }.toSet()
        Regex("only (?:used|applies|applied) (?:when|if)\\s+([a-z_]+)\\s+is not\\s+['\"]?([\\w:.-]+)['\"]?", RegexOption.IGNORE_CASE).find(d.description)
            ?.takeIf { it.groupValues[1] in names && it.groupValues[1] != d.name }?.let { return Rule(it.groupValues[1], it.groupValues[2], true) }
        Regex("only (?:used|applies|applied) (?:when|if)\\s+([a-z_]+)\\s*(?:=|==|is)\\s*['\"]?([\\w:.-]+)['\"]?", RegexOption.IGNORE_CASE).find(d.description)
            ?.takeIf { it.groupValues[1] in names && it.groupValues[1] != d.name && !it.groupValues[2].equals("not", true) }?.let { return Rule(it.groupValues[1], it.groupValues[2], false) }
        if (d.name == "width" || d.name == "height") schema.fields.firstOrNull { "custom" in it.choices }?.let { return Rule(it.name, "custom", false) }
        if (d.name == "aspect_ratio" && "custom" !in d.choices && schema.fields.any { it.name == "size" && "custom" in it.choices }) return Rule("size", "custom", true)
        return null
    }

    /** Whether [d] counts with [values]; an unset owner counts at its default. */
    fun active(d: InputDefinition, schema: InputSchema, values: List<InputValue>): Boolean {
        val r = rule(d, schema) ?: return true
        val current = values.firstOrNull { it.name == r.owner }?.value ?: schema.fields.firstOrNull { it.name == r.owner }?.defaultValue
        return if (r.not) current != r.value else current == r.value
    }

    /** [values] without the settings that do not count. */
    fun keep(schema: InputSchema, values: List<InputValue>): List<InputValue> =
        values.filter { v -> schema.fields.firstOrNull { it.name == v.name }?.let { active(it, schema, values) } ?: true }
}

/**
 * Settings named in a chat message, read from the user's own words (never from the chat model) and matched against the
 * model's real settings. Common phrases ("2 pictures", "1080p", "with sound", "safety off", "seed 42", "30 steps",
 * "as PNG", "anime style", "negative prompt: blurry") and any setting by its own name ("prompt strength 0.7").
 */
object SettingWords {
    data class Extraction(val values: List<InputValue>, val shown: List<String>, val missing: List<String>, val problems: List<String>)

    private val numberWords = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30)
    private const val NUM = "(\\d+(?:\\.\\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty)"
    private fun num(s: String): String = numberWords[s.lowercase()]?.toString() ?: s
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private class Concept(val key: String, val label: String, val names: List<String>, val choiceOnly: Boolean = false, val read: (String) -> String?)

    private val concepts = listOf(
        Concept("count", "Number of pictures", listOf("num_outputs", "n", "max_images", "number_of_images", "num_images", "batch_size")) { t -> re("\\b$NUM\\s+(?:different\\s+)?(?:pictures|images|photos|versions|variations|options|outputs|takes)\\b").find(t)?.let { num(it.groupValues[1]) } },
        Concept("seconds", "Length", listOf("duration", "seconds", "video_length")) { t -> re("\\b$NUM[\\s-]*(?:sec|secs|seconds?)\\b").find(t)?.let { num(it.groupValues[1]) } },
        Concept("resolution", "Resolution", listOf("resolution", "size", "quality_preset"), choiceOnly = true) { t -> re("\\b(480p|540p|720p|768p|1080p|1440p|2160p|1k|2k|4k)\\b").find(t)?.groupValues?.get(1) },
        Concept("seed", "Seed", listOf("seed")) { t -> re("\\bseed\\s*(?:of|=|:|to|number)?\\s*(\\d{1,10})\\b").find(t)?.groupValues?.get(1) },
        Concept("steps", "Steps", listOf("num_inference_steps", "steps", "sample_steps")) { t -> (re("\\b(\\d{1,3})\\s+(?:inference\\s+)?steps\\b").find(t) ?: re("\\bsteps\\s*(?:of|=|:|to)?\\s*(\\d{1,3})\\b").find(t))?.groupValues?.get(1) },
        Concept("guidance", "Guidance", listOf("guidance", "guidance_scale", "sample_guide_scale", "cfg", "cfg_scale")) { t -> re("\\b(?:guidance(?:\\s+scale)?|cfg(?:\\s+scale)?)\\s*(?:of|=|:|to|at)?\\s*(\\d+(?:\\.\\d+)?)\\b").find(t)?.groupValues?.get(1) },
        Concept("fps", "Frames per second", listOf("frames_per_second", "fps")) { t -> (re("\\b(\\d{1,2})\\s*fps\\b").find(t) ?: re("\\b(\\d{1,2})\\s+frames\\s+per\\s+second\\b").find(t))?.groupValues?.get(1) },
        Concept("format", "File type", listOf("output_format", "format"), choiceOnly = true) { t -> (re("\\b(?:as|in|to)\\s+(?:a\\s+)?(png|jpe?g|webp)\\b").find(t) ?: re("\\b(png|jpe?g|webp)\\s+(?:format|file)\\b").find(t))?.groupValues?.get(1)?.lowercase()?.replace("jpeg", "jpg") },
        Concept("sound", "Sound", listOf("generate_audio", "audio", "enable_audio", "with_audio", "sound")) { t -> when {
            re("\\b(no|without|mute|muted|silent)\\s*(sound|audio)?\\b").containsMatchIn(t) && re("\\b(sound|audio|silent|mute)").containsMatchIn(t) -> "false"
            re("\\b(with|add|include|and)\\s+(sound|audio|music)\\b").containsMatchIn(t) -> "true"
            else -> null } },
        Concept("safety", "Safety checker", listOf("disable_safety_checker", "enable_safety_checker", "safety_checker", "safety_tolerance")) { t -> when {
            re("\\b(safety|nsfw)\\s*(checker|filter)?\\s*(off|disabled|false)\\b|\\b(without|no|disable|turn off)\\s+(the\\s+)?(safety|nsfw)\\s*(checker|filter)?\\b").containsMatchIn(t) -> "off"
            re("\\b(safety|nsfw)\\s*(checker|filter)?\\s*(on|enabled)\\b").containsMatchIn(t) -> "on"
            else -> null } },
        Concept("fast", "Fast mode", listOf("go_fast", "turbo", "fast_mode")) { t -> when {
            re("\\bfast\\s*mode\\s*(off|disabled)\\b|\\b(no|without)\\s+fast\\s*mode\\b").containsMatchIn(t) -> "false"
            re("\\bfast\\s*mode\\s*(on|enabled)?\\b|\\bturbo\\b").containsMatchIn(t) -> "true"
            else -> null } },
        Concept("megapixels", "Megapixels", listOf("megapixels", "output_megapixels"), choiceOnly = true) { t -> re("\\b(\\d+(?:\\.\\d+)?)\\s*(?:mp|megapixels?)\\b").find(t)?.groupValues?.get(1) },
        Concept("quality", "File quality", listOf("output_quality", "output_compression")) { t -> re("\\b(?:file\\s+|jpe?g\\s+|output\\s+)?quality\\s*(?:of|=|:|to|at)?\\s*(\\d{1,3})\\s*%?").find(t)?.groupValues?.get(1) },
        Concept("negative", "Avoid", listOf("negative_prompt")) { t -> re("\\b(?:negative\\s+prompt|avoid)\\s*:\\s*([^.;\\n]{2,300})").find(t)?.groupValues?.get(1)?.trim() },
        Concept("lora", "LoRA strength", listOf("lora_scale")) { t -> re("\\blora\\s+(?:strength|scale|weight)\\s*(?:of|=|:|to|at)?\\s*(-?\\d+(?:\\.\\d+)?)\\b").find(t)?.groupValues?.get(1) },
    )

    private fun shown(d: InputDefinition, v: String, label: String?): String = "${label ?: InputLabels.title(d.name)} ${when (v) { "true" -> "on"; "false" -> "off"; else -> v }}"

    /** [raw] made valid for [d] (as the exact choice, a number, or true/false), or the reason it cannot be. */
    private fun fit(d: InputDefinition, raw: String): Pair<String?, String?> {
        val label = InputLabels.title(d.name)
        val v = when (d.kind) {
            InputKind.BOOLEAN -> when (raw.lowercase()) { "on", "true", "yes", "enabled" -> "true"; "off", "false", "no", "disabled" -> "false"; else -> return null to "$label must be on or off" }
            else -> if (d.choices.isNotEmpty()) d.choices.firstOrNull { it.equals(raw, true) } ?: return null to "$label cannot be $raw (it takes ${d.choices.take(8).joinToString()})" else raw
        }
        if (d.kind == InputKind.INTEGER || d.kind == InputKind.NUMBER) {
            val n = v.toDoubleOrNull() ?: return null to "$label must be a number"
            if (d.kind == InputKind.INTEGER && n % 1.0 != 0.0) return null to "$label must be a whole number"
            if ((d.minimum != null && n < d.minimum) || (d.maximum != null && n > d.maximum)) return null to "$label ${v.removeSuffix(".0")} is outside ${d.minimum?.let { fmt(it) } ?: ""} to ${d.maximum?.let { fmt(it) } ?: ""}"
            return (if (d.kind == InputKind.INTEGER) n.toLong().toString() else v) to null
        }
        return try { ModelOptions.value(d, v).value to null } catch (e: UserError) { null to e.message }
    }
    private fun fmt(n: Double) = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

    fun extract(text: String, fields: List<InputDefinition>): Extraction {
        val usable = fields.filter { !it.uri && it.name != "aspect_ratio" }
        val byName = usable.associateBy { it.name }
        val set = LinkedHashMap<String, String>()
        val shown = ArrayList<String>(); val missing = ArrayList<String>(); val problems = ArrayList<String>()
        fun put(d: InputDefinition, raw: String, label: String? = null, show: ((String) -> String)? = null) {
            if (d.name in set) return
            val (value, problem) = fit(d, raw)
            if (value == null) { problems.add(problem.orEmpty()); return }
            set[d.name] = value
            shown.add(show?.invoke(value) ?: shown(d, value, label))
        }
        // 1. Any setting by its own name or label: "prompt strength 0.7", "steps to 40", "style: anime".
        for (d in usable) {
            if (d.name == "disable_safety_checker") continue
            val terms = listOf(InputLabels.title(d.name), d.name.replace('_', ' '), d.name).map { it.lowercase().replace(Regex("\\s*\\(.*\\)$"), "") }.filter { it.length >= 4 }.distinct()
            for (term in terms) {
                val m = re("\\b${Regex.escape(term)}\\b\\s*(?:to|=|:|of|at|is)?\\s*(\"[^\"]{1,300}\"|-?\\d+(?:\\.\\d+)?%?|on|off|true|false|[A-Za-z0-9:*._-]{1,30})").find(text) ?: continue
                val quoted = m.groupValues[1].startsWith("\"")
                val raw = m.groupValues[1].removeSurrounding("\"").removeSuffix("%")
                if (d.kind == InputKind.TEXT && d.choices.isEmpty() && !quoted) continue
                if (d.choices.isNotEmpty() && d.choices.none { it.equals(raw, true) }) continue
                if ((d.kind == InputKind.INTEGER || d.kind == InputKind.NUMBER) && !raw.matches(Regex("-?\\d.*"))) continue
                put(d, raw); break
            }
        }
        // 2. Common phrases, matched to whichever setting the model uses for that idea.
        for (k in concepts) {
            val value = k.read(text) ?: continue
            val d = k.names.firstNotNullOfOrNull { byName[it] }
            if (k.key == "seconds" && d == null && byName["num_frames"] != null) {
                // Wan 2.2 counts frames: 81 frames is 5 seconds at 16 frames per second.
                val fps = byName["frames_per_second"]?.defaultValue?.toIntOrNull() ?: 16
                put(byName.getValue("num_frames"), (value.toDouble() * fps + 1).toLong().toString(), show = { f -> "Length $value seconds ($f frames)" })
                continue
            }
            if (d == null) { missing.add(k.label); continue }
            if (d.name in set) continue
            when {
                k.key == "seconds" -> put(d, value, show = { v -> "Length $v seconds" })
                k.key == "safety" && d.name == "disable_safety_checker" -> put(d, if (value == "off") "true" else "false", show = { v -> "Safety checker ${if (v == "true") "off" else "on"}" })
                k.key == "safety" && d.name == "safety_tolerance" -> put(d, if (value == "off") fmt(d.maximum ?: 6.0) else fmt(d.minimum ?: 1.0), "Safety tolerance")
                k.key == "safety" -> put(d, if (value == "on") "true" else "false", show = { v -> "Safety checker ${if (v == "true") "on" else "off"}" })
                k.choiceOnly && d.choices.isNotEmpty() && d.choices.none { it.equals(value, true) } -> problems.add("${InputLabels.title(d.name)} cannot be $value on this model (it takes ${d.choices.take(8).joinToString()})")
                else -> put(d, value, k.label)
            }
        }
        // 3. A style the model lists by name: "anime style".
        byName["style"]?.takeIf { it.choices.isNotEmpty() && "style" !in set }?.let { style ->
            style.choices.firstOrNull { c -> re("\\b${Regex.escape(c)}\\s+style\\b|\\bstyle\\s*(?::|=|of)?\\s*${Regex.escape(c)}\\b").containsMatchIn(text) }?.let { put(style, it, "Style") }
        }
        return Extraction(set.map { (n, v) -> InputValue(n, byName.getValue(n).kind, v) }, shown, missing, problems)
    }
}
