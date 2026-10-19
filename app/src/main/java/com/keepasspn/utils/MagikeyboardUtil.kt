package com.keepasspn.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.keepasspn.utils.NoOpLog
import com.keepasspn.credentialprovider.magikeyboard.MagikeyboardService

object MagikeyboardUtil {
    private val TAG = MagikeyboardUtil::class.java.name

    // Set whether MagikeyboardService is enabled. This change is persistent and survives app
    // crashes and device restarts. The state is changed immediately and does not require an app
    // restart.
    fun setEnabled(context: Context, enabled: Boolean) {
        val componentState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }

        NoOpLog.d(TAG, "Setting service state: $enabled")

        val component = ComponentName(context, MagikeyboardService::class.java)
        context.packageManager.setComponentEnabledSetting(component, componentState, PackageManager.DONT_KILL_APP)
    }
}