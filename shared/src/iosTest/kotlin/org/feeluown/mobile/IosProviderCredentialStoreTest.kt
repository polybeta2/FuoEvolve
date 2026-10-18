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

        store.delete(providerId)
        assertNull(store.read(providerId))

        try {
            store.write(providerId, expected)
            assertEquals(expected, store.read(providerId))
        } finally {
            store.delete(providerId)
        }

        assertNull(store.read(providerId))
    }
}
