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
import com.keepasspn.database.element.MasterCredential
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.DatabaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A saved database opens with its own password and refuses another one.
 * The KDBX XML uses the Android XML serializer, so the test runs with Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseLoadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val limits = Limits({ _, _ -> true }, 1L)
    private val challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray = { _, _ -> ByteArray(0) }

    private fun savedDatabase(password: String): ByteArray {
        val database = DatabaseInfo()
        database.createData("Test", "Root", null)
        database.kdfEngine = KdfFactory.defaultKdf
        val output = ByteArrayOutputStream()
        database.saveData(
            folder.newFile("cache.kdbx"),
            { output },
            { null },
            MasterCredential(password.toCharArray()),
            challengeResponseRetriever,
            limits
        )
        return output.toByteArray()
    }

    private fun openWith(bytes: ByteArray, password: String): DatabaseInfo {
        val loaded = DatabaseInfo()
        loaded.loadData(
            ByteArrayInputStream(bytes),
            MasterCredential(password.toCharArray()),
            challengeResponseRetriever,
            true,
            false,
            folder.newFolder("binaries"),
            limits,
            false,
            null
        )
        return loaded
    }

    @Test
    fun rightPasswordIsAccepted() {
        val loaded = openWith(savedDatabase("correct horse"), "correct horse")
        assertEquals("Root", loaded.rootGroup!!.title)
    }

    @Test
    fun databaseCannotBeOpenedWithAWrongPassword() {
        val bytes = savedDatabase("correct horse")
        try {
            openWith(bytes, "wrong horse")
        } catch (e: Exception) {
            return
        }
        fail("The database opened with a wrong password")
    }
}
