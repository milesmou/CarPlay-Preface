package com.shilapi.xcertplay

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Application-owned chrome avoids OEM AlertDialog list/button styling on the E02. */
internal class PhonePickerDialog(
    private val owner: Activity,
    palette: DiPlayPalette,
    choices: List<Choice>,
    selectedAddress: String?,
    title: String,
    emptyMessage: String,
    pairingTitle: String,
    onChoose: (Choice) -> Unit,
    onPair: () -> Unit,
    onCancel: () -> Unit,
) : Dialog(owner, owner.appDialogTheme()) {
    data class Choice(val address: String, val name: String?)

    private val header = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val footer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        addView(list, ViewGroup.LayoutParams(-1, -2))
    }
    private val root = object : LinearLayout(context) {
        var heightLimit = resources.displayMetrics.heightPixels

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val compact = heightLimit / resources.displayMetrics.density /
                resources.configuration.fontScale < Layout.COMPACT_HEIGHT_SP
            val padding = dp(if (compact) Layout.COMPACT_PADDING_DP else Layout.PADDING_DP)
            setPadding(padding, padding, padding, padding)
            if (header.childCount > 1) header.getChildAt(1).visibility = if (compact) View.GONE else View.VISIBLE
            val gap = dp(if (compact) Layout.COMPACT_BLOCK_GAP_DP else Layout.BLOCK_GAP_DP)
            for (child in listOf(scroll, footer)) {
                val params = child.layoutParams as LayoutParams
                if (params.topMargin != gap) child.layoutParams = params.apply { topMargin = gap }
            }
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            val exactWidth = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY)
            val naturalHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            header.measure(exactWidth, naturalHeight)
            footer.measure(exactWidth, naturalHeight)
            val remaining = (heightLimit - paddingTop - paddingBottom - header.measuredHeight -
                footer.measuredHeight - gap * 2).coerceAtLeast(1)
            val listHeight = minOf(remaining, listNaturalHeight(exactWidth, naturalHeight))
            if (scroll.layoutParams.height != listHeight) scroll.layoutParams = scroll.layoutParams.apply {
                height = listHeight
            }
            super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(heightLimit, MeasureSpec.AT_MOST))
        }
    }.apply { orientation = LinearLayout.VERTICAL }
    private val ownerLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sizeToOwner() }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val padding = dp(Layout.PADDING_DP)
        root.setPadding(padding, padding, padding, padding)
        root.background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(Layout.CORNER_DP).toFloat()
            setStroke(dp(Layout.STROKE_DP), palette.outline)
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = Layout.TITLE_SP
            setTextColor(palette.primaryText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            accessibilityHeadingCompat()
        })
        if (choices.isNotEmpty()) header.addView(TextView(context).apply {
            text = context.getString(R.string.phone_picker_hint)
            textSize = Layout.HINT_SP
            setTextColor(palette.secondaryText)
            setPadding(0, dp(Layout.TEXT_GAP_DP), 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        choices.forEachIndexed { index, choice ->
            val name = choice.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.paired_device)
            val duplicate = choices.count { it.name == choice.name } > 1
            val label = if (duplicate) "$name · ${choice.address.takeLast(5)}" else name
            val selected = selectedAddress?.equals(choice.address, ignoreCase = true) == true
            val row = control(if (selected) "$label  ✓" else label,
                if (selected) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, palette) {
                dismiss()
                onChoose(choice)
            }.apply {
                tag = choice.address
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isSelected = selected
                contentDescription = if (selected) context.getString(R.string.phone_picker_selected, label) else label
                maxLines = Layout.NAME_LINES
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = dp(Layout.ROW_GAP_DP)
            })
        }
        if (choices.isEmpty()) list.addView(TextView(context).apply {
            text = emptyMessage
            textSize = Layout.HINT_SP
            setTextColor(palette.secondaryText)
        })
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Layout.BLOCK_GAP_DP) })
        footer.addView(control(pairingTitle,
            if (choices.isEmpty()) DiPlayButtonKind.PRIMARY else DiPlayButtonKind.SECONDARY, palette) {
            dismiss()
            onPair()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(control(context.getString(R.string.cancel), DiPlayButtonKind.SECONDARY, palette) {
            cancel()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(Layout.ROW_GAP_DP) })
        // Both footer controls grow together when the font requires wrapping.
        footer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val height = maxOf(footer.getChildAt(0).height, footer.getChildAt(1).height)
            for (index in 0..1) if (footer.getChildAt(index).minimumHeight != height) {
                footer.getChildAt(index).minimumHeight = height
            }
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Layout.BLOCK_GAP_DP) })
        setContentView(root)
        setOnCancelListener { onCancel() }
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = Layout.DIM_AMOUNT }
        }
        owner.window.decorView.addOnLayoutChangeListener(ownerLayout)
        sizeToOwner()
    }

    override fun onStop() {
        owner.window.decorView.removeOnLayoutChangeListener(ownerLayout)
        super.onStop()
    }

    private fun sizeToOwner() {
        val bounds = Rect()
        owner.window.decorView.getWindowVisibleDisplayFrame(bounds)
        val metrics = owner.resources.displayMetrics
        val width = bounds.width().takeIf { it > 0 } ?: metrics.widthPixels
        val height = bounds.height().takeIf { it > 0 } ?: metrics.heightPixels
        val margin = dp(Layout.OUTER_MARGIN_DP)
        root.heightLimit = (height - margin * 2).coerceAtLeast(1)
        window?.setLayout(minOf(dp(Layout.MAX_WIDTH_DP), (width - margin * 2).coerceAtLeast(1)), -2)
        root.requestLayout()
    }

    private fun listNaturalHeight(width: Int, height: Int): Int {
        list.measure(width, height)
        return list.measuredHeight
    }

    private fun control(title: String, kind: DiPlayButtonKind, palette: DiPlayPalette, click: () -> Unit) =
        DiPlayButton(context).apply {
            text = title
            textSize = Layout.CONTROL_SP
            minHeight = dp(Layout.CONTROL_HEIGHT_DP)
            setPadding(dp(Layout.CONTROL_PADDING_DP), dp(Layout.TEXT_GAP_DP),
                dp(Layout.CONTROL_PADDING_DP), dp(Layout.TEXT_GAP_DP))
            style(kind, palette, overlay = false)
            setOnClickListener { click() }
        }

    private fun View.accessibilityHeadingCompat() {
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    /** All dimensions for this dialog live together; button radius is inherited from DiPlayButton. */
    private object Layout {
        const val MAX_WIDTH_DP = 760
        const val OUTER_MARGIN_DP = 12
        const val PADDING_DP = 20
        const val COMPACT_PADDING_DP = 8
        const val COMPACT_BLOCK_GAP_DP = 8
        const val COMPACT_HEIGHT_SP = 260
        const val BLOCK_GAP_DP = 16
        const val ROW_GAP_DP = 10
        const val TEXT_GAP_DP = 8
        const val CORNER_DP = 24
        const val STROKE_DP = 1
        const val CONTROL_HEIGHT_DP = 56
        const val CONTROL_PADDING_DP = 16
        const val NAME_LINES = 3
        const val TITLE_SP = 24f
        const val HINT_SP = 15f
        const val CONTROL_SP = 18f
        const val DIM_AMOUNT = 0.6f
    }
}
