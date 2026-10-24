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

import java.time.Instant

/**
 * Reads the JSON file of a Proton Pass export.
 *
 * The file holds vaults; each vault holds items, and a vault name becomes the group of its items.
 * Login fields, the alias address and the one-time password go to their fields, and the item note
 * goes to the notes. Extra fields become custom fields, a TOTP extra field becomes the one-time
 * password, and the creation and modification times become the dates of the entry.
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
                    val field = ImportedField("item", ImportValues.json(item), isProtected = false)
                    collector.add("", "", "", "", "", listOf(vaultName), null, listOf(field))
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
        val title = ImportValues.text(metadata?.get("name"))
        val fields = ArrayList<ImportedField>()

        var username = ""
        var password = ""
        val urls = ArrayList<String>()
        var otpUri: String? = null

        when (type) {
            "login" -> if (content != null) {
                val itemUsername = ImportValues.text(content["itemUsername"])
                val itemEmail = ImportValues.text(content["itemEmail"])
                username = itemUsername.ifEmpty { itemEmail }
                if (itemEmail.isNotEmpty() && itemEmail != username) {
                    fields.add(ImportedField("email", itemEmail, isProtected = false))
                }
                password = ImportValues.text(content["password"])
                (content["urls"] as? List<*>)?.forEach { urls.add(ImportValues.text(it)) }
                (content["autofillUrls"] as? List<*>)?.forEach { autofill ->
                    addField("autofill URL", autofill, fields)
                }
                val totp = ImportValues.text(content["totpUri"])
                if (totp.isNotEmpty()) {
                    otpUri = ImportValues.otpUriOf(totp, title)
                    if (otpUri == null) fields.add(ImportedField("TOTP", totp, isProtected = true))
                }
                content["passkeys"]?.let { passkeys -> addField("passkey", passkeys, fields) }
                addUnmappedContent(content, LOGIN_CONTENT_KEYS, fields)
            }
            "alias" -> {
                username = ImportValues.text(item["aliasEmail"])
                if (content != null) addUnmappedContent(content, emptySet(), fields)
            }
            else -> if (content != null) addUnmappedContent(content, emptySet(), fields)
        }

        (data?.get("extraFields") as? List<*>)?.forEach { extra ->
            val otp = addExtraField(extra as? Map<*, *>, title, fields)
            if (otp != null && otpUri == null) otpUri = otp
        }

        val files = item["files"] as? List<*>
        if (!files.isNullOrEmpty()) addField("files", files, fields)
        val folderId = ImportValues.text(item["folderId"])
        if (folderId.isNotEmpty()) fields.add(ImportedField("folderId", folderId, isProtected = false))
        val lastUse = ImportValues.text(item["lastUseTime"])
        if (lastUse.isNotEmpty()) fields.add(ImportedField("last used", lastUse, isProtected = false))

        val createdAt = timeOf(item, metadata, "createTime")
        val modifiedAt = timeOf(item, metadata, "modifyTime")

        collector.add(
            title = title,
            username = username,
            password = password,
            url = urls.firstOrNull().orEmpty(),
            notes = ImportValues.text(metadata?.get("note")),
            groupPath = listOf(vaultName),
            otpUri = otpUri,
            fields = fields.toList().let { list ->
                list + urls.drop(1).mapIndexed { index, url ->
                    ImportedField("URL ${index + 2}", url, isProtected = false)
                }
            },
            createdAt = createdAt,
            modifiedAt = modifiedAt
        )
    }

    /**
     * An extra field of the item. A TOTP field returns its one-time password address when it has one,
     * otherwise the field is kept as a protected custom field.
     */
    private fun addExtraField(extra: Map<*, *>?, title: String, fields: MutableList<ImportedField>): String? {
        if (extra == null) return null
        val name = ImportValues.text(extra["fieldName"]).ifEmpty { ImportValues.text(extra["name"]) }
            .ifEmpty { "field" }
        val dataMap = extra["data"] as? Map<*, *>
        val value = ImportValues.text(dataMap?.get("content") ?: extra["content"] ?: extra["value"])
        if (value.isEmpty()) return null
        val kind = ImportValues.text(extra["type"])
        if (kind == "totp") {
            val otp = ImportValues.otpUriOf(value, title)
            if (otp != null) return otp
            fields.add(ImportedField(name, value, isProtected = true))
            return null
        }
        fields.add(ImportedField(name, value, isProtected = kind == "hidden" || ImportValues.isSecretName(name)))
        return null
    }

    /**
     * Creation or modification time of an item, in seconds or milliseconds, from the item or its metadata.
     */
    private fun timeOf(item: Map<*, *>, metadata: Map<*, *>?, key: String): Instant? {
        val text = ImportValues.text(item[key]).ifEmpty { ImportValues.text(metadata?.get(key)) }
        return ImportValues.instantOf(text)
    }

    private fun addField(name: String, value: Any?, fields: MutableList<ImportedField>) {
        val text = ImportValues.text(value)
        if (text.isEmpty() || text == "[]") return
        fields.add(ImportedField(name, text, isProtected = ImportValues.isSecretName(name)))
    }

    private fun addUnmappedContent(content: Map<*, *>, mapped: Set<String>, fields: MutableList<ImportedField>) {
        for ((key, value) in content) {
            val name = key.toString()
            if (name in mapped) continue
            addField(name, value, fields)
        }
    }
}
