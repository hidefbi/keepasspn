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
package com.keepasspn.credentialprovider.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillManager
import androidx.annotation.RequiresApi
import com.keepasspn.R
import com.keepasspn.model.OtpModel
import com.keepasspn.otp.OtpElement
import com.keepasspn.timeout.timeoutCopyToClipboard
import com.keepasspn.utils.NoOpLog
import com.keepasspn.utils.getParcelableExtraCompat

/**
 * Transparent step launched when an autofill entry that has a TOTP is selected.
 *
 * Android only allows clipboard writes from the foreground, so the TOTP code is copied here,
 * then the prepared dataset is returned to the autofill framework.
 * The fill never depends on the copy: the dataset is returned even if the copy fails.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
class AutofillTotpCopyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataset = intent.getParcelableExtraCompat<Dataset>(EXTRA_FILLED_DATASET)
        if (dataset == null) {
            NoOpLog.w(TAG, "No dataset to fill")
            setResult(RESULT_CANCELED)
        } else {
            copyTotpIfNeeded(intent.getParcelableExtraCompat<OtpModel>(EXTRA_OTP_MODEL))
            setResult(
                RESULT_OK,
                Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    private fun copyTotpIfNeeded(otpModel: OtpModel?) {
        if (otpModel == null) return
        try {
            val token = OtpElement(otpModel).token
            timeoutCopyToClipboard(
                label = getString(R.string.entry_otp),
                value = token,
                sensitive = true
            )
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to copy the TOTP code", e)
        } finally {
            otpModel.clear()
        }
    }

    companion object {
        private val TAG = AutofillTotpCopyActivity::class.java.name

        const val EXTRA_OTP_MODEL = "com.keepasspn.autofill.EXTRA_OTP_MODEL"
        const val EXTRA_FILLED_DATASET = "com.keepasspn.autofill.EXTRA_FILLED_DATASET"
    }
}
