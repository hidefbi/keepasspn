/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.password

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class UsernameGeneratorTest {

    @Test
    fun lengthIsClampedToSupportedRange() {
        val short = UsernameGenerator.generate(1, true, false, false)
        val long = UsernameGenerator.generate(500, true, false, false)
        assertEquals(UsernameGenerator.MIN_LENGTH, short.length)
        assertEquals(UsernameGenerator.MAX_LENGTH, long.length)
    }

    @Test
    fun onlyLowercaseWhenOnlyLowercaseSelected() {
        repeat(20) {
            val value = UsernameGenerator.generate(24, true, false, false)
            assertTrue(value.all { it in 'a'..'z' })
        }
    }

    @Test
    fun onlyDigitsWhenOnlyDigitsSelected() {
        repeat(20) {
            val value = UsernameGenerator.generate(24, false, false, true)
            assertTrue(value.all { it in '0'..'9' })
        }
    }

    @Test
    fun fallsBackToLowercaseWhenNothingSelected() {
        val value = UsernameGenerator.generate(12, false, false, false)
        assertEquals(12, value.length)
        assertTrue(value.all { it in 'a'..'z' })
    }

    @Test
    fun mixedSetsOnlyUseAllowedCharacters() {
        val random = SecureRandom()
        repeat(50) {
            val value = UsernameGenerator.generate(32, true, true, false, random)
            assertTrue(value.all { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }
}
