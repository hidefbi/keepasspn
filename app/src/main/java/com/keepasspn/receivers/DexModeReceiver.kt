package com.keepasspn.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.keepasspn.utils.NoOpLog
import com.keepasspn.utils.MagikeyboardUtil

class DexModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val enabled = when (intent?.action) {
            "android.app.action.ENTER_KNOX_DESKTOP_MODE" -> {
                NoOpLog.i(TAG, "Entered DeX mode")
                false
            }
            "android.app.action.EXIT_KNOX_DESKTOP_MODE" -> {
                NoOpLog.i(TAG, "Left DeX mode")
                true
            }
            else -> return
        }

        MagikeyboardUtil.setEnabled(context!!, enabled)
    }

    companion object {
        private val TAG = DexModeReceiver::class.java.name
    }
}