package com.orangetiger.otstudio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannerTest {
    private val refs = listOf(ImageRef(17, "a green window"), ImageRef(14, "a tuxedo cat"))

    private fun ok(raw: String, selected: Long? = null) = (Planner.parse(raw, refs, selected) as PlanResult.Ok).plan
    private fun invalid(raw: String) = Planner.parse(raw, refs, null) as PlanResult.Invalid

    @Test fun answersImagesEditsAndVideosParse() {
        assertEquals(JobPlan("answer", "Tigers sleep a lot."), ok("""{"action":"answer","reply":"Tigers sleep a lot.","prompt":"","reference":"none","shape":"default","seconds":5}"""))
        val image = ok("""{"action":"image","reply":"A square cat.","prompt":"a cat on a red sofa, soft light","reference":"image_14","shape":"square","seconds":5}""")
        assertEquals("image", image.action); assertNull("an image never carries a reference", image.reference); assertNull(image.seconds); assertEquals("square", image.shape)
        val edit = ok("""{"action":"edit","reply":"Black border.","prompt":"change the border to black","reference":"last_image","shape":"default","seconds":2}""")
        assertEquals(17L, edit.reference)
        // "That picture" follows the user's selection.
        assertEquals(14L, ok("""{"action":"edit","reply":"x","prompt":"make it blue","reference":"last_image","shape":"default","seconds":2}""", selected = 14).reference)
        val video = ok("""{"action":"video","reply":"A clip.","prompt":"the cat stands up and walks away","reference":"image_14","shape":"landscape","seconds":20}""")
        assertEquals(14L, video.reference); assertEquals(5, video.seconds)
        // Chatty wrappers around the JSON are tolerated.
        assertEquals("image", ok("Sure! {\"action\":\"image\",\"reply\":\"ok\",\"prompt\":\"a fox\",\"reference\":\"none\",\"shape\":\"default\",\"seconds\":5} Enjoy").action)
    }

    @Test fun badPlansAreNeverGuessed() {
        assertEquals("", invalid("not json at all { broken").reply)
        assertEquals(JobPlan("answer", "plain words"), ok("plain words"))
        invalid("""{"action":"launch","reply":"x"}""")
        invalid("""{"action":"image","reply":"x","prompt":"","reference":"none"}""")
        invalid("""{"action":"image","reply":"x","prompt":"see https://evil.example/a.png","reference":"none"}""")
        invalid("""{"action":"edit","reply":"x","prompt":"make it blue","reference":"none"}""")
        invalid("""{"action":"edit","reply":"x","prompt":"make it blue","reference":"image_99"}""")
        invalid("""{"action":"image","reply":"x","prompt":"${"a".repeat(Rules.MAX_PROMPT + 1)}","reference":"none"}""")
        assertTrue(Planner.parse("""{"action":"edit","reply":"x","prompt":"blue","reference":"last_image"}""", emptyList(), null) is PlanResult.Invalid)
    }

    @Test fun theSchemaOffersOnlyListedPictures() {
        val schema = Planner.schema(refs)
        val refEnum = schema.getJSONObject("properties").getJSONObject("reference").getJSONArray("enum")
        assertEquals(listOf("none", "last_image", "image_17", "image_14"), (0 until refEnum.length()).map { refEnum.getString(it) })
        assertFalse(schema.getBoolean("additionalProperties"))
        // The action comes first in the text, so the model decides what to do before writing anything else.
        val text = Planner.schemaText(refs)
        assertTrue(text.indexOf("\"action\"") < text.indexOf("\"reply\"") && text.indexOf("\"reply\"") < text.indexOf("\"reference\"") && text.indexOf("\"prompt\"") < text.indexOf("\"seconds\""))
        assertTrue(Planner.responseFormat(refs).indexOf("\"action\"") < Planner.responseFormat(refs).indexOf("\"seconds\""))
        val format = JSONObject(Planner.responseFormat(refs))
        assertEquals("json_schema", format.getString("type")); assertTrue(format.getJSONObject("json_schema").getBoolean("strict"))
        val prompt = Planner.systemPrompt("MEMORY LINE", refs, 14)
        assertTrue(prompt.startsWith("MEMORY LINE")); assertTrue(prompt.contains("image_17: a green window")); assertTrue(prompt.contains("The user selected image_14"))
        assertTrue(Planner.systemPrompt("", emptyList(), null).contains("No pictures exist yet"))
    }

    @Test fun theReplyStreamsOutOfPartialJson() {
        assertEquals("", Planner.streamingReply("{\"action\":\"ima"))
        assertEquals("Here is", Planner.streamingReply("{\"action\":\"image\",\"reply\":\"Here is"))
        assertEquals("Line\nTwo \"quoted\" \u00e9", Planner.streamingReply("{\"reply\":\"Line\\nTwo \\\"quoted\\\" \\u00e9\",\"prompt\":\"x"))
        assertEquals("Ends with a", Planner.streamingReply("{\"reply\":\"Ends with a\\"))
    }

    @Test fun plansMapOntoRealInputsOnly() {
        val schnell = Rules.defaults(Mode.IMAGE)
        val (wide, notes) = JobBuilder.apply(schnell, JobPlan("image", "r", "p", shape = "landscape"))
        assertEquals("16:9", wide.options.first { it.name == "aspect_ratio" }.value); assertTrue(notes.isEmpty())
        val wan = Rules.defaults(Mode.VIDEO)
        val (clip, _) = JobBuilder.apply(wan, JobPlan("video", "r", "p", seconds = 15))
        assertEquals("15", clip.options.first { it.name == "duration" }.value)
        val wan22 = wan.copy(model = "wan-video/wan-2.2-t2v-fast", schema = ModelCatalog.find(Mode.VIDEO, "wan-video/wan-2.2-t2v-fast")!!.schema, options = emptyList())
        assertEquals("121", JobBuilder.apply(wan22, JobPlan("video", "r", "p", seconds = 10)).first.options.first { it.name == "num_frames" }.value)
        // WAN 2.7 text-to-video has no square choice for "portrait"? It has 9:16, so portrait maps there.
        assertEquals("9:16", JobBuilder.apply(wan, JobPlan("video", "r", "p", shape = "portrait", seconds = 5)).first.options.first { it.name == "aspect_ratio" }.value)
        // An edit keeps the picture's shape whatever the plan says.
        val kontext = schnell.copy(model = "black-forest-labs/flux-kontext-dev", schema = ModelCatalog.find(Mode.IMAGE, "black-forest-labs/flux-kontext-dev")!!.schema, options = listOf(InputValue("aspect_ratio", InputKind.TEXT, "match_input_image")))
        assertEquals("match_input_image", JobBuilder.apply(kontext, JobPlan("edit", "r", "p", 1, shape = "square")).first.options.first { it.name == "aspect_ratio" }.value)
        val noAspect = wan.copy(model = "x/y", schema = InputSchema(emptyList()), options = emptyList())
        assertEquals(2, JobBuilder.apply(noAspect, JobPlan("video", "r", "p", shape = "square", seconds = 5)).second.size)
    }

    @Test fun summariesAndPricesUsePlainWords() {
        assertEquals("Image, square", PlainSummary.describe(Mode.IMAGE, "black-forest-labs/flux-schnell", listOf(InputValue("aspect_ratio", InputKind.TEXT, "1:1"), InputValue("num_outputs", InputKind.INTEGER, "1"))))
        assertEquals("5-second clip, from your picture, wide, 720p", PlainSummary.describe(Mode.VIDEO, "wan-video/wan-2.7-i2v", listOf(InputValue("duration", InputKind.INTEGER, "5"), InputValue("first_frame", InputKind.TEXT, "https://x"), InputValue("aspect_ratio", InputKind.TEXT, "16:9"), InputValue("resolution", InputKind.TEXT, "720p"))))
        assertEquals("Edit of your picture, same shape as the picture, safety checker off", PlainSummary.describe(Mode.IMAGE, "black-forest-labs/flux-kontext-dev", listOf(InputValue("aspect_ratio", InputKind.TEXT, "match_input_image"), InputValue("disable_safety_checker", InputKind.BOOLEAN, "true"))))
        assertEquals("less than 1 cent", Prices.estimate("black-forest-labs/flux-schnell", emptyList()))
        assertEquals("about 50 cents", Prices.estimate("wan-video/wan-2.7-i2v", listOf(InputValue("duration", InputKind.INTEGER, "5"), InputValue("resolution", InputKind.TEXT, "720p"))))
        assertEquals("about 2.5 cents", Prices.estimate("black-forest-labs/flux-kontext-dev", emptyList()))
        assertNull(Prices.estimate("someone/unknown", emptyList()))
    }

    @Test fun plansSurviveStorage() {
        val plan = JobPlan("video", "A clip.", "the cat walks", 14, "landscape", 5)
        assertEquals(plan, JobPlan.fromJson(plan.toJson()))
        assertNull(JobPlan.fromJson("{broken"))
    }
}
