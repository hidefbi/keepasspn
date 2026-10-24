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
 * Reads JSON files: a list of entries, or an object holding the list under one of
 * [ENTRY_LIST_KEYS]. A Proton Pass export (an object with "vaults") is read by [ProtonPassParser].
 *
 * Keys read per entry, the first present one wins:
 * title or name, username, password, url or uri, notes or note, group or folder, totp.
 * A nested "login" object (Bitwarden layout) provides the username, password, URIs and totp when
 * the entry does not have them itself. A "folderId" is looked up in the "folders" list, and the
 * "fields" list (Bitwarden custom fields) becomes custom fields. Dates are read from
 * creationDate and revisionDate, or their common variants.
 *
 * Every other key is kept as a custom field, so no value is lost and none is mixed into the notes.
 */
internal object JsonImportParser {

    private val ENTRY_LIST_KEYS = listOf("items", "entries", "logins", "passwords")
    private val TITLE_KEYS = listOf("title", "name")
    private val USERNAME_KEYS = listOf("username")
    private val PASSWORD_KEYS = listOf("password")
    private val URL_KEYS = listOf("url", "uri")
    private val NOTES_KEYS = listOf("notes", "note")
    private val GROUP_KEYS = listOf("group", "folder")
    private val OTP_KEYS = listOf("totp")
    private val CREATED_KEYS = listOf("creationDate", "created", "createdAt", "created_at")
    private val MODIFIED_KEYS = listOf("revisionDate", "modified", "modifiedAt", "lastModified", "updated", "updatedAt", "updated_at")
    private val FIELD_LIST_KEY = "fields"

    /** Keys read into a known place, so they are not repeated as custom fields. */
    private val MAPPED_KEYS = setOf(
        "title", "name", "username", "password", "url", "uri", "notes", "note",
        "group", "folder", "folderId", "login", "totp", FIELD_LIST_KEY
    ) + CREATED_KEYS + MODIFIED_KEYS

    private val LOGIN_MAPPED_KEYS = setOf("username", "password", "uris", "totp")

    fun parse(bytes: ByteArray): ImportResult {
        val text = ImportText.decodeUtf8(bytes)
        val root = SafeJsonParser(text, ImportLimits.MAX_JSON_DEPTH).parse()
        if (root is Map<*, *> && root["encrypted"] == true) {
            throw ImportFileException(ImportFileError.ENCRYPTED_NOT_SUPPORTED)
        }
        val collector = ImportCollector()
        if (root is Map<*, *> && root["vaults"] is Map<*, *>) {
            ProtonPassParser.parse(root, collector)
            return collector.result()
        }

        val items: List<*> = when (root) {
            is List<*> -> root
            is Map<*, *> -> ENTRY_LIST_KEYS.firstNotNullOfOrNull { root[it] as? List<*> }
                ?: throw ImportFileException(ImportFileError.MALFORMED_STRUCTURE)
            else -> throw ImportFileException(ImportFileError.MALFORMED_STRUCTURE)
        }
        if (items.isEmpty()) {
            throw ImportFileException(ImportFileError.EMPTY_FILE)
        }
        if (items.size > ImportLimits.MAX_ENTRIES) {
            throw ImportFileException(ImportFileError.TOO_MANY_ENTRIES)
        }

        val folderNames = folderNames(root as? Map<*, *>)
        for (item in items) {
            addItem(item, folderNames, collector)
        }
        return collector.result()
    }

    /**
     * Folder names by identifier, from a "folders" list of objects with "id" and "name".
     */
    private fun folderNames(root: Map<*, *>?): Map<String, String> {
        val folders = root?.get("folders") as? List<*> ?: return emptyMap()
        val names = HashMap<String, String>()
        for (folder in folders) {
            val map = folder as? Map<*, *> ?: continue
            val id = map["id"] as? String ?: continue
            val name = map["name"] as? String ?: continue
            names[id] = name
        }
        return names
    }

