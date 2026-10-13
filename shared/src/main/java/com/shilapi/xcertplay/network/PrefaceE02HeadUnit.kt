package com.shilapi.xcertplay.network

import android.os.Build

/** Firmware evidence: IHU519G / FS11A1, Android 9 build 327; see docs/PREFACE_FIRMWARE_327.md. */
object PrefaceE02HeadUnit {
    const val MEDIA_STREAM = 3
    const val NAVIGATION_STREAM = 12

    val detected: Boolean get() = matches(Build.PRODUCT, Build.MODEL, Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL)

    fun matches(product: String?, model: String?, sdk: Int, incremental: String?): Boolean =
        product.equals("IHU519G", ignoreCase = true) && model.equals("FS11A1", ignoreCase = true) &&
            sdk == 28 && incremental == "327"
}
