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
package com.keepasspn.database.element

import com.keepasspn.database.element.binary.BinaryCache
import com.keepasspn.database.element.binary.BinaryData
import com.keepasspn.database.element.database.DatabaseKDB
import com.keepasspn.database.element.database.DatabaseKDBX
import com.keepasspn.database.element.icon.IconImageCustom
import com.keepasspn.database.element.icon.IconImageStandard
import com.keepasspn.database.element.icon.IconImageStandard.Companion.NUMBER_STANDARD_ICONS
import com.keepasspn.database.element.icon.IconsManager
import java.util.UUID

/**
 * Standard and custom icons of the loaded database.
 *
 * The icons belong to the KDB or KDBX database currently loaded, so they are read through
 * [kdb], [kdbx] and [binaryCache] at each call, as the database may change.
 */
internal class DatabaseIcons(
    private val kdb: () -> DatabaseKDB?,
    private val kdbx: () -> DatabaseKDBX?,
    private val binaryCache: () -> BinaryCache,
) {

    private val iconsManager: IconsManager
        get() {
            return kdb()?.iconsManager ?: kdbx()?.iconsManager
            ?: IconsManager(NUMBER_STANDARD_ICONS)
        }

    fun doForEachStandardIcons(action: (IconImageStandard) -> Unit) {
        iconsManager.doForEachStandardIcon(action)
    }

    fun getStandardIcon(iconId: Int): IconImageStandard {
        return iconsManager.getIcon(iconId)
    }

    fun doForEachCustomIcons(action: (IconImageCustom, BinaryData) -> Unit) {
        iconsManager.doForEachCustomIcon(action)
    }

    fun getCustomIcon(iconId: UUID): IconImageCustom? {
        return iconsManager.getIcon(iconId)
    }

    fun buildNewCustomIcon(result: (IconImageCustom?, BinaryData?) -> Unit) {
        kdbx()?.buildNewCustomIcon(null, result)
    }

    fun buildNewCustomIcon(name: String, result: (IconImageCustom?, BinaryData?) -> Unit) {
        kdbx()?.addCustomIcon(null, name, null, false, result)
    }

    fun isCustomIconBinaryDuplicate(binaryData: BinaryData): Boolean {
        return kdbx()?.isCustomIconBinaryDuplicate(binaryData) ?: false
    }

    fun getBinaryForCustomIcon(iconId: UUID): BinaryData? {
        return iconsManager.getBinaryForCustomIcon(iconId)
    }

    fun removeCustomIcon(customIcon: IconImageCustom) {
        iconsManager.removeCustomIcon(customIcon.uuid, binaryCache())
        kdbx()?.addDeletedObject(customIcon.uuid)
    }

    fun updateCustomIcon(customIcon: IconImageCustom) {
        iconsManager.getIcon(customIcon.uuid)?.updateWith(customIcon)
    }
}
