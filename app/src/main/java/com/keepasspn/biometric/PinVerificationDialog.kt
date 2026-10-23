/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.biometric

import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.keepasspn.R
import kotlinx.coroutines.launch

/**
 * Asks for the database PIN to verify the user.
 *
 * Uses the same PIN store and failure policy as PIN unlock: a wrong PIN counts, and
 * after the limit the PIN is deleted and [onFailed] receives [PinWipedException].
 * The dialog uses the app's text-field style, so it follows every theme.
 */
@RequiresApi(Build.VERSION_CODES.M)
object PinVerificationDialog {

    /**
     * @param onVerified called once the PIN is correct
     * @param onFailed called with null when the user cancels, or with the error otherwise
     */
    fun show(
        activity: FragmentActivity,
        databaseUri: Uri,
        onVerified: () -> Unit,
        onFailed: (Throwable?) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_pin_verify, null)
        val layout = view.findViewById<TextInputLayout>(R.id.pin_verify_layout)
        val edit = view.findViewById<TextInputEditText>(R.id.pin_verify_edit)
        val progress = view.findViewById<ProgressBar>(R.id.pin_verify_progress)

        var finished = false
        var busy = false
        fun cancel() {
            // Ignore cancel (back key, negative button) while the PIN check is running
            if (!finished && !busy) {
                finished = true
                onFailed(null)
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_unlock_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null) // set below to keep the dialog open on error
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()

        // Show progress and lock the input while the key derivation runs, so the
        // user gets immediate feedback and cannot start a second attempt
        fun setBusy(isBusy: Boolean) {
            busy = isBusy
            progress.visibility = if (isBusy) View.VISIBLE else View.GONE
            edit.isEnabled = !isBusy
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !isBusy
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !isBusy
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy || finished) return@setOnClickListener
                layout.error = null
                val pin = edit.text.toString().toCharArray()
                setBusy(true)
                activity.lifecycleScope.launch {
                    try {
                        // The PIN array is wiped by unlockWithPin
                        val password = PinCryptoHelper.unlockWithPin(activity, databaseUri, pin)
                        password.fill(0)
                        finished = true
                        dialog.dismiss()
                        onVerified()
                    } catch (e: WrongPinException) {
                        setBusy(false)
                        layout.error = activity.getString(
                            R.string.pin_unlock_wrong_pin, e.remainingAttemptsBeforeLockout
                        )
                    } catch (e: PinWipedException) {
                        finished = true
                        dialog.dismiss()
                        Toast.makeText(activity, R.string.pin_unlock_wiped, Toast.LENGTH_LONG).show()
                        onFailed(e)
                    } catch (e: Exception) {
                        finished = true
                        dialog.dismiss()
                        Toast.makeText(activity, deviceUnlockError(e, activity), Toast.LENGTH_LONG).show()
                        onFailed(e)
                    }
                }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        // Focus the PIN field and open the keyboard as soon as the dialog appears
        edit.post {
            edit.requestFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
