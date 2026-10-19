/*
 * Copyright 2021 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.database.element.icon

import android.os.Parcel
import android.os.Parcelable
import com.keepasspn.utils.readParcelableCompat

class IconImage() : IconImageDraw() {

    var standard: IconImageStandard = IconImageStandard()
    var custom: IconImageCustom = IconImageCustom()

    constructor(iconImage: IconImage) : this() {
        this.standard = IconImageStandard(iconImage.standard)
        this.custom = IconImageCustom(iconImage.custom)
    }

    constructor(iconImageStandard: IconImageStandard) : this() {
        this.standard = iconImageStandard
    }

    constructor(iconImageCustom: IconImageCustom) : this() {
        this.custom = iconImageCustom
    }

    constructor(iconImageStandard: IconImageStandard,
                iconImageCustom: IconImageCustom) : this() {
        this.standard = iconImageStandard
        this.custom = iconImageCustom
    }

    constructor(parcel: Parcel) : this() {
        standard = parcel.readParcelableCompat() ?: standard
        custom = parcel.readParcelableCompat() ?: custom
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(standard, flags)
        parcel.writeParcelable(custom, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun getIconImageToDraw(): IconImage {
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IconImage) return false

        if (standard != other.standard) return false
        if (custom != other.custom) return false

        return true
    }

    override fun hashCode(): Int {
        var result = standard.hashCode()
        result = 31 * result + custom.hashCode()
        return result
    }

    companion object CREATOR : Parcelable.Creator<IconImage> {
        override fun createFromParcel(parcel: Parcel): IconImage {
            return IconImage(parcel)
        }

        override fun newArray(size: Int): Array<IconImage?> {
            return arrayOfNulls(size)
        }
    }
}
