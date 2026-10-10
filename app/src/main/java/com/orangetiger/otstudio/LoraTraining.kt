package com.orangetiger.otstudio

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Portable (no Android types). LoRA bases and training on Replicate, as in OT Studio for Windows (src/core/lora.js and
// src/main/loras.js). Starting a training is billed, so the app sends it once and never repeats it on its own.

/** A LoRA's base model: the picture model that runs it, and the input that takes its weights. */
data class LoraBase(val id: String, val title: String, val model: String, val field: String)

object LoraBases {
    val FLUX = LoraBase("flux-dev", "FLUX dev", "black-forest-labs/flux-dev-lora", "lora_weights")
    /**
     * Qwen Image LoRAs (owner decision, 2026-10-08, on Windows): the trainer is also the picture model that runs a Qwen
     * LoRA from its weights file. It runs by version only, never through the model endpoint, and plain Qwen Image
     * cannot use these LoRAs.
     */
    val QWEN = LoraBase("qwen-image", "Qwen Image", "qwen/qwen-image-lora-trainer", "replicate_weights")
    val all = listOf(FLUX, QWEN)
    fun of(id: String): LoraBase? = all.firstOrNull { it.id == id }
    fun forModel(model: String): LoraBase? = all.firstOrNull { it.model == model }
}

/** A training kept on this phone until the user removes it. [progress] runs from 0 to 1; null before the first step. */
data class LoraTrainingRecord(
    val id: String, val name: String, val trigger: String, val base: String, val steps: Int, val rank: Int,
    val trainer: String, val trainerVersion: String, val destination: String, val state: String,
    val trainingId: String = "", val progress: Double? = null, val phase: String = "", val firstAt: Long = 0, val firstProgress: Double = 0.0,
    val minutesLeft: Int? = null, val error: String = "", val reference: String = "", val created: Long = 0, val finished: Long = 0, val nextCheck: Long = 0
) {
    val running get() = state in listOf("starting", "processing")
}

object LoraTraining {
    const val TRAINER = "ostris/flux-dev-lora-trainer"
    /** The FLUX trainer stays on this checked version; the Qwen trainer runs on its newest version, read from Replicate. */
    const val TRAINER_VERSION = "26dce37af90b9d997eeb970d92e47de3064d46c300504ae376c75bef6a9022d2"
    const val MIN_SIDE = 512
    const val MAX_PHOTOS = 20
    const val MAX_ZIP = 95 * 1024 * 1024
    const val CHECK_MS = 30_000L
    val TRIGGER = Regex("[A-Za-z][A-Za-z0-9_]{0,79}")
    /** Qwen learns from plain words, not made-up tokens: "a person named Pao". */
    val QWEN_PHRASE = Regex("[A-Za-z][A-Za-z0-9 ,'.-]{1,79}")

    /** A rare, prompt-friendly trigger from the LoRA name, such as MARKFACE for "Mark face". */
    fun suggestTrigger(name: String): String {
        var letters = name.filter { it.isLetterOrDigit() && it.code < 128 }.uppercase().take(12)
        if (letters.isEmpty() || !letters[0].isLetter()) letters = "OT$letters"
        return if (letters.length >= 4) letters else "${letters}LORA"
    }

    /** Words for a Qwen LoRA from its name: "a person named Pao" for "Pao". */
    fun suggestPhrase(name: String): String {
        val first = name.trim().split(Regex("\\s+")).first().filter { (it.isLetterOrDigit() && it.code < 128) || it in "'-" }
        return if (first.isEmpty()) "a person" else "a person named ${first[0].uppercaseChar()}${first.drop(1)}"
    }

    fun suggest(base: LoraBase, name: String) = if (base == LoraBases.QWEN) suggestPhrase(name) else suggestTrigger(name)

    /** The private Replicate model for a LoRA: lora-<name>, or lora-qwen-<name> for Qwen. */
    fun destinationSlug(name: String, base: LoraBase): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50).trimEnd('-')
        if (slug.isEmpty()) throw UserError("Use a LoRA name with at least one letter or digit.")
        return if (base == LoraBases.QWEN) "lora-qwen-$slug" else "lora-$slug"
    }

    /** The default rank and the allowed steps and ranks for each trainer. */
    fun defaultRank(base: LoraBase) = if (base == LoraBases.QWEN) 64 else 16
    private fun limits(base: LoraBase) = if (base == LoraBases.QWEN) (100..6000) to (8..128) else (3..6000) to (1..128)

    /** Checks a training request before anything is sent. */
    fun check(name: String, trigger: String, base: LoraBase, steps: Int, rank: Int, photos: Int) {
        if (name.isBlank() || name.length > 80 || name.any { it.isISOControl() }) throw UserError("Give the LoRA a name of up to 80 characters.")
        if (base == LoraBases.QWEN && !QWEN_PHRASE.matches(trigger)) throw UserError("Describe the subject in a few plain words, such as \"a person named Pao\".")
        if (base == LoraBases.FLUX && !TRIGGER.matches(trigger)) throw UserError("Use a trigger word of letters, digits, or underscores that starts with a letter, such as MARKFACE.")
        val (s, r) = limits(base)
        if (steps !in s || rank !in r) throw UserError("Steps must be ${s.first} to ${s.last}, and rank ${r.first} to ${r.last}.")
        if (photos !in 1..MAX_PHOTOS) throw UserError("Choose 1 to $MAX_PHOTOS photos. 10 or more work best.")
        destinationSlug(name, base)
    }

    /** The trainer's input. Qwen has no captioning step: every photo gets one plain description. */
    fun input(base: LoraBase, fileUrl: String, trigger: String, steps: Int, rank: Int): JSONObject =
        if (base == LoraBases.QWEN) JSONObject().put("dataset", fileUrl).put("steps", steps).put("lora_rank", rank).put("default_caption", "A photo of $trigger").put("batch_size", 1)
        else JSONObject().put("input_images", fileUrl).put("trigger_word", trigger).put("steps", steps).put("lora_rank", rank).put("autocaption", true)

    /** The training photos as one ZIP: photo-01.jpg, photo-02.jpg, and so on. */
    fun zip(photos: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            photos.forEachIndexed { i, bytes ->
                z.putNextEntry(ZipEntry("photo-${(i + 1).toString().padStart(2, '0')}.jpg"))
                z.write(bytes)
                z.closeEntry()
            }
        }
        if (out.size() > MAX_ZIP) throw UserError("The photos are larger than 95 MB together. Remove a few.")
        return out.toByteArray()
    }

    /**
     * How far training is, from 0 to 1, from the newest "done/total" step count in the log, or null before the first
     * step. Only numbers are read, and only counts out of the job's own step total.
     */
    fun progress(logs: String?, steps: Int): Double? {
        if (logs.isNullOrEmpty()) return null
        var found: Double? = null
        Regex("(\\d{1,6})\\s*/\\s*(\\d{1,6})").findAll(logs.takeLast(50_000)).forEach { m ->
            val done = m.groupValues[1].toInt(); val total = m.groupValues[2].toInt()
            if (total == steps && done in 0..total) found = done.toDouble() / total
        }
        return found
    }

    /**
     * The trainer's stage from the end of its log: captioning, loading, training, or saving; null when the log says
     * nothing yet. Only known words are looked for; the log itself is never shown.
     */
    fun phase(logs: String?, progressNow: Double?): String? {
        if (logs.isNullOrEmpty()) return null
        val tail = logs.takeLast(4000).lowercase()
        fun last(re: String) = Regex(re).findAll(tail).lastOrNull()?.range?.first ?: -1
        val at = linkedMapOf(
            "saving" to last("saving|uploading|upload|pushing|writing weights|safetensors"),
            "training" to last("\\d+\\s*/\\s*\\d+\\s*\\[|it/s|s/it"),
            "loading" to last("download|loading|load checkpoint|pget|quantiz"),
            "captioning" to last("caption|llava|florence|blip"),
        )
        if (progressNow != null && progressNow >= 0.99 && at.getValue("saving") >= 0) return "saving"
        return at.filter { it.value >= 0 }.maxByOrNull { it.value }?.key
    }

    /** Minutes left, from how fast the steps went since the first one was seen; null until there is enough to tell. */
    fun minutesLeft(firstAt: Long, firstProgress: Double, nowAt: Long, progressNow: Double?): Int? {
        if (firstAt <= 0 || progressNow == null) return null
        val done = progressNow - firstProgress
        val elapsed = nowAt - firstAt
        if (done < 0.02 || elapsed < 60_000) return null
        return maxOf(1, Math.round((elapsed / done) * (1 - progressNow) / 60_000).toInt())
    }

    /** A training as stored on the phone. */
    fun toJson(r: LoraTrainingRecord): String = JSONObject().put("id", r.id).put("name", r.name).put("trigger", r.trigger).put("base", r.base)
        .put("steps", r.steps).put("rank", r.rank).put("trainer", r.trainer).put("trainerVersion", r.trainerVersion).put("destination", r.destination)
        .put("state", r.state).put("trainingId", r.trainingId).put("progress", r.progress ?: JSONObject.NULL).put("phase", r.phase)
        .put("firstAt", r.firstAt).put("firstProgress", r.firstProgress).put("minutesLeft", r.minutesLeft ?: JSONObject.NULL).put("error", r.error)
        .put("reference", r.reference).put("created", r.created).put("finished", r.finished).put("nextCheck", r.nextCheck).toString()

    /** Reads a stored training; null when it is damaged. */
    fun fromJson(raw: String): LoraTrainingRecord? = try {
        val o = JSONObject(raw)
        LoraTrainingRecord(o.getString("id"), o.getString("name"), o.getString("trigger"), o.getString("base"), o.getInt("steps"), o.getInt("rank"),
            o.getString("trainer"), o.getString("trainerVersion"), o.getString("destination"), o.getString("state"), o.optString("trainingId"),
            if (o.isNull("progress")) null else o.optDouble("progress"), o.optString("phase"), o.optLong("firstAt"), o.optDouble("firstProgress", 0.0),
            if (o.isNull("minutesLeft")) null else o.optInt("minutesLeft"), o.optString("error"), o.optString("reference"),
            o.optLong("created"), o.optLong("finished"), o.optLong("nextCheck"))
    } catch (_: Exception) { null }

    /** After a restart, a training that was being sent has an unknown outcome; running ones are checked at once. */
    fun afterRestart(r: LoraTrainingRecord): LoraTrainingRecord = when {
        r.state == "sending" -> r.copy(state = "uncertain", error = "OT Studio closed while starting this training. Check replicate.com/trainings before you start another.")
        r.running -> r.copy(nextCheck = 0)
        else -> r
    }

    /** The stage in plain words. */
    fun phaseText(phase: String): String = when (phase) {
        "captioning" -> "Describing the photos"
        "loading" -> "Loading the base model"
        "training" -> "Training"
        "saving" -> "Saving the LoRA"
        else -> "Waiting for Replicate"
    }

    /**
     * The usable LoRA from a finished training: a version of the private model, as owner/model/version. A Qwen
     * training may instead return only a weights file, which Qwen Image with a LoRA can run.
     */
    fun reference(output: JSONObject?, destination: String, base: LoraBase): String? {
        val version = output?.optString("version").orEmpty().replace(':', '/')
        if (Regex(Regex.escape(destination) + "/[a-f0-9]{64}").matches(version)) return version
        val weights = output?.optString("weights").orEmpty()
        return weights.takeIf { base == LoraBases.QWEN && it.startsWith("https://") && it.length <= 4000 }
    }
}
