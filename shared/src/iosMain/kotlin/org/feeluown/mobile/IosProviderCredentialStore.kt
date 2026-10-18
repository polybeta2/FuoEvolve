package org.feeluown.mobile

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.Security.errSecItemNotFound
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.providerCredentialsFromCookieInput
import platform.Security.errSecSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Suppress("CAST_NEVER_SUCCEEDS")
internal class IosProviderCredentialStore : ProviderCredentialStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun read(providerId: String): ProviderCredentials? = memScoped {
        val result = alloc<ObjCObjectVar<Any?>>()
        val status = withDictionary(providerId, includeData = true) { query ->
            SecItemCopyMatching(query, result.ptr.reinterpret())
        }
        if (status == errSecItemNotFound) return@memScoped null
        check(status == 0) { "Keychain read failed: $status" }
        val data = result.value as? NSData ?: return@memScoped null
        val bytes = data.bytes?.readBytes(data.length.toInt()) ?: return@memScoped null
        runCatching { json.decodeFromString<ProviderCredentials>(bytes.decodeToString()) }.getOrNull()
    }

    override suspend fun write(providerId: String, credentials: ProviderCredentials) {
        val value = json.encodeToString(credentials)
        val bytes = value.encodeToByteArray()
        memScoped {
            val data = NSData.Companion.create(
                bytes = bytes.refTo(0).getPointer(this),
                length = bytes.size.toULong(),
            )
            withDictionary(providerId, includeData = false) { query ->
                SecItemDelete(query)
            }
            val status = withDictionary(providerId, includeData = false, data = data) { dictionary ->
                SecItemAdd(dictionary, null)
            }
            check(status == 0) { "Keychain write failed: $status" }
        }
    }

    override suspend fun delete(providerId: String) {
        val status = withDictionary(providerId, includeData = false) { query ->
            SecItemDelete(query)
        }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "Keychain delete failed: $status"
        }
    }

    override suspend fun migrateLegacyIfNeeded() {
        val legacy = IosLegacySettingsLoader().load()
        legacy.providerCookieInputs.forEach { (providerId, raw) ->
            if (read(providerId) == null) {
                write(providerId, providerCredentialsFromCookieInput(raw))
            }
        }
        legacy.providerHeaderInputs.forEach { (providerId, input) ->
            if (read(providerId) == null && input.authorization.isNotBlank() && input.cookie.isNotBlank()) {
                write(
                    providerId,
                    ProviderCredentials(
                        authorization = input.authorization.trim(),
                        cookieHeader = input.cookie.trim(),
                    ),
                )
            }
        }
    }

    private inline fun <T> withDictionary(
        providerId: String,
        includeData: Boolean,
        data: NSData? = null,
        block: (CFDictionaryRef) -> T,
    ): T {
        val dictionary: CFMutableDictionaryRef = CFDictionaryCreateMutable(
            null,
            4,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("Failed to create Keychain dictionary")
        val service = CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8)
            ?: error("Failed to create Keychain service")
        val account = CFStringCreateWithCString(null, providerId, kCFStringEncodingUTF8)
            ?: error("Failed to create Keychain account")
        return try {
            CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(dictionary, kSecAttrService, service)
            CFDictionarySetValue(dictionary, kSecAttrAccount, account)
            if (includeData) CFDictionarySetValue(dictionary, kSecReturnData, kCFBooleanTrue)
            if (data != null) {
                val dataReference =
                    CFBridgingRetain(data) ?: error("Failed to bridge Keychain data")
                try {
                    CFDictionarySetValue(
                        dictionary,
                        kSecValueData,
                        dataReference.reinterpret<ByteVar>(),
                    )
                } finally {
                    CFBridgingRelease(dataReference)
                }
            }
            block(dictionary)
        } finally {
            CFRelease(service)
            CFRelease(account)
            CFRelease(dictionary)
        }
    }

    private companion object {
        const val SERVICE = "org.feeluown.mobile.provider.credentials"
    }
}
