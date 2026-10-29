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
package com.keepasspn.tests.database

import com.keepasspn.database.crypto.kdf.KdfFactory
import com.keepasspn.database.crypto.kdf.Limits
import com.keepasspn.model.DatabaseInfo
import com.keepasspn.database.element.MasterCredential
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.EntryInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A database written to KDBX and read back keeps its entries and their passwords.
 */
class DatabaseRoundTripTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val limits = Limits({ _, _ -> true }, 1L)
    private val challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray = { _, _ -> ByteArray(0) }

    @Test
    fun entriesAreReadBackAfterSaving() {
        val database = DatabaseInfo()
        database.createData("Test", "Root", null)
        database.kdfEngine = KdfFactory.defaultKdf
        val root = database.rootGroup!!
        listOf("Mail" to "s3cret", "Bank" to "p4ss").forEach { (title, password) ->
            val entryInfo = EntryInfo().apply {
                this.title = title
                username = "alice"
                this.password = password.toCharArray()
                url = "https://example.com"
            }
            val entry = database.createEntry(entryInfo)!!
            database.addEntryTo(entry, root)
        }

        val output = ByteArrayOutputStream()
        database.saveData(
            folder.newFile("cache.kdbx"),
            { output },
            { null },
            MasterCredential("correct horse".toCharArray()),
            challengeResponseRetriever,
            limits
        )

        val loaded = DatabaseInfo()
        loaded.loadData(
            ByteArrayInputStream(output.toByteArray()),
            MasterCredential("correct horse".toCharArray()),
            challengeResponseRetriever,
            true,
            false,
            folder.newFolder("binaries"),
            limits,
            false,
            null
        )

        val entries = loaded.rootGroup!!.getChildEntries().sortedBy { it.title }
        assertEquals(listOf("Bank", "Mail"), entries.map { it.title })
        assertArrayEquals("p4ss".toCharArray(), entries[0].password)
        assertArrayEquals("s3cret".toCharArray(), entries[1].password)
    }
}
