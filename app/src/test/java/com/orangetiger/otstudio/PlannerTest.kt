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

    @Test fun aPlansReplyStaysOneShortLine() {
        val long = "A red fox stands in snow. The background is a dense forest with golden light and falling flakes everywhere."
        assertEquals("A red fox stands in snow.", ok("{\"action\":\"image\",\"reply\":\"$long\",\"prompt\":\"fox\",\"reference\":\"none\",\"shape\":\"default\",\"seconds\":5}").reply)
        val noStop = "x".repeat(300)
        assertEquals(200, ok("{\"action\":\"image\",\"reply\":\"$noStop\",\"prompt\":\"fox\",\"reference\":\"none\",\"shape\":\"default\",\"seconds\":5}").reply.length)
        // Answers keep their full text.
        assertEquals(long, ok("{\"action\":\"answer\",\"reply\":\"$long\",\"prompt\":\"\",\"reference\":\"none\",\"shape\":\"default\",\"seconds\":5}").reply)
    }

    @Test fun badPlansAreNeverGuessed() {
        assertEquals("", invalid("not json at all { broken").reply)
        // An answer cut off by the length limit keeps its text.
        assertEquals(JobPlan("answer", "Sunsets are best when..."), ok("{\"action\":\"answer\",\"reply\":\"Sunsets are best when"))
        assertEquals("A fox", invalid("{\"action\":\"image\",\"reply\":\"A fox").reply)
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
        assertTrue(Planner.systemPrompt("MEMORY LINE").startsWith("MEMORY LINE"))
        // The picture list rides with the message, so the fixed instructions stay the same and the engine can reuse its work.
        assertFalse(Planner.systemPrompt("M").contains("image_"))
        val message = Planner.userMessage("make it red", refs, 14)
        assertTrue(message.contains("image_17: a green window")); assertTrue(message.contains("The user selected image_14"))
        assertTrue(message.endsWith("The user's message:\nmake it red"))
        assertTrue(Planner.userMessage("hi", emptyList(), null).contains("No pictures exist yet"))
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

    @Test fun anExpansionKeepsThePictureAndPicksAShape() {
        val refs = listOf(ImageRef(21, "a woman on a beach"))
        assertTrue(Planner.schemaText(refs).contains("\"expand\""))
        val wide = Planner.parse("""{"action":"expand","reply":"Widening it.","prompt":"more of the beach","reference":"image_21","shape":"default","seconds":5}""", refs, null)
        assertEquals(JobPlan("expand", "Widening it.", "more of the beach", 21, "landscape"), (wide as PlanResult.Ok).plan)
        assertEquals("16:9", Planner.expandRatio("landscape")); assertEquals("9:16", Planner.expandRatio("portrait")); assertEquals("1:1", Planner.expandRatio("square"))
        assertTrue(Planner.parse("""{"action":"expand","reply":"x","prompt":"more","reference":"none","shape":"square","seconds":5}""", refs, null) is PlanResult.Invalid)
        assertEquals("Your picture, expanded, tall", PlainSummary.describe(Mode.IMAGE, ModelCatalog.expander.model, listOf(InputValue("aspect_ratio", InputKind.TEXT, "9:16"))))
        assertEquals("about 4 cents", Prices.estimate(ModelCatalog.expander.model, emptyList()))
        assertEquals(ModelCatalog.expander, ModelCatalog.find(Mode.IMAGE, "black-forest-labs/flux-fill-dev"))
        ModelOptions.validate(ModelCatalog.expander.schema, listOf(InputValue("image", InputKind.TEXT, "https://api.replicate.com/v1/files/x"),
            InputValue("mask", InputKind.TEXT, "https://api.replicate.com/v1/files/y"), InputValue("megapixels", InputKind.TEXT, "match_input"),
            InputValue("output_format", InputKind.TEXT, "png"), InputValue("disable_safety_checker", InputKind.BOOLEAN, "true")))
    }

    @Test fun expandGeometryKeepsThePictureWholeAndSendsASmallCanvas() {
        // A square picture made wide keeps its full height; the new area splits evenly on both sides.
        assertEquals(Outpaint.Layout(1778, 1000, 389, 0, 1000, 1000), Outpaint.layout(1000, 1000, "16:9"))
        // A landscape photo made tall keeps its full width.
        assertEquals(Outpaint.Layout(3000, 5333, 0, 1666, 3000, 2000), Outpaint.layout(3000, 2000, "9:16"))
        assertThrows(UserError::class.java) { Outpaint.layout(1600, 900, "16:9") }
        assertThrows(UserError::class.java) { Outpaint.layout(1000, 1000, "wide") }
        val send = Outpaint.send(Outpaint.layout(1000, 1000, "16:9"))
        assertTrue(send.w <= Outpaint.MAX_SEND && send.h <= Outpaint.MAX_SEND && send.w % 32 == 0 && send.h % 32 == 0)
        assertEquals(1408, send.contentW); assertTrue(send.contentH in 790..793)
        assertEquals(83, Outpaint.feather(Outpaint.layout(1000, 1000, "16:9")))
        assertTrue(Outpaint.prompt("").startsWith("Continue the scene"))
        assertTrue(Outpaint.prompt("more of the beach.").startsWith("more of the beach. Continue"))
    }
}
