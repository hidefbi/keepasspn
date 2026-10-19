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

import com.keepasspn.database.NoOpLog
import com.keepasspn.database.element.DateInstant
import com.keepasspn.database.element.binary.BinaryCache
import com.keepasspn.database.element.binary.BinaryData
import com.keepasspn.database.element.binary.CustomIconPool
import com.keepasspn.database.element.icon.IconImageStandard.Companion.KEY_ID
import java.util.UUID

class IconsManager(numberOfIcons: Int) {

    private val standardCache = List(numberOfIcons) {
        IconImageStandard(it)
    }
    private val customCache = CustomIconPool()

    fun getIcon(iconId: Int): IconImageStandard {
        val searchIconId = if (IconImageStandard.isCorrectIconId(iconId)) iconId else KEY_ID
        return standardCache[searchIconId]
    }

    fun doForEachStandardIcon(action: (IconImageStandard) -> Unit) {
        standardCache.forEach { icon ->
            action.invoke(icon)
        }
    }

    /*
     *  Custom
     */

    fun addCustomIcon(key: UUID? = null,
                      name: String,
                      lastModificationTime: DateInstant?,
                      builder: (uniqueBinaryId: String) -> BinaryData,
                      result: (IconImageCustom, BinaryData?) -> Unit) {
        customCache.put(key, name, lastModificationTime, builder, result)
    }

    fun getIcon(iconUuid: UUID): IconImageCustom? {
        return customCache.getCustomIcon(iconUuid)
    }

    fun isCustomIconBinaryDuplicate(binaryData: BinaryData): Boolean {
        return customCache.isBinaryDuplicate(binaryData)
    }

    fun removeCustomIcon(iconUuid: UUID, binaryCache: BinaryCache) {
        val binary = customCache[iconUuid]
        customCache.remove(iconUuid)
        try {
            binary?.clear(binaryCache)
        } catch (e: Exception) {
            NoOpLog.w(TAG, "Unable to remove custom icon binary", e)
        }
    }

    fun getBinaryForCustomIcon(iconUuid: UUID): BinaryData? {
        return customCache[iconUuid]
    }

    fun doForEachCustomIcon(action: (IconImageCustom, BinaryData) -> Unit) {
        customCache.doForEachCustomIcon(action)
    }

    fun containsCustomIconWithNameOrLastModificationTime(): Boolean {
        return customCache.any { customIcon ->
            customIcon.name.isNotEmpty() || customIcon.lastModificationTime != null
        }
    }

    /**
     * Clear the cache of icons
     */
    fun clear() {
        customCache.clear()
    }

    companion object {
        private val TAG = IconsManager::class.java.name
    }
}
