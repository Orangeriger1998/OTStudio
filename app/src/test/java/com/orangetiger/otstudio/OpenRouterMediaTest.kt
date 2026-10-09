package com.orangetiger.otstudio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterMediaTest {
    private fun model(mode: Mode, id: String, options: List<InputValue> = emptyList()): ResolvedModel {
        val s = ModelSettings(mode, Protocol.REPLICATE, OpenRouterMedia.BASE, id)
        return ResolvedModel(s.copy(options = s.options.filter { o -> options.none { it.name == o.name } } + options).also(Rules::validate), Mode.CHAT, "synthetic-test-key")
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected a UserError") } catch (_: UserError) { } }
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test fun modelsAreListedWithNamesAndPass() {
        val ids = OpenRouterMedia.presets.map { it.model }
        assertTrue("openrouter/alibaba/wan-2.7" in ids && "openrouter/bytedance-seed/seedream-4.5" in ids)
        assertTrue(OpenRouterMedia.presets.all { ModelCatalog.find(it.mode, it.model) != null })
        assertEquals("Wan 2.7 on OpenRouter, 10 cents a second", ModelNames.title("openrouter/alibaba/wan-2.7"))
        OpenRouterMedia.presets.forEach { Rules.validate(ModelSettings(it.mode, Protocol.REPLICATE, OpenRouterMedia.BASE, it.model)) }
        rejects { Rules.validate(ModelSettings(Mode.IMAGE, Protocol.REPLICATE, OpenRouterMedia.BASE, "openrouter/../x")) }
        assertEquals(14, PictureList.max("openrouter/bytedance-seed/seedream-4.5"))
    }

    @Test fun picturesGoInsideTheRequestAndComeBackInsideTheAnswer() {
        val api = ProviderApi { method, url, key, body ->
            assertEquals("POST", method); assertEquals("https://openrouter.ai/api/v1/images", url); assertEquals("synthetic-test-key", key)
            val r = JSONObject(body!!)
            assertEquals("bytedance-seed/seedream-4.5", r.getString("model"))
            assertEquals("the smile from image 1", r.getString("prompt"))
            assertEquals("4:3", r.getString("aspect_ratio")); assertEquals(2, r.getInt("n"))
            val ref = r.getJSONArray("input_references").getJSONObject(0)
            assertEquals("image_url", ref.getString("type")); assertEquals("data:image/jpeg;base64,AAAA", ref.getJSONObject("image_url").getString("url"))
            HttpReply(200, """{"data":[{"b64_json":"${java.util.Base64.getEncoder().encodeToString(png)}"},{"b64_json":"${java.util.Base64.getEncoder().encodeToString(png)}"}]}""")
        }
        api.phonePicture = { ref -> assertEquals("phone:3f2a-photo.jpg", ref); "data:image/jpeg;base64,AAAA" }
        val p = api.create(model(Mode.IMAGE, "openrouter/bytedance-seed/seedream-4.5", listOf(
            InputValue("aspect_ratio", InputKind.TEXT, "4:3"), InputValue("n", InputKind.INTEGER, "2"), InputValue("input_references", InputKind.TEXT, "phone:3f2a-photo.jpg"))), "the smile from @img1")
        assertEquals("succeeded", p.state); assertEquals(2, p.inline.size); assertTrue(p.inline[0].contentEquals(png))
    }

    @Test fun videosUseFramesAndAreCheckedAsTasks() {
        val api = ProviderApi { method, url, _, body ->
            if (method == "POST") {
                assertEquals("https://openrouter.ai/api/v1/videos", url)
                val r = JSONObject(body!!)
                assertEquals("alibaba/wan-2.7", r.getString("model")); assertEquals(7, r.getInt("duration")); assertTrue(r.getBoolean("generate_audio"))
                val frame = r.getJSONArray("frame_images").getJSONObject(0)
                assertEquals("first_frame", frame.getString("frame_type")); assertEquals("https://example.com/a.png", frame.getJSONObject("image_url").getString("url"))
                assertFalse(r.has("first_frame"))
                HttpReply(202, """{"id":"vid_1","polling_url":"https://openrouter.ai/api/v1/videos/vid_1","status":"pending"}""")
            } else {
                assertEquals("https://openrouter.ai/api/v1/videos/vid_1", url)
                HttpReply(200, """{"id":"vid_1","status":"completed","unsigned_urls":["https://openrouter.ai/api/v1/videos/vid_1/content?index=0"],"usage":{"cost":0.7}}""")
            }
        }
        val started = api.create(model(Mode.VIDEO, "openrouter/alibaba/wan-2.7", listOf(InputValue("duration", InputKind.INTEGER, "7"),
            InputValue("generate_audio", InputKind.BOOLEAN, "true"), InputValue("first_frame", InputKind.TEXT, "https://example.com/a.png"))), "she turns")
        assertEquals("starting", started.state)
        val done = api.status(OpenRouterMedia.BASE, "synthetic-test-key", "vid_1")
        assertEquals(listOf("https://openrouter.ai/api/v1/videos/vid_1/content?index=0"), done.outputUrls)
        rejects { api.cancel(OpenRouterMedia.BASE, "synthetic-test-key", "vid_1") }
    }

    @Test fun keysStayWithOpenRouterAndPhonePhotosStayWithItsModels() {
        assertTrue(Rules.authorizeDelivery(OpenRouterMedia.BASE, "https://openrouter.ai/api/v1/videos/vid_1/content?index=0"))
        assertFalse(Rules.authorizeDelivery("https://api.replicate.com/v1", "https://openrouter.ai/api/v1/videos/vid_1/content"))
        assertFalse(Rules.authorizeDelivery(OpenRouterMedia.BASE, "https://replicate.delivery/a.mp4"))
        rejects { Rules.outputUrl("https://openrouter.ai.example.com/a.mp4") }
        val seedream = ModelCatalog.find(Mode.IMAGE, "bytedance/seedream-4.5")!!.schema
        rejects { ModelOptions.validateForModel("bytedance/seedream-4.5", seedream, listOf(InputValue("image_input", InputKind.TEXT, "phone:abc.jpg"))) }
        rejects { ModelOptions.value(seedream.fields.first { it.name == "image_input" }, "phone:../secret.jpg") }
        // An ending picture needs a starting picture, and a Replicate upload link cannot reach OpenRouter.
        val api = ProviderApi { _, _, _, _ -> fail("nothing may be sent"); HttpReply(500, "") }
        rejects { api.create(model(Mode.VIDEO, "openrouter/google/veo-3.1-lite", listOf(InputValue("last_frame", InputKind.TEXT, "https://example.com/b.png"))), "x") }
        rejects { api.create(model(Mode.IMAGE, "openrouter/qwen/qwen-image-3", listOf(InputValue("input_references", InputKind.TEXT, "https://api.replicate.com/v1/files/abc"))), "x") }
    }

    @Test fun pricesFollowLengthSoundAndResolution() {
        fun price(id: String, vararg o: InputValue) = Prices.estimate(id, o.toList())
        assertEquals("about 70 cents", price("openrouter/alibaba/wan-2.7", InputValue("duration", InputKind.INTEGER, "7")))
        assertEquals("about 24 cents", price("openrouter/google/veo-3.1-lite", InputValue("duration", InputKind.INTEGER, "8")))
        assertEquals("about 64 cents", price("openrouter/google/veo-3.1-lite", InputValue("duration", InputKind.INTEGER, "8"), InputValue("resolution", InputKind.TEXT, "1080p"), InputValue("generate_audio", InputKind.BOOLEAN, "true")))
        assertEquals("about 8 cents", price("openrouter/bytedance-seed/seedream-4.5", InputValue("n", InputKind.INTEGER, "2")))
        assertTrue(price("openrouter/bytedance/seedance-1-5-pro")!!.contains("openrouter.ai"))
    }

    @Test fun failedVideoKeepsTheReason() {
        val p = OpenRouterMedia.videoTask(JSONObject("""{"id":"vid_2","status":"failed","error":{"message":"Content\u0007flagged"}}"""))
        assertTrue(p.failed); assertEquals("Content flagged", p.problem)
        rejects { OpenRouterMedia.videoTask(JSONObject("""{"id":"vid_2","status":"weird"}""")) }
    }
}
