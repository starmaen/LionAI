package com.lion.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNetworkRetryTest {
    @Test
    fun transientDnsAndTransportFailuresAreRetryable() {
        assertTrue(isTransientPackageNetworkError("WARNING: fetching APKINDEX: DNS: transient error (try again later)"))
        assertTrue(isTransientPackageNetworkError("npm ERR! network request failed: connection reset by peer"))
        assertTrue(isTransientPackageNetworkError("Temporary failure in name resolution"))
    }

    @Test
    fun packageAndVerificationFailuresAreNotRetried() {
        assertFalse(isTransientPackageNetworkError("openjdk17-jdk (no such package)"))
        assertFalse(isTransientPackageNetworkError("Error occurred during initialization of VM"))
        assertFalse(isTransientPackageNetworkError("permission denied while read timed out"))
        assertFalse(isTransientPackageNetworkError("timeout; permission denied"))
        assertFalse(isTransientPackageNetworkError("gradle --version exited 1"))
    }
}
