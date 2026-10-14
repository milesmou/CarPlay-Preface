package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

internal fun ManualHotspotValidation.Error.messageResource(): Int = when (this) {
    ManualHotspotValidation.Error.EMPTY_NAME -> R.string.settings_hotspot_empty_name
    ManualHotspotValidation.Error.LONG_NAME -> R.string.settings_hotspot_long_name
    ManualHotspotValidation.Error.INVALID_CHARACTER -> R.string.settings_hotspot_invalid_character
    ManualHotspotValidation.Error.PASSWORD_LENGTH -> R.string.settings_hotspot_password_length
}
