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

class BrandIconIndexTest {

    private val index = BrandIconIndex(
        listOf(
            BrandIcon("github", "GitHub", emptyList(), "181717", "M0 0"),
            BrandIcon("x", "X", emptyList(), "000000", "M0 0"),
            BrandIcon("paypal", "PayPal", emptyList(), "003087", "M0 0"),
            BrandIcon("dotenv", ".ENV", listOf("Dotenv"), "ECD53F", "M0 0"),
            BrandIcon("1password", "1Password", emptyList(), "0094F5", "M0 0"),
            BrandIcon("foo", "Bar", emptyList(), "000000", "M0 0"),
            BrandIcon("bar", "Foo", emptyList(), "000000", "M0 0")
        )
    )

    @Test
    fun matchesSlugOfRegistrableLabel() {
        assertEquals("github", index.findForRegistrableDomain("github.com")?.slug)
        assertEquals("paypal", index.findForRegistrableDomain("paypal.com")?.slug)
    }

    @Test
    fun matchesLabelRegardlessOfCase() {
        assertEquals("github", index.findForRegistrableDomain("GitHub.com")?.slug)
    }

    @Test
    fun matchesTitleAndAlias() {
        assertEquals("dotenv", index.findForRegistrableDomain("dotenv.org")?.slug)
        assertEquals("dotenv", index.findForRegistrableDomain("env.com")?.slug)
        assertEquals("1password", index.findForRegistrableDomain("1password.com")?.slug)
    }

    @Test
    fun manualAliasMapsToTarget() {
        assertEquals("x", index.findForRegistrableDomain("twitter.com")?.slug)
    }

    @Test
    fun slugWinsOverTitle() {
        // Icon "bar" has title "Foo", icon "foo" has slug "foo"
        assertEquals("foo", index.findForRegistrableDomain("foo.com")?.slug)
        assertEquals("bar", index.findForRegistrableDomain("bar.com")?.slug)
    }

    @Test
    fun partialOrLookalikeLabelsDoNotMatch() {
        assertNull(index.findForRegistrableDomain("paypal-secure.com"))
        assertNull(index.findForRegistrableDomain("githubx.com"))
        assertNull(index.findForRegistrableDomain("unknown.org"))
    }

    @Test
    fun emptyOrDegenerateInputDoesNotMatch() {
        assertNull(index.findForRegistrableDomain(""))
        assertNull(index.findForRegistrableDomain(".com"))
        assertNull(index.findForRegistrableDomain("---"))
    }
}
