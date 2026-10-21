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
package com.keepasspn.activities

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.keepasspn.R

/**
 * Asks the user to confirm before the camera is opened to scan one-time codes.
 */
object ImportOtpPrompt {

    fun show(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.import_otp_prompt_title)
            .setMessage(R.string.import_otp_prompt_message)
            .setNegativeButton(R.string.import_otp_prompt_cancel, null)
            .setPositiveButton(R.string.import_otp_prompt_start) { _, _ ->
                context.startActivity(Intent(context, ImportOtpScannerActivity::class.java))
            }
            .show()
    }
}
