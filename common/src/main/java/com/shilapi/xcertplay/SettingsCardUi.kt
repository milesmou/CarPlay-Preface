package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

internal interface SettingsCardUi {
    val context: Context
    val muted: Int
    val warning: Int
    val blockGapDp: Int
    fun label(text: String, size: Int, color: Int): TextView
    fun button(title: String, primary: Boolean = false, click: () -> Unit): SettingButton
    fun actionRow(actions: List<SettingButton>): LinearLayout
    fun matchButton(): LinearLayout.LayoutParams
    fun space(height: Int): View
    fun column(): LinearLayout
    fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, save: (Boolean) -> Unit): Switch
    fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int,
        reconnects: Boolean = true, save: (Int) -> Unit)
}
