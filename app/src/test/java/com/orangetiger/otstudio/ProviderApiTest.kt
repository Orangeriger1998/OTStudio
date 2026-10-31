package com.orangetiger.otstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderApiTest {
    private fun model(mode: Mode) = Rules.resolve(mode, Rules::defaults) { "synthetic-test-key" }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected a UserError") } catch (_: UserError) { } }

    @Test fun chatUsesOpenRouterKeyAndTypedMessages() {
        val api = ProviderApi { method, url, key, body ->
            assertEquals("POST", method); assertEquals("https://openrouter.ai/api/v1/chat/completions", url)
            assertEquals("synthetic-test-key", key)
            val request = JSONObject(body!!)
            assertEquals("mistralai/mistral-nemo", request.getString("model"))
            assertFalse(request.getBoolean("stream"))
            assertEquals("hello", request.getJSONArray("messages").getJSONObject(0).getString("content"))
            HttpReply(200, """{"choices":[{"message":{"content":"Synthetic reply"}}]}""")
        }
        assertEquals("Synthetic reply", api.chat(model(Mode.CHAT), listOf(Turn("user", "hello"))))
    }
    @Test fun fluxSubmissionUsesModelSpecificPredictionRoute() {
        val api = ProviderApi { method, url, _, body ->
            assertEquals("POST", method)
            assertEquals("https://api.replicate.com/v1/models/black-forest-labs/flux-schnell/predictions", url)
            val input = JSONObject(body!!).getJSONObject("input")
            assertEquals("picture", input.getString("prompt")); assertEquals("1:1", input.getString("aspect_ratio"))
            assertFalse(input.has("duration"))
            HttpReply(201, """{"id":"job123","status":"starting","output":null}""")
        }
        assertEquals("job123", api.create(model(Mode.IMAGE), "picture").id)
    }
    @Test fun wanUsesDurationResolutionAndHandlesSingleOutput() {
        val api = ProviderApi { _, url, _, body ->
            assertTrue(url.endsWith("/wan-video/wan-2.7-t2v/predictions"))
            val input = JSONObject(body!!).getJSONObject("input")
            assertEquals(5, input.getInt("duration")); assertEquals("720p", input.getString("resolution"))
            HttpReply(201, """{"id":"job123","status":"succeeded","output":"https://replicate.delivery/output.mp4"}""")
        }
        assertEquals(listOf("https://replicate.delivery/output.mp4"), api.create(model(Mode.VIDEO), "video").outputUrls)
    }
    @Test fun imageArrayAndPollingDoNotCreateAnotherPrediction() {
        var calls = 0
        val api = ProviderApi { method, url, _, body ->
            calls++; assertEquals("GET", method); assertNull(body); assertTrue(url.endsWith("/predictions/job123"))
            HttpReply(200, """{"id":"job123","status":"succeeded","output":["https://replicate.delivery/a.png"]}""")
        }
        assertEquals(1, api.status("https://api.replicate.com/v1", "test", "job123").outputUrls.size)
        assertEquals(1, calls)
    }
    @Test fun cancellationUsesExistingJobAndWaitsForProviderState() {
        val api = ProviderApi { method, url, _, _ ->
            assertEquals("POST", method); assertTrue(url.endsWith("/predictions/job123/cancel"))
            HttpReply(200, """{"id":"job123","status":"canceled","output":null}""")
        }
        assertEquals("canceled", api.cancel("https://api.replicate.com/v1", "test", "job123").state)
    }
    @Test fun providerErrorsAreSanitizedAndNotRetried() {
        for (code in listOf(400, 401, 402, 403, 404, 422, 429, 500, 302)) {
            var calls = 0
            val api = ProviderApi { _, _, _, _ -> calls++; HttpReply(code, "sensitive raw provider payload") }
            try { api.create(model(Mode.IMAGE), "test"); fail("Expected error") }
            catch (e: UserError) { assertFalse(e.message.orEmpty().contains("sensitive")) }
            assertEquals(1, calls)
        }
    }
    @Test fun malformedAndUnknownProviderResultsFailClosed() {
        for (body in listOf("not json", "{}", """{"id":"job","status":"mystery"}""")) {
            val api = ProviderApi { _, _, _, _ -> HttpReply(200, body) }
            rejects { api.create(model(Mode.IMAGE), "test") }
        }
    }
    @Test fun completedJobWithUnusableOutputIsClosedInsteadOfStuck() {
        for (output in listOf("null", "\"https://evil.test/x\"", """{"video":"https://replicate.delivery/v.mp4"}""", """["https://replicate.delivery/a.png",1]""")) {
            val api = ProviderApi { _, _, _, _ -> HttpReply(200, """{"id":"job123","status":"succeeded","output":$output}""") }
            val result = api.status("https://api.replicate.com/v1", "test", "job123")
            assertEquals("unusable", result.state); assertEquals("job123", result.id)
            assertTrue(result.outputUrls.isEmpty()); assertTrue(result.problem.isNotBlank())
        }
        val aborted = ProviderApi { _, _, _, _ -> HttpReply(200, """{"id":"job123","status":"aborted","output":null}""") }
        assertEquals("canceled", aborted.status("https://api.replicate.com/v1", "test", "job123").state)
    }
    @Test fun definiteRejectionsAreDistinguishedFromUnknownOutcomes() {
        for (code in listOf(400, 401, 402, 403, 404, 422, 429)) {
            val api = ProviderApi { _, _, _, _ -> HttpReply(code, "") }
            try { api.create(model(Mode.IMAGE), "test"); fail("Expected error") } catch (e: UserError) { assertTrue("HTTP $code", e is ProviderRejected) }
        }
        for (code in listOf(302, 408, 409, 500, 503)) {
            val api = ProviderApi { _, _, _, _ -> HttpReply(code, "") }
            try { api.create(model(Mode.IMAGE), "test"); fail("Expected error") } catch (e: UserError) { assertFalse("HTTP $code", e is ProviderRejected) }
        }
    }
    @Test fun aRejectionShowsTheProvidersOwnReasonAsPlainText() {
        val api = ProviderApi { _, _, _, _ -> HttpReply(422, """{"title":"Input validation failed","detail":"- input.size: Value must be one of 1K, 2K, 4K\n\u0007"}""") }
        val e = try { api.create(model(Mode.IMAGE), "test"); null } catch (e: ProviderRejected) { e }
        assertEquals("The provider rejected these inputs. It said: - input.size: Value must be one of 1K, 2K, 4K", e?.message)
        val plain = ProviderApi { _, _, _, _ -> HttpReply(400, "not json") }
        val e2 = try { plain.create(model(Mode.IMAGE), "test"); null } catch (e: ProviderRejected) { e }
        assertTrue(e2!!.message!!.endsWith("Check the model ID, aspect ratio, and duration."))
    }
    @Test fun aFailedJobKeepsTheProvidersReason() {
        val api = ProviderApi { _, _, _, _ -> HttpReply(200, """{"id":"job123","status":"failed","output":null,"error":"NSFW content detected.\nTry a different prompt."}""") }
        val p = api.status("https://api.replicate.com/v1", "test", "job123")
        assertEquals("failed", p.state); assertEquals("NSFW content detected. Try a different prompt.", p.problem)
    }
    @Test fun aPrivateTrainedLoraRunsItsOwnModelVersion() {
        val version = "e".repeat(64)
        val calls = ArrayList<Triple<String, String, String?>>()
        val versionSchema = """{"id":"$version","openapi_schema":{"components":{"schemas":{"Input":{"type":"object","properties":{
            "prompt":{"type":"string"},"aspect_ratio":{"type":"string","enum":["1:1","9:16"],"default":"1:1"},"lora_scale":{"type":"number","default":1},
            "guidance_scale":{"type":"number","default":3},"disable_safety_checker":{"type":"boolean","default":false}}}}}}}"""
        val api = ProviderApi { method, url, _, body ->
            calls.add(Triple(method, url, body))
            if (method == "GET") HttpReply(200, versionSchema) else HttpReply(201, """{"id":"job9","status":"starting","output":null}""")
        }
        val lora = ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", LoraCodec.MODEL)
        val withLora = lora.copy(options = listOf(InputValue("aspect_ratio", InputKind.TEXT, "9:16"), InputValue("lora_weights", InputKind.TEXT, "me/lora-sam/$version"),
            InputValue("lora_scale", InputKind.NUMBER, "1.0"), InputValue("guidance", InputKind.NUMBER, "3.5"), InputValue("disable_safety_checker", InputKind.BOOLEAN, "true")))
        assertEquals("job9", api.create(ResolvedModel(withLora, Mode.IMAGE, "test"), "SAM at the beach").id)
        assertEquals("https://api.replicate.com/v1/models/me/lora-sam/versions/$version", calls[0].second)
        assertEquals("https://api.replicate.com/v1/predictions", calls[1].second)
        val sent = JSONObject(calls[1].third!!)
        assertEquals(version, sent.getString("version"))
        val input = sent.getJSONObject("input")
        assertEquals("SAM at the beach", input.getString("prompt")); assertEquals("9:16", input.getString("aspect_ratio"))
        assertEquals(3.5, input.getDouble("guidance_scale"), 0.0); assertTrue(input.getBoolean("disable_safety_checker"))
        assertFalse(input.has("lora_weights")); assertFalse(input.has("guidance"))
    }
    private fun spicy(mode: Mode, model: String, vararg options: InputValue): ResolvedModel {
        val s = ModelSettings(mode, Protocol.REPLICATE, Rules.SPICY_BASE, model)
        return ResolvedModel(s.copy(options = s.options.filter { o -> options.none { it.name == o.name } } + options), mode, "sk-spicy-test")
    }
    private fun spicyRecorder(calls: MutableList<Triple<String, String, String?>>) = ProviderApi { method, url, _, body ->
        calls.add(Triple(method, url, body))
        when {
            url.endsWith("/images/generations") || url.endsWith("/images/edits") -> HttpReply(200, """{"id":"sj_img1","object":"image.generation","data":[{"url":"https://cdn.spicyapi.com/outputs/a.png"}],"cost_usd":0.06}""")
            url.endsWith("/videos/generations") -> HttpReply(200, """{"id":"sj_vid1","status":"queued","cost_usd":0.7}""")
            else -> HttpReply(200, """{"id":"sj_vid1","status":"succeeded","output":{"video_url":"https://cdn.spicyapi.com/outputs/v.mp4"}}""")
        }
    }
    @Test fun spicyApiTextModelsSendWordsAndSettings() {
        val calls = ArrayList<Triple<String, String, String?>>()
        val api = spicyRecorder(calls)
        val picture = api.create(spicy(Mode.IMAGE, "spicyapi/spicy-image-1", InputValue("size", InputKind.TEXT, "1024*1024")), "a woman on a beach at golden hour")
        assertEquals("succeeded", picture.state); assertEquals(listOf("https://cdn.spicyapi.com/outputs/a.png"), picture.outputUrls)
        val sent = JSONObject(calls[0].third!!)
        assertEquals("https://api.spicyapi.com/v1/images/generations", calls[0].second)
        assertEquals("spicy-image-1", sent.getString("model")); assertEquals("1024*1024", sent.getString("size"))
        assertFalse(sent.has("image_url")); assertFalse(sent.has("image"))
        val task = api.create(spicy(Mode.VIDEO, "spicyapi/spicy-cinema-1"), "a city street at night, slow camera")
        assertEquals("starting", task.state)
        val body = JSONObject(calls[1].third!!)
        assertEquals("spicy-cinema-1", body.getString("model")); assertEquals(5, body.getInt("duration")); assertEquals("720P", body.getString("resolution"))
        val done = api.status(Rules.SPICY_BASE, "sk-spicy-test", "sj_vid1")
        assertEquals("https://api.spicyapi.com/v1/videos/tasks/sj_vid1", calls[2].second)
        assertEquals(listOf("https://cdn.spicyapi.com/outputs/v.mp4"), done.outputUrls)
        val failed = ProviderApi { _, _, _, _ -> HttpReply(200, """{"id":"sj_vid2","status":"failed","error":"Blocked by screening","error_code":"blocked"}""") }.status(Rules.SPICY_BASE, "k", "sj_vid2")
        assertEquals("failed", failed.state); assertEquals("Blocked by screening blocked", failed.problem)
    }
    @Test fun spicyApiPicturesMustBeSpicyApisOwn() {
        val own = "https://cdn.spicyapi.com/outputs/sj_2f1c-0.png"
        val calls = ArrayList<Triple<String, String, String?>>()
        val api = spicyRecorder(calls)
        // An edit goes to the edits endpoint, with the pictures as a list and the picture being edited first.
        api.create(spicy(Mode.IMAGE, "spicyapi/spicy-image-edit-1", InputValue("image_urls", InputKind.TEXT, own)), "put her in a red dress")
        assertEquals("https://api.spicyapi.com/v1/images/edits", calls[0].second)
        val edit = JSONObject(calls[0].third!!)
        assertEquals("spicy-image-edit-1", edit.getString("model")); assertEquals(own, edit.getJSONArray("image_urls").getString(0)); assertEquals("put her in a red dress", edit.getString("prompt"))
        // The upscaler sends the picture and the factor, and no prompt.
        val up = ModelCatalog.spicyUpscaler
        api.create(ResolvedModel(ModelSettings(Mode.IMAGE, Protocol.REPLICATE, Rules.SPICY_BASE, up.model, schema = up.schema,
            options = listOf(InputValue("image_url", InputKind.TEXT, own), InputValue("scale", InputKind.INTEGER, "2"))), Mode.IMAGE, "sk-spicy-test"), null)
        val scaled = JSONObject(calls[1].third!!)
        assertEquals("https://api.spicyapi.com/v1/images/edits", calls[1].second); assertEquals(own, scaled.getString("image_url")); assertEquals(2, scaled.getInt("scale")); assertFalse(scaled.has("prompt"))
        // A clip from a starting and an ending picture keeps the picture's shape, so no shape is sent.
        api.create(spicy(Mode.VIDEO, "spicyapi/spicy-motion-3", InputValue("image_url", InputKind.TEXT, own), InputValue("last_frame_url", InputKind.TEXT, own)), "she turns to the window")
        val clip = JSONObject(calls[2].third!!)
        assertEquals("https://api.spicyapi.com/v1/videos/generations", calls[2].second)
        assertEquals(own, clip.getString("image_url")); assertEquals(own, clip.getString("last_frame_url")); assertFalse(clip.has("aspect_ratio")); assertEquals("720P", clip.getString("resolution"))
        // Reference pictures go as a list.
        api.create(spicy(Mode.VIDEO, "spicyapi/spicy-cinema-1-character", InputValue("reference_image_urls", InputKind.TEXT, PictureList.join(listOf(own, own)))), "@img1 and @img2 dance")
        val group = JSONObject(calls[3].third!!)
        assertEquals(2, group.getJSONArray("reference_image_urls").length()); assertEquals("image 1 and image 2 dance", group.getString("prompt")); assertEquals("16:9", group.getString("aspect_ratio"))
        // A picture from anywhere else, or a conflicting mix, never reaches SpicyAPI: nothing is sent.
        val before = calls.size
        for (foreign in listOf("https://api.replicate.com/v1/files/abc", "https://example.com/a.png", "phone:p1.jpg"))
            rejects { api.create(spicy(Mode.VIDEO, "spicyapi/spicy-cinema-1-image", InputValue("image_url", InputKind.TEXT, foreign)), "move") }
        rejects { api.create(spicy(Mode.VIDEO, "spicyapi/spicy-motion-3", InputValue("image_url", InputKind.TEXT, own), InputValue("reference_image_urls", InputKind.TEXT, own)), "move") }
        rejects { api.create(spicy(Mode.VIDEO, "spicyapi/spicy-motion-3", InputValue("last_frame_url", InputKind.TEXT, own)), "move") }
        rejects { api.create(spicy(Mode.VIDEO, "spicyapi/spicy-cinema-1-image"), "move") }
        assertEquals(before, calls.size)
        assertTrue(Rules.spicyPicture(own)); assertFalse(Rules.spicyPicture("https://cdn.spicyapi.com.evil.example/a.png")); assertFalse(Rules.spicyPicture("https://user@cdn.spicyapi.com/a.png"))
    }
    @Test fun spicyReferenceAndActionModelsTakeNoPromptAndTheReferenceTakesAPhonePhoto() {
        val calls = ArrayList<Triple<String, String, String?>>()
        val api = spicyRecorder(calls)
        api.phonePicture = { ref -> assertEquals("phone:p1.jpg", ref); "data:image/jpeg;base64,AAAA" }
        api.create(spicy(Mode.IMAGE, ModelCatalog.SPICY_REFERENCE, InputValue("reference_image", InputKind.TEXT, "phone:p1.jpg")), "From the picture")
        val body = JSONObject(calls[0].third!!)
        assertEquals("https://api.spicyapi.com/v1/images/generations", calls[0].second)
        assertEquals("data:image/jpeg;base64,AAAA", body.getString("reference_image")); assertFalse(body.has("prompt"))
        api.create(spicy(Mode.IMAGE, "spicyapi/spicy-image-action-1", InputValue("image_url", InputKind.TEXT, "https://cdn.spicyapi.com/outputs/w.png"), InputValue("action", InputKind.TEXT, "pov-missionary")), "ignored")
        val action = JSONObject(calls[1].third!!)
        assertEquals("pov-missionary", action.getString("action")); assertEquals("https://cdn.spicyapi.com/outputs/w.png", action.getString("image_url")); assertFalse(action.has("prompt"))
        // The action model does not take a phone photo: the woman must come from a SpicyAPI picture.
        rejects { api.create(spicy(Mode.IMAGE, "spicyapi/spicy-image-action-1", InputValue("image_url", InputKind.TEXT, "phone:p1.jpg"), InputValue("action", InputKind.TEXT, "pov-missionary")), "x") }
        assertEquals(2, calls.size)
    }
    @Test fun nonTextChatOutputIsNotPresentedAsSuccess() {
        for (body in listOf("{}", """{"choices":[{"message":{"content":null}}]}""", """{"choices":[{"message":{"content":[]}}]}""")) {
            val api = ProviderApi { _, _, _, _ -> HttpReply(200, body) }
            rejects { api.chat(model(Mode.CHAT), listOf(Turn("user", "hi"))) }
        }
    }
    @Test fun photoUploadSendsMultipartToReplicateAndReturnsItsFileUrl() {
        var sentUrl = ""; var sentType = ""; var sentBody = ByteArray(0); var sentKey = ""
        val api = ProviderApi({ _, _, _, _ -> fail("JSON transport must not be used"); HttpReply(500, "") },
            BinaryTransport { url, key, type, body -> sentUrl = url; sentKey = key; sentType = type; sentBody = body
                HttpReply(201, """{"id":"abc123","urls":{"get":"https://api.replicate.com/v1/files/abc123"}}""") })
        val photo = byteArrayOf(1, 2, 3, 4)
        assertEquals("https://api.replicate.com/v1/files/abc123", api.uploadFile("https://api.replicate.com/v1", "synthetic", "My Cat!.jpg", "image/jpeg", photo))
        assertEquals("https://api.replicate.com/v1/files", sentUrl); assertEquals("synthetic", sentKey)
        assertTrue(sentType.startsWith("multipart/form-data; boundary=OTStudio"))
        val text = String(sentBody, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"content\"; filename=\"MyCat.jpg\"")); assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue(text.contains(String(photo, Charsets.ISO_8859_1)))
    }
    @Test fun photoUploadRefusesOtherHostsTypesSizesAndBadReplies() {
        val ok = BinaryTransport { _, _, _, _ -> HttpReply(201, """{"id":"abc","urls":{"get":"https://api.replicate.com/v1/files/abc"}}""") }
        val api = ProviderApi({ _, _, _, _ -> HttpReply(500, "") }, ok)
        rejects { api.uploadFile("https://openrouter.ai/api/v1", "k", "a.jpg", "image/jpeg", byteArrayOf(1)) }
        rejects { api.uploadFile("https://api.replicate.com/v1", "k", "a.gif", "image/gif", byteArrayOf(1)) }
        rejects { api.uploadFile("https://api.replicate.com/v1", "k", "a.jpg", "image/jpeg", ByteArray(0)) }
        rejects { api.uploadFile("https://api.replicate.com/v1", "k", "a.jpg", "image/jpeg", ByteArray(Rules.MAX_UPLOAD_BYTES + 1)) }
        for (reply in listOf("""{"id":"abc","urls":{"get":"https://evil.test/abc"}}""", """{"id":"other","urls":{"get":"https://api.replicate.com/v1/files/abc"}}""", "{}")) {
            val bad = ProviderApi({ _, _, _, _ -> HttpReply(500, "") }, BinaryTransport { _, _, _, _ -> HttpReply(201, reply) })
            rejects { bad.uploadFile("https://api.replicate.com/v1", "k", "a.jpg", "image/jpeg", byteArrayOf(1)) }
        }
        val refused = ProviderApi({ _, _, _, _ -> HttpReply(500, "") }, BinaryTransport { _, _, _, _ -> HttpReply(401, "") })
        try { refused.uploadFile("https://api.replicate.com/v1", "k", "a.jpg", "image/jpeg", byteArrayOf(1)); fail() } catch (e: UserError) { assertTrue(e is ProviderRejected) }
    }
    @Test fun upscalerRequestCarriesTheImageAndScaleButNoPrompt() {
        val preset = ModelCatalog.upscaler
        val base = model(Mode.IMAGE)
        val upscaler = base.copy(settings = base.settings.copy(model = preset.model, schema = preset.schema,
            options = listOf(InputValue("image", InputKind.TEXT, "https://api.replicate.com/v1/files/abc"), InputValue("scale", InputKind.NUMBER, "2"))))
        val api = ProviderApi { _, url, _, body ->
            assertTrue(url.endsWith("/models/nightmareai/real-esrgan/predictions"))
            val input = JSONObject(body!!).getJSONObject("input")
            assertFalse(input.has("prompt")); assertEquals(2.0, input.getDouble("scale"), 0.0)
            assertEquals("https://api.replicate.com/v1/files/abc", input.getString("image"))
            HttpReply(201, """{"id":"up1","status":"starting","output":null}""")
        }
        assertEquals("up1", api.create(upscaler, null).id)
        assertNull(ModelCatalog.presets.firstOrNull { it.model == preset.model })
    }
    @Test fun photoUploadAcceptsDottedReplicateFileIdsButNotPathSteps() {
        val dotted = "ZmlsZS0xMjM0NTY3ODkw.YWJjZGVmZ2hpams.bG1ub3BxcnN0dXZ3"
        fun reply(id: String) = ProviderApi({ _, _, _, _ -> HttpReply(500, "") }, BinaryTransport { _, _, _, _ -> HttpReply(201, """{"id":"$id","urls":{"get":"https://api.replicate.com/v1/files/$id"}}""") })
        assertEquals("https://api.replicate.com/v1/files/$dotted", reply(dotted).uploadFile("https://api.replicate.com/v1", "k", "a.jpg", "image/jpeg", byteArrayOf(1)))
        for (bad in listOf("a..b", ".abc", "abc.")) rejects { reply(bad).uploadFile("https://api.replicate.com/v1", "k", "a.jpg", "image/jpeg", byteArrayOf(1)) }
    }
}
