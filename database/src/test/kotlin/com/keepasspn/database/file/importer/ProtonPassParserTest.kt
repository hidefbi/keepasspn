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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uses synthetic data only, in the layout of a Proton Pass JSON export.
 */
class ProtonPassParserTest {

    private fun export(items: String): ByteArray {
        val text = """{"version":"1","vaults":{"v1":{"name":"Personal","items":[$items]}}}"""
        return text.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun loginIsReadWithVaultAsGroup() {
        val item = """{"folderId":null,"data":{"type":"login",""" +
            """"metadata":{"name":"Mail","note":"n1"},""" +
            """"content":{"itemUsername":"alice","itemEmail":"","password":"pw1",""" +
            """"urls":["https://mail.example.com"],""" +
            """"totpUri":"otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP","passkeys":[]}},"files":[]}"""
        val result = JsonImportParser.parse(export(item))
        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("Mail", entry.title)
        assertEquals("alice", entry.username)
        assertEquals("pw1", String(entry.password))
        assertEquals("https://mail.example.com", entry.url)
        assertEquals(listOf("Personal"), entry.groupPath)
        assertEquals("otpauth://totp/Mail?secret=JBSWY3DPEHPK3PXP", entry.otpUri)
        assertTrue(entry.notes.contains("n1"))
    }

    @Test
    fun emailIsUsedWhenNoUsername() {
        val item = """{"data":{"type":"login","metadata":{"name":"Shop"},""" +
            """"content":{"itemUsername":"","itemEmail":"bob@example.com","password":"pw2","urls":[]}}}"""
        val entry = JsonImportParser.parse(export(item)).entries[0]
        assertEquals("bob@example.com", entry.username)
        assertEquals("", entry.url)
    }

    @Test
    fun secondUrlIsKeptInNotes() {
        val item = """{"data":{"type":"login","metadata":{"name":"Site"},""" +
            """"content":{"password":"p","urls":["https://a.example","https://b.example"]}}}"""
        val entry = JsonImportParser.parse(export(item)).entries[0]
        assertEquals("https://a.example", entry.url)
        assertEquals("URL 2", entry.fields[0].name)
        assertEquals("https://b.example", entry.fields[0].value)
    }

    @Test
    fun noteItemKeepsItsContentInNotes() {
        val item = """{"data":{"type":"note","metadata":{"name":"Wifi"},""" +
            """"content":{"secret":"s3"}}}"""
        val entry = JsonImportParser.parse(export(item)).entries[0]
        assertEquals("Wifi", entry.title)
        assertEquals("", String(entry.password))
        assertEquals("", entry.notes)
        assertEquals("secret", entry.fields[0].name)
        assertEquals("s3", entry.fields[0].value)
        assertTrue(entry.fields[0].isProtected)
        assertNull(entry.otpUri)
    }

    @Test
    fun itemOutsideAnyVaultIsNotGrouped() {
        val text = """{"vaults":{"v1":{"name":"","items":[{"data":{"type":"login",""" +
            """"metadata":{"name":"A"},"content":{"password":"p"}}}]}}}"""
        val entry = JsonImportParser.parse(text.toByteArray(Charsets.UTF_8)).entries[0]
        assertEquals(emptyList<String>(), entry.groupPath)
    }
}
