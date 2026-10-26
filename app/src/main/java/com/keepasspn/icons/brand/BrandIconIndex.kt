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

data class BrandIcon(
    val slug: String,
    val title: String,
    val aliases: List<String>,
    val hexColor: String,
    val pathData: String
)

/**
 * Finds a brand icon for the registrable domain of a web address.
 * Only an exact match on the first label of the registrable domain counts,
 * so "paypal-secure.com" does not get the PayPal icon.
 */
class BrandIconIndex(icons: List<BrandIcon>) {

    private val bySlug: Map<String, BrandIcon> = icons.associateBy { it.slug }

    private val bySearchKey: Map<String, String> = buildSearchKeys(icons)

    fun size(): Int = bySlug.size

    fun findForRegistrableDomain(registrableDomain: String): BrandIcon? {
        val label = registrableDomain.lowercase().substringBefore('.')
        val key = normalize(label)
        if (key.isEmpty()) return null
        val slug = bySearchKey[key] ?: return null
        return bySlug[slug]
    }

    companion object {
        /** Known brand names that differ from the domain label */
        internal val MANUAL_ALIASES: Map<String, String> = mapOf(
            "twitter" to "x"
        )

        internal fun normalize(value: String): String {
            return value.lowercase().filter { it.isLetterOrDigit() }
        }

        // Slugs first, then titles, then aliases, so a slug always wins over a title.
        // Written without Map.putIfAbsent, which is not available below API 24.
        private fun buildSearchKeys(icons: List<BrandIcon>): Map<String, String> {
            val keys = HashMap<String, String>()
            fun addKey(rawKey: String, slug: String) {
                val key = normalize(rawKey)
                if (key.isNotEmpty() && !keys.containsKey(key)) keys[key] = slug
            }
            icons.forEach { addKey(it.slug, it.slug) }
            icons.forEach { addKey(it.title, it.slug) }
            icons.forEach { icon -> icon.aliases.forEach { addKey(it, icon.slug) } }
            val slugs = icons.map { it.slug }.toSet()
            MANUAL_ALIASES.forEach { (alias, slug) ->
                if (slugs.contains(slug)) {
                    val key = normalize(alias)
                    if (key.isNotEmpty()) keys[key] = slug
                }
            }
            return keys
        }
    }
}