    private fun addItem(item: Any?, folderNames: Map<String, String>, collector: ImportCollector) {
        val map = item as? Map<*, *>
        if (map == null) {
            // Not an object: its whole value is kept as one custom field
            val field = ImportedField("item", ImportValues.json(item), isProtected = false)
            collector.add("", "", "", "", "", emptyList(), null, listOf(field))
            return
        }

        val login = map["login"]
        val loginMap = login as? Map<*, *>
        val fields = ArrayList<ImportedField>()

        val title = pick(TITLE_KEYS, map).orEmpty()
        val username = pick(USERNAME_KEYS, map) ?: pick(USERNAME_KEYS, loginMap).orEmpty()
        val password = pick(PASSWORD_KEYS, map) ?: pick(PASSWORD_KEYS, loginMap).orEmpty()

        val uris = (loginMap?.get("uris") as? List<*>)
            ?.mapNotNull { uri -> pick(listOf("uri"), uri as? Map<*, *>)?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val urlFromLogin = pick(URL_KEYS, map) == null
        val url = pick(URL_KEYS, map) ?: uris.firstOrNull().orEmpty()
        uris.forEachIndexed { index, uri ->
            if (!(urlFromLogin && index == 0)) fields.add(ImportedField("URI ${index + 1}", uri, isProtected = false))
        }

        val notes = pick(NOTES_KEYS, map).orEmpty()

        val groupPath = ArrayList<String>()
        val group = pick(GROUP_KEYS, map)
        if (!group.isNullOrEmpty()) {
            groupPath.add(group)
        } else {
            val folderId = pick(listOf("folderId"), map)
            if (!folderId.isNullOrEmpty()) {
                val folderName = folderNames[folderId]
                if (folderName != null) {
                    groupPath.add(folderName)
                } else {
                    fields.add(ImportedField("folderId", folderId, isProtected = false))
                }
            }
        }

        var otpUri: String? = null
        listOfNotNull(pick(OTP_KEYS, map), pick(OTP_KEYS, loginMap)).forEach { candidate ->
            if (candidate.isEmpty()) return@forEach
            val uri = ImportValues.otpUriOf(candidate, title)
            if (uri != null && otpUri == null) {
                otpUri = uri
            } else {
                fields.add(ImportedField("TOTP", candidate, isProtected = true))
            }
        }

        val createdAt = dateOf(CREATED_KEYS, map, fields)
        val modifiedAt = dateOf(MODIFIED_KEYS, map, fields)

        if (login != null && loginMap == null && login != SafeJsonParser.JsonNull) {
            fields.add(ImportedField("login", ImportValues.json(login), isProtected = false))
        }
        loginMap?.forEach { (key, value) ->
            val name = key.toString()
            if (name !in LOGIN_MAPPED_KEYS) {
                addField("login.$name", value, fields)
            }
        }
        map.forEach { (key, value) ->
            val name = key.toString()
            if (name !in MAPPED_KEYS) {
                addField(name, value, fields)
            }
        }
        (map[FIELD_LIST_KEY] as? List<*>)?.forEach { custom ->
            addCustomField(custom as? Map<*, *>, fields)
        }

        collector.add(
            title = title,
            username = username,
            password = password,
            url = url,
            notes = notes,
            groupPath = groupPath,
            otpUri = otpUri,
            fields = fields,
            createdAt = createdAt,
            modifiedAt = modifiedAt
        )
    }

    /**
     * A custom field of a Bitwarden "fields" list: name, value and type. Type 1 is a hidden field.
     */
    private fun addCustomField(custom: Map<*, *>?, fields: MutableList<ImportedField>) {
        if (custom == null) return
        val name = pick(listOf("name"), custom) ?: "field"
        val value = pick(listOf("value"), custom).orEmpty()
        if (value.isEmpty()) return
        val hidden = pick(listOf("type"), custom) == "1"
        fields.add(ImportedField(name, value, isProtected = hidden || ImportValues.isSecretName(name)))
    }

    /**
     * Adds a value as a custom field, unless it is empty. Objects and lists keep their JSON text.
     */
    private fun addField(name: String, value: Any?, fields: MutableList<ImportedField>) {
        if (value == SafeJsonParser.JsonNull) return
        val text = ImportValues.text(value)
        if (text.isEmpty()) return
        fields.add(ImportedField(name, text, isProtected = ImportValues.isSecretName(name)))
    }

    /**
     * Date of the first of [keys] present. A value that is not a date is kept as a custom field.
     */
    private fun dateOf(keys: List<String>, source: Map<*, *>, fields: MutableList<ImportedField>): Instant? {
        val key = keys.firstOrNull { source[it] != null && source[it] != SafeJsonParser.JsonNull } ?: return null
        val text = ImportValues.text(source[key])
        if (text.isEmpty()) return null
        val date = ImportValues.instantOf(text)
        if (date == null) {
            fields.add(ImportedField(key, text, isProtected = false))
        }
        return date
    }

    /**
     * Text of the first key present with a value, or null when none is. A JSON null counts as absent.
     */
    private fun pick(keys: List<String>, source: Map<*, *>?): String? {
        if (source == null) return null
        for (key in keys) {
            val value = source[key] ?: continue
            if (value == SafeJsonParser.JsonNull) continue
            return ImportValues.text(value)
        }
        return null
    }
}
