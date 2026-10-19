package com.shilapi.xcertplay

import android.content.Context

/** Select only the audio transport; volume keys remain with the head unit. */
internal enum class MusicWheelMode {
    CARPLAY, CAR_BLUETOOTH;

    companion object {
        fun load(context: Context): MusicWheelMode =
            if (AirPlayPersistence.loadCarBluetoothAudio(context)) CAR_BLUETOOTH else CARPLAY

        fun save(context: Context, mode: MusicWheelMode) {
            AirPlayPersistence.saveCarBluetoothAudio(context, mode == CAR_BLUETOOTH)
        }
    }
}
