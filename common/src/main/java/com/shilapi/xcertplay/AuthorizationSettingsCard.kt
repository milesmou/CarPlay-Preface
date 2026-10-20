package com.shilapi.xcertplay

import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** Immutable page data; rendering never prepares a VPN or starts authorization. */
internal data class AuthorizationCardState(
    val componentMessage: Int,
    val exportPending: Boolean,
    val exportFailed: Boolean,
    val storageDenied: Boolean,
    val savedPath: String?,
    val alreadySaved: Boolean,
    val folderUnavailable: Boolean,
    val canWriteSettings: Boolean,
    val writeUnavailable: Boolean,
    val vpnValue: String,
    val vpnResult: SystemAuthorization.Result,
    val canDrawOverlays: Boolean,
    val additionalUnavailable: Boolean,
)

internal data class AuthorizationCardActions(
    val export: () -> Unit,
    val openDownloads: () -> Unit,
    val writeSettings: () -> Unit,
    val vpn: () -> Unit,
    val overlay: () -> Unit,
)

internal class AuthorizationSettingsCard(private val ui: SettingsCardUi) {
    private fun getString(id: Int): String = ui.context.getString(id)
    private fun getString(id: Int, vararg args: Any): String = ui.context.getString(id, *args)

    fun render(card: LinearLayout, state: AuthorizationCardState, actions: AuthorizationCardActions) = with(ui) {
        card.addView(label(getString(state.componentMessage), 14, muted))
        val componentActions = mutableListOf<SettingButton>()
        componentActions += button(getString(if (state.exportPending) R.string.settings_authorization_component_preparing
            else R.string.settings_authorization_component_install), false, actions.export).apply {
            tag = "authorization-component-export"
            isEnabled = !state.exportPending
        }
        componentActions += button(getString(R.string.settings_authorization_open_downloads), false, actions.openDownloads).apply { tag = "authorization-open-downloads" }
        card.addView(actionRow(componentActions))
        if (state.exportFailed) card.addView(label(getString(R.string.settings_authorization_component_install_failed), 14, warning))
        if (state.storageDenied) card.addView(label(getString(R.string.settings_authorization_component_storage_denied), 14, warning))
        state.savedPath?.let { card.addView(label(getString(
            if (state.alreadySaved) R.string.settings_authorization_component_already_saved
            else R.string.settings_authorization_component_saved, it), 14, muted)) }
        if (state.folderUnavailable) card.addView(label(getString(R.string.settings_authorization_component_folder_unavailable), 14, warning))
        card.addView(space(blockGapDp))
        val writeState = getString(if (state.canWriteSettings)
            R.string.settings_authorization_allowed else R.string.settings_authorization_not_allowed)
        card.addView(button("${getString(R.string.settings_authorization_write)} · $writeState", false,
            actions.writeSettings).apply { wrapContentHeight = true }, matchButton())
        card.addView(label(getString(R.string.settings_authorization_write_description), 14, muted))
        if (state.writeUnavailable) card.addView(label(getString(R.string.settings_authorization_unavailable), 14, warning))
        card.addView(space(blockGapDp))
        card.addView(button("${getString(R.string.settings_authorization_vpn)} · ${state.vpnValue}", false,
            actions.vpn).apply { wrapContentHeight = true }, matchButton())
        card.addView(label(getString(R.string.settings_authorization_vpn_description), 14, muted))
        val resultMessage = when (state.vpnResult) {
            SystemAuthorization.Result.CANCELLED -> R.string.settings_authorization_cancelled
            SystemAuthorization.Result.NOT_ALLOWED -> R.string.settings_authorization_not_completed
            SystemAuthorization.Result.UNAVAILABLE -> R.string.settings_authorization_unavailable
            SystemAuthorization.Result.ALLOWED -> R.string.settings_authorization_vpn_checked
            else -> null
        }
        resultMessage?.let { card.addView(label(getString(it), 14,
            if (state.vpnResult == SystemAuthorization.Result.ALLOWED) muted else warning)) }
        card.addView(space(blockGapDp))
        val overlayStatus = getString(if (state.canDrawOverlays) R.string.settings_authorization_allowed else R.string.settings_authorization_not_allowed)
        card.addView(button("${getString(R.string.settings_authorization_overlay)} · $overlayStatus", false, actions.overlay).apply { wrapContentHeight = true }, matchButton())
        card.addView(label(getString(R.string.settings_authorization_overlay_description), 14, muted))
        if (state.additionalUnavailable) card.addView(label(getString(R.string.settings_authorization_unavailable), 14, warning))
    }
}
