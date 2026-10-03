package com.orangetiger.otstudio

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
        fields[1].setText("example/custom-text")
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
        assertFalse(views(activity.window.decorView).filterIsInstance<EditText>().last().isEnabled)
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
}
