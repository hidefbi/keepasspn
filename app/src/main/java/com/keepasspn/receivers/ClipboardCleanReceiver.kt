package com.keepasspn.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.keepasspn.timeout.cleanClipboard

/**
 * Clears the clipboard when the timeout of the latest copy is reached.
 * An alarm whose token is not the latest copy token is ignored.
 */
class ClipboardCleanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val copyToken = intent.getStringExtra(EXTRA_COPY_TOKEN) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LATEST_TOKEN, null) == copyToken) {
            prefs.edit().remove(KEY_LATEST_TOKEN).apply()
            context.cleanClipboard()
        }
    }

    companion object {
        const val EXTRA_COPY_TOKEN = "com.keepasspn.EXTRA_COPY_TOKEN"
        const val PREFS_NAME = "clipboard_clean"
        const val KEY_LATEST_TOKEN = "latest_copy_token"
    }
}
