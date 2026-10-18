package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.random.Random
import org.feeluown.mobile.provider.core.ProviderCredentials

class IosProviderCredentialStoreTest {
    @Test
    fun keychainRoundTripUsesNativeDictionaryForQueriesAndAdd() = runTest {
        val store = IosProviderCredentialStore()
        val providerId = "ios-keychain-dictionary-regression-${Random.nextLong()}"
        val expected = ProviderCredentials(
            cookies = mapOf("sid" to "test-session"),
            authorization = "Bearer test-token",
            cookieHeader = "sid=test-session",
        )

        println("KEYCHAIN_DIAGNOSTIC before initial delete")
        store.delete(providerId)
        println("KEYCHAIN_DIAGNOSTIC before initial read")
        assertNull(store.read(providerId))

        try {
            println("KEYCHAIN_DIAGNOSTIC before write")
            store.write(providerId, expected)
            println("KEYCHAIN_DIAGNOSTIC before read after write")
            assertEquals(expected, store.read(providerId))
        } finally {
            println("KEYCHAIN_DIAGNOSTIC before final delete")
            store.delete(providerId)
        }

        println("KEYCHAIN_DIAGNOSTIC before final read")
        assertNull(store.read(providerId))
    }
}
