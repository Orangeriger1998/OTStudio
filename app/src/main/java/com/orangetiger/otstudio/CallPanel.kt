package com.orangetiger.otstudio

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/** API CALL: a full-screen panel for choosing the model and its inputs. Nothing is sent to a model from here except Suggest setup. */
class CallPanel(private val activity: MainActivity, private val app: StudioApp, initial: Mode, private val prompt: String,
                private val applied: (Mode) -> Unit, private val keys: (Mode) -> Unit, private val closed: () -> Unit) {
    private var mode = initial
    private var draft = app.settings.get(initial)
    private val features = linkedSetOf<String>()
    private var requiredResolution: String? = null
    private var filtersOpen = false
    private var notice = ""
    private var waiting = false
    private var handedOff = false
    private var scrollY = 0
    private val readers = ArrayList<() -> InputValue?>()
    private val edits = linkedMapOf<String, ModelSettings>()
    private val dialog = Dialog(activity, R.style.Theme_OTStudio_FullScreenDialog)
    private val ui = Ui(activity)
    private var scroll: ScrollView? = null

    private fun dp(v: Int) = ui.dp(v)
    private fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            MaterialAlertDialogBuilder(activity).setMessage((e as? UserError)?.message ?: "Could not save settings. Check device storage.").setPositiveButton("OK", null).show()
        }
    }
    /** Inputs shown in this render. Inputs the simple view hides keep their saved values. */
    private val shown = HashSet<String>()
    private fun read(): ModelSettings = draft.copy(options = readers.mapNotNull { it() } + draft.options.filter { it.name !in shown }).also(Rules::validate)
    private val prefs by lazy { activity.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    /** The simple view shows the few settings most people change; "Show all settings" shows every input and tool. */
    private var allSettings: Boolean
        get() = prefs.getBoolean("api_call_all", false)
        set(v) { prefs.edit().putBoolean("api_call_all", v).apply() }
    private fun identity(s: ModelSettings) = "${s.mode}|${Rules.origin(s.baseUrl)}|${s.model}"
    private fun remember() { draft = read(); edits[identity(draft)] = draft }
    private fun select(model: String): ModelSettings {
        val loaded = app.settings.forModel(draft, model)
        return edits[identity(loaded)] ?: loaded
    }
    private fun candidates(): List<ModelPreset> = app.settings.models(mode, draft.baseUrl).filter { ModelOptions.supports(it, features, requiredResolution) }
    private fun gone() = !dialog.isShowing || activity.isFinishing || activity.isDestroyed

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    fun show() {
        // Apply and API keys navigate on their own; only a plain close returns to the chat screen.
        dialog.setOnDismissListener { if (!handedOff) closed() }
        // Android 15 draws full-screen dialogs edge to edge, so the panel takes that layout on every version and pads itself.
        dialog.window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        render(); dialog.show(); dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun render() {
        scroll?.let { scrollY = it.scrollY }
        readers.clear(); shown.clear()
        val root = ui.column().apply { setPadding(dp(16), dp(8), dp(16), dp(10)); setBackgroundColor(ui.color(R.color.ot_black)) }
        // The window is edge to edge and does not resize, so the root pads for the system bars and the keyboard exactly once.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right, dp(10) + maxOf(bars.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        val bar = ui.row().apply { setPadding(0, 0, 0, dp(6)) }
        bar.addView(ui.iconButton(R.drawable.ic_close, "Close") { dialog.dismiss() })
        val titles = ui.column()
        titles.addView(ui.text("API CALL", Ui.Type.TITLE))
        titles.addView(ui.text(if (allSettings) "Every setting this model offers." else "The model and the few settings most people change.", Ui.Type.SMALL, ui.muted))
        bar.addView(titles, ui.weight())
        root.addView(bar)
        if (waiting) root.addView(ui.progress(), ui.matchWrap(6))
        if (notice.isNotEmpty()) ui.card(root, 12, R.color.ot_blue_container, 8).addView(ui.text(notice, Ui.Type.BODY, ui.color(R.color.ot_on_blue_container)).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        val sv = ScrollView(activity).also { scroll = it }
        val content = ui.column()
        sv.addView(content)
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val create = ui.card(content)
        ui.sectionTitle(create, "Create")
        ui.segmented(create, listOf("Chat" to null, "Image" to null, "Video" to null), mode.ordinal) { i ->
            safely { remember(); mode = Mode.entries[i]; val loaded = app.settings.get(mode); draft = edits[identity(loaded)] ?: loaded; features.clear(); requiredResolution = null; scrollY = 0; render() }
        }.isEnabled = !waiting

        val modelCard = ui.card(content)
        ui.sectionTitle(modelCard, "Model", if (allSettings) draft.baseUrl else null)
        val all = app.settings.models(mode, draft.baseUrl)
        val matches = candidates()
        val filtered = features.isNotEmpty() || requiredResolution != null
        if (mode != Mode.CHAT && allSettings) {
            modelCard.addView(ui.textButton(if (filtersOpen) "Hide model finder" else "Find a model by features", R.drawable.ic_tune) { safely { remember(); filtersOpen = !filtersOpen; render() } }.apply { isEnabled = !waiting })
            if (filtersOpen) {
                modelCard.addView(ui.text("Resolution", Ui.Type.LABEL, ui.muted))
                val res = ChipGroup(activity).apply { isSingleSelection = true }
                listOf("Any", "480p", "720p", "1080p").forEach { r -> res.addView(ui.filterChip(r, (requiredResolution ?: "Any") == r) { c -> if (c) safely { remember(); requiredResolution = r.takeUnless { it == "Any" }; render() } }) }
                modelCard.addView(res)
                modelCard.addView(ui.text("Features", Ui.Type.LABEL, ui.muted).apply { setPadding(0, dp(8), 0, 0) })
                val chips = ChipGroup(activity)
                ModelOptions.featureNames.forEach { name -> chips.addView(ui.filterChip(name, name in features) { c -> safely { remember(); if (c) features.add(name) else features.remove(name); render() } }) }
                modelCard.addView(chips, ui.matchWrap(8))
            }
        }
        val choices = if (filtered) listOf("Choose matching model") + matches.map { it.model } else all.map { it.model }
        val selection = if (draft.model in choices) draft.model else if (filtered) "Choose matching model" else draft.model
        val shownChoices = if (!filtered && draft.model !in choices) choices + draft.model else choices
        // The simple view names each model by what it is for; the full view shows provider IDs.
        val label = { id: String -> if (allSettings || id == "Choose matching model") id else ModelNames.title(id) }
        val labels = shownChoices.map(label)
        ui.dropdown(modelCard, if (filtered) "Matching models (${matches.size})" else if (allSettings) "Saved models" else "Model", labels, label(selection)) { picked ->
            val model = shownChoices[labels.indexOf(picked)]
            if (model != "Choose matching model" && model != draft.model) safely { remember(); draft = select(model); notice = ""; render() }
        }.isEnabled = !waiting
        if (!allSettings) modelCard.addView(ui.text(draft.model, Ui.Type.CAPTION, ui.muted).apply { setPadding(dp(4), dp(2), 0, 0) })
        if (filtered && matches.none { it.model == draft.model }) modelCard.addView(ui.text("The current model does not meet every selected requirement. Choose a matching model before applying.", Ui.Type.SMALL, ui.color(R.color.ot_error)))
        if (allSettings) {
        val addRow = ui.row()
        val addHolder = ui.column()
        val custom = ui.field(addHolder, "Add a model (owner/model)", "")
        (custom.parent?.parent as? View)?.let { (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 0 }
        addRow.addView(addHolder, ui.weight())
        addRow.addView(ui.tonal("Add") {
            safely { remember(); draft = select(custom.text.toString().trim()); app.settings.remember(draft); features.clear(); requiredResolution = null; notice = "Model added to this provider's list."; render() }
        }.apply { isEnabled = !waiting; contentDescription = "Use and remember typed model"; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } })
        modelCard.addView(addRow)
        }
        val fixed = ModelCatalog.find(mode, draft.model)?.fixedResolution
        if (fixed != null) { ui.gap(modelCard, 8); ui.dropdown(modelCard, "Resolution selects the WAN 2.1 model", listOf("480p", "720p"), fixed) { r -> if (r != fixed) safely { remember(); draft = select(ModelOptions.routedModel(draft.model, r)); render() } } }

        val schema = draft.schema
        if (schema == null) ui.card(content).addView(ui.text("Load this model's inputs before generation. No modifier names are guessed.", Ui.Type.BODY))
        else {
            if (mode == Mode.IMAGE && draft.model == "black-forest-labs/flux-dev-lora") {
                val loraCard = ui.card(content)
                ui.sectionTitle(loraCard, "Saved FLUX dev LoRAs", "Import the training script's .otstudio-lora.json file using the LoRA library. FLUX LoRAs cannot be used as WAN video LoRAs.")
                val library = app.loras.list()
                val labels = listOf("Choose saved LoRA") + library.mapIndexed { i, it -> "${i + 1}. ${it.name} | trigger: ${it.trigger.ifBlank { "none" }}" }
                ui.dropdown(loraCard, "LoRA", labels, labels.first()) { selected ->
                    val index = labels.indexOf(selected) - 1
                    if (index >= 0) safely {
                        remember(); val lora = library[index]
                        draft = draft.copy(options = draft.options.filter { it.name !in listOf("lora_weights", "lora_scale") } + listOf(InputValue("lora_weights", InputKind.TEXT, lora.reference), InputValue("lora_scale", InputKind.NUMBER, lora.scale.toString())))
                        notice = "LoRA selected: ${lora.name}. Include ${lora.trigger.ifBlank { "your description" }} in your image prompt. Apply to save."; render()
                    }
                }
            }
            content.addView(ui.text("Inputs", Ui.Type.SUBTITLE).apply { setPadding(dp(4), dp(4), 0, 0) })
            content.addView(ui.text("Each setting starts at the model's default. Change only what you need; Use default puts a setting back.", Ui.Type.SMALL, ui.muted).apply { setPadding(dp(4), 0, 0, dp(8)) })
            val visible = schema.fields.filter { d -> allSettings || d.required || photoField(d) || d.name in ModelNames.simple }
            visible.forEach { definition -> shown.add(definition.name); option(content, definition) }
            val hidden = schema.fields.size - visible.size
            content.addView(ui.outlined(if (allSettings) "Show fewer settings" else if (hidden > 0) "Show all settings ($hidden more)" else "Show all settings") {
                safely { remember(); allSettings = !allSettings; render() }
            }.apply { isEnabled = !waiting }, ui.matchWrap(8))
            if (schema.unsupported.isNotEmpty()) content.addView(ui.text("Inputs needing another editor: ${schema.unsupported.joinToString()}. Required unsupported inputs block generation.", Ui.Type.SMALL, ui.muted))
            if (mode == Mode.VIDEO && schema.fields.none { it.name in listOf("frames_per_second", "frame_per_second", "fps") }) content.addView(ui.text("This model does not expose frame-rate control. Use the Frame rate filter to find one that does.", Ui.Type.SMALL, ui.muted))
        }

        val tools = ui.card(content)
        if (!allSettings) tools.visibility = View.GONE
        ui.sectionTitle(tools, "Tools")
        if (mode != Mode.CHAT) {
            tools.addView(ui.outlined("Refresh model inputs", R.drawable.ic_refresh) {
                safely {
                    remember(); waiting = true; notice = "Reading provider metadata. No generation is submitted."; render()
                    app.loadInputs(draft) { updated, error ->
                        if (gone()) return@loadInputs
                        waiting = false
                        if (updated != null) {
                            val retained = draft.options.filter { v -> try { ModelOptions.validate(updated, listOf(v)); true } catch (_: UserError) { false } }
                            draft = draft.copy(schema = updated, options = retained)
                            notice = "Inputs refreshed. Removed ${readRemovedCount(schema, updated)} unavailable input definitions. Review and apply to save."
                        } else notice = error ?: "Could not load inputs."
                        render()
                    }
                }
            }.apply { isEnabled = !waiting }, ui.matchWrap(4))
            tools.addView(ui.tonal("Suggest setup with text LLM", R.drawable.ic_auto_awesome) {
                safely {
                    remember(); Rules.prompt(prompt); val offered = candidates()
                    if (offered.isEmpty()) throw UserError("No model meets these filters. Change the requirements or add a model and load its inputs.")
                    waiting = true; notice = "Asking your text LLM. This uses its normal API pricing; no media is generated."; render()
                    app.suggestSetup(prompt, offered, (features + listOfNotNull(requiredResolution)).joinToString()) { suggestion, error ->
                        if (gone()) return@suggestSetup
                        waiting = false; notice = error.orEmpty(); render()
                        if (suggestion != null) MaterialAlertDialogBuilder(activity).setTitle("Review suggested setup").setMessage("${suggestion.model}\n\n${ModelOptions.summary(suggestion.model, suggestion.options)}\n\n${suggestion.reason}\n\nNo media has been generated.")
                            .setPositiveButton("Use in panel") { _, _ -> safely { draft = app.settings.forModel(draft, suggestion.model).copy(options = suggestion.options); notice = "Suggestion loaded. Fill any required media inputs, review settings, then Apply."; render() } }.setNegativeButton("Keep current", null).show()
                    }
                }
            }.apply { isEnabled = !waiting }, ui.matchWrap(4))
            tools.addView(ui.text("Suggest setup sends your current prompt and matching model capabilities to your text LLM. Only Apply changes your selected setup; Generate is a separate action.", Ui.Type.SMALL, ui.muted))
        } else tools.addView(ui.text("These are common Chat Completions controls. Individual providers and models may restrict support.", Ui.Type.SMALL, ui.muted))
        tools.addView(ui.textButton("Reset this model's overrides") { draft = draft.copy(options = ModelOptions.initial(mode, draft.model, draft.schema)); notice = "Default inputs restored in the panel. Apply to save."; render() }.apply { isEnabled = !waiting })

        val actions = ui.row().apply { setPadding(0, dp(8), 0, 0) }
        actions.addView(ui.textButton("API keys", R.drawable.ic_key) { safely { remember(); handedOff = true; dialog.dismiss(); keys(mode) } }.apply { isEnabled = !waiting })
        // Zero-height spacer: a plain View with wrap height would claim the whole panel and hide the inputs.
        actions.addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
        actions.addView(ui.outlined("Close") { dialog.dismiss() }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) } })
        actions.addView(ui.filled("Apply") {
            safely {
                if ((features.isNotEmpty() || requiredResolution != null) && candidates().none { it.model == draft.model }) throw UserError("Select a model that meets the feature filters first.")
                remember(); edits.values.forEach(app.settings::remember); app.settings.save(draft, null); handedOff = true; dialog.dismiss(); applied(mode)
            }
        }.apply { isEnabled = !waiting })
        root.addView(actions)
        dialog.setContentView(root)
        ViewCompat.requestApplyInsets(root)
        // Keep the keyboard closed until the user taps a field.
        root.isFocusableInTouchMode = true; root.requestFocus()
        dialog.setCancelable(!waiting)
        sv.post { sv.scrollTo(0, scrollY) }
    }

    private fun readRemovedCount(old: InputSchema?, current: InputSchema): Int = old?.fields?.count { oldField -> current.fields.none { it.name == oldField.name } } ?: 0

    private fun photoField(d: InputDefinition) = d.uri && (d.name.contains("image") || d.name.contains("frame"))

    /**
     * One control per setting. It shows what will be sent: the model's default until the user changes it.
     * A changed setting is sent; Use default removes it again. Required settings are always sent.
     */
    private fun option(parent: LinearLayout, d: InputDefinition) {
        val stored = draft.options.firstOrNull { it.name == d.name }
        val card = ui.card(parent, padding = 12, bottom = 8)
        val head = ui.row()
        head.addView(ui.text(InputLabels.title(d.name) + if (d.required) " (required)" else "", Ui.Type.SUBTITLE), ui.weight())
        if (stored != null && !d.required && !(photoField(d) && !allSettings)) head.addView(ui.textButton("Use default") {
            safely { remember(); draft = draft.copy(options = draft.options.filter { it.name != d.name }); render() }
        }.apply { isEnabled = !waiting; contentDescription = "Use the model's default for ${InputLabels.title(d.name)}" })
        card.addView(head)
        card.addView(ui.text(d.name, Ui.Type.CAPTION, ui.muted))
        val bounds = if (d.minimum != null || d.maximum != null) "From ${d.minimum ?: "any"} to ${d.maximum ?: "any"}. " else ""
        val inverted = InputLabels.invertedSafety(d)
        val help = if (inverted) "Off lets the model make anything Replicate's usage policy allows. Model default: ${if (d.defaultValue == "true") "off" else "on"}."
            else (bounds + d.description).trim()
        val readValue: () -> InputValue?
        if (d.choices.isNotEmpty()) {
            val default = InputLabels.defaultChoice(d)
            val items = (if (d.required) emptyList() else listOf(default)) + d.choices
            val box: MaterialAutoCompleteTextView = ui.dropdown(card, "Choose", items, stored?.value ?: if (d.required) (d.defaultValue ?: d.choices.first()) else default)
            box.isEnabled = !waiting
            readValue = { box.text.toString().takeIf { it != default }?.let { ModelOptions.value(d, it) } }
        } else if (d.kind == InputKind.BOOLEAN) {
            // The switch shows the effective state. For the safety setting it shows the checker itself, so On means on.
            val sent = (stored?.value ?: d.defaultValue ?: "false") == "true"
            var changed = stored != null
            val toggle = ui.switch(if (inverted) "Safety checker on" else "On", if (inverted) !sent else sent).apply { isEnabled = !waiting }
            toggle.setOnCheckedChangeListener { _, _ -> changed = true }
            card.addView(toggle)
            readValue = { if (changed || d.required) ModelOptions.value(d, (if (inverted) !toggle.isChecked else toggle.isChecked).toString()) else null }
        } else if (photoField(d) && !allSettings) {
            // The simple view shows a picture input as attached or not, never as a web address.
            card.addView(ui.text(if (stored != null) "A picture is attached. It goes with every request on this model until you remove it." else "No picture attached.", Ui.Type.BODY).apply { setPadding(0, dp(4), 0, 0) })
            if (stored != null) card.addView(ui.textButton("Remove the picture") {
                safely { remember(); draft = draft.copy(options = draft.options.filter { it.name != d.name }); render() }
            }.apply { isEnabled = !waiting }, LinearLayout.LayoutParams(-2, -2))
            readValue = { stored }
        } else {
            val hint = d.defaultValue?.takeIf { it.isNotEmpty() }?.let { "Empty uses the model default: $it" } ?: "Empty leaves it to the model"
            val edit = ui.field(card, if (d.required) "Value" else "Value (optional)", stored?.value.orEmpty(), multiline = d.kind == InputKind.TEXT,
                helper = hint, numeric = d.kind != InputKind.TEXT, decimal = d.kind == InputKind.NUMBER)
            edit.isEnabled = !waiting
            readValue = { edit.text.toString().let { if (d.kind == InputKind.NUMBER) it.replace(',', '.') else it }.trim().takeIf { it.isNotEmpty() }?.let { ModelOptions.value(d, it) } }
        }
        if (help.isNotEmpty()) card.addView(ui.text(help, Ui.Type.SMALL, ui.muted).apply { setPadding(0, dp(4), 0, 0) })
        if (photoField(d)) card.addView(ui.tonal("Choose photo", R.drawable.ic_add_photo_alternate) { choosePhoto(d) }.apply {
            isEnabled = !waiting; contentDescription = "Choose a photo for ${d.name}"; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) }
        })
        readers.add(readValue)
    }

    /** Picks a photo, uploads it to Replicate, and fills this input with the returned file URL. */
    private fun choosePhoto(d: InputDefinition) = safely {
        remember()
        activity.pickPhoto { uri ->
            if (uri == null || gone()) return@pickPhoto
            waiting = true; notice = "Uploading the photo to your Replicate account..."; render()
            app.uploadPhoto(mode, { activity.readPhoto(uri) }) { url, error ->
                if (gone()) return@uploadPhoto
                waiting = false
                if (url != null) {
                    draft = draft.copy(options = draft.options.filter { it.name != d.name } + InputValue(d.name, InputKind.TEXT, url))
                    notice = "Photo uploaded and set as ${d.name}. Apply to save."
                } else notice = error ?: "Could not upload the photo."
                render()
            }
        }
    }
}
