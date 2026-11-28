package com.keepasspn.tests.utils

import com.keepasspn.utils.inTheSameDomainAs
import junit.framework.TestCase

class UriHelperTest: TestCase() {

    fun testBuildURL() {
        val expected = "domain.org"

        assertTrue(expected.inTheSameDomainAs("domain.org", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("http://domain.org", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("https://domain.org", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("domain.org/login", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("http://domain.org/login", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("https://domain.org/login", sameSubDomain = false))

        assertTrue(expected.inTheSameDomainAs("https://www.domain.org", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("www.domain.org", sameSubDomain = false))
        assertTrue(expected.inTheSameDomainAs("ww.domain.org", sameSubDomain = false))

        assertTrue(expected.inTheSameDomainAs("domain.org", sameSubDomain = true))
        assertTrue(expected.inTheSameDomainAs("http://domain.org", sameSubDomain = true))
        assertTrue(expected.inTheSameDomainAs("https://domain.org", sameSubDomain = true))
        assertTrue(expected.inTheSameDomainAs("domain.org/login", sameSubDomain = true))
        assertTrue(expected.inTheSameDomainAs("http://domain.org/login", sameSubDomain = true))
        assertTrue(expected.inTheSameDomainAs("https://domain.org/login", sameSubDomain = true))

        assertFalse(expected.inTheSameDomainAs("https://www.domain.org", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("www.domain.org", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("ww.domain.org", sameSubDomain = true))

        assertFalse(expected.inTheSameDomainAs("domain.com", sameSubDomain = false))
        assertFalse(expected.inTheSameDomainAs("omain.org", sameSubDomain = false))
        assertFalse(expected.inTheSameDomainAs("odomain.org", sameSubDomain = false))
        assertFalse(expected.inTheSameDomainAs("tcp://domain.org", sameSubDomain = false))
        assertFalse(expected.inTheSameDomainAs("dom.org/domain.org", sameSubDomain = false))

        assertFalse(expected.inTheSameDomainAs("domain.com", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("omain.org", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("odomain.org", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("tcp://domain.org", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("dom.org/domain.org", sameSubDomain = true))

        assertFalse(expected.inTheSameDomainAs("https://example.com/domain.org", sameSubDomain = true))
        assertFalse(expected.inTheSameDomainAs("https://example.com/www.domain.org", sameSubDomain = false))
    }

    fun testParentDomainIsNotASubdomainMatch() {
        // An entry for a subdomain must not match the parent domain or a sibling tenant
        assertFalse("login.domain.org".inTheSameDomainAs("domain.org", sameSubDomain = false))
        assertFalse("acme.tenant-saas.com".inTheSameDomainAs("evil.tenant-saas.com", sameSubDomain = false))
        assertFalse("acme.tenant-saas.com".inTheSameDomainAs("tenant-saas.com", sameSubDomain = false))
        assertFalse("acme.tenant-saas.com".inTheSameDomainAs("tenant-saas.com", sameSubDomain = true))
        // An entry for a parent domain still matches its subdomains
        assertTrue("domain.org".inTheSameDomainAs("login.domain.org", sameSubDomain = false))
        assertTrue("https://tenant-saas.com".inTheSameDomainAs("https://acme.tenant-saas.com/login", sameSubDomain = false))
        assertFalse("domain.org".inTheSameDomainAs("login.domain.org", sameSubDomain = true))
    }
}