/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 *  KeePassPN is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassPN is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.timeout

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import java.util.UUID
import com.keepasspn.utils.NoOpLog
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.keepasspn.R
import com.keepasspn.receivers.ClipboardCleanReceiver
import com.keepasspn.settings.PreferencesUtil

private const val TAG = "ClipboardHelper"
private const val DEFAULT_LABEL = ""
private const val CLIPBOARD_CLEAN_REQUEST_CODE = 7531

private fun Context.clipboardManager() = ContextCompat.getSystemService(
    applicationContext,
    ClipboardManager::class.java
)

/**
 * Copy value to clipboard and clear it after a timeout.
 * @param label The label of the clipboard entry.
 * @param value The value to copy.
 * @param sensitive Whether the value is sensitive.
 */
fun Context.timeoutCopyToClipboard(
    label: String,
    value: CharSequence,
    sensitive: Boolean = true
) {
    try {
        copyToClipboard(label, value, sensitive)
    } catch (e: Exception) {
        NoOpLog.e(TAG, "Unable to copy to clipboard", e)
        Toast.makeText(
            applicationContext,
            applicationContext.getString(R.string.clipboard_error_title),
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val clipboardTimeout = PreferencesUtil.getClipboardTimeout(applicationContext)
    if (clipboardTimeout > 0) {
        scheduleClipboardClean(clipboardTimeout)
    }
}

/**
 * Schedule the clipboard clean with an alarm, so it still runs when the app is in background
 * or its process is frozen. A new copy replaces the pending alarm (restarts the timer) and
 * gets a new token, so an older alarm can never clean the clipboard of a newer copy.
 */
private fun Context.scheduleClipboardClean(delayMillis: Long) {
    val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        ?: return
    val triggerTime = System.currentTimeMillis() + delayMillis
    // A unique token per copy: only the alarm of the latest copy is allowed to clean
    val copyToken = UUID.randomUUID().toString()
    applicationContext.getSharedPreferences(
        ClipboardCleanReceiver.PREFS_NAME, Context.MODE_PRIVATE
    ).edit().putString(ClipboardCleanReceiver.KEY_LATEST_TOKEN, copyToken).apply()
    val pendingIntent = PendingIntent.getBroadcast(
        applicationContext,
        CLIPBOARD_CLEAN_REQUEST_CODE,
        Intent(applicationContext, ClipboardCleanReceiver::class.java)
            .putExtra(ClipboardCleanReceiver.EXTRA_COPY_TOKEN, copyToken),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && !alarmManager.canScheduleExactAlarms()) {
        alarmManager.set(AlarmManager.RTC, triggerTime, pendingIntent)
    } else {
        alarmManager.setExact(AlarmManager.RTC, triggerTime, pendingIntent)
    }
}

/**
 * Copy value to clipboard and clear it after a timeout.
 * @param label The label of the clipboard entry.
 * @param value The value to copy.
 * @param sensitive Whether the value is sensitive.
 */
fun Context.timeoutCopyToClipboard(
    label: String,
    value: CharArray?,
    sensitive: Boolean = true
) {
    if (value == null) return
    timeoutCopyToClipboard(label, String(value), sensitive)
}

/**
 * Copy value to clipboard.
 * @param label The label of the clipboard entry.
 * @param value The value to copy.
 * @param sensitive Whether the value is sensitive.
 */
fun Context.copyToClipboard(
    label: String,
    value: CharSequence,
    sensitive: Boolean = true
) {
    clipboardManager()?.setPrimaryClip(
        ClipData.newPlainText(DEFAULT_LABEL, value).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", sensitive)
                }
            }
        },
    )
    if (label.isNotEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
        Toast.makeText(
            applicationContext,
            applicationContext.getString(R.string.copy_field, label),
            Toast.LENGTH_LONG
        ).show()
    }
}

/**
 * Copy value to clipboard.
 * @param label The label of the clipboard entry.
 * @param value The value to copy.
 * @param sensitive Whether the value is sensitive.
 */
fun Context.copyToClipboard(
    label: String,
    value: CharArray?,
    sensitive: Boolean = true
) {
    if (value == null) return
    copyToClipboard(label, String(value), sensitive)
}

/**
 * Clean the clipboard.
 */
fun Context.cleanClipboard() {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboardManager()?.clearPrimaryClip()
        } else {
            copyToClipboard(DEFAULT_LABEL, "")
        }
    } catch (e: Exception) {
        NoOpLog.e(TAG, "Unable to clean the clipboard", e)
    }
}
