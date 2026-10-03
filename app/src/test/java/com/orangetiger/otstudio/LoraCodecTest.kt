package com.orangetiger.otstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoraCodecTest {
    private val raw = """{"format":"otstudio-lora-v1","name":"Test style","reference":"owner/model/${"a".repeat(64)}","trigger_word":"TOK","scale":1.0,"base_model":"flux-dev"}"""
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected UserError") } catch (_: UserError) { } }
    @Test fun importedLoraProducesNativeInferenceInputs() {
        val lora = LoraCodec.parse(raw)
        assertEquals("TOK", lora.trigger)
        val s = ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", "black-forest-labs/flux-dev-lora").let { it.copy(options = it.options + listOf(InputValue("lora_weights", InputKind.TEXT, lora.reference), InputValue("lora_scale", InputKind.NUMBER, lora.scale.toString()))) }
        ProviderApi { _, url, _, body ->
            assertTrue(url.endsWith("/black-forest-labs/flux-dev-lora/predictions"))
            val input = JSONObject(body!!).getJSONObject("input")
            assertEquals(lora.reference, input.getString("lora_weights")); assertEquals(1.0, input.getDouble("lora_scale"), 0.0)
            assertFalse(input.has("trigger_word")); assertFalse(input.has("hf_api_token"))
            HttpReply(201, """{"id":"test","status":"starting"}""")
        }.create(ResolvedModel(s, Mode.IMAGE, "synthetic"), "TOK in a garden")
    }
    @Test fun incompatibleMalformedAndOversizedImportsAreRejected() {
        listOf("{}", raw.replace("flux-dev", "wan-2.2"), raw.replace("1.0", "4.0"), raw.replace("1.0", "\"1.0\""), raw.replace("Test style", "x".repeat(81)), raw + "x".repeat(17000), JSONObject(raw).put("api_key", "synthetic").toString()).forEach { rejects { LoraCodec.parse(it) } }
    }
    @Test fun referencesCannotBeLocalFilesOrCarryEmbeddedCredentials() {
        listOf("file:///tmp/weights", "http://example.com/lora.safetensors", "https://key@example.com/x", "../model", "owner/model/bad-version").forEach { ref -> rejects { LoraCodec.validate(SavedLora("test", ref, "TOK", 1.0)) } }
        assertEquals("https://example.com/lora.safetensors?signature=test", LoraCodec.validate(SavedLora("test", "https://example.com/lora.safetensors?signature=test", "TOK", 1.0)).reference)
    }
}
