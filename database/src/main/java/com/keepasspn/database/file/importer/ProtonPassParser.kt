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
package com.keepasspn.database.file.importer

/**
 * Reads the JSON file of a Proton Pass export.
 *
 * The file holds vaults; each vault holds items. A vault name becomes the group of its items.
 * Login fields, the alias address and the one-time password are mapped to their fields. Every
 * other value of an item is kept in the notes, so nothing is lost.
 */
internal object ProtonPassParser {

    private val LOGIN_CONTENT_KEYS = setOf(
        "itemUsername", "itemEmail", "password", "urls", "autofillUrls", "totpUri", "passkeys"
    )

    fun parse(root: Map<*, *>, collector: ImportCollector) {
        val vaults = root["vaults"] as? Map<*, *> ?: return
        for (vault in vaults.values) {
            val vaultMap = vault as? Map<*, *> ?: continue
            val vaultName = ImportValues.text(vaultMap["name"])
            val items = vaultMap["items"] as? List<*> ?: continue
            for (item in items) {
                val itemMap = item as? Map<*, *>
                if (itemMap == null) {
                    collector.add("", "", "", "", ImportValues.json(item), listOf(vaultName), null)
                } else {
                    addItem(itemMap, vaultName, collector)
                }
            }
        }
    }

    private fun addItem(item: Map<*, *>, vaultName: String, collector: ImportCollector) {
        val data = item["data"] as? Map<*, *>
        val metadata = data?.get("metadata") as? Map<*, *>
        val content = data?.get("content") as? Map<*, *>
        val type = ImportValues.text(data?.get("type"))

        val lines = ArrayList<String>()
        val note = ImportValues.text(metadata?.get("note"))
        if (note.isNotEmpty()) lines.add(note)

        var username = ""
        var password = ""
        val urls = ArrayList<String>()
        var otpUri: String? = null

        when (type) {
            "login" -> if (content != null) {
                val itemUsername = ImportValues.text(content["itemUsername"])
                val itemEmail = ImportValues.text(content["itemEmail"])
                username = itemUsername.ifEmpty { itemEmail }
                if (itemEmail.isNotEmpty() && itemEmail != username) lines.add("itemEmail: $itemEmail")
                password = ImportValues.text(content["password"])
                (content["urls"] as? List<*>)?.forEach { urls.add(ImportValues.text(it)) }
                (content["autofillUrls"] as? List<*>)?.forEach { autofill ->
                    val fill = autofill as? Map<*, *>
                    if (fill != null) {
                        lines.add("autofillUrl: ${ImportValues.text(fill["url"])} (mode ${ImportValues.text(fill["mode"])})")
                    } else {
                        lines.add("autofillUrl: ${ImportValues.json(autofill)}")
                    }
                }
                val totp = ImportValues.text(content["totpUri"])
                if (totp.startsWith("otpauth://", ignoreCase = true)) {
                    otpUri = totp
                } else if (totp.isNotEmpty()) {
                    lines.add("totp: $totp")
                }
                content["passkeys"]?.let { passkeys ->
                    val text = ImportValues.json(passkeys)
                    if (text != "[]") lines.add("passkeys: $text")
                }
                addUnmappedContent(content, LOGIN_CONTENT_KEYS, lines)
            }
            "alias" -> {
                username = ImportValues.text(item["aliasEmail"])
                if (content != null) addUnmappedContent(content, emptySet<String>(), lines)
            }
            else -> if (content != null) addUnmappedContent(content, emptySet<String>(), lines)
        }

        urls.drop(1).forEach { lines.add("url: $it") }
        val extraFields = data?.get("extraFields") as? List<*>
        extraFields?.forEach { field -> lines.add("field: ${ImportValues.json(field)}") }
        val files = item["files"] as? List<*>
        if (!files.isNullOrEmpty()) lines.add("files: ${ImportValues.json(files)}")
        val folderId = ImportValues.text(item["folderId"])
        if (folderId.isNotEmpty()) lines.add("folderId: $folderId")

        val title = ImportValues.text(metadata?.get("name"))
        collector.add(
            title = title,
            username = username,
            password = password,
            url = urls.firstOrNull().orEmpty(),
            notes = lines.joinToString("\n"),
            groupPath = if (vaultName.isEmpty()) emptyList() else listOf(vaultName),
            otpUri = otpUri
        )
    }

    private fun addUnmappedContent(content: Map<*, *>, mapped: Set<String>, lines: MutableList<String>) {
        for ((key, value) in content) {
            val name = key.toString()
            if (name in mapped) continue
            val text = ImportValues.text(value)
            if (text.isNotEmpty()) lines.add("$name: $text")
        }
    }
}
