package com.keepasspn.database.element.binary

import com.keepasspn.encrypt.HashManager
import java.io.Serializable
import java.security.Key
import javax.crypto.KeyGenerator

class LoadedKey(val key: Key, val iv: ByteArray): Serializable {
    companion object {
        const val BINARY_CIPHER = "Blowfish/CBC/PKCS5Padding"

        fun generateNewCipherKey(): LoadedKey {
            return LoadedKey(
                KeyGenerator.getInstance("Blowfish").generateKey(),
                HashManager.generateRandom(8)
            )
        }
    }
}