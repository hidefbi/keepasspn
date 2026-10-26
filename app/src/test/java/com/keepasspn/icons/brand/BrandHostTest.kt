/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 * KeePassPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.keepasspn.icons.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandHostTest {

    @Test
    fun fullAddressGivesHost() {
        assertEquals("github.com", hostOf("https://github.com/login"))
        assertEquals("github.com", hostOf("https://www.GitHub.com/login?x=1#top"))
    }

    @Test
    fun bareDomainGivesHost() {
        assertEquals("google.com", hostOf("google.com"))
        assertEquals("google.com", hostOf("  google.com. "))
    }

    @Test
    fun portAndUserAreRemoved() {
        assertEquals("example.org", hostOf("https://user@example.org:8443/path"))
    }

    @Test
    fun emptyAddressGivesNull() {
        assertNull(hostOf("   "))
        assertNull(hostOf("https://"))
    }
}
