package com.orangetiger.otstudio

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import android.app.Dialog
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidFlowTest {
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    // Buttons are matched by visible text, or by accessibility label for icon-only buttons such as Settings.
    private fun click(activity: MainActivity, text: String) { views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text || it.contentDescription?.toString() == text }.performClick() }

    @Suppress("DEPRECATION")
    @Test fun backFromSettingsOpenedByApiKeysReturnsToChat() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "API CALL")
        shadowOf(Looper.getMainLooper()).idle()
        val panel: Dialog = ShadowDialog.getLatestDialog()
        views(panel.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() == "API keys" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Done" })
        activity.onBackPressed()
        assertFalse(activity.isFinishing)
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any { it.contentDescription?.toString() == "Settings" })
        controller.pause().stop().destroy()
    }

    @Test fun settingsScreenSurvivesRotation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        click(controller.get(), "Video"); click(controller.get(), "Settings")
        controller.recreate()
        val buttons = views(controller.get().window.decorView).filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(buttons.contains("Done")); assertTrue(buttons.contains("Save video settings"))
        controller.pause().stop().destroy()
    }

    @Test fun settingsPersistAnEditedTextModelWithoutAKey() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Settings")
        val fields = views(activity.window.decorView).filterIsInstance<EditText>()
        fields.first { it.text.toString() == "mistralai/mistral-nemo" }.setText("example/custom-text")
        click(activity, "Save chat settings")
        val fresh = SettingsStore(activity)
        assertEquals("example/custom-text", fresh.get(Mode.CHAT).model)
        assertFalse(fresh.hasKey(Mode.CHAT))
        controller.pause().stop().destroy()
    }

    @Test fun chatSettingsSwitchToThisPhoneAndOpenTheModelManager() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Settings")
        click(activity, "On this phone")
        assertEquals(ChatEngine.LOCAL, LocalModelStore(activity).engine)
        click(activity, "Manage models")
        val labels = views(activity.window.decorView).filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(labels.contains("Download (2.4 GB)"))
        assertTrue(labels.contains("Add to the list"))
        // The model manager survives rotation, and Back returns to Settings.
        controller.recreate()
        assertTrue(views(controller.get().window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Add to the list" })
        controller.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(views(controller.get().window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Save chat settings" })
        // The main screen's setup card offers Models instead of API CALL for on-device chat.
        controller.get().onBackPressedDispatcher.onBackPressed()
        val main = views(controller.get().window.decorView).filterIsInstance<Button>().map { it.text.toString() }
        assertTrue(main.contains("Models")); assertFalse(main.contains("API CALL"))
        controller.pause().stop().destroy()
    }

    @Test fun memoryLinesPersistToggleEditAndDelete() {
        val db = History(RuntimeEnvironment.getApplication())
        val a = db.memory.add("Call the user Mark.")
        val b = db.memory.add("  Do not use em dashes in your responses. ")
        db.memory.setEnabled(a, false)
        db.memory.edit(b, "Never use em dashes.")
        db.close()
        val fresh = History(RuntimeEnvironment.getApplication())
        assertEquals(listOf("Call the user Mark." to false, "Never use em dashes." to true), fresh.memory.lines().map { it.text to it.enabled })
        fresh.memory.delete(a)
        assertEquals(1, fresh.memory.lines().size)
        assertThrows(UserError::class.java) { fresh.memory.add(" ") }
        fresh.close()
    }

    @Test fun chatWithHistoryOffStaysInMemoryAndMediaIsStillSaved() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val db = app.history
        val savedId = db.insertLocal("saved question", "test model")
        db.update(savedId, "succeeded", "saved answer")
        app.saveChatHistory = false
        assertTrue(db.list(Mode.CHAT).isEmpty())
        val tempId = db.insertLocal("private question", "test model")
        assertTrue(tempId < 0)
        db.update(tempId, "succeeded", "private answer")
        assertEquals(listOf("private question"), db.list(Mode.CHAT).map { it.prompt })
        assertEquals(listOf("private question", "private answer"), db.turns().map { it.content })
        // Media still goes to the database.
        val image = db.insert(Mode.IMAGE, "a photo", Rules.resolve(Mode.IMAGE, Rules::defaults) { "synthetic" })
        assertTrue(image > 0)
        // A new process (simulated by reopening the database) has no trace of the private turn.
        val reopened = History(app)
        assertFalse(reopened.list(Mode.CHAT).any { it.prompt == "private question" })
        reopened.close()
        app.saveChatHistory = true
        assertEquals(listOf("saved question"), db.list(Mode.CHAT).map { it.prompt })
        app.clearChat()
        assertTrue(db.list(Mode.CHAT).isEmpty())
        assertEquals(1, db.list(Mode.IMAGE).size)
    }

    @Test fun settingsOpenTheMemoryScreenAndAddAnInstruction() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Settings")
        click(activity, "Edit memory")
        assertTrue(views(activity.window.decorView).any { it is TextView && it.text.toString() == "Call the user Mark.\nDo not use em dashes in your responses." })
        click(activity, "Add instruction")
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        views(dialog.window!!.decorView).filterIsInstance<EditText>().first().setText("Answer in short paragraphs.")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(views(activity.window.decorView).any { it is TextView && it.text.toString() == "Answer in short paragraphs." })
        assertEquals("Answer in short paragraphs.", (activity.application as StudioApp).history.memory.lines().single().text)
        // The memory screen survives rotation, and Back returns to Settings.
        controller.recreate()
        assertTrue(views(controller.get().window.decorView).any { it is TextView && it.text.toString() == "Answer in short paragraphs." })
        controller.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(views(controller.get().window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Edit memory" })
        controller.pause().stop().destroy()
    }

    @Test fun aPlanShowsAJobCardAndNothingRunsWithoutGenerate() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val chatId = app.history.insertLocal("make me a square fox picture", "test model")
        app.history.update(chatId, "succeeded", "Here is a square fox.", plan = JobPlan("image", "Here is a square fox.", "a red fox in snow, soft light", shape = "square").toJson())
        val bad = app.history.insertLocal("do the thing", "test model")
        app.history.update(bad, "succeeded", "", plan = JobPlan("invalid", "The model did not return a plan.").toJson())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val texts = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Image, square, safety checker off"))
        assertTrue(texts.any { it.startsWith("Model: black-forest-labs/flux-schnell") && it.contains("Likely cost: less than 1 cent") })
        assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().any { it.text.toString() == "a red fox in snow, soft light" })
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Picture from my words" })
        // Without a Replicate key, Generate explains instead of sending anything.
        click(activity, "Generate")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, app.history.entry(chatId)!!.jobEntry)
        assertTrue(app.history.list(Mode.IMAGE).isEmpty())
        // Cancel keeps a record and sends nothing.
        ShadowDialog.getLatestDialog()?.dismiss()
        click(activity, "Cancel")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(-1L, app.history.entry(chatId)!!.jobEntry)
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Plan canceled. Nothing was sent." })
        // The own-words fallback turns the user's text into a card, still unpaid.
        click(activity, "Picture from my words")
        shadowOf(Looper.getMainLooper()).idle()
        val replaced = JobPlan.fromJson(app.history.entry(bad)!!.plan)!!
        assertEquals("image", replaced.action); assertEquals("do the thing", replaced.prompt)
        controller.pause().stop().destroy()
    }

    @Test fun theTavilyKeyIsCheckedEncryptedAndSourcesShow() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        assertTrue(app.settings.allowSearch)
        assertThrows(UserError::class.java) { app.settings.saveSearchKey("sk-or-wrong") }
        assertFalse(app.settings.hasSearchKey())
        val id = app.history.insertLocal("who painted the mona lisa", "test model")
        app.history.update(id, "succeeded", "Leonardo da Vinci [1].", sources = SearchResult.listToJson(listOf(SearchResult("Mona Lisa painter", "Wikipedia", "", listOf(SearchHit("Mona Lisa", "https://en.wikipedia.org/wiki/Mona_Lisa", "A portrait by Leonardo."))))))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val texts = views(controller.get().window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Searched Wikipedia: Mona Lisa painter"))
        assertTrue(texts.contains("Mona Lisa (en.wikipedia.org)"))
        assertTrue(texts.contains("Text from Wikipedia, available under CC BY-SA 4.0."))
        controller.pause().stop().destroy()
    }

    @Test fun repliesShowBasicMarkdown() {
        val styled = Markdown.styled("## Facts\n1. **Bold** and *soft* with `code`\n* item\n2 * 3 = 6") as android.text.Spanned
        assertEquals("Facts\n1. Bold and soft with code\n• item\n2 * 3 = 6", styled.toString())
        val styles = styled.getSpans(0, styled.length, android.text.style.StyleSpan::class.java).map { styled.subSequence(styled.getSpanStart(it), styled.getSpanEnd(it)).toString() to it.style }
        assertTrue(styles.contains("Facts" to android.graphics.Typeface.BOLD))
        assertTrue(styles.contains("Bold" to android.graphics.Typeface.BOLD))
        assertTrue(styles.contains("soft" to android.graphics.Typeface.ITALIC))
    }

    @Test fun aNewInstallAsksWhereChatRuns() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertFalse(LocalModelStore(activity).engineChosen)
        click(activity, "On this phone")
        assertEquals(ChatEngine.LOCAL, LocalModelStore(activity).engine)
        assertTrue(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Add to the list" })
        // Once chosen, the main screen no longer asks.
        activity.onBackPressedDispatcher.onBackPressed(); activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "OpenRouter, with your key" })
        controller.pause().stop().destroy()
    }

    @Test fun onDeviceChatWithoutAModelExplainsWhatToDo() {
        val context = RuntimeEnvironment.getApplication() as StudioApp
        context.local.engine = ChatEngine.LOCAL
        try { context.send(Mode.CHAT, "hello"); fail("Expected an explanation") }
        catch (e: UserError) { assertTrue(e.message!!.contains("Download a model first")) }
        assertTrue(context.history.list(Mode.CHAT).isEmpty())
        // Adding a custom link stores it once and refuses duplicates.
        val m = context.local.addCustom("https://huggingface.co/a/b/resolve/main/c.gguf")
        assertTrue(context.local.all().any { it.localName == m.localName })
        assertThrows(UserError::class.java) { context.local.addCustom("https://huggingface.co/a/b/blob/main/c.gguf") }
        context.local.removeCustom(m)
        assertEquals(LocalModelCatalog.entries.size, context.local.all().size)
    }

    @Test fun imageTextKeyCheckboxCannotSaveAcrossProviders() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Image")
        click(activity, "Settings")
        val checkbox = views(activity.window.decorView).filterIsInstance<CheckBox>().first { it.text == "Use text LLM API key" }
        checkbox.isChecked = true
        click(activity, "Save image settings")
        assertEquals(KeySource.OWN, SettingsStore(activity).get(Mode.IMAGE).keySource)
        assertFalse(views(activity.window.decorView).filterIsInstance<EditText>().last { (it.parent?.parent as? com.google.android.material.textfield.TextInputLayout)?.hint?.toString() != "SpicyAPI key" }.isEnabled)
        controller.pause().stop().destroy()
    }

    @Test fun videoKeyCheckboxesAreMutuallyExclusive() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        click(activity, "Video"); click(activity, "Settings")
        val checks = views(activity.window.decorView).filterIsInstance<CheckBox>()
        val text = checks.first { it.text == "Use text LLM API key" }
        val image = checks.first { it.text == "Use image API key" }
        assertTrue(image.isChecked); assertFalse(text.isChecked)
        text.isChecked = true
        assertFalse(image.isChecked); assertTrue(text.isChecked)
        text.isChecked = false
        assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().last().isEnabled)
        controller.pause().stop().destroy()
    }

    @Test fun sqliteHistorySurvivesReopenAndPreservesLiteralPrompts() {
        val context = RuntimeEnvironment.getApplication()
        var db = History(context)
        val literal = "'); DROP TABLE entries; -- synthetic test"
        val model = Rules.resolve(Mode.IMAGE, Rules::defaults) { "synthetic-only" }
        val id = db.insert(Mode.IMAGE, literal, model)
        db.update(id, "processing", remoteId = "persisted-job")
        db.close()
        db = History(context)
        db.recover()
        val entry = db.list(Mode.IMAGE).last()
        assertEquals(literal, entry.prompt); assertEquals("persisted-job", entry.remoteId); assertEquals("processing", entry.state)
        db.close()
    }

    @Test fun sqliteRecoveryMarksUnacknowledgedSubmissionUncertain() {
        val db = History(RuntimeEnvironment.getApplication())
        val model = Rules.resolve(Mode.VIDEO, Rules::defaults) { "synthetic-only" }
        val id = db.insert(Mode.VIDEO, "synthetic video prompt", model)
        db.recover()
        val entry = db.list(Mode.VIDEO).first { it.id == id }
        assertEquals("uncertain", entry.state)
        assertEquals("", entry.remoteId)
        assertTrue(entry.response.contains("dashboard"))
        db.close()
    }

    @Test fun profilesRememberCustomModelsAndExplicitFalseAcrossReopen() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsStore(context)
        val base = Rules.defaults(Mode.IMAGE)
        val custom = settings.forModel(base, " test/custom ").copy(schema = InputSchema(listOf(InputDefinition("fast", InputKind.BOOLEAN, "true"))), options = listOf(InputValue("fast", InputKind.BOOLEAN, "false")))
        settings.save(custom, null)
        val fresh = SettingsStore(context)
        assertEquals("test/custom", fresh.get(Mode.IMAGE).model)
        assertEquals("false", fresh.get(Mode.IMAGE).options.single().value)
        assertTrue(fresh.models(Mode.IMAGE, base.baseUrl).any { it.model == "test/custom" })
        assertNull(fresh.forModel(base.copy(baseUrl = "https://other.example/v1"), "test/custom").schema)
    }

    @Test fun allOutputLinksAndCompletionAreAtomic() {
        val db = History(RuntimeEnvironment.getApplication())
        val model = Rules.resolve(Mode.IMAGE, Rules::defaults) { "synthetic" }
        val id = db.insert(Mode.IMAGE, "Test", model)
        val good = "https://replicate.delivery/one.png"
        try { db.completePrediction(id, Prediction("job1", "succeeded", listOf(good, "https://evil.test/two.png"), false)); fail("Expected validation error") } catch (_: UserError) { }
        assertEquals("submitting", db.list(Mode.IMAGE).first { it.id == id }.state)
        assertTrue(db.outputs(listOf(id)).isEmpty())
        db.completePrediction(id, Prediction("job1", "succeeded", listOf(good, "https://replicate.delivery/two.png"), false))
        db.close()
        val fresh = History(RuntimeEnvironment.getApplication())
        fresh.recover()
        assertEquals("download", fresh.list(Mode.IMAGE).first { it.id == id }.state)
        assertEquals(2, fresh.outputs(listOf(id))[id]!!.size)
        fresh.close()
    }

    @Test fun loraLibraryPersistsAndUpdatesSameReferenceWithoutDuplicates() {
        val context = RuntimeEnvironment.getApplication()
        val store = LoraStore(context)
        store.save(SavedLora("Style", "owner/style", "TOK", 1.0)); store.close()
        val fresh = LoraStore(context)
        assertEquals("TOK", fresh.list().single().trigger)
        fresh.save(SavedLora("Updated", "owner/style", "STYLE", 0.8))
        assertEquals(1, fresh.list().size)
        assertEquals("STYLE", fresh.list().single().trigger)
        fresh.close()
    }

    @Test fun versionOneHistoryMigrationKeepsExistingOutputAndFile() {
        val context = RuntimeEnvironment.getApplication()
        // The app object opens studio.db at startup; close it so the file can really be replaced by a 0.1 database.
        (context as StudioApp).history.close()
        context.deleteDatabase("studio.db")
        // Create the 0.1 database through a version 1 helper, the way the old app stored its schema version.
        val legacy = object : SQLiteOpenHelper(context, "studio.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, mode TEXT NOT NULL, prompt TEXT NOT NULL, response TEXT NOT NULL, state TEXT NOT NULL, model TEXT NOT NULL, base_url TEXT NOT NULL, credential_mode TEXT NOT NULL, remote_id TEXT NOT NULL, output_url TEXT NOT NULL, local_file TEXT NOT NULL, created INTEGER NOT NULL)")
                db.execSQL("INSERT INTO entries VALUES(1,'IMAGE','Test','Ready','succeeded','black-forest-labs/flux-schnell','https://api.replicate.com/v1','IMAGE','job1','https://replicate.delivery/image.png','existing.png',1)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        legacy.writableDatabase; legacy.close()
        val upgraded = History(context)
        assertEquals("existing.png", upgraded.list(Mode.IMAGE).single().localFile)
        assertEquals("existing.png", upgraded.outputs(listOf(1L))[1L]!!.single().localFile)
        upgraded.close()
    }

    @Test fun aPhotoFromThePhoneBecomesAPictureTheChatCanUse() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val id = app.importPhoto(byteArrayOf(1, 2, 3), "image/jpeg")
        val e = app.history.entry(id)!!
        assertEquals(Mode.IMAGE, e.mode); assertEquals("succeeded", e.state); assertEquals(History.PHONE_BASE, e.baseUrl)
        assertTrue(e.prompt, e.prompt.matches(Regex("Your photo, added [A-Z][a-z]{2} \\d{1,2}, \\d{1,2}:\\d{2} [AP]M")))
        val file = java.io.File(java.io.File(app.filesDir, "media"), e.localFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(e.localFile, app.history.outputs(listOf(id))[id]!!.single().localFile)
        // The chat sees it as the newest picture, and an edit or a video reads its bytes.
        assertEquals(id, app.imageRefs().first().id)
        assertArrayEquals(byteArrayOf(1, 2, 3), app.mediaBytes(e).first)
        assertThrows(UserError::class.java) { app.importPhoto(byteArrayOf(1), "image/gif") }
        // When the chat model returns no plan, "my words" can still edit the chosen photo, and only a chosen one.
        val chat = app.history.insertLocal("make it black and white", "test model")
        app.history.update(chat, "succeeded", "No plan.")
        app.selectedImage = null
        assertThrows(UserError::class.java) { app.planFromWords(app.history.entry(chat)!!, "edit") }
        app.selectedImage = id
        app.planFromWords(app.history.entry(chat)!!, "edit")
        assertEquals(JobPlan("edit", "Your own words, as you typed them.", "make it black and white", id), JobPlan.fromJson(app.history.entry(chat)!!.plan))
        app.selectedImage = null
    }

    @Test fun theMessageBarHasAPhotoButtonInEveryMode() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        listOf("Chat", "Image", "Video").forEach { tab ->
            views(activity.window.decorView).filterIsInstance<android.widget.RadioButton>().firstOrNull { it.text.toString() == tab }?.performClick()
                ?: views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == tab }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(tab, views(activity.window.decorView).any { it.contentDescription?.toString() == "Add a photo from this phone" })
        }
    }

    @Test fun theSafetyCheckerIsOneSwitchThatReadsTheRightWayRound() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity, "Image"); shadowOf(Looper.getMainLooper()).idle()
        click(activity, "API CALL"); shadowOf(Looper.getMainLooper()).idle()
        val panel: Dialog = ShadowDialog.getLatestDialog()
        val all = views(panel.window!!.decorView)
        assertTrue(all.filterIsInstance<TextView>().any { it.text.toString() == "Safety checker" })
        assertFalse(all.filterIsInstance<CheckBox>().any { it.contentDescription?.toString()?.startsWith("Override") == true })
        val toggle = all.filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>().first { it.text.toString() == "Safety checker on" }
        // The model's default keeps the checker on, and nothing is sent until the user changes it.
        assertTrue(toggle.isChecked)
        toggle.performClick()
        assertFalse(toggle.isChecked)
        all.filterIsInstance<Button>().first { it.text.toString() == "Apply" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("true", app.settings.get(Mode.IMAGE).options.firstOrNull { it.name == "disable_safety_checker" }?.value)
    }

    @Test fun theSimpleViewHidesRareSettingsButKeepsTheirValues() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val dev = app.settings.forModel(app.settings.get(Mode.IMAGE), "black-forest-labs/flux-dev")
        app.settings.save(dev.copy(options = dev.options.filter { it.name != "seed" } + InputValue("seed", InputKind.INTEGER, "42")), null)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity, "Image"); shadowOf(Looper.getMainLooper()).idle()
        click(activity, "API CALL"); shadowOf(Looper.getMainLooper()).idle()
        val panel: Dialog = ShadowDialog.getLatestDialog()
        var all = views(panel.window!!.decorView)
        val titles = all.filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(titles.contains("Shape")); assertTrue(titles.contains("Picture")); assertFalse(titles.contains("Seed"))
        assertTrue(all.filterIsInstance<android.widget.AutoCompleteTextView>().any { it.text.toString() == "Detailed picture (FLUX dev), 2.5 cents" })
        // Applying the simple view keeps the hidden seed.
        all.filterIsInstance<Button>().first { it.text.toString() == "Apply" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("42", app.settings.get(Mode.IMAGE).options.firstOrNull { it.name == "seed" }?.value)
        click(activity, "API CALL"); shadowOf(Looper.getMainLooper()).idle()
        val again: Dialog = ShadowDialog.getLatestDialog()
        views(again.window!!.decorView).filterIsInstance<Button>().first { it.text.toString().startsWith("Show all settings") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        all = views(again.window!!.decorView)
        assertTrue(all.filterIsInstance<TextView>().any { it.text.toString() == "Seed" })
        all.filterIsInstance<Button>().first { it.text.toString() == "Show fewer settings" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun seedreamShowsWidthAndHeightOnlyForACustomSize() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val seed = app.settings.forModel(app.settings.get(Mode.IMAGE), "bytedance/seedream-4.5")
        app.settings.save(seed.copy(options = seed.options.filter { it.name != "size" }), null)
        fun titles(): List<String> {
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            click(activity, "Image"); shadowOf(Looper.getMainLooper()).idle()
            click(activity, "API CALL"); shadowOf(Looper.getMainLooper()).idle()
            val panel: Dialog = ShadowDialog.getLatestDialog()
            // The panel remembers "Show all settings" between openings.
            views(panel.window!!.decorView).filterIsInstance<Button>().firstOrNull { it.text.toString().startsWith("Show all settings") }?.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            return views(panel.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        }
        val standard = titles()
        assertTrue(standard.contains("Shape")); assertFalse(standard.contains("Width")); assertFalse(standard.contains("Height"))
        app.settings.save(app.settings.get(Mode.IMAGE).let { s -> s.copy(options = s.options.filter { it.name !in listOf("size", "width", "height", "aspect_ratio") } + InputValue("size", InputKind.TEXT, "custom") + InputValue("width", InputKind.INTEGER, "2048") + InputValue("height", InputKind.INTEGER, "1536")) }, null)
        val custom = titles()
        assertTrue(custom.contains("Width")); assertTrue(custom.contains("Height")); assertFalse(custom.contains("Shape"))
    }

    @Test fun theChatSaysWhetherItsModelIsPrivateOrOnline() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "OpenRouter: your messages go online" })
    }

    @Test fun aSavedSeedreamProfileStillSendsItsPictureAsAList() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val seed = app.settings.forModel(app.settings.get(Mode.IMAGE), "bytedance/seedream-4.5")
        app.settings.remember(seed)
        val loaded = app.settings.forModel(app.settings.get(Mode.IMAGE), "bytedance/seedream-4.5")
        assertTrue(loaded.schema!!.fields.first { it.name == "image_input" }.list)
        val body = org.json.JSONObject()
        SchemaCodec.append(body, loaded.schema!!, listOf(InputValue("image_input", InputKind.TEXT, "https://api.replicate.com/v1/files/x")))
        assertTrue(body.get("image_input") is org.json.JSONArray)
    }

    @Test fun anAttachedPictureShowsOnTheImageScreenAndCanBeRemoved() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val dev = app.settings.forModel(app.settings.get(Mode.IMAGE), "black-forest-labs/flux-dev")
        app.settings.save(dev.copy(options = dev.options + InputValue("image", InputKind.TEXT, "https://api.replicate.com/v1/files/abc")), null)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity, "Image"); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Picture attached. Every request uses it." })
        click(activity, "Remove"); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(app.attachedPictures(Mode.IMAGE).isEmpty())
        assertFalse(views(activity.window.decorView).filterIsInstance<Button>().any { it.text.toString() == "Remove" })
    }

    @Test fun imageHistoryCanBeClearedOneCardOrAll() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val keep = app.importPhoto(byteArrayOf(1, 2), "image/jpeg")
        val gone = app.importPhoto(byteArrayOf(3, 4), "image/png")
        val media = java.io.File(app.filesDir, "media")
        val goneFile = java.io.File(media, app.history.entry(gone)!!.localFile)
        val video = app.history.insert(Mode.VIDEO, "a clip", Rules.resolve(Mode.VIDEO, Rules::defaults) { "synthetic" })
        app.history.update(video, "failed", "No.")
        app.selectedImage = gone
        app.clearMedia(Mode.IMAGE, listOf(gone))
        assertNull(app.history.entry(gone)); assertFalse(goneFile.exists()); assertNotNull(app.history.entry(keep)); assertNull(app.selectedImage)
        app.clearMedia(Mode.IMAGE)
        assertTrue(app.history.list(Mode.IMAGE).isEmpty())
        // Clearing images leaves videos alone.
        assertEquals(1, app.history.list(Mode.VIDEO).size)
    }

    @Test fun clearingAPictureFieldInAllSettingsRemovesIt() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val wan = app.settings.forModel(app.settings.get(Mode.VIDEO), "wan-video/wan-2.2-i2v-fast")
        app.settings.save(wan.copy(options = wan.options.filter { it.name !in listOf("image", "last_image") } + InputValue("image", InputKind.TEXT, "https://api.replicate.com/v1/files/first") + InputValue("last_image", InputKind.TEXT, "https://api.replicate.com/v1/files/last")), null)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity, "Video"); shadowOf(Looper.getMainLooper()).idle()
        click(activity, "API CALL"); shadowOf(Looper.getMainLooper()).idle()
        val panel: Dialog = ShadowDialog.getLatestDialog()
        views(panel.window!!.decorView).filterIsInstance<Button>().first { it.text.toString().startsWith("Show all settings") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val field = views(panel.window!!.decorView).filterIsInstance<EditText>().first { it.text.toString() == "https://api.replicate.com/v1/files/last" }
        field.setText("")
        views(panel.window!!.decorView).filterIsInstance<Button>().first { it.text.toString() == "Apply" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val saved = app.settings.get(Mode.VIDEO).options.map { it.name }
        assertFalse(saved.toString(), "last_image" in saved); assertTrue("image" in saved)
        // On the main screen, the starting picture has its own Remove.
        app.settings.save(app.settings.get(Mode.VIDEO).let { it.copy(options = it.options + InputValue("last_image", InputKind.TEXT, "https://api.replicate.com/v1/files/last")) }, null)
        app.removePictures(Mode.VIDEO, listOf("last_image"))
        assertEquals(listOf("image"), app.attachedPictures(Mode.VIDEO))
        assertEquals("Starting picture", InputLabels.title("image", Mode.VIDEO)); assertEquals("Ending picture", InputLabels.title("last_image", Mode.VIDEO))
    }

    @Test fun repeatBringsBackTheModelInputsPicturesAndWords() {
        val app = RuntimeEnvironment.getApplication() as StudioApp
        val wan = app.settings.forModel(app.settings.get(Mode.VIDEO), "wan-video/wan-2.2-i2v-fast")
        val used = wan.copy(options = wan.options.filter { it.name !in listOf("image", "last_image", "resolution") } + listOf(InputValue("image", InputKind.TEXT, "https://api.replicate.com/v1/files/first"),
            InputValue("last_image", InputKind.TEXT, "https://api.replicate.com/v1/files/last"), InputValue("resolution", InputKind.TEXT, "720p")))
        val id = app.history.insert(Mode.VIDEO, "she dances on the beach", ResolvedModel(used, Mode.VIDEO, "synthetic"))
        app.history.update(id, "failed", "No.")
        // The Video settings move on to something else, then Repeat brings that job's back.
        app.settings.save(app.settings.forModel(app.settings.get(Mode.VIDEO), "wan-video/wan-2.7-t2v"), null)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        click(activity, "Video"); shadowOf(Looper.getMainLooper()).idle()
        click(activity, "Repeat"); shadowOf(Looper.getMainLooper()).idle()
        val s = app.settings.get(Mode.VIDEO)
        assertEquals("wan-video/wan-2.2-i2v-fast", s.model)
        val values = s.options.associate { it.name to it.value }
        assertEquals("https://api.replicate.com/v1/files/first", values["image"]); assertEquals("https://api.replicate.com/v1/files/last", values["last_image"]); assertEquals("720p", values["resolution"])
        assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().any { it.text.toString() == "she dances on the beach" })
    }

    /** Version 1.9.0 upgrades the LoRA library: every saved FLUX dev LoRA stays, and Qwen LoRAs and trainings can be kept. */
    @Test fun theLoraLibraryUpgradeKeepsSavedLoras() {
        val context = RuntimeEnvironment.getApplication()
        // The app opened its own library at start; close it so this test can lay down a version 1 file.
        (context as StudioApp).loras.close()
        context.deleteDatabase("lora-library.db")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("lora-library.db").apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE loras (reference TEXT PRIMARY KEY, name TEXT NOT NULL, trigger_word TEXT NOT NULL, scale REAL NOT NULL CHECK(scale BETWEEN -1 AND 3), base_model TEXT NOT NULL CHECK(base_model = 'flux-dev'))")
            db.execSQL("INSERT INTO loras VALUES ('anna/lora-anna/${"a".repeat(64)}', 'Anna', 'ANNAFACE', 0.9, 'flux-dev')")
            db.version = 1
        }
        val store = LoraStore(context)
        assertEquals(listOf(SavedLora("Anna", "anna/lora-anna/${"a".repeat(64)}", "ANNAFACE", 0.9, "flux-dev")), store.list())
        store.save(SavedLora("Pao", "https://replicate.delivery/pao.tar", "a person named Pao", 1.0, "qwen-image"))
        val t = LoraTrainingRecord("t1", "Pao", "a person named Pao", "qwen-image", 1000, 64, LoraBases.QWEN.model, "c".repeat(64), "anna/lora-qwen-pao", "sending", created = 5)
        store.saveTraining(t)
        store.recoverTrainings()
        assertEquals(2, store.list().size)
        assertEquals("uncertain", store.trainings().single().state)
        store.close()
    }
}
