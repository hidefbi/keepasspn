/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 * KeePassPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.keepasspn.icons.brand

import android.graphics.Color
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.icon.IconImageCustom
import com.keepasspn.tasks.BinaryDatabaseManager

/**
 * Stores brands as custom icons of a database.
 *
 * A brand is stored once: its custom icon is named after the brand slug, so the next request
 * for the same brand gets the icon already in the database instead of a copy of it.
 */
object BrandIconStore {

    /** A custom icon of the database; [created] tells whether it was stored by this call */
    class StoredBrandIcon(val icon: IconImageCustom, val created: Boolean)

    /** Colors for a brand drawn without a theme, such as during an import */
    val DEFAULT_BACKGROUND: Int = Color.WHITE
    val DEFAULT_FOREGROUND: Int = Color.BLACK

    private const val STORED_SIZE_PX = 128

    /**
     * Returns the custom icon of [brand], storing it first when the database does not have it.
     * Returns null when the brand can not be drawn or stored.
     */
    fun findOrStore(
        database: ContextualDatabase,
        brand: BrandIcon,
        background: Int?,
        foreground: Int?,
    ): StoredBrandIcon? {
        findByName(database, brand.slug)?.let { return StoredBrandIcon(it, created = false) }
        val bitmap = BrandIconRenderer.toBitmap(brand, STORED_SIZE_PX, background, foreground)
            ?: return null
        var stored: IconImageCustom? = null
        database.buildNewCustomIcon(brand.slug) { customIcon, binary ->
            if (customIcon == null) return@buildNewCustomIcon
            if (binary != null) {
                BinaryDatabaseManager.storeBitmapInBinaryFile(database, bitmap, binary)
                if (binary.getSize() > 0) {
                    stored = customIcon
                    return@buildNewCustomIcon
                }
            }
            database.removeCustomIcon(customIcon)
        }
        return stored?.let { StoredBrandIcon(it, created = true) }
    }

    private fun findByName(database: ContextualDatabase, name: String): IconImageCustom? {
        var found: IconImageCustom? = null
        database.doForEachCustomIcons { customIcon, _ ->
            if (found == null && customIcon.name == name) {
                found = customIcon
            }
        }
        return found
    }
}
