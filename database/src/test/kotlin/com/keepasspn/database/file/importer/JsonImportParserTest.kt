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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class JsonImportParserTest {

    private fun json(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    @Test
    fun arrayOfEntriesIsImportedAsIs() {
        val text = """[{"title":"Mail","username":"alice","password":"s1",""" +
            """"url":"https://mail.example.com","notes":"n","group":"Work"}]"""
        val result = JsonImportParser.parse(json(text))
        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("Mail", entry.title)
        assertEquals("alice", entry.username)
        assertEquals("s1", String(entry.password))
        assertEquals("https://mail.example.com", entry.url)
        assertEquals("n", entry.notes)
        assertEquals(listOf("Work"), entry.groupPath)
        assertEquals(0, result.removedCharacters)
    }

    @Test
    fun duplicateEntriesAreAllKept() {
        val text = """[{"name":"Shop","password":"p"},{"name":"Shop","password":"p"}]"""
        assertEquals(2, JsonImportParser.parse(json(text)).entries.size)
    }

    @Test
    fun entryWithOnlyANumberIsKept() {
        val result = JsonImportParser.parse(json("""[{"password":12345}]"""))
        assertEquals(1, result.entries.size)
        assertEquals("12345", String(result.entries[0].password))
        assertEquals("", result.entries[0].title)
    }

    @Test
    fun itemsWrapperIsAccepted() {
        val result = JsonImportParser.parse(json("""{"items":[{"name":"Shop","password":"s2"}]}"""))
        assertEquals("Shop", result.entries[0].title)
        assertEquals("s2", String(result.entries[0].password))
    }

    @Test
    fun bitwardenLoginIsRead() {
        val text = """[{"name":"Shop","login":{"username":"u","password":"p",""" +
            """"uris":[{"uri":"https://a.example"},{"uri":"https://b.example"}],""" +
            """"totp":"otpauth://totp/Shop?secret=JBSWY3DPEHPK3PXP"}}]"""
        val entry = JsonImportParser.parse(json(text)).entries[0]
        assertEquals("u", entry.username)
        assertEquals("p", String(entry.password))
        assertEquals("https://a.example", entry.url)
        assertEquals("uri: https://b.example", entry.notes)
        assertEquals("otpauth://totp/Shop?secret=JBSWY3DPEHPK3PXP", entry.otpUri)
    }

    @Test
    fun folderIdIsReplacedByFolderName() {
        val text = """{"folders":[{"id":"f1","name":"Work"}],""" +
            """"items":[{"name":"A","password":"p","folderId":"f1"}]}"""
        assertEquals(listOf("Work"), JsonImportParser.parse(json(text)).entries[0].groupPath)
    }

    @Test
    fun unknownKeysAreKeptInNotes() {
        val entry = JsonImportParser.parse(json("""[{"name":"A","password":"p","pin":"1234"}]""")).entries[0]
        assertEquals("pin: 1234", entry.notes)
    }

    @Test
    fun invalidControlCharacterIsRemovedAndCounted() {
        val result = JsonImportParser.parse(json("""[{"name":"A\u0001B","password":"p"}]"""))
        assertEquals("AB", result.entries[0].title)
        assertEquals(1, result.removedCharacters)
    }

    @Test
    fun encryptedFileIsRefused() {
        assertRefused(ImportFileError.ENCRYPTED_NOT_SUPPORTED) {
            JsonImportParser.parse(json("""{"encrypted":true,"items":[]}"""))
        }
    }

    @Test
    fun emptyListIsRefused() {
        assertRefused(ImportFileError.EMPTY_FILE) { JsonImportParser.parse(json("[]")) }
    }

    @Test
    fun objectWithoutListIsRefused() {
        assertRefused(ImportFileError.MALFORMED_STRUCTURE) { JsonImportParser.parse(json("""{"a":1}""")) }
    }

    @Test
    fun entryWithoutOtpHasNoOtpUri() {
        assertNull(JsonImportParser.parse(json("""[{"password":"p"}]""")).entries[0].otpUri)
    }

    private fun assertRefused(error: ImportFileError, block: () -> Unit) {
        try {
            block()
            fail("Expected $error")
        } catch (e: ImportFileException) {
            assertEquals(error, e.error)
        }
    }
}
