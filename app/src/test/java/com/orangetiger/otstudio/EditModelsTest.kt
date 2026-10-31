package com.orangetiger.otstudio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditModelsTest {
    private val photo = "https://api.replicate.com/v1/files/abc.def-123"

    private fun editSettings(id: String): ModelSettings {
        val base = Rules.defaults(Mode.IMAGE)
        val schema = ModelCatalog.find(Mode.IMAGE, id)!!.schema
        val start = base.copy(model = id, schema = schema, options = ModelOptions.initial(Mode.IMAGE, id, schema))
        val field = ModelCatalog.edit(id)!!.imageField
        return start.copy(options = start.options + InputValue(field, InputKind.TEXT, photo))
    }

    private fun submitted(id: String): JSONObject {
        var input: JSONObject? = null
        val api = ProviderApi { method, url, _, body ->
            assertEquals("POST", method)
            assertEquals("https://api.replicate.com/v1/models/$id/predictions", url)
            input = JSONObject(body!!).getJSONObject("input")
            HttpReply(201, """{"id":"job1","status":"starting","output":null}""")
        }
        api.create(ResolvedModel(editSettings(id), Mode.IMAGE, "synthetic-key"), "change the orange border to black")
        return input!!
    }

    @Test fun everyEditModelStartsWithTheSafetyCheckerOffAndTheInputShape() {
        // SpicyAPI's editor runs at SpicyAPI and is covered in ProviderApiTest.
        ModelCatalog.edits.filter { !ModelCatalog.spicy(it.model) }.forEach { e ->
            val input = submitted(e.model)
            assertEquals(e.model, true, input.getBoolean("disable_safety_checker"))
            assertEquals(e.model, "match_input_image", input.getString("aspect_ratio"))
            assertEquals("change the orange border to black", input.getString("prompt"))
            assertTrue(ModelOptions.supports(ModelCatalog.find(Mode.IMAGE, e.model)!!, setOf("Editing")))
        }
        assertFalse(ModelOptions.supports(ModelCatalog.find(Mode.IMAGE, "black-forest-labs/flux-dev")!!, setOf("Editing")))
        assertTrue(ModelOptions.supports(ModelCatalog.find(Mode.IMAGE, "spicyapi/spicy-image-edit-1")!!, setOf("Editing")))
        assertNull(ModelCatalog.find(Mode.IMAGE, "qwen/qwen-image-edit")); assertNull(ModelCatalog.find(Mode.VIDEO, "wavespeedai/wan-2.1-t2v-480p"))
    }

    @Test fun singleImageModelsSendAStringAndListModelsSendAnArray() {
        assertEquals(photo, submitted("black-forest-labs/flux-kontext-dev").getString("input_image"))
        assertEquals(photo, submitted("qwen/qwen-edit-multiangle").getString("image"))
        val plus = submitted("qwen/qwen-image-edit-plus").get("image")
        assertTrue(plus is JSONArray); assertEquals(photo, (plus as JSONArray).getString(0)); assertEquals(1, plus.length())
        assertEquals(photo, submitted("black-forest-labs/flux-2-dev").getJSONArray("input_images").getString(0))
        assertEquals(photo, submitted("bytedance/seedream-4.5").getJSONArray("image_input").getString(0))
        assertEquals(photo, submitted("prunaai/p-image-edit").getJSONArray("images").getString(0))
    }

    @Test fun anEditWithoutItsImageIsRefusedBeforeAnyRequest() {
        val base = Rules.defaults(Mode.IMAGE)
        val schema = ModelCatalog.find(Mode.IMAGE, "qwen/qwen-image-edit-plus")!!.schema
        val noImage = base.copy(model = "qwen/qwen-image-edit-plus", schema = schema, options = ModelOptions.initial(Mode.IMAGE, "qwen/qwen-image-edit-plus", schema))
        val api = ProviderApi { _, _, _, _ -> fail("No request may be sent"); HttpReply(500, "") }
        assertThrows(UserError::class.java) { api.create(ResolvedModel(noImage, Mode.IMAGE, "k"), "edit") }
        // Optional-image models are still refused for an edit without its image.
        val p = ModelCatalog.find(Mode.IMAGE, "prunaai/p-image-edit")!!.schema
        assertThrows(UserError::class.java) { ModelOptions.validateForModel("prunaai/p-image-edit", p, ModelOptions.initial(Mode.IMAGE, "prunaai/p-image-edit", p)) }
    }

    @Test fun providerSchemasWithUrlArraysBecomeListFields() {
        val doc = JSONObject("""{"components":{"schemas":{"Input":{"required":["prompt","image"],"properties":{
            "prompt":{"type":"string"},
            "image":{"type":"array","items":{"type":"string","format":"uri"},"default":[]},
            "tags":{"type":"array","items":{"type":"string"}},
            "disable_safety_checker":{"type":"boolean","default":false}}}}}}""")
        val schema = SchemaCodec.parse(doc)
        val image = schema.fields.first { it.name == "image" }
        assertTrue(image.list); assertTrue(image.uri); assertTrue(image.required); assertNull(image.defaultValue)
        // An array of plain strings stays unsupported.
        assertTrue("tags" in schema.unsupported)
    }

    @Test fun useAsReferenceFillsAnEditModelsImageInput() {
        val current = editSettings("qwen/qwen-image-edit-plus").let { s -> s.copy(options = s.options.filter { it.name != "image" }) }
        val updated = MediaRole.REFERENCE.apply(current, photo) { error("should keep the current model") }
        assertEquals(photo, updated.options.first { it.name == "image" }.value)
    }
}
