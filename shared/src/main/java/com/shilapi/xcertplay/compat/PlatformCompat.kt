package com.shilapi.xcertplay.compat

import android.location.LocationManager
import android.os.Build

/** [LocationManager.isLocationEnabled] needs API 28; before that a location provider must be on. */
fun LocationManager.isLocationEnabledCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        isLocationEnabled
    } else {
        isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
