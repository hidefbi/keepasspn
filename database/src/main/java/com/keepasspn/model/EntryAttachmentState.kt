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
package com.keepasspn.model

import android.os.Parcel
import android.os.Parcelable
import com.keepasspn.database.element.Attachment
import com.keepasspn.database.element.binary.BinaryByte
import com.keepasspn.utils.readEnum
import com.keepasspn.utils.readParcelableCompat
import com.keepasspn.utils.writeEnum

data class EntryAttachmentState(
    var attachment: Attachment,
    var streamDirection: StreamDirection,
    var downloadState: AttachmentState = AttachmentState.COMPLETE,
    var downloadProgression: Int = 0
) : Parcelable {

    constructor(parcel: Parcel) : this(
            parcel.readParcelableCompat() ?: Attachment("", BinaryByte()),
            parcel.readEnum<StreamDirection>() ?: StreamDirection.DOWNLOAD,
            parcel.readEnum<AttachmentState>() ?: AttachmentState.COMPLETE,
            parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(attachment, flags)
        parcel.writeEnum(streamDirection)
        parcel.writeEnum(downloadState)
        parcel.writeInt(downloadProgression)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EntryAttachmentState

        if (downloadProgression != other.downloadProgression) return false
        if (attachment != other.attachment) return false
        if (streamDirection != other.streamDirection) return false
        if (downloadState != other.downloadState) return false

        return true
    }

    override fun hashCode(): Int {
        var result = downloadProgression
        result = 31 * result + attachment.hashCode()
        result = 31 * result + streamDirection.hashCode()
        result = 31 * result + downloadState.hashCode()
        return result
    }

    companion object CREATOR : Parcelable.Creator<EntryAttachmentState> {
        override fun createFromParcel(parcel: Parcel): EntryAttachmentState {
            return EntryAttachmentState(parcel)
        }

        override fun newArray(size: Int): Array<EntryAttachmentState?> {
            return arrayOfNulls(size)
        }
    }
}

enum class AttachmentState {
    START, IN_PROGRESS, COMPLETE, CANCELED, ERROR
}