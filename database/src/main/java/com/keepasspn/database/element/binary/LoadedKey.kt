package com.keepasspn.database.element.binary

import java.io.Serializable
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Session key protecting the temporary binary files written on disk.
 *
 * AES-256 in GCM mode (authenticated encryption). A fresh random nonce is generated for
 * every file and stored in that file's header, so a key is never reused with the same nonce.
 */
class LoadedKey(val key: SecretKey) : Serializable {
    companion object {
        const val BINARY_CIPHER = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128

        fun generateNewCipherKey(): LoadedKey {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256)
            return LoadedKey(keyGenerator.generateKey())
        }
    }
}