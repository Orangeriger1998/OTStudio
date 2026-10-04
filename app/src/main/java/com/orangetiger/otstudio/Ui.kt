package com.orangetiger.otstudio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as M

/** Material 3 building blocks shared by every OT Studio screen, so panels stay visually consistent. */
class Ui(private val context: Context) {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun color(id: Int) = ContextCompat.getColor(context, id)
    val onSurface get() = color(R.color.ot_on_surface)
    val muted get() = color(R.color.ot_on_surface_variant)
    val orange get() = color(R.color.ot_orange)

    fun column(padding: Int = 0) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; if (padding > 0) setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun gap(parent: ViewGroup, size: Int = 12) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(size))) }
    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
    fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }

    enum class Type(val style: Int) {
        DISPLAY(M.style.TextAppearance_Material3_HeadlineMedium), HEADLINE(M.style.TextAppearance_Material3_HeadlineSmall),
        TITLE(M.style.TextAppearance_Material3_TitleLarge), SUBTITLE(M.style.TextAppearance_Material3_TitleMedium),
        BODY_LARGE(M.style.TextAppearance_Material3_BodyLarge), BODY(M.style.TextAppearance_Material3_BodyMedium),
        SMALL(M.style.TextAppearance_Material3_BodySmall), LABEL(M.style.TextAppearance_Material3_LabelLarge), CAPTION(M.style.TextAppearance_Material3_LabelSmall)
    }
    fun text(value: CharSequence, type: Type = Type.BODY, color: Int = onSurface) = TextView(context).apply {
        text = value; setTextAppearance(type.style); setTextColor(color)
    }
    fun bold(view: TextView) = view.apply { typeface = Typeface.create(typeface, Typeface.BOLD) }

    private fun styled(overlay: Int) = ContextThemeWrapper(context, overlay)
    private fun button(overlay: Int, label: String, icon: Int?, action: () -> Unit) = MaterialButton(styled(overlay)).apply {
        text = label; isAllCaps = false; icon?.let { setIconResource(it) }; iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START; setOnClickListener { action() }
    }
    fun filled(label: String, icon: Int? = null, action: () -> Unit) = button(R.style.ThemeOverlay_OT_FilledButton, label, icon, action)
    fun tonal(label: String, icon: Int? = null, action: () -> Unit) = button(R.style.ThemeOverlay_OT_TonalButton, label, icon, action)
    fun outlined(label: String, icon: Int? = null, action: () -> Unit) = button(R.style.ThemeOverlay_OT_OutlinedButton, label, icon, action)
    fun textButton(label: String, icon: Int? = null, action: () -> Unit) = button(R.style.ThemeOverlay_OT_TextButton, label, icon, action)
    fun danger(label: String, icon: Int? = null, action: () -> Unit) = textButton(label, icon, action).apply {
        setTextColor(color(R.color.ot_error)); iconTint = ColorStateList.valueOf(color(R.color.ot_error))
    }
    /** Icon-only button; [description] is what screen readers announce. */
    fun iconButton(icon: Int, description: String, filled: Boolean = false, action: () -> Unit) =
        MaterialButton(styled(if (filled) R.style.ThemeOverlay_OT_FilledIconButton else R.style.ThemeOverlay_OT_IconButton)).apply {
            setIconResource(icon); contentDescription = description; tooltipText = description; setOnClickListener { action() }
        }

    /** A rounded surface card; returns the inner column to add content to. */
    fun card(parent: ViewGroup, padding: Int = 16, tone: Int = R.color.ot_surface_container, bottom: Int = 12): LinearLayout {
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(color(tone)); radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = 0
        }
        val inner = column(padding)
        card.addView(inner)
        parent.addView(card, matchWrap(bottom))
        return inner
    }
    fun sectionTitle(parent: ViewGroup, title: String, subtitle: String? = null) {
        parent.addView(bold(text(title, Type.SUBTITLE)))
        subtitle?.let { parent.addView(text(it, Type.SMALL, muted).apply { setPadding(0, dp(2), 0, dp(8)) }) } ?: gap(parent, 8)
    }

    /** Outlined text field with a floating label. Returns the editable text box. */
    fun field(parent: ViewGroup, label: String, value: String, secret: Boolean = false, multiline: Boolean = false, helper: String? = null, numeric: Boolean = false, decimal: Boolean = false): TextInputEditText {
        val layout = TextInputLayout(styled(R.style.ThemeOverlay_OT_OutlinedField)).apply {
            hint = label; helper?.let { helperText = it }
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
        }
        val edit = TextInputEditText(layout.context).apply {
            id = View.generateViewId(); setText(value)
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiline) { minLines = 1; maxLines = 4 } else setSingleLine(true)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        layout.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(layout, matchWrap(10))
        return edit
    }
    fun layoutOf(edit: View): TextInputLayout? {
        var p = edit.parent
        while (p != null && p !is TextInputLayout) p = p.parent
        return p as? TextInputLayout
    }

    /** Exposed dropdown menu (Material 3 replacement for a spinner). */
    fun dropdown(parent: ViewGroup, label: String, items: List<String>, selected: String, onSelect: ((String) -> Unit)? = null): MaterialAutoCompleteTextView {
        val layout = TextInputLayout(styled(R.style.ThemeOverlay_OT_Dropdown)).apply {
            hint = label; setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
        }
        val box = MaterialAutoCompleteTextView(layout.context).apply {
            id = View.generateViewId(); inputType = InputType.TYPE_NULL; contentDescription = label
            setSimpleItems(items.toTypedArray()); setText(selected.ifEmpty { items.firstOrNull().orEmpty() }, false)
            setOnItemClickListener { _, _, position, _ -> onSelect?.invoke(items[position]) }
        }
        layout.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(layout, matchWrap(10))
        return box
    }

    /** Single-choice segmented buttons, such as Chat / Image / Video. */
    fun segmented(parent: ViewGroup, items: List<Pair<String, Int?>>, selected: Int, onSelect: (Int) -> Unit): MaterialButtonToggleGroup {
        val group = MaterialButtonToggleGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
        val ids = items.map { (label, icon) ->
            MaterialButton(styled(R.style.ThemeOverlay_OT_OutlinedButton)).apply {
                id = View.generateViewId(); text = label; isAllCaps = false; icon?.let { setIconResource(it) }; isCheckable = true
            }.also { group.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }.id
        }
        group.check(ids[selected])
        group.addOnButtonCheckedListener { _, checkedId, isChecked -> if (isChecked) ids.indexOf(checkedId).takeIf { it >= 0 && it != selected }?.let(onSelect) }
        parent.addView(group, matchWrap(12))
        return group
    }

    fun switch(label: String, checked: Boolean) = com.google.android.material.materialswitch.MaterialSwitch(context).apply {
        text = label; isChecked = checked; setTextColor(onSurface)
    }
    fun checkbox(label: String, checked: Boolean, description: String? = null) = MaterialCheckBox(context).apply {
        text = label; isChecked = checked; description?.let { contentDescription = it }
    }
    fun filterChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Chip(styled(R.style.ThemeOverlay_OT_FilterChip)).apply {
        text = label; isCheckable = true; isChecked = checked; setOnCheckedChangeListener { _, c -> onChange(c) }
    }
    /** Small rounded status label. */
    fun pill(value: String, bg: Int, fg: Int) = text(value, Type.CAPTION, color(fg)).apply {
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(color(bg)); cornerRadius = dp(12).toFloat() }
    }
    fun progress() = LinearProgressIndicator(context).apply { isIndeterminate = true; setIndicatorColor(orange); trackColor = color(R.color.ot_surface_high) }
    fun avatar(sizeDp: Int) = ShapeableImageView(context).apply {
        setImageResource(R.drawable.tiger_avatar); contentDescription = "OT Studio tiger"
        shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(RelativeCornerSize(0.5f)).build()
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}
