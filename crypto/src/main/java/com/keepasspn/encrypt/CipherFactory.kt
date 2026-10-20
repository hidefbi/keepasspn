package com.keepasspn.encrypt

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CipherFactory {

    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }

    @Throws(NoSuchAlgorithmException::class, NoSuchPaddingException::class, InvalidKeyException::class, InvalidAlgorithmParameterException::class)
    fun getAES(opmode: Int, key: ByteArray, iv: ByteArray): Cipher {
        // Always the platform (JCA) implementation: audited and hardware accelerated.
        // No custom native cipher is used for user data.
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(opmode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    @Throws(NoSuchAlgorithmException::class, NoSuchPaddingException::class, InvalidKeyException::class, InvalidAlgorithmParameterException::class)
    fun getTwofish(opmode: Int, key: ByteArray, iv: ByteArray, forceCompatibility: Boolean = false): Cipher {
        val cipher: Cipher = if (forceCompatibility) {
            Cipher.getInstance("Twofish/CBC/NoPadding")
        } else {
            Cipher.getInstance("Twofish/CBC/PKCS7PADDING")
        }
        cipher.init(opmode, SecretKeySpec(key, "Twofish"), IvParameterSpec(iv))
        return cipher
    }

    @Throws(NoSuchAlgorithmException::class, NoSuchPaddingException::class, InvalidKeyException::class, InvalidAlgorithmParameterException::class)
    fun getChacha20(opmode: Int, key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("Chacha7539", BouncyCastleProvider())
        cipher.init(opmode, SecretKeySpec(key, "ChaCha7539"), IvParameterSpec(iv))
        return cipher
    }
}