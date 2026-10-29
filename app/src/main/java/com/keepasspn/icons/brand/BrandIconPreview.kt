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

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Draws brand icons in lists for entries that keep the default icon.
 *
 * Nothing is written to the database: the brand is only looked up from the web address of the
 * entry, and the bitmap is kept in memory for the life of the list.
 */
class BrandIconPreview(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Bitmap per web address, null when no brand matches that address.
     * Least recently used addresses are dropped, so long lists do not keep every bitmap.
     */
    private val bitmaps = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean {
            return size > MAX_CACHED_ADDRESSES
        }
    }
    private val pending = HashSet<String>()

    /** Bitmap already drawn for [webDomain], or null while it loads or when no brand matches */
    fun bitmapFor(webDomain: String): Bitmap? = bitmaps[webDomain]

    /**
     * Looks up the brand of [webDomain] in the background, then calls [onLoaded] on the main thread.
     * Nothing happens when the address is already known or already loading.
     */
    fun load(webDomain: String, background: Int, foreground: Int, onLoaded: () -> Unit) {
        if (bitmaps.containsKey(webDomain) || !pending.add(webDomain)) return
        scope.launch {
            val brand = try {
                BrandIconRepository.brandIconForWebDomain(appContext, webDomain)
            } catch (e: Exception) {
                null
            }
            val bitmap = brand?.let {
                withContext(Dispatchers.Default) {
                    BrandIconRenderer.toBitmap(it, PREVIEW_SIZE_PX, background, foreground)
                }
            }
            pending.remove(webDomain)
            bitmaps[webDomain] = bitmap
            onLoaded()
        }
    }

    companion object {
        private const val PREVIEW_SIZE_PX = 96
        private const val MAX_CACHED_ADDRESSES = 256
    }
}
