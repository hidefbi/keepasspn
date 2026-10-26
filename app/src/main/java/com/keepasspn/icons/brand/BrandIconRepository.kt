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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import org.json.JSONObject

/**
 * Offline brand icons, from the Simple Icons package (CC0 1.0).
 * The data is read from assets/brand_icons.json and loaded once.
 */
object BrandIconRepository {

    private const val ASSET_NAME = "brand_icons.json"

    @Volatile
    private var cachedIndex: BrandIconIndex? = null

    suspend fun index(context: Context): BrandIconIndex {
        cachedIndex?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedIndex ?: loadIndex(context.applicationContext).also { cachedIndex = it }
        }
    }

    /**
     * Returns the brand icon for a web domain, or null when no brand matches.
     * The registrable domain is taken from the public suffix list first,
     * so "accounts.google.com" is matched as "google.com".
     */
    suspend fun brandIconForWebDomain(context: Context, webDomain: String): BrandIcon? {
        val host = webDomain.trim().trimEnd('.').lowercase().removePrefix("www.")
        if (host.isEmpty()) return null
        val registrable = PublicSuffixList(context).getPublicSuffixPlusOne(host).await()
            ?: return null
        return index(context).findForRegistrableDomain(registrable)
    }

    private fun loadIndex(context: Context): BrandIconIndex {
        val text = context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val icons = ArrayList<BrandIcon>()
        val array = JSONObject(text).getJSONArray("icons")
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val aliases = item.optJSONArray("a")
            val aliasList = ArrayList<String>()
            if (aliases != null) {
                for (j in 0 until aliases.length()) aliasList.add(aliases.getString(j))
            }
            icons.add(
                BrandIcon(
                    slug = item.getString("s"),
                    title = item.getString("t"),
                    aliases = aliasList,
                    hexColor = item.getString("c"),
                    pathData = item.getString("d")
                )
            )
        }
        return BrandIconIndex(icons)
    }
}
