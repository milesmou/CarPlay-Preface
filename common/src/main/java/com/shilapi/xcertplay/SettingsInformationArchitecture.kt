package com.shilapi.xcertplay

internal enum class SettingsCategory {
    CONNECTION, AUTHORIZATION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, DIAGNOSTICS, EXPERIMENTAL, ABOUT,
}

/** A settings card. Every entry needs one category in [SettingsInformationArchitecture]; see AGENTS.md. */
internal enum class SettingsSection {
    SYSTEM_AUTHORIZATION,
    CARPLAY_CONTROLS,
    WHEEL_KEYS,
    CONNECTION_SETUP,
    DIAGNOSTICS,
    AUTOMATIC_CONNECTION,
    USB_CONNECTION,
    HOTSPOT_AUTOMATION,
    DISPLAY_AND_PERFORMANCE,
    ADVANCED_MEDIA,
    CAR_BUTTON,
    AUDIO_ROUTING,
    AUDIO_SCHEME,
    INSTRUMENT_NAVIGATION,
    LOCATION,
    EXTERNAL_CONTROLLER,
}

internal object SettingsInformationArchitecture {
    val sectionsByCategory: Map<SettingsCategory, Set<SettingsSection>> = mapOf(
        SettingsCategory.ABOUT to emptySet(),
        SettingsCategory.AUTHORIZATION to setOf(SettingsSection.SYSTEM_AUTHORIZATION),
        SettingsCategory.CONNECTION to setOf(
            SettingsSection.AUTOMATIC_CONNECTION,
            SettingsSection.CONNECTION_SETUP,
            SettingsSection.USB_CONNECTION,
            SettingsSection.HOTSPOT_AUTOMATION,
        ),
        SettingsCategory.DISPLAY to setOf(SettingsSection.DISPLAY_AND_PERFORMANCE),
        SettingsCategory.AUDIO to setOf(SettingsSection.AUDIO_SCHEME, SettingsSection.AUDIO_ROUTING),
        SettingsCategory.NAVIGATION to setOf(SettingsSection.INSTRUMENT_NAVIGATION, SettingsSection.LOCATION),
        SettingsCategory.VEHICLE to setOf(
            SettingsSection.CARPLAY_CONTROLS,
            SettingsSection.WHEEL_KEYS,
            SettingsSection.CAR_BUTTON,
        ),
        SettingsCategory.DIAGNOSTICS to setOf(SettingsSection.DIAGNOSTICS),
        SettingsCategory.EXPERIMENTAL to setOf(
            SettingsSection.ADVANCED_MEDIA,
            SettingsSection.EXTERNAL_CONTROLLER,
        ),
    )
}
