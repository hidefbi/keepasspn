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
package com.keepasspn.database.element.database

import android.os.Parcel
import android.os.Parcelable
import com.keepasspn.utils.readEnum
import com.keepasspn.utils.writeEnum

// Note: We can get away with using int's to store unsigned 32-bit ints
//       since we won't do arithmetic on these values (also unlikely to
//       reach negative ids).
enum class CompressionAlgorithm : Parcelable {
    NONE,
    GZIP;

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeEnum(this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<CompressionAlgorithm> {
        override fun createFromParcel(parcel: Parcel): CompressionAlgorithm {
            return parcel.readEnum<CompressionAlgorithm>() ?: NONE
        }

        override fun newArray(size: Int): Array<CompressionAlgorithm?> {
            return arrayOfNulls(size)
        }
    }
}
