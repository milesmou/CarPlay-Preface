package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import kotlin.math.roundToInt

/** App-owned form chrome keeps OEM dialog themes from obscuring text or shrinking touch targets. */
internal class HotspotCredentialsDialog(private val owner: Activity, private val palette: DiPlayPalette,
    initialName: String, initialPassword: String, initialSecurity: ManualHotspotSecurity,
    private val save: (String, String, ManualHotspotSecurity) -> Unit) : Dialog(owner, owner.appDialogTheme()) {
    private val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fieldRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val nameGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val passwordGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var fieldsInColumns = false
    private val footer = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val height = (0 until childCount).maxOfOrNull { getChildAt(it).measuredHeight } ?: 0
            for (index in 0 until childCount) getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(getChildAt(index).measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            setMeasuredDimension(measuredWidth, height)
        }
    }.apply { orientation = LinearLayout.HORIZONTAL }
    private val scroll = ScrollView(context).apply { isFillViewport = false; addView(body) }
    private val error = text("", Layout.NOTE_SP, palette.warning).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val nameWarning = text(context.getString(R.string.settings_hotspot_name_non_english), Layout.NOTE_SP, palette.warning)
    private val ssid = input(initialName, false).apply { tag = "hotspot-name" }
    private val password = input(initialPassword, true).apply { tag = "hotspot-password" }
    private val modes = listOf(ManualHotspotSecurity.WPA2, ManualHotspotSecurity.WPA3_TRANSITION, ManualHotspotSecurity.WPA3)
    private val modeTitles = listOf(context.getString(R.string.wpa2), context.getString(R.string.wpa3_transition), context.getString(R.string.wpa3))
    private var security = initialSecurity.takeIf { it in modes } ?: ManualHotspotSecurity.WPA2
    private val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val securityControl = control("", DiPlayButtonKind.SECONDARY) {
        choices.visibility = if (choices.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }.apply { tag = "hotspot-security"; gravity = Gravity.CENTER_VERTICAL or Gravity.START }
    private val root = object : LinearLayout(context) {
        var heightLimit = resources.displayMetrics.heightPixels
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val compact = heightLimit / resources.displayMetrics.density / resources.configuration.fontScale < Layout.COMPACT_HEIGHT_SP
            val padding = dp(if (compact) Layout.COMPACT_PADDING_DP else Layout.PADDING_DP)
            setPadding(padding, padding, padding, padding)
            val gap = dp(if (compact) Layout.COMPACT_GAP_DP else Layout.BLOCK_GAP_DP)
            listOf(scroll, footer).forEach { view ->
                (view.layoutParams as LayoutParams).topMargin = gap
            }
            val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
            val columns = compact && width / resources.displayMetrics.density / resources.configuration.fontScale >= Layout.COLUMN_MIN_WIDTH_SP
            if (fieldsInColumns != columns) {
                fieldsInColumns = columns
                fieldRow.orientation = if (columns) HORIZONTAL else VERTICAL
                nameGroup.layoutParams = LayoutParams(if (columns) 0 else -1, -2, if (columns) 1f else 0f)
                passwordGroup.layoutParams = LayoutParams(if (columns) 0 else -1, -2, if (columns) 1f else 0f).apply {
                    marginStart = if (columns) dp(Layout.ROW_GAP_DP) else 0
                    topMargin = if (columns) 0 else dp(Layout.BLOCK_GAP_DP)
                }
            }
            val exact = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            header.measure(exact, natural); footer.measure(exact, natural); body.measure(exact, natural)
            val remaining = (heightLimit - paddingTop - paddingBottom - header.measuredHeight - footer.measuredHeight - gap * 2).coerceAtLeast(1)
            scroll.layoutParams.height = minOf(body.measuredHeight, remaining)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST))
        }
    }.apply { orientation = LinearLayout.VERTICAL; isFocusableInTouchMode = true }
    private var sizedWidth = 0
    private val visibleBoundsListener = ViewTreeObserver.OnGlobalLayoutListener { sizeToOwner() }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.background = GradientDrawable().apply {
            setColor(palette.surface); cornerRadius = dp(Layout.CORNER_DP).toFloat()
            setStroke(dp(Layout.STROKE_DP), palette.outline)
        }
        header.addView(text(context.getString(R.string.car_hotspot_details), Layout.TITLE_SP, palette.primaryText).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        header.addView(error)
        body.addView(text(context.getString(R.string.settings_hotspot_dialog_hint), Layout.NOTE_SP, palette.secondaryText))
        field(nameGroup, context.getString(R.string.hotspot_name), ssid)
        nameGroup.addView(nameWarning)
        field(passwordGroup, context.getString(R.string.settings_hotspot_password), password)
        fieldRow.addView(nameGroup, LinearLayout.LayoutParams(-1, -2))
        fieldRow.addView(passwordGroup, spaced())
        body.addView(fieldRow, spaced())
        val helpers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        helpers.addView(CheckBox(context).apply {
            text = context.getString(R.string.show_password); textSize = Layout.NOTE_SP
            minHeight = dp(Layout.CONTROL_HEIGHT_DP); setTextColor(palette.primaryText)
            buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(palette.accent, palette.secondaryText))
            setOnCheckedChangeListener { _, checked ->
                val start = password.selectionStart.coerceAtLeast(0)
                val end = password.selectionEnd.coerceAtLeast(0)
                password.transformationMethod = if (checked) null else PasswordTransformationMethod.getInstance()
                password.setSelection(minOf(start, password.length()), minOf(end, password.length()))
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        helpers.addView(control(context.getString(R.string.hide_keyboard), DiPlayButtonKind.SECONDARY) { hideKeyboard() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(Layout.ROW_GAP_DP) })
        body.addView(helpers, spaced())
        body.addView(securityControl, spaced())
        modes.forEachIndexed { index, mode ->
            choices.addView(control(modeTitles[index], DiPlayButtonKind.SECONDARY) {
                security = mode; choices.visibility = View.GONE; updateSecurity()
            }.apply { tag = mode }, spaced())
        }
        body.addView(choices)
        footer.addView(control(context.getString(R.string.cancel), DiPlayButtonKind.SECONDARY) { cancel() },
            LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(control(context.getString(R.string.save_details), DiPlayButtonKind.PRIMARY) { submit() }.apply { tag = "hotspot-save" },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(Layout.ROW_GAP_DP) })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, spaced()); root.addView(footer, spaced())
        setContentView(root)
        ssid.imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fun updateNameWarning() {
            nameWarning.visibility = if (ManualHotspotValidation.nameMayNotReachIphone(ssid.text.toString())) View.VISIBLE else View.GONE
        }
        ssid.doAfterTextChanged { updateNameWarning(); error.visibility = View.GONE }
        password.doAfterTextChanged { updateSecurity(); error.visibility = View.GONE }
        updateNameWarning(); updateSecurity()
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> revealFocusedField() }
    }

    private fun field(group: LinearLayout, title: String, input: EditText) {
        val label = text(title, Layout.NOTE_SP, palette.primaryText)
        input.id = View.generateViewId(); label.labelFor = input.id
        group.addView(label); group.addView(input, spaced(Layout.TEXT_GAP_DP))
    }

    private fun input(value: String, secret: Boolean) = EditText(context).apply {
        setSingleLine(); textSize = Layout.INPUT_SP; minHeight = dp(Layout.CONTROL_HEIGHT_DP)
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
        setText(value); setTextColor(palette.primaryText); setHintTextColor(palette.secondaryText)
        setPadding(dp(Layout.CONTROL_PADDING_DP), dp(Layout.TEXT_GAP_DP), dp(Layout.CONTROL_PADDING_DP), dp(Layout.TEXT_GAP_DP))
        fun repaint() {
            background = GradientDrawable().apply {
                setColor(palette.button)
                cornerRadius = minOf(dp(Layout.CORNER_DP).toFloat(), dp(Layout.CONTROL_HEIGHT_DP) * Layout.RADIUS_RATIO)
                setStroke(dp(if (hasFocus()) Layout.FOCUS_STROKE_DP else Layout.STROKE_DP), if (hasFocus()) palette.accent else palette.outline)
            }
        }
        onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            repaint()
            if (focused) scroll.post { revealFocusedField() }
        }; repaint()
    }

    private fun revealFocusedField() {
        val input = when { ssid.hasFocus() -> ssid; password.hasFocus() -> password; else -> return }
        if (input.height <= 0 || scroll.height <= 0) return
        val bounds = Rect(0, 0, input.width, input.height)
        scroll.offsetDescendantRectToMyCoords(input, bounds)
        scroll.scrollTo(0, (bounds.top - (scroll.height - input.height) / 2).coerceAtLeast(0))
    }

    private fun updateSecurity() {
        val hasPassword = password.length() > 0
        securityControl.text = "${context.getString(R.string.security)} · ${if (hasPassword) modeTitles[modes.indexOf(security)] else context.getString(R.string.open)}  ›"
        securityControl.isEnabled = hasPassword; securityControl.alpha = if (hasPassword) 1f else Layout.DISABLED_ALPHA
        if (!hasPassword) choices.visibility = View.GONE
        modes.forEachIndexed { index, mode -> (choices.getChildAt(index) as DiPlayButton).apply {
            isSelected = mode == security
            style(if (isSelected) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, palette, false)
        } }
    }

    private fun submit() {
        val name = ssid.text.toString(); val secret = password.text.toString()
        val problem = ManualHotspotValidation.error(name, secret)
        if (problem != null) {
            error.text = context.getString(problem.messageResource()); error.visibility = View.VISIBLE
            return
        }
        hideKeyboard(); dismiss()
        save(name, secret, if (secret.isEmpty()) ManualHotspotSecurity.OPEN else security)
    }

    private fun hideKeyboard() {
        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(password.windowToken ?: ssid.windowToken, 0)
        ssid.clearFocus(); password.clearFocus(); root.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = Layout.DIM_AMOUNT }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            decorView.viewTreeObserver.addOnGlobalLayoutListener(visibleBoundsListener)
        }
        owner.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(visibleBoundsListener)
        root.requestFocus(); sizeToOwner()
    }

    override fun onStop() {
        window?.decorView?.viewTreeObserver?.removeOnGlobalLayoutListener(visibleBoundsListener)
        owner.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(visibleBoundsListener)
        hideKeyboard(); super.onStop()
    }

    private fun sizeToOwner() {
        val ownerBounds = Rect(); owner.window.decorView.getWindowVisibleDisplayFrame(ownerBounds)
        val dialogBounds = Rect(); window?.decorView?.getWindowVisibleDisplayFrame(dialogBounds)
        val metrics = owner.resources.displayMetrics
        val width = ownerBounds.width().takeIf { it > 0 } ?: metrics.widthPixels
        val height = listOf(ownerBounds.height(), dialogBounds.height()).filter { it > 0 }.minOrNull() ?: metrics.heightPixels
        fitVisibleArea(width, height)
    }

    internal fun fitVisibleArea(width: Int, height: Int) {
        val margin = dp(Layout.OUTER_MARGIN_DP)
        val limit = (height - margin * 2).coerceAtLeast(1)
        val targetWidth = minOf(dp(Layout.MAX_WIDTH_DP), (width - margin * 2).coerceAtLeast(1))
        if (root.heightLimit != limit || sizedWidth != targetWidth) {
            root.heightLimit = limit; sizedWidth = targetWidth
            window?.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT); root.requestLayout()
        }
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply { text = value; textSize = size; setTextColor(color) }
    private fun control(title: String, kind: DiPlayButtonKind, click: () -> Unit) = DiPlayButton(context).apply {
        text = title; textSize = Layout.CONTROL_SP; minHeight = dp(Layout.CONTROL_HEIGHT_DP)
        setPadding(dp(Layout.CONTROL_PADDING_DP), dp(Layout.TEXT_GAP_DP), dp(Layout.CONTROL_PADDING_DP), dp(Layout.TEXT_GAP_DP))
        style(kind, palette, false); setOnClickListener { click() }
    }
    private fun spaced(gap: Int = Layout.BLOCK_GAP_DP) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private object Layout {
        const val MAX_WIDTH_DP = 760
        const val OUTER_MARGIN_DP = 12
        const val PADDING_DP = 20
        const val COMPACT_PADDING_DP = 6
        const val COMPACT_HEIGHT_SP = 300
        const val COLUMN_MIN_WIDTH_SP = 420
        const val COMPACT_GAP_DP = 6
        const val BLOCK_GAP_DP = 16
        const val ROW_GAP_DP = 10
        const val TEXT_GAP_DP = 8
        const val CONTROL_PADDING_DP = 16
        const val CONTROL_HEIGHT_DP = 56
        const val CORNER_DP = 24
        const val STROKE_DP = 1
        const val FOCUS_STROKE_DP = 2
        const val TITLE_SP = 24f
        const val INPUT_SP = 20f
        const val NOTE_SP = 15f
        const val CONTROL_SP = 18f
        const val RADIUS_RATIO = 20f / 56
        const val DIM_AMOUNT = 0.6f
        const val DISABLED_ALPHA = 0.65f
    }
}
