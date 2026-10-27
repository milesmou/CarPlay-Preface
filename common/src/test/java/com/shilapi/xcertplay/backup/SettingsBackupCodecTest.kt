package com.shilapi.xcertplay.backup

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsBackupCodecTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun instrumentNavigationBacksUpOnlyItsSwitch() {
        val values = prefs("diplay_instrument_navigation")
        values.edit().putBoolean("enabled", true).putString("last_road", "private-road").commit()
        val bytes = SettingsBackupCodec.export(context, "test")
        val saved = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("settings")
            .getJSONObject("diplay_instrument_navigation")
        assertTrue(saved.getBoolean("enabled"))
        assertFalse(saved.has("last_road"))
        values.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(context, bytes).invalid)
        assertTrue(com.shilapi.xcertplay.vehicle.PrefaceInstrumentSettings.enabled(context))
    }

    @Test fun automaticConnectionBacksUpOnlyTheUnifiedOption() {
        val values = prefs("diplay")
        values.edit().clear().putBoolean("auto_connect", false)
            .putBoolean("connect_on_phone_bluetooth", true).putString("default_connection_mode", "usb").commit()
        val document = JSONObject(SettingsBackupCodec.export(context, "0.1.1").toString(Charsets.UTF_8))
        val saved = document.getJSONObject("settings").getJSONObject("diplay")
        assertFalse(saved.getBoolean("auto_connect"))
        assertFalse(saved.has("connect_on_phone_bluetooth"))
        assertFalse(saved.has("default_connection_mode"))
        values.edit().clear().commit()
        val imported = """{"format":"diplay-settings","version":1,"settings":{"diplay":{
            "auto_connect":false,"connect_on_phone_bluetooth":true,"default_connection_mode":"usb"}}}""".toByteArray()
        val result = SettingsBackupCodec.import(context, imported)
        assertEquals(1, result.applied)
        assertEquals(2, result.skipped)
        assertFalse(com.shilapi.xcertplay.DiPlayPreferences.autoConnect(context))
        assertFalse(values.contains("default_connection_mode"))
    }

    @Test fun wheelBindingsRoundTripWithoutIncludingUnrelatedInputData() {
        val values = prefs("diplay_media_wheel")
        values.edit().clear().putInt("PREVIOUS", 131).putInt("NEXT", -1)
            .putInt("SIRI", 231).putString("device_name", "private-device").commit()
        try {
            val bytes = SettingsBackupCodec.export(context, "0.1.1")
            val saved = JSONObject(bytes.toString(Charsets.UTF_8))
                .getJSONObject("settings").getJSONObject("diplay_media_wheel")
            assertEquals(3, saved.length())
            assertFalse(saved.has("device_name"))
            values.edit().clear().commit()
            assertFalse(SettingsBackupCodec.import(context, bytes).invalid)
            assertEquals(131, values.getInt("PREVIOUS", 0))
            assertEquals(-1, values.getInt("NEXT", 0))
            assertEquals(231, values.getInt("SIRI", 0))
        } finally { values.edit().clear().commit() }
    }

    @Test fun backupUsesCurrentKeysAndSkipsObsoleteConfiguration() {
        val values = prefs("xcertplay_airplay")
        values.edit().clear()
            .putInt("display_scale_percent", 75)
            .putInt("navigation_audio_channel", 0)
            .putBoolean("cluster_music_enabled", true)
            .putInt("cluster_speedometer_size", 100)
            .putInt("display_scale_tenths", 6)
            .putInt("navigation_stream_type", 15)
            .putString("cluster_turn_card_overlay_position", "LEFT")
            .putInt("cluster_marker_horizontal_step", 2)
            .putBoolean("adapt_pip_resolution", true)
            .commit()
        val file = JSONObject(SettingsBackupCodec.export(context, "0.1.1").toString(Charsets.UTF_8))
            .getJSONObject("settings").getJSONObject("xcertplay_airplay")
        assertEquals(75, file.getInt("display_scale_percent"))
        assertEquals(0, file.getInt("navigation_audio_channel"))
        assertTrue(file.getBoolean("cluster_music_enabled"))
        assertEquals(100, file.getInt("cluster_speedometer_size"))
        for (key in listOf("display_scale_tenths", "navigation_stream_type",
            "cluster_turn_card_overlay_position", "cluster_marker_horizontal_step", "adapt_pip_resolution")) {
            assertFalse(file.has(key))
        }
        val obsolete = """{"format":"diplay-settings","version":1,"settings":{
            "xcertplay_airplay":{"display_scale_tenths":6,"navigation_stream_type":15,
            "cluster_turn_card_overlay_position":"LEFT","cluster_marker_horizontal_step":2,"adapt_pip_resolution":true}}}"""
        values.edit().clear().commit()
        val outcome = SettingsBackupCodec.import(context, obsolete.toByteArray())
        assertFalse(outcome.invalid)
        assertEquals(0, outcome.applied)
        assertEquals(5, outcome.skipped)
        assertTrue(values.all.isEmpty())
    }

    @Test
    fun importingAnOlderBackupSkipsRemovedVehicleAdaptersAndKeepsAudioSettings() {
        prefs("xcertplay_airplay").edit().clear()
            .putInt("navigation_audio_channel", 12).apply()
        prefs("diplay_fce_navigation").edit().clear().apply()
        val document = """
            {"format":"diplay-settings","version":1,"settings":{
              "xcertplay_airplay":{"media_audio_channel":3,"harman_fullscreen":true,"hide_byd_call_popup":true},
              "diplay_fce_navigation":{"enabled":true}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertFalse(outcome.invalid)
        assertEquals(1, outcome.applied)
        assertEquals(3, outcome.skipped)
        assertEquals(3, prefs("xcertplay_airplay").getInt("media_audio_channel", 0))
        assertEquals(12, prefs("xcertplay_airplay").getInt("navigation_audio_channel", 0))
        assertFalse(prefs("xcertplay_airplay").contains("harman_fullscreen"))
        assertFalse(prefs("xcertplay_airplay").contains("hide_byd_call_popup"))
        assertFalse(prefs("diplay_fce_navigation").contains("enabled"))
    }

    @Test
    fun exportOmitsSensitiveAndNavigationKeys() {
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 60)
            .putString("identity_private", "secret")
            .putString("manual_hotspot_passphrase", "wifi-pass")
            .putStringSet("pairing_ids", setOf("pair-1"))
            .putString("pairing.0F7A", "a1b2")
            .putString("manual_hotspot_ssid", "car-ap")
            .putString("mfi_target", "REMOTE")
            .putBoolean("cluster_map_enabled", true)
            .apply()
        prefs("diplay").edit()
            .putBoolean("auto_connect", true)
            .putString("page", "settings")
            .putString("phone_name", "iphone-of-owner")
            .apply()
        prefs("carplay_picture").edit().putInt("brightness", 80).apply()
        val document = JSONObject(String(SettingsBackupCodec.export(context, "0.2.17")))
        assertEquals(SettingsBackupCodec.FORMAT, document.getString("format"))
        assertEquals(SettingsBackupCodec.VERSION, document.getInt("version"))
        val airplay = document.getJSONObject("settings").getJSONObject("xcertplay_airplay")
        assertEquals(60, airplay.getInt("display_fps"))
        assertFalse(airplay.has("cluster_map_enabled"))
        assertFalse(airplay.has("manual_hotspot_ssid"))
        assertFalse(airplay.has("pairing.0F7A"))
        assertFalse(airplay.has("mfi_target"))
        assertFalse(airplay.has("identity_private"))
        assertFalse(airplay.has("manual_hotspot_passphrase"))
        assertFalse(airplay.has("pairing_ids"))
        val diplay = document.getJSONObject("settings").getJSONObject("diplay")
        assertTrue(diplay.getBoolean("auto_connect"))
        assertFalse(diplay.has("page"))
        assertFalse(diplay.has("phone_name"))
        assertEquals(80, document.getJSONObject("settings").getJSONObject("carplay_picture").getInt("brightness"))
        assertFalse(document.getJSONObject("settings").has("diplay_car_hotspot"))
    }

    @Test
    fun importAppliesKnownKeysAndSkipsUnknownOrForbidden() {
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"display_fps": 48, "brand_new_key": 7, "manual_hotspot_passphrase": "x",
                "pairing.attacker": "00ff"},
              "diplay": {"auto_connect": true, "page": "about"},
              "carplay_picture": {"brightness": 66}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(false, outcome.invalid)
        assertEquals(3, outcome.applied)
        assertEquals(4, outcome.skipped)
        assertEquals(48, prefs("xcertplay_airplay").getInt("display_fps", 0))
        assertFalse(prefs("xcertplay_airplay").contains("manual_hotspot_passphrase"))
        // Only listed settings travel: an imported file cannot add an iPhone pairing key or any unknown key.
        assertFalse(prefs("xcertplay_airplay").contains("pairing.attacker"))
        assertFalse(prefs("xcertplay_airplay").contains("brand_new_key"))
        assertEquals(true, prefs("diplay").getBoolean("auto_connect", false))
        assertFalse(prefs("diplay").contains("page"))
        assertEquals(66, prefs("carplay_picture").getInt("brightness", 0))
    }

    @Test
    fun importRejectsForeignOrNewerDocuments() {
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"other\",\"version\":1,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":99,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "not json".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{}".toByteArray()).invalid)
    }

    @Test
    fun importMatchesTypesAgainstStoredValues() {
        prefs("xcertplay_airplay").edit().putInt("display_fps", 60).putBoolean("hevc_enabled", false).apply()
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"display_fps": "48", "hevc_enabled": true, "display_scale_percent": 85}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(2, outcome.applied)
        assertEquals(1, outcome.skipped)
        assertEquals(60, prefs("xcertplay_airplay").getInt("display_fps", 0))
        assertEquals(true, prefs("xcertplay_airplay").getBoolean("hevc_enabled", true))
        assertEquals(85, prefs("xcertplay_airplay").getInt("display_scale_percent", 0))
    }

    @Test
    fun importRejectsMissingOrUnsupportedVersions() {
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":0,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":-1,\"settings\":{}}".toByteArray()).invalid)
    }

    @Test
    fun importRejectsOutOfRangeOrFractionalNumbers() {
        prefs("xcertplay_airplay").edit()
            .putLong("cluster_marker_x_percent", 5L)
            .putFloat("ui_scale_percent", 1f)
            .putInt("display_fps", 60)
            .apply()
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"cluster_marker_x_percent": 1.5, "ui_scale_percent": 1e300, "display_fps": 3000000000}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(0, outcome.applied)
        assertEquals(3, outcome.skipped)
        assertEquals(5L, prefs("xcertplay_airplay").getLong("cluster_marker_x_percent", 0L))
        assertEquals(1f, prefs("xcertplay_airplay").getFloat("ui_scale_percent", 0f))
        assertEquals(60, prefs("xcertplay_airplay").getInt("display_fps", 0))
    }

    @Test
    fun exportImportRoundTripRestoresValues() {
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 60)
            .putBoolean("hevc_enabled", true)
            .putString("manual_hotspot_ssid", "car-ap")
            .putFloat("ui_scale_percent", 100f)
            .apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", true).apply()
        val document = SettingsBackupCodec.export(context, "0.2.17")
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 30)
            .putBoolean("hevc_enabled", false)
            .putString("manual_hotspot_ssid", "changed")
            .putFloat("ui_scale_percent", 120f)
            .apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", false).apply()
        val outcome = SettingsBackupCodec.import(context, document)
        assertEquals(false, outcome.invalid)
        val airplay = prefs("xcertplay_airplay")
        assertEquals(60, airplay.getInt("display_fps", 0))
        assertEquals(true, airplay.getBoolean("hevc_enabled", false))
        // The car's hotspot name is not part of a backup.
        assertEquals("changed", airplay.getString("manual_hotspot_ssid", ""))
        assertEquals(100f, airplay.getFloat("ui_scale_percent", 0f))
        // The removed car-hotspot mode is not restored from a settings backup.
        assertEquals(false, prefs("diplay_car_hotspot").getBoolean("auto_enable", false))
        assertFalse(airplay.contains("identity_private"))
    }
}
