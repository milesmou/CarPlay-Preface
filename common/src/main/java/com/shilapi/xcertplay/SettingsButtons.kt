package com.shilapi.xcertplay

import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

internal const val VALUE_SEPARATOR = " · "

internal class SettingsActionRow(context: android.content.Context) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
    }
}

/**
 * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
 * start-aligned, muted value, chevron. Any other text stays a centred action button.
 * The plain text is unchanged, so callers and tests keep matching "Title · Value".
 */
internal class SettingButton(
    context: android.content.Context,
    private val muted: Int,
    private val accent: Int,
) : Button(context) {
    var wrapContentHeight = false
    var action = false
    var contentOffsetY = 0f
    var alignVisibleGlyphs = true
    private val glyphBounds = android.graphics.Rect()

    fun textAlignmentCentreY(): Float {
        return alignedTextCentreY(this, text, glyphBounds)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        var offset = contentOffsetY
        if (alignVisibleGlyphs) {
            offset = 0f
            // Wrapped labels keep Android's multiline layout. Measure single-line labels
            // on every draw so changing a setting value also updates its visual centre.
            if (layout?.lineCount == 1 && baseline >= 0) {
                val textCentre = textAlignmentCentreY()
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                offset = contentCentre - textCentre
            }
            // The chevron is already centred by TextView; compensate for the text shift.
            compoundDrawablesRelative.filterNotNull().forEach { drawable ->
                val bounds = drawable.bounds
                val top = -offset.roundToInt()
                drawable.setBounds(bounds.left, top, bounds.right, top + bounds.height())
            }
        }
        val checkpoint = canvas.save()
        canvas.translate(0f, offset)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val split = if (action) -1 else text?.indexOf(VALUE_SEPARATOR) ?: -1
        if (text == null || split <= 0) {
            super.setText(text, type)
            gravity = Gravity.CENTER
            setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val styled = android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(muted), split, text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        super.setText(styled, BufferType.SPANNABLE)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
            context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) }, null)
    }
}

internal fun alignedTextCentreY(
    view: TextView,
    text: CharSequence,
    bounds: android.graphics.Rect,
    drawingBaseline: Int = view.baseline,
): Float {
    if (usesChineseGlyphAlignment(view.resources.configuration.locales[0].language, text)) {
        val value = text.toString()
        view.paint.getTextBounds(value, 0, value.length, bounds)
        return drawingBaseline + (bounds.top + bounds.bottom) / 2f
    }
    // Keep the baseline stable when labels contain descenders or accents.
    val metrics = view.paint.fontMetrics
    return drawingBaseline + (metrics.ascent + metrics.descent) / 2f
}

internal fun usesChineseGlyphAlignment(language: String, text: CharSequence): Boolean {
    return language == "zh" && text.isNotBlank() &&
        text.none { it in 'A'..'Z' || it in 'a'..'z' }
}
