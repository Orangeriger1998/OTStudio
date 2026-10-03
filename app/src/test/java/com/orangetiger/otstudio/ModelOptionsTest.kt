package com.orangetiger.otstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelOptionsTest {
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected UserError") } catch (_: UserError) { } }
    @Test fun allPreloadedSchemasAndDefaultsValidate() {
        ModelCatalog.presets.forEach { p ->
            ModelOptions.validateSchema(p.schema)
            p.schema.fields.forEach { d -> d.defaultValue?.let { ModelOptions.value(d, it) } }
            ModelOptions.validate(p.schema, ModelOptions.initial(p.mode, p.model, p.schema))
        }
    }
    @Test fun resolutionChangesTheWan21RouteWithoutAddingUnsupportedInputs() {
        val id = ModelOptions.routedModel("wavespeedai/wan-2.1-t2v-480p", "720p")
        assertEquals("wavespeedai/wan-2.1-t2v-720p", id)
        val settings = ModelSettings(Mode.VIDEO, Protocol.REPLICATE, "https://api.replicate.com/v1", id)
        var calls = 0
        ProviderApi { _, url, _, body ->
            calls++; assertTrue(url.endsWith("/wavespeedai/wan-2.1-t2v-720p/predictions"))
            val input = JSONObject(body!!).getJSONObject("input")
            assertFalse(input.has("resolution")); assertFalse(input.has("duration"))
            HttpReply(201, """{"id":"test","status":"starting"}""")
        }.create(ResolvedModel(settings, Mode.VIDEO, "synthetic"), "Test prompt")
        assertEquals(1, calls)
        rejects { ModelOptions.routedModel(id, "1080p") }
    }
    @Test fun wanFastModeIsEnumAndWan22UsesPluralFrameRate() {
        val w21 = ModelCatalog.find(Mode.VIDEO, "wavespeedai/wan-2.1-t2v-480p")!!.schema
        val fast = w21.fields.first { it.name == "fast_mode" }
        assertEquals(InputKind.TEXT, fast.kind); assertEquals(listOf("Off", "Balanced", "Fast"), fast.choices)
        rejects { ModelOptions.value(fast, "true") }
        val w22 = ModelCatalog.find(Mode.VIDEO, "wan-video/wan-2.2-t2v-fast")!!.schema
        val target = JSONObject()
        SchemaCodec.append(target, w22, listOf(InputValue("frames_per_second", InputKind.INTEGER, "24"), InputValue("disable_safety_checker", InputKind.BOOLEAN, "false")))
        assertEquals(24, target.getInt("frames_per_second")); assertFalse(target.getBoolean("disable_safety_checker")); assertFalse(target.has("frame_per_second"))
        rejects { ModelOptions.validate(w22, listOf(InputValue("frame_per_second", InputKind.INTEGER, "24"))) }
    }
    @Test fun optionalOffIsDifferentFromProviderDefault() {
        val schema = ModelCatalog.find(Mode.IMAGE, "black-forest-labs/flux-schnell")!!.schema
        val defaults = JSONObject(); SchemaCodec.append(defaults, schema, emptyList()); assertFalse(defaults.has("go_fast"))
        val explicit = JSONObject(); SchemaCodec.append(explicit, schema, listOf(InputValue("go_fast", InputKind.BOOLEAN, "false")))
        assertTrue(explicit.has("go_fast")); assertFalse(explicit.getBoolean("go_fast"))
    }
    @Test fun capabilityFilterFindsFrameRateModelAndRespectsResolution() {
        val matches = ModelCatalog.presets.filter { it.mode == Mode.VIDEO && ModelOptions.supports(it, setOf("Frame rate", "LoRA"), "720p") }
        assertEquals(setOf("wan-video/wan-2.2-t2v-fast", "wan-video/wan-2.2-i2v-fast"), matches.map { it.model }.toSet())
        matches.forEach { assertFalse(ModelOptions.supports(it, setOf("Audio input"))) }
    }
    @Test fun schemasRejectDuplicateReservedAndNonfiniteValues() {
        val field = InputDefinition("sample_shift", InputKind.NUMBER, minimum = 0.0, maximum = 10.0)
        listOf("NaN", "Infinity", "-1", "11").forEach { rejects { ModelOptions.value(field, it) } }
        rejects { ModelOptions.validateSchema(InputSchema(listOf(field, field))) }
        rejects { ModelOptions.validateSchema(InputSchema(listOf(InputDefinition("api_key", InputKind.TEXT)))) }
        rejects { ModelOptions.validate(InputSchema(listOf(field)), listOf(InputValue("sample_shift", InputKind.INTEGER, "1"))) }
    }
    @Test fun schemaParsingPreservesTypesChoicesBoundsAndUnsupportedRequirements() {
        val doc = JSONObject("""{"components":{"schemas":{"Input":{"required":["prompt","complex"],"properties":{"prompt":{"type":"string"},"frames_per_second":{"type":"integer","minimum":5,"maximum":30},"fast_mode":{"allOf":[{"${'$'}ref":"#/components/schemas/Fast"}],"default":"Balanced"},"complex":{"type":"object"}}},"Fast":{"type":"string","enum":["Off","Balanced","Fast"]}}}}""")
        val parsed = SchemaCodec.parse(doc)
        assertEquals(InputKind.INTEGER, parsed.fields.first { it.name == "frames_per_second" }.kind)
        assertEquals("Balanced", parsed.fields.first { it.name == "fast_mode" }.defaultValue)
        assertEquals(listOf("complex"), parsed.unsupportedRequired)
        rejects { ModelOptions.validate(parsed, emptyList(), true) }
    }
    @Test fun reservedAndInvalidSchemaNamesAreListedAsUnsupportedInsteadOfFailing() {
        val doc = JSONObject("""{"components":{"schemas":{"Input":{"required":["model"],"properties":{"prompt":{"type":"string"},"model":{"type":"string"},"webhook":{"type":"string"},"bad-name":{"type":"integer"},"seed":{"type":"integer"}}}}}}""")
        val parsed = SchemaCodec.parse(doc)
        assertEquals(listOf("seed"), parsed.fields.map { it.name })
        assertEquals(setOf("model", "webhook", "bad-name"), parsed.unsupported.toSet())
        assertEquals(listOf("model"), parsed.unsupportedRequired)
    }
    @Test fun checkedButBlankOptionalTextIsOmittedFromTheRequest() {
        val schema = InputSchema(listOf(InputDefinition("negative_prompt", InputKind.TEXT), InputDefinition("first_clip", InputKind.TEXT, uri = true), InputDefinition("seed", InputKind.INTEGER)))
        val body = JSONObject()
        SchemaCodec.append(body, schema, listOf(InputValue("negative_prompt", InputKind.TEXT, " "), InputValue("first_clip", InputKind.TEXT, ""), InputValue("seed", InputKind.INTEGER, "7")))
        assertEquals(listOf("seed"), body.keys().asSequence().toList())
    }
    @Test fun unknownModelsNeedSchemaBeforeAnyPaidCall() {
        var calls = 0
        val settings = ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", "example/custom")
        rejects { ProviderApi { _, _, _, _ -> calls++; HttpReply(200, "{}") }.create(ResolvedModel(settings, Mode.IMAGE, "synthetic"), "Test") }
        assertEquals(0, calls)
    }
    @Test fun schemaLoadingIsGetAndChecksModelIdentity() {
        val settings = Rules.defaults(Mode.VIDEO)
        val api = ProviderApi { method, url, _, body ->
            assertEquals("GET", method); assertTrue(url.endsWith("/models/wan-video/wan-2.7-t2v")); assertNull(body)
            HttpReply(200, """{"owner":"wrong","name":"model","latest_version":{}}""")
        }
        rejects { api.loadSchema(ResolvedModel(settings, Mode.IMAGE, "synthetic")) }
    }
    @Test fun recommendationsRejectInventedModelsInputsTypesAndBounds() {
        val candidates = listOf(ModelCatalog.find(Mode.VIDEO, "wan-video/wan-2.2-t2v-fast")!!)
        val good = """{"model":"wan-video/wan-2.2-t2v-fast","options":{"resolution":"720p","frames_per_second":24},"reason":"Frame-rate control"}"""
        assertEquals("24", SchemaCodec.suggestion(good, candidates).options.first { it.name == "frames_per_second" }.value)
        listOf(good.replace("wan-2.2-t2v-fast", "invented"), good.replace("frames_per_second", "api_key"), good.replace(":24", ":99"), good.replace(":24", ":\"24\""), "not JSON").forEach { rejects { SchemaCodec.suggestion(it, candidates) } }
    }
    @Test fun suggestionsWrappedInChattyTextOrFencesAreFoundButStillValidated() {
        val candidates = listOf(ModelCatalog.find(Mode.VIDEO, "wan-video/wan-2.2-t2v-fast")!!)
        val json = """{"model":"wan-video/wan-2.2-t2v-fast","options":{"resolution":"720p"},"reason":"Uses {braces} and \"quotes\" safely"}"""
        for (reply in listOf("Sure! Here is the setup:\n$json\nEnjoy.", "```json\n$json\n```", "Setup: ```$json``` Let me know.")) {
            val s = SchemaCodec.suggestion(reply, candidates)
            assertEquals("wan-video/wan-2.2-t2v-fast", s.model); assertEquals("Uses {braces} and \"quotes\" safely", s.reason)
        }
        listOf("No JSON here.", "Almost {\"model\": ", "Here: ${json.replace("wan-2.2-t2v-fast", "invented")}").forEach { rejects { SchemaCodec.suggestion(it, candidates) } }
    }
    @Test fun invalidCallInputsNeverReachTransport() {
        val settings = Rules.defaults(Mode.IMAGE).copy(options = listOf(InputValue("num_outputs", InputKind.INTEGER, "5")))
        var calls = 0
        rejects { ProviderApi { _, _, _, _ -> calls++; HttpReply(200,"{}") }.create(ResolvedModel(settings, Mode.IMAGE, "synthetic"), "Test") }
        assertEquals(0, calls)
    }
    @Test fun mediaInputUrlsRequireHttpsAndNoEmbeddedCredentials() {
        val field = InputDefinition("audio", InputKind.TEXT, uri = true)
        rejects { ModelOptions.value(field, "http://example.com/audio.mp3") }
        rejects { ModelOptions.value(field, "https://key@example.com/audio.mp3") }
        assertEquals("https://example.com/audio.mp3", ModelOptions.value(field, "https://example.com/audio.mp3").value)
    }
    @Test fun startingAndEndingFramesSelectOnlyImageToVideoModels() {
        val matches = ModelCatalog.presets.filter { ModelOptions.supports(it, setOf("Starting frame", "Ending frame"), "720p") }
        assertEquals(setOf("wan-video/wan-2.2-i2v-fast", "wan-video/wan-2.7-i2v"), matches.map { it.model }.toSet())
    }
    @Test fun missingOrConflictingStartingInputsNeverDispatch() {
        var calls = 0
        val api = ProviderApi { _, _, _, _ -> calls++; HttpReply(201, """{"id":"ok","status":"starting"}""") }
        val s = ModelSettings(Mode.VIDEO, Protocol.REPLICATE, "https://api.replicate.com/v1", "wan-video/wan-2.7-i2v")
        val first = InputValue("first_frame", InputKind.TEXT, "https://example.com/start.png")
        val last = InputValue("last_frame", InputKind.TEXT, "https://example.com/end.png")
        val clip = InputValue("first_clip", InputKind.TEXT, "https://example.com/clip.mp4")
        listOf(emptyList(), listOf(last), listOf(first, clip), listOf(last, clip)).forEach { values -> rejects { api.create(ResolvedModel(s.copy(options = values), Mode.VIDEO, "synthetic"), "Move") } }
        assertEquals(0, calls)
        api.create(ResolvedModel(s.copy(options = listOf(first, last)), Mode.VIDEO, "synthetic"), "Move")
        assertEquals(1, calls)
        val w22 = ModelSettings(Mode.VIDEO, Protocol.REPLICATE, s.baseUrl, "wan-video/wan-2.2-i2v-fast")
        rejects { api.create(ResolvedModel(w22, Mode.VIDEO, "synthetic"), "Move") }
        assertEquals(1, calls)
    }
    @Test fun credentialFieldsNeverEnterSavedModelOptionsOrSuggestions() {
        val names = listOf("hf_api_token", "civitai_api_token", "wandb_api_key", "hf_token", "password")
        val properties = JSONObject().put("seed", JSONObject().put("type", "integer"))
        names.forEach { properties.put(it, JSONObject().put("type", "string")) }
        properties.put("hidden_value", JSONObject().put("type", "string").put("format", "password"))
        val document = JSONObject().put("components", JSONObject().put("schemas", JSONObject().put("Input", JSONObject().put("properties", properties).put("required", org.json.JSONArray(listOf("hf_token"))))))
        val schema = SchemaCodec.parse(document)
        assertEquals(listOf("seed"), schema.fields.map { it.name })
        assertTrue(schema.unsupported.containsAll(names)); assertTrue("hidden_value" in schema.unsupported)
        assertEquals(listOf("hf_token"), schema.unsupportedRequired)
        rejects { ModelOptions.validate(schema, emptyList(), true) }
        names.forEach { name -> rejects { ModelOptions.validateSchema(InputSchema(listOf(InputDefinition(name, InputKind.TEXT)))) } }
    }
    @Test fun authenticatedDeliveryIsRestrictedToReplicateOriginsAndHosts() {
        assertTrue(Rules.authorizeDelivery("https://api.replicate.com/v1", "https://pbxt.replicate.delivery/image.png"))
        assertFalse(Rules.authorizeDelivery("https://example.com/v1", "https://replicate.delivery/image.png"))
        listOf("https://replicate.delivery.evil.test/x", "https://evil.test/x", "https://replicate.delivery:444/x", "http://replicate.delivery/x").forEach { url -> rejects { Rules.authorizeDelivery("https://api.replicate.com/v1", url) } }
    }
    @Test fun replicateStorageOutputsDownloadWithoutEverReceivingTheKey() {
        val r2 = "https://ai-gateway-outputs.0d37909e38d3e99c29fa2cd343ac421a.r2.cloudflarestorage.com/out-0.webp?X-Amz-Signature=abc"
        assertEquals(r2, Rules.outputUrl(r2))
        assertFalse(Rules.authorizeDelivery("https://api.replicate.com/v1", r2))
        listOf("https://ai-gateway-outputs.ffffffffffffffffffffffffffffffff.r2.cloudflarestorage.com/x", "https://other.0d37909e38d3e99c29fa2cd343ac421a.r2.cloudflarestorage.com/x",
            "http://ai-gateway-outputs.0d37909e38d3e99c29fa2cd343ac421a.r2.cloudflarestorage.com/x", "https://evil.r2.cloudflarestorage.com/x").forEach { url -> rejects { Rules.outputUrl(url) } }
        try { Rules.outputUrl("https://evil.test/x?token=secret"); fail("Expected rejection") } catch (e: UserError) { assertTrue(e.message!!.contains("evil.test")); assertFalse(e.message!!.contains("secret")) }
    }
    @Test fun modelSuggestionCanAwaitAStartingFrameButCannotGenerateWithoutIt() {
        val preset = ModelCatalog.find(Mode.VIDEO, "wan-video/wan-2.2-i2v-fast")!!
        val suggested = SchemaCodec.suggestion("""{"model":"wan-video/wan-2.2-i2v-fast","options":{"resolution":"720p"},"reason":"Add your starting frame."}""", listOf(preset))
        assertEquals(preset.model, suggested.model)
        rejects { ModelOptions.validateForModel(suggested.model, preset.schema, suggested.options) }
    }
    @Test fun photosFillTheRightInputAndSwitchModelOnlyWhenNeeded() {
        val catalog = { mode: Mode, id: String -> Rules.defaults(mode).copy(model = id, schema = ModelCatalog.find(mode, id)!!.schema, options = emptyList()) }
        val url = "https://api.replicate.com/v1/files/abc"
        val schnell = catalog(Mode.IMAGE, "black-forest-labs/flux-schnell")
        val ref = MediaRole.REFERENCE.apply(schnell, url) { catalog(Mode.IMAGE, it) }
        assertEquals("black-forest-labs/flux-dev", ref.model); assertEquals(url, ref.options.single { it.name == "image" }.value)
        val lora = catalog(Mode.IMAGE, "black-forest-labs/flux-dev-lora")
        assertEquals("black-forest-labs/flux-dev-lora", MediaRole.REFERENCE.apply(lora, url) { catalog(Mode.IMAGE, it) }.model)
        val t2v = catalog(Mode.VIDEO, "wan-video/wan-2.7-t2v")
        val start = MediaRole.START_FRAME.apply(t2v, url) { catalog(Mode.VIDEO, it) }
        assertEquals("wan-video/wan-2.7-i2v", start.model); assertEquals(url, start.options.single { it.name == "first_frame" }.value)
        val withClip = start.copy(options = listOf(InputValue("first_clip", InputKind.TEXT, "https://example.com/c.mp4")))
        assertTrue(MediaRole.START_FRAME.apply(withClip, url) { catalog(Mode.VIDEO, it) }.options.none { it.name == "first_clip" })
        val end = MediaRole.END_FRAME.apply(start, "https://api.replicate.com/v1/files/def") { catalog(Mode.VIDEO, it) }
        assertEquals(setOf("first_frame", "last_frame"), end.options.map { it.name }.toSet())
        val fast = catalog(Mode.VIDEO, "wan-video/wan-2.2-i2v-fast")
        assertEquals("image", MediaRole.START_FRAME.apply(fast, url) { catalog(Mode.VIDEO, it) }.options.single().name)
        assertEquals("last_image", MediaRole.END_FRAME.apply(fast, url) { catalog(Mode.VIDEO, it) }.options.single().name)
    }
}
