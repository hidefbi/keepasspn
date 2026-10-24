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
 * Reads JSON files: a list of entries, or an object holding the list under one of
 * [ENTRY_LIST_KEYS]. A Proton Pass export (an object with "vaults") is read by [ProtonPassParser].
 *
 * Keys read per entry, the first present one wins:
 * title or name, username, password, url or uri, notes or note, group or folder, totp.
 * A nested "login" object (Bitwarden layout) provides the username, password, first URI and
 * totp when the entry does not have them itself. A "folderId" is looked up in the "folders" list.
 *
 * Every other key is kept in the notes as "key: value", so no data is lost.
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

    /** Keys that are read into a field, so they are not repeated in the notes. */
    private val MAPPED_KEYS = setOf(
        "title", "name", "username", "password", "url", "uri", "notes", "note",
        "group", "folder", "folderId", "login", "totp"
    )

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
            // Not an object: kept whole in the notes
            collector.add("", "", "", "", ImportValues.json(item), emptyList(), null)
            return
        }

        val login = map["login"]
        val loginMap = login as? Map<*, *>
        val lines = ArrayList<String>()

        val title = pick(TITLE_KEYS, map).orEmpty()
        val username = pick(USERNAME_KEYS, map) ?: pick(USERNAME_KEYS, loginMap).orEmpty()
        val password = pick(PASSWORD_KEYS, map) ?: pick(PASSWORD_KEYS, loginMap).orEmpty()
        val uris = (loginMap?.get("uris") as? List<*>)
            ?.mapNotNull { uri -> pick(listOf("uri"), uri as? Map<*, *>)?.takeIf { it.isNotEmpty() } }
            .orEmpty()
        val url = pick(URL_KEYS, map) ?: uris.firstOrNull().orEmpty()
        uris.drop(if (pick(URL_KEYS, map) == null && uris.isNotEmpty()) 1 else 0)
            .forEach { lines.add("uri: $it") }

        pick(NOTES_KEYS, map)?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }

        val groupPath = ArrayList<String>()
        val group = pick(GROUP_KEYS, map)
        if (!group.isNullOrEmpty()) {
            groupPath.add(group)
        } else {
            val folderId = pick(listOf("folderId"), map)
            if (!folderId.isNullOrEmpty()) {
                val folderName = folderNames[folderId]
                if (folderName != null) groupPath.add(folderName) else lines.add("folderId: $folderId")
            }
        }

        var otpUri: String? = null
        for (candidate in listOfNotNull(pick(OTP_KEYS, map), pick(OTP_KEYS, loginMap))) {
            if (otpUri == null && candidate.startsWith("otpauth://", ignoreCase = true)) {
                otpUri = candidate
            } else if (candidate.isNotEmpty()) {
                lines.add("totp: $candidate")
            }
        }

        if (login != null && loginMap == null && login != SafeJsonParser.JsonNull) {
            lines.add("login: ${ImportValues.json(login)}")
        }
        loginMap?.forEach { (key, value) ->
            val name = key.toString()
            if (name !in LOGIN_MAPPED_KEYS) {
                val text = ImportValues.text(value)
                if (text.isNotEmpty()) lines.add("login.$name: $text")
            }
        }
        map.forEach { (key, value) ->
            val name = key.toString()
            if (name !in MAPPED_KEYS) {
                val text = ImportValues.text(value)
                if (text.isNotEmpty()) lines.add("$name: $text")
            }
        }

        collector.add(
            title = title,
            username = username,
            password = password,
            url = url,
            notes = lines.joinToString("\n"),
            groupPath = groupPath,
            otpUri = otpUri
        )
    }

    private val LOGIN_MAPPED_KEYS = setOf("username", "password", "uris", "totp")

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
