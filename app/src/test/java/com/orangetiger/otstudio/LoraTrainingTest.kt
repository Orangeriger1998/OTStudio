package com.orangetiger.otstudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class LoraTrainingTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected a UserError") } catch (_: UserError) { } }
    private val version = "b".repeat(64)

    @Test fun namesTriggersAndChecks() {
        assertEquals("MARKFACE", LoraTraining.suggestTrigger("Mark face"))
        assertEquals("OT1LORA", LoraTraining.suggestTrigger("1"))
        assertEquals("a person named Pao", LoraTraining.suggestPhrase("pao loves mark"))
        assertEquals("lora-anna-face", LoraTraining.destinationSlug("Anna face!", LoraBases.FLUX))
        assertEquals("lora-qwen-anna-face", LoraTraining.destinationSlug("Anna face", LoraBases.QWEN))
        LoraTraining.check("Anna", "ANNAFACE", LoraBases.FLUX, 1000, 16, 10)
        LoraTraining.check("Anna", "a person named Anna", LoraBases.QWEN, 1000, 64, 10)
        rejects { LoraTraining.check("Anna", "a person named Anna", LoraBases.FLUX, 1000, 16, 10) }
        rejects { LoraTraining.check("Anna", "ANNAFACE", LoraBases.QWEN, 50, 64, 10) }
        rejects { LoraTraining.check("Anna", "ANNAFACE", LoraBases.FLUX, 1000, 16, 21) }
        rejects { LoraTraining.check("!!!", "ANNAFACE", LoraBases.FLUX, 1000, 16, 5) }
    }

    @Test fun trainerInputsFollowTheBase() {
        val flux = LoraTraining.input(LoraBases.FLUX, "https://api.replicate.com/v1/files/x", "ANNAFACE", 1000, 16)
        assertEquals("ANNAFACE", flux.getString("trigger_word")); assertTrue(flux.getBoolean("autocaption")); assertFalse(flux.has("dataset"))
        val qwen = LoraTraining.input(LoraBases.QWEN, "https://api.replicate.com/v1/files/x", "a person named Anna", 1500, 64)
        assertEquals("A photo of a person named Anna", qwen.getString("default_caption")); assertEquals(64, qwen.getInt("lora_rank")); assertFalse(qwen.has("trigger_word"))
    }

    @Test fun photosArePackedInOrder() {
        val zip = LoraTraining.zip(listOf(byteArrayOf(1, 2), byteArrayOf(3)))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; names.add(e.name) } }
        assertEquals(listOf("photo-01.jpg", "photo-02.jpg"), names)
    }

    @Test fun progressStageAndTimeLeftComeFromTheLog() {
        val log = "Downloading weights\nflux_train: 120/1000 [02:00<14:40, 1.00it/s]\nother 3/4 noise\nflux_train: 250/1000 [04:10<12:30, 1.00it/s]"
        assertEquals(0.25, LoraTraining.progress(log, 1000)!!, 1e-9)
        assertNull(LoraTraining.progress("loading 3/4", 1000))
        assertEquals("training", LoraTraining.phase(log, 0.25))
        assertEquals("captioning", LoraTraining.phase("Loading model\nRunning florence caption on 10 images", null))
        assertEquals("saving", LoraTraining.phase("$log\n1000/1000 [16:40, 1.00it/s]\nSaving safetensors", 1.0))
        assertNull(LoraTraining.minutesLeft(1_000, 0.10, 30_000, 0.20))
        assertEquals(13, LoraTraining.minutesLeft(1, 0.10, 1 + 5 * 60_000, 0.35))
    }

    @Test fun aFinishedTrainingGivesAUsableReference() {
        val out = JSONObject().put("version", "anna/lora-anna:$version").put("weights", "https://replicate.delivery/w.tar")
        assertEquals("anna/lora-anna/$version", LoraTraining.reference(out, "anna/lora-anna", LoraBases.FLUX))
        assertNull(LoraTraining.reference(JSONObject().put("version", "someone/else:$version"), "anna/lora-anna", LoraBases.FLUX))
        assertEquals("https://replicate.delivery/w.tar", LoraTraining.reference(JSONObject().put("weights", "https://replicate.delivery/w.tar"), "anna/lora-qwen-anna", LoraBases.QWEN))
        assertNull(LoraTraining.reference(JSONObject().put("weights", "https://replicate.delivery/w.tar"), "anna/lora-anna", LoraBases.FLUX))
    }

    @Test fun recordsSurviveStorageAndRestart() {
        val r = LoraTrainingRecord("id1", "Anna", "ANNAFACE", "flux-dev", 1000, 16, LoraTraining.TRAINER, LoraTraining.TRAINER_VERSION, "anna/lora-anna", "processing", "tr_1", 0.4, "training", 5, 0.1, 12)
        assertEquals(r, LoraTraining.fromJson(LoraTraining.toJson(r)))
        assertEquals(0L, LoraTraining.afterRestart(r.copy(nextCheck = 99)).nextCheck)
        assertEquals("uncertain", LoraTraining.afterRestart(r.copy(state = "sending")).state)
        assertNull(LoraTraining.fromJson("{broken"))
    }

    @Test fun qwenLorasRunByVersionOnTheirOwnModel() {
        val qwen = LoraBases.QWEN
        val lora = SavedLora("Anna", "https://replicate.delivery/anna.tar", "a person named Anna", 1.0, qwen.id)
        rejects { LoraCodec.apply(ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", LoraCodec.MODEL), lora) }
        rejects { LoraCodec.validate(lora.copy(scale = -0.5)) }
        val s = LoraCodec.apply(ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", qwen.model), lora)
        val calls = ArrayList<String>()
        val api = ProviderApi { method, url, _, body ->
            calls.add("$method $url")
            if (method == "GET") HttpReply(200, """{"latest_version":{"id":"$version"}}""")
            else {
                val root = JSONObject(body!!)
                assertEquals(version, root.getString("version"))
                assertEquals(lora.reference, root.getJSONObject("input").getString("replicate_weights"))
                HttpReply(201, """{"id":"p1","status":"starting"}""")
            }
        }
        assertEquals("p1", api.create(ResolvedModel(s, Mode.IMAGE, "synthetic"), "a person named Anna at the beach").id)
        assertEquals(listOf("GET https://api.replicate.com/v1/models/qwen/qwen-image-lora-trainer", "POST https://api.replicate.com/v1/predictions"), calls)
        // Without a LoRA, nothing is sent.
        val bare = ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", qwen.model)
        rejects { ProviderApi { _, _, _, _ -> fail("nothing may be sent"); HttpReply(500, "") }.create(ResolvedModel(bare, Mode.IMAGE, "synthetic"), "x") }
    }

    @Test fun aTrainedQwenModelRunsItsOwnVersion() {
        val own = "anna/lora-qwen-anna/$version"
        val s = LoraCodec.apply(ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", LoraBases.QWEN.model), SavedLora("Anna", own, "a person named Anna", 1.0, "qwen-image"))
        val api = ProviderApi { method, url, _, body ->
            if (method == "GET") {
                assertEquals("https://api.replicate.com/v1/models/anna/lora-qwen-anna/versions/$version", url)
                HttpReply(200, """{"openapi_schema":{"components":{"schemas":{"Input":{"properties":{"prompt":{"type":"string"},"lora_scale":{"type":"number"},"aspect_ratio":{"type":"string"}}}}}}}""")
            } else {
                val input = JSONObject(body!!).getJSONObject("input")
                assertFalse(input.has("replicate_weights")); assertEquals(1.0, input.getDouble("lora_scale"), 0.0)
                HttpReply(201, """{"id":"p2","status":"starting"}""")
            }
        }
        assertEquals("p2", api.create(ResolvedModel(s, Mode.IMAGE, "synthetic"), "a person named Anna").id)
    }

    @Test fun trainingRequestsGoOnlyToReplicate() {
        val api = ProviderApi { method, url, key, body ->
            assertTrue(url.startsWith("https://api.replicate.com/v1/")); assertEquals("synthetic", key)
            when {
                url.endsWith("/account") -> HttpReply(200, """{"username":"anna"}""")
                method == "GET" && url.endsWith("/models/anna/lora-anna") -> HttpReply(404, "{}")
                url.endsWith("/models") -> { assertEquals("private", JSONObject(body!!).getString("visibility")); HttpReply(201, """{"owner":"anna","name":"lora-anna"}""") }
                url.contains("/trainings") && method == "POST" -> { assertEquals("anna/lora-anna", JSONObject(body!!).getString("destination")); HttpReply(201, """{"id":"tr_9","status":"starting"}""") }
                else -> { fail("unexpected $method $url"); HttpReply(500, "") }
            }
        }
        val b = "https://api.replicate.com/v1"
        assertEquals("anna", api.account(b, "synthetic"))
        assertEquals("anna/lora-anna", api.ensureDestination(b, "synthetic", "anna", "lora-anna", LoraBases.FLUX))
        assertEquals("tr_9" to "starting", api.startTraining(b, "synthetic", LoraTraining.TRAINER, LoraTraining.TRAINER_VERSION, "anna/lora-anna", JSONObject()))
        rejects { api.uploadArchive("https://example.com/v1", "synthetic", byteArrayOf(1)) }
        val publicModel = ProviderApi { _, _, _, _ -> HttpReply(200, """{"visibility":"public"}""") }
        rejects { publicModel.ensureDestination(b, "synthetic", "anna", "lora-anna", LoraBases.FLUX) }
    }
}
