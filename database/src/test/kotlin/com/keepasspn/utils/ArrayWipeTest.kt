/*
 * Copyright 2026 hidefbi.
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
package com.keepasspn.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ArrayWipeTest {

    @Test
    fun charArrayWipeZeroesEverything() {
        val chars = "secret".toCharArray()
        chars.wipe()
        assertArrayEquals(CharArray(6) { charNull }, chars)
    }

    @Test
    fun byteArrayWipeZeroesEverything() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        bytes.wipe()
        assertArrayEquals(ByteArray(4), bytes)
    }

    @Test
    fun wipeOfEmptyArraysDoesNothing() {
        CharArray(0).wipe()
        ByteArray(0).wipe()
    }

    @Test
    fun clearStillWipes() {
        val chars = "pin".toCharArray()
        chars.clear()
        assertArrayEquals(CharArray(3) { charNull }, chars)

        val bytes = byteArrayOf(5, 6)
        bytes.clear()
        assertArrayEquals(ByteArray(2), bytes)
    }

    @Test
    fun useAndWipeReturnsResultAndWipesChars() {
        val chars = "pin".toCharArray()
        val length = chars.useAndWipe { it.size }
        assertEquals(3, length)
        assertArrayEquals(CharArray(3) { charNull }, chars)
    }

    @Test
    fun useAndWipeWipesBytesWhenBlockThrows() {
        val bytes = byteArrayOf(9, 9, 9)
        try {
            bytes.useAndWipe { throw IllegalStateException("boom") }
            fail("exception expected")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertArrayEquals(ByteArray(3), bytes)
    }

    @Test
    fun useAndWipeWipesCharsWhenBlockThrows() {
        val chars = "secret".toCharArray()
        try {
            chars.useAndWipe { throw IllegalStateException("boom") }
            fail("exception expected")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertArrayEquals(CharArray(6) { charNull }, chars)
    }
}
