package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Projection quick menu rendering; session actions remain owned by the host. */
internal class CarPlayQuickMenu(
    private val context: Context,
    private val palette: DiPlayPalette,
    private val dismiss: () -> Unit,
    private val home: () -> Unit,
    private val settings: () -> Unit,
    private val picture: () -> Unit,
    private val exit: () -> Unit,
    private val volume: CarPlayMenuVolume,
    private val onVolumeControls: (CarPlayMenuVolumeControls) -> Unit,
) {
    private val resources get() = context.resources
    private fun getString(id: Int) = context.getString(id)
    private fun getDrawable(id: Int) = context.getDrawable(id)
    private fun findDescendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(findDescendants(view.getChildAt(i)))
    }

    fun build(): View {
        // Size app chrome like the home screen, without changing video resources or touch mapping.
        val menuOverride = InterfaceSize.override(resources.configuration, InterfaceSize.preference(context))
        val menuContext = android.view.ContextThemeWrapper(context, 0).apply {
            menuOverride?.let {
                applyOverrideConfiguration(InterfaceSize.contextOverride(it))
            }
        }
        fun menuDp(value: Int) = (value * menuContext.resources.displayMetrics.density).roundToInt()
        fun settingsMenuWidth(available: Int) = minOf(menuDp(MAX_SETTINGS_MENU_WIDTH_DP),
            (available - menuDp(SETTINGS_MENU_OUTER_GAP_DP * 2)).coerceAtLeast(1))
        fun text(value: Int, size: Float) = TextView(menuContext).apply {
            this.text = getString(value)
            textSize = size
            includeFontPadding = false
            setTextColor(palette.secondaryText)
            typeface = Typeface.DEFAULT
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        fun surface() = GradientDrawable().apply {
            setColor(palette.surface)
            setStroke(menuDp(SETTINGS_MENU_BORDER_DP), palette.outline)
            cornerRadius = menuDp(SETTINGS_MENU_CONTROL_HEIGHT_DP / 2).toFloat()
        }
        val overlay = object : FrameLayout(menuContext) {
            private val tapSlop = android.view.ViewConfiguration.get(menuContext).scaledTouchSlop
            private var blankTap = false
            private var downX = 0f
            private var downY = 0f

            // Blank-area gestures are handled above; a drag must never become the View's default click.
            override fun onTouchEvent(event: MotionEvent): Boolean = true

            private fun hitsControl(x: Float, y: Float): Boolean {
                val origin = IntArray(2)
                getLocationOnScreen(origin)
                val bounds = android.graphics.Rect()
                return findDescendants(this).any { child ->
                    child !== this && child !is ScrollView &&
                        (child is Button || child is SeekBar || child.isClickable || child.isLongClickable) &&
                        child.getGlobalVisibleRect(bounds) &&
                        bounds.contains((origin[0] + x).toInt(), (origin[1] + y).toInt())
                }
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        blankTap = !hitsControl(event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (dx * dx + dy * dy > tapSlop * tapSlop) blankTap = false
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> blankTap = false
                    MotionEvent.ACTION_UP -> {
                        val dismiss = blankTap && !hitsControl(event.x, event.y)
                        blankTap = false
                        if (dismiss) {
                            // Cancel the scroll container's touch before hiding it. The tap never reaches CarPlay.
                            MotionEvent.obtain(event).also { cancel ->
                                cancel.action = MotionEvent.ACTION_CANCEL
                                super.dispatchTouchEvent(cancel)
                                cancel.recycle()
                            }
                            performClick()
                            return true
                        }
                    }
                }
                return super.dispatchTouchEvent(event)
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                getChildAt(0)?.layoutParams?.width = settingsMenuWidth(MeasureSpec.getSize(widthMeasureSpec))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(palette.background, SETTINGS_MENU_SCRIM_ALPHA))
            contentDescription = getString(R.string.settings_quick_dismiss_hint)
            setOnClickListener { dismiss() }
        }
        val content = LinearLayout(menuContext).apply {
            orientation = LinearLayout.VERTICAL
            background = surface()
            setPadding(menuDp(SETTINGS_MENU_PADDING_DP), menuDp(SETTINGS_MENU_PADDING_DP),
                menuDp(SETTINGS_MENU_PADDING_DP), menuDp(SETTINGS_MENU_PADDING_DP))
        }

        fun action(label: Int, icon: Int, kind: DiPlayButtonKind = DiPlayButtonKind.SECONDARY, onClick: () -> Unit) =
            DiPlayButton(menuContext).apply {
                text = getString(label)
                textSize = 16f
                includeFontPadding = false
                compoundDrawablePadding = menuDp(SETTINGS_MENU_ICON_GAP_DP)
                setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply {
                    val size = menuDp(SETTINGS_MENU_ICON_SIZE_DP)
                    setBounds(0, 0, size, size)
                    setTint(if (kind == DiPlayButtonKind.DANGER) Color.WHITE else palette.accent)
                }, null, null, null)
                minHeight = menuDp(SETTINGS_MENU_CONTROL_HEIGHT_DP)
                setPadding(menuDp(SETTINGS_MENU_BUTTON_PADDING_DP), 0, menuDp(SETTINGS_MENU_BUTTON_PADDING_DP), 0)
                style(kind, palette, overlay = false)
                setOnClickListener { onClick() }
            }

        fun actionRow(first: View, second: View,
            minimumItemWidthDp: Int = SETTINGS_MENU_MIN_ACTION_WIDTH_DP): View = object : LinearLayout(menuContext) {
            init {
                isBaselineAligned = false
                addView(first, LayoutParams(0, -2, 1f))
                addView(second, LayoutParams(0, -2, 1f))
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val stacked = MeasureSpec.getSize(widthMeasureSpec) <
                    menuDp(minimumItemWidthDp * 2 + SETTINGS_MENU_ACTION_GAP_DP)
                orientation = if (stacked) VERTICAL else HORIZONTAL
                for (index in 0 until childCount) {
                    val params = getChildAt(index).layoutParams as LayoutParams
                    params.width = if (stacked) LayoutParams.MATCH_PARENT else 0
                    params.height = LayoutParams.WRAP_CONTENT
                    params.weight = if (stacked) 0f else 1f
                    params.marginStart = if (!stacked && index > 0) menuDp(SETTINGS_MENU_ACTION_GAP_DP) else 0
                    params.marginEnd = 0
                    params.resolveLayoutDirection(layoutDirection)
                    params.topMargin = if (stacked && index > 0) menuDp(SETTINGS_MENU_ACTION_GAP_DP) else 0
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                if (!stacked) {
                    val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
                        MeasureSpec.getSize(heightMeasureSpec) else maxOf(first.measuredHeight, second.measuredHeight)
                    for (child in listOf(first, second)) {
                        child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                    }
                    setMeasuredDimension(measuredWidth, height)
                }
            }
        }

        val actions = actionRow(
            actionRow(
                action(R.string.settings_return_to_home, R.drawable.ic_dp_home) { home() },
                action(R.string.settings_all_settings, R.drawable.ic_dp_advanced) { settings() },
            ),
            actionRow(
                action(R.string.picture_adjustments, R.drawable.ic_dp_display) { picture() },
                action(R.string.exit_application, R.drawable.ic_dp_back, DiPlayButtonKind.DANGER) { exit() },
            ),
            minimumItemWidthDp = SETTINGS_MENU_MIN_ACTION_WIDTH_DP * 2 + SETTINGS_MENU_ACTION_GAP_DP,
        )

        val volumes = CarPlayMenuVolumeControls(menuContext, palette, volume)
        onVolumeControls(volumes)
        val audio = LinearLayout(menuContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(R.string.settings_quick_volume, 14f))
            addView(volumes, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = menuDp(SETTINGS_MENU_VOLUME_HEADING_GAP_DP)
            })
        }
        val body = LinearLayout(menuContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions, LinearLayout.LayoutParams(-1, -2))
            addView(audio, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = menuDp(SETTINGS_MENU_SECTION_GAP_DP)
            })
        }
        content.addView(body, LinearLayout.LayoutParams(-1, -2))

        // A very narrow or heavily constrained window can scroll; normal landscape windows fit all actions.
        overlay.addView(
            ScrollView(menuContext).apply { addView(content, ViewGroup.LayoutParams(-1, -2)) },
            FrameLayout.LayoutParams(settingsMenuWidth(resources.displayMetrics.widthPixels), -2, Gravity.CENTER).apply {
                topMargin = menuDp(SETTINGS_MENU_OUTER_GAP_DP)
                bottomMargin = menuDp(SETTINGS_MENU_OUTER_GAP_DP)
            },
        )
        return overlay
    }

    companion object {
        private const val SETTINGS_MENU_ACTION_GAP_DP = 12
        private const val SETTINGS_MENU_ICON_SIZE_DP = 20
        private const val SETTINGS_MENU_ICON_GAP_DP = 8
        private const val SETTINGS_MENU_BORDER_DP = 1
        private const val SETTINGS_MENU_SCRIM_ALPHA = 224
        private const val SETTINGS_MENU_CONTROL_HEIGHT_DP = 56
        private const val SETTINGS_MENU_BUTTON_PADDING_DP = 16
        private const val SETTINGS_MENU_MIN_ACTION_WIDTH_DP = 160
        private const val SETTINGS_MENU_OUTER_GAP_DP = 16
        private const val SETTINGS_MENU_PADDING_DP = 16
        private const val SETTINGS_MENU_SECTION_GAP_DP = 16
        private const val SETTINGS_MENU_VOLUME_HEADING_GAP_DP = 8
        const val MAX_SETTINGS_MENU_WIDTH_DP = 1040
    }
}
