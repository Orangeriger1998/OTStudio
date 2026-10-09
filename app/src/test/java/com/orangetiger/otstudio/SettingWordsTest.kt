package com.orangetiger.otstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingWordsTest {
    private fun num(n: String, min: Double? = null, max: Double? = null, default: String? = null, integer: Boolean = true) =
        InputDefinition(n, if (integer) InputKind.INTEGER else InputKind.NUMBER, default, minimum = min, maximum = max)
    private fun choice(n: String, values: List<String>, default: String? = null) = InputDefinition(n, InputKind.TEXT, default, values)
    private fun bool(n: String, default: String = "false") = InputDefinition(n, InputKind.BOOLEAN, default)

    private val fluxDev = listOf(
        num("num_outputs", 1.0, 4.0, "1"), num("num_inference_steps", 1.0, 50.0, "28"), num("guidance", 0.0, 10.0, "3", integer = false),
        choice("output_format", listOf("webp", "jpg", "png"), "webp"), num("output_quality", 0.0, 100.0, "80"),
        bool("go_fast", "true"), num("seed"), bool("disable_safety_checker"), num("lora_scale", -1.0, 3.0, "1", integer = false)
    )

    @Test fun picturesSettingsAreReadFromTheUsersWords() {
        val r = SettingWords.extract("a red fox, 3 pictures, 40 steps, prompt strength 4.5, as PNG, quality 95, seed 1234, fast mode off, safety off, lora strength 0.8", fluxDev)
        val set = r.values.associate { it.name to it.value }
        assertEquals(mapOf("guidance" to "4.5", "lora_scale" to "0.8", "num_outputs" to "3", "seed" to "1234", "num_inference_steps" to "40",
            "output_format" to "png", "disable_safety_checker" to "true", "go_fast" to "false", "output_quality" to "95"), set)
        assertTrue("Safety checker off" in r.shown)
        assertEquals(mapOf("disable_safety_checker" to "false"), SettingWords.extract("safety checker on please", fluxDev).values.associate { it.name to it.value })
    }

    @Test fun valuesAreCheckedAndMissingSettingsAreNamed() {
        val r = SettingWords.extract("a cat, 9 pictures, 99 steps, with sound, 1080p", fluxDev)
        assertTrue(r.values.isEmpty())
        assertEquals(listOf("Resolution", "Sound"), r.missing.sorted())
        assertTrue(r.problems.any { "outside 1 to 4" in it })
    }

    @Test fun wan22CountsFramesAndOrdinaryWordsAreNotSettings() {
        val wan = listOf(choice("resolution", listOf("480p", "720p"), "480p"), num("num_frames", 81.0, 121.0, "81"), num("frames_per_second", 5.0, 30.0, "16"))
        val r = SettingWords.extract("she turns, 7 seconds, 720p", wan)
        assertEquals("113", r.values.first { it.name == "num_frames" }.value)
        assertTrue("Length 7 seconds (113 frames)" in r.shown)
        val plain = SettingWords.extract("a man in a 1970s tuxedo shirt, two people dancing, high quality", fluxDev)
        assertTrue(plain.values.isEmpty() && plain.problems.isEmpty() && plain.missing.isEmpty())
    }

    @Test fun typedRatiosAndClockTimes() {
        assertEquals("4:3", Ratios.fromText("A studio photo of a bald man. 4:3 aspect ratio."))
        assertEquals("16:9", Ratios.fromText("make it 16x9"))
        assertEquals("3:2", Ratios.fromText("a sunset, 3 by 2"))
        assertNull(Ratios.fromText("a city at 10:30 pm"))
        assertEquals("3:2", Ratios.nearest("4:3", listOf("match_input_image", "1:1", "16:9", "3:2")))
    }

    @Test fun pictureLabelsReachTheModelAsImageNumbers() {
        assertEquals("Take the expression of image 1 and put it on the woman in image 2.", PictureRefs.rewrite("Take the expression of @img1 and put it on the woman in @img2.", 2))
        assertEquals("email me@img1.com", PictureRefs.rewrite("email me@img1.com", 0))
        try { PictureRefs.rewrite("the woman in @img3", 2); fail("a missing picture must stop the request") } catch (e: UserError) { assertTrue("only 2 pictures are attached" in e.message.orEmpty()) }
        val schema = InputSchema(listOf(InputDefinition("image_input", InputKind.TEXT, uri = true, list = true)))
        assertEquals(2, PictureRefs.count(schema, listOf(InputValue("image_input", InputKind.TEXT, "https://a/1\nhttps://a/2"))))
    }

    @Test fun seedreamWidthAndHeightCountOnlyForACustomSize() {
        val size = InputDefinition("size", InputKind.TEXT, "2K", listOf("2K", "4K", "custom"))
        val aspect = InputDefinition("aspect_ratio", InputKind.TEXT, "match_input_image", listOf("match_input_image", "1:1", "4:3"), description = "Image aspect ratio. Only used when size is not 'custom'.")
        val width = InputDefinition("width", InputKind.INTEGER, minimum = 1024.0, maximum = 4096.0, description = "Use with custom size.")
        val schema = InputSchema(listOf(size, aspect, width))
        assertFalse(LinkedSettings.active(width, schema, emptyList()))
        assertTrue(LinkedSettings.active(aspect, schema, emptyList()))
        val custom = listOf(InputValue("size", InputKind.TEXT, "custom"), InputValue("aspect_ratio", InputKind.TEXT, "4:3"), InputValue("width", InputKind.INTEGER, "2048"))
        assertEquals(listOf("size", "width"), LinkedSettings.keep(schema, custom).map { it.name })
        assertEquals(listOf("aspect_ratio"), LinkedSettings.keep(schema, listOf(InputValue("aspect_ratio", InputKind.TEXT, "4:3"), InputValue("width", InputKind.INTEGER, "3000"))).map { it.name })
    }

    @Test fun jobCardsUseTheRatioAndSettingsTheUserTyped() {
        val schema = InputSchema(listOf(choice("aspect_ratio", listOf("1:1", "4:3", "16:9"), "1:1"), num("num_outputs", 1.0, 4.0, "1"), bool("disable_safety_checker")))
        val s = ModelSettings(Mode.IMAGE, Protocol.REPLICATE, "https://api.replicate.com/v1", "bytedance/seedream-4.5", schema = schema)
        val (out, notes) = JobBuilder.apply(s, JobPlan("image", "ok", "a lighthouse"), "a lighthouse at dusk, 4:3, 2 pictures, safety off, with sound")
        val set = out.options.associate { it.name to it.value }
        assertEquals("4:3", set["aspect_ratio"]); assertEquals("2", set["num_outputs"]); assertEquals("true", set["disable_safety_checker"])
        assertEquals("Settings: Shape 4:3 · Number of pictures 2 · Safety checker off.", notes.first())
        assertTrue(notes.any { it == "Not on this model: Sound." })
    }
}
