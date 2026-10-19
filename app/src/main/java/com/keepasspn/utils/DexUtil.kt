package com.keepasspn.utils

import android.content.res.Configuration
import com.keepasspn.utils.NoOpLog

object DexUtil {
    private val TAG = DexUtil::class.java.name

    // Determine if the current environment is in DeX mode. Always returns false on non-Samsung
    // devices.
    fun isDexMode(config: Configuration): Boolean {
        // This is the documented way to check this: https://developer.samsung.com/samsung-dex/modify-optimizing.html
        return try {
            val configClass = config.javaClass
            val enabledConstant = configClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(configClass)
            val enabledField = configClass.getField("semDesktopModeEnabled").getInt(config)
            val isEnabled = enabledConstant == enabledField

            NoOpLog.d(TAG, "DeX currently enabled: $isEnabled")

            isEnabled
        } catch (e: Exception) {
            NoOpLog.d(TAG, "Failed to check for DeX mode; likely not Samsung device: $e")
            false
        }
    }
}