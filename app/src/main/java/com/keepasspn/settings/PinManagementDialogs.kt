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
package com.keepasspn.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.keepasspn.R
import com.keepasspn.biometric.PinCryptoHelper
import com.keepasspn.biometric.PinPolicyManager
import com.keepasspn.biometric.PinWipedException
import com.keepasspn.biometric.WrongPinException
import com.keepasspn.biometric.deviceUnlockError
import com.keepasspn.database.pin.PinPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings dialogs that manage PINs of existing databases.
 * Every dialog uses the activity theme and the app's text-field style, so it follows
 * all KeePassPN themes and fonts.
 */
@RequiresApi(Build.VERSION_CODES.M)
object PinManagementDialogs {

    fun showDatabaseList(context: Context, scope: LifecycleCoroutineScope) {
        scope.launch {
            val uris = try {
                PinCryptoHelper.listPinDatabaseUris(context)
            } catch (e: Exception) {
                Toast.makeText(context, deviceUnlockError(e, context), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (uris.isEmpty()) {
                Toast.makeText(context, R.string.pin_manage_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = withContext(Dispatchers.IO) { uris.map { displayName(context, it) } }
            AlertDialog.Builder(context)
                .setTitle(R.string.pin_manage_title)
                .setItems(names.toTypedArray()) { _, index ->
                    showActions(context, scope, uris[index], names[index])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun showActions(
        context: Context,
        scope: LifecycleCoroutineScope,
        databaseUri: Uri,
        name: String
    ) {
        AlertDialog.Builder(context)
            .setTitle(name)
            .setItems(arrayOf(
                context.getString(R.string.pin_manage_action_change),
                context.getString(R.string.pin_manage_action_disable)
            )) { _, which ->
                when (which) {
                    0 -> showChangePinDialog(context, scope, databaseUri)
                    1 -> confirmDisable(context, scope, databaseUri)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDisable(context: Context, scope: LifecycleCoroutineScope, databaseUri: Uri) {
        AlertDialog.Builder(context)
            .setTitle(R.string.pin_disable_confirm_title)
            .setMessage(R.string.pin_disable_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                scope.launch {
                    PinCryptoHelper.removePin(context, databaseUri)
                    Toast.makeText(context, R.string.pin_disabled_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChangePinDialog(context: Context, scope: LifecycleCoroutineScope, databaseUri: Uri) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin_change, null)
        val oldLayout = view.findViewById<TextInputLayout>(R.id.pin_change_old_layout)
        val newLayout = view.findViewById<TextInputLayout>(R.id.pin_change_new_layout)
        val confirmLayout = view.findViewById<TextInputLayout>(R.id.pin_change_confirm_layout)
        val oldEdit = view.findViewById<TextInputEditText>(R.id.pin_change_old)
        val newEdit = view.findViewById<TextInputEditText>(R.id.pin_change_new)
        val confirmEdit = view.findViewById<TextInputEditText>(R.id.pin_change_confirm)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.pin_change_dialog_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                oldLayout.error = null
                newLayout.error = null
                confirmLayout.error = null

                val oldPin = oldEdit.text.toString().toCharArray()
                val newPin = newEdit.text.toString().toCharArray()
                val confirmPin = confirmEdit.text.toString().toCharArray()
                try {
                    when {
                        newPin.size < PIN_MIN_LENGTH -> {
                            newLayout.error = context.getString(R.string.pin_setup_too_short)
                        }
                        !newPin.contentEquals(confirmPin) -> {
                            confirmLayout.error = context.getString(R.string.pin_setup_mismatch)
                        }
                        else -> {
                            // The PIN arrays are wiped by changePin, whatever the outcome
                            scope.launch {
                                try {
                                    PinCryptoHelper.changePin(context, databaseUri, oldPin, newPin)
                                    dialog.dismiss()
                                    Toast.makeText(context, R.string.pin_change_done, Toast.LENGTH_SHORT).show()
                                } catch (e: WrongPinException) {
                                    oldLayout.error = context.getString(
                                        R.string.pin_change_wrong_old, e.remainingAttemptsBeforeLockout
                                    )
                                } catch (e: PinWipedException) {
                                    dialog.dismiss()
                                    Toast.makeText(context, R.string.pin_unlock_wiped, Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, deviceUnlockError(e, context), Toast.LENGTH_LONG).show()
                                } finally {
                                    oldPin.fill('\u0000')
                                    newPin.fill('\u0000')
                                    confirmPin.fill('\u0000')
                                }
                            }
                            return@setOnClickListener
                        }
                    }
                } finally {
                    // Covers the validation branches, which return before the coroutine
                    if (newLayout.error != null || confirmLayout.error != null) {
                        oldPin.fill('\u0000')
                        newPin.fill('\u0000')
                        confirmPin.fill('\u0000')
                    }
                }
            }
        }
        dialog.show()
    }

    private fun displayName(context: Context, databaseUri: Uri): String {
        val documentName = try {
            DocumentFile.fromSingleUri(context, databaseUri)?.name
        } catch (_: Exception) {
            null
        }
        return documentName ?: databaseUri.lastPathSegment ?: databaseUri.toString()
    }

    private const val PIN_MIN_LENGTH = 6
}
