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
package com.keepasspn.settings.preference

import android.content.Context
import android.util.AttributeSet
import com.keepasspn.R
import com.keepasspn.utils.DataByte

open class InputSizePreference @JvmOverloads constructor(context: Context,
                                                         attrs: AttributeSet? = null,
                                                         defStyleAttr: Int = R.attr.dialogPreferenceStyle,
                                                         defStyleRes: Int = defStyleAttr)
    : InputNumberPreference(context, attrs, defStyleAttr, defStyleRes) {

    override fun setSummary(summary: CharSequence?) {
        var summaryString = summary
        try {
            val memorySize = summary.toString().toLong()
            summaryString = if (memorySize >= 0) {
                // To convert bytes to mebibytes
                DataByte(memorySize, DataByte.ByteFormat.BYTE)
                        .toBetterByteFormat().toString(context)
            } else {
                memorySize.toString()
            }
        } catch (e: Exception) {
        } finally {
            super.setSummary(summaryString)
        }
    }
}
