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
 *
 */
package com.keepasspn.biometric

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import com.keepasspn.encrypt.argon2.Argon2Transformer
import com.keepasspn.encrypt.argon2.Argon2Type
import com.keepasspn.app.database.AppDatabase
import com.keepasspn.app.database.PinDatabaseEntity
import com.keepasspn.database.pin.PinEnvelope
import com.keepasspn.database.pin.PinPolicy
import com.keepasspn.utils.NoOpLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.CharBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The PIN does not match (GCM tag check failed on the outer layer). */
class WrongPinException(val remainingAttemptsBeforeLockout: Int) : Exception()

/**
 * The PIN entry was deleted after [PinPolicy.MAX_PIN_FAILURES] wrong attempts.
 * The master password is required from now on.
 */
class PinWipedException : Exception()

/**
 * The stored entry cannot be used (malformed, unsupported, or its device key is gone).
 * This is not a wrong PIN, so it does not count towards the lockout.
 */
class PinCorruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * PIN quick-unlock for one database, independent of biometric "Advanced Unlock".
 *
 * Format v2 (see [PinEnvelope]):
 *
 *   password
 *     -> AES-GCM with the device key (AndroidKeyStore, non-exportable),
 *        AAD = label + database URI
 *     -> AES-GCM with Argon2id(PIN, random 32-byte salt), 64 MiB / 3 iterations / 1 lane,
 *        AAD = label + database URI + full header
 *     -> stored as one string with the parameters inside, so they can be raised later
 *
 * Both layers must succeed: the ciphertext is useless without this device's Keystore
 * key, and the PIN alone cannot decrypt it. The AAD binds every layer to its database,
 * so an entry copied to another database fails authentication.
 *
 * Limits, stated plainly: a PIN has little entropy. The attempt counter lives in this
 * app's database, so a rooted device can reset it. Treat PIN unlock as a convenience,
 * not a replacement for the master password.
 *
 * Entries written by the previous revision (v1, no prefix) still unlock. On success they
 * are re-wrapped in the v2 format.
 */
@RequiresApi(Build.VERSION_CODES.M)
object PinCryptoHelper {

    private const val TAG = "PinCryptoHelper"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val DEVICE_KEY_ALIAS = "com.keepasspn.pin.device_key"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val DERIVED_KEY_LENGTH_BYTES = 32
    private const val ARGON2_VERSION_13 = 0x13
    private const val MIN_PIN_LENGTH = 6

    // v2 KDF parameters, written into every new envelope
    private const val V2_MEMORY_KIB = 64 * 1024
    private const val V2_ITERATIONS = 3
    private const val V2_PARALLELISM = 1

    // v1 parameters, only to unlock entries created by the previous revision
    private const val LEGACY_MEMORY_KIB = 19 * 1024
    private const val LEGACY_ITERATIONS = 3
    private const val LEGACY_PARALLELISM = 2


    // Serialises every PIN operation in this process. Without it, concurrent
    // attempts (e.g. a double tap) could all pass the lockout check before any
    // failure is recorded.
    private val pinLock = Mutex()
    private val random = SecureRandom()

    // ---------------------------------------------------------------------
    // Device-bound key (AndroidKeyStore, no biometric/device-credential gate)
    // ---------------------------------------------------------------------

    private fun existingDeviceKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(DEVICE_KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateDeviceKey(context: Context): SecretKey {
        existingDeviceKey()?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                DEVICE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).apply {
                setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                setKeySize(256)
                // Not requiring user authentication: the in-app PIN gates access instead.
                // Usable only while the device is unlocked, where the platform supports it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && context.packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
                ) {
                    setIsStrongBoxBacked(true)
                }
            }.build()
        )
        return keyGenerator.generateKey()
    }

    /** Removes the device key. Entries that depend on it become unusable. */
    fun deleteDeviceKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(DEVICE_KEY_ALIAS)
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to delete device key", e)
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun dao(context: Context) =
        AppDatabase.getDatabase(context.applicationContext).pinDatabaseDao()

    /** UTF-8 bytes of the PIN. The intermediate buffer is wiped. The caller wipes the result. */
    private fun encodePin(pin: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(pin))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    private fun validatePin(pin: CharArray) {
        require(pin.size >= MIN_PIN_LENGTH) { "PIN must contain at least $MIN_PIN_LENGTH digits" }
        require(pin.all { it in '0'..'9' }) { "PIN must contain digits only" }
    }

    private fun deriveKey(
        pinBytes: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int
    ): SecretKey {
        val keyBytes = Argon2Transformer.transformKey(
            type = Argon2Type.ARGON2_ID,
            password = pinBytes,
            salt = salt,
            parallelism = parallelism.toUInt(),
            memory = memoryKiB.toUInt(),
            iterations = iterations.toUInt(),
            version = ARGON2_VERSION_13
        )
        try {
            require(keyBytes.size >= DERIVED_KEY_LENGTH_BYTES) { "KDF output too short" }
            // SecretKeySpec copies the array, so the original can be wiped
            return SecretKeySpec(
                keyBytes.copyOf(DERIVED_KEY_LENGTH_BYTES),
                KeyProperties.KEY_ALGORITHM_AES
            )
        } finally {
            keyBytes.fill(0)
        }
    }

    /** Encrypts [password] for [databaseUri] with the v2 format and stores it. */
    private fun encryptAndStore(context: Context, databaseUri: String, pinBytes: ByteArray, password: ByteArray) {
        val deviceKey = getOrCreateDeviceKey(context)
        val innerCipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, deviceKey)
            updateAAD(PinEnvelope.innerAad(databaseUri))
        }
        val innerCiphertext = innerCipher.doFinal(password)
        val innerIv = innerCipher.iv

        val salt = ByteArray(PinEnvelope.SALT_LENGTH).also { random.nextBytes(it) }
        val outerIv = ByteArray(PinEnvelope.IV_LENGTH).also { random.nextBytes(it) }
        val header = PinEnvelope.buildHeader(V2_MEMORY_KIB, V2_ITERATIONS, V2_PARALLELISM, salt, outerIv)

        val pinKey = deriveKey(pinBytes, salt, V2_MEMORY_KIB, V2_ITERATIONS, V2_PARALLELISM)
        val outerCiphertext = Cipher.getInstance(AES_GCM).run {
            init(Cipher.ENCRYPT_MODE, pinKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, outerIv))
            updateAAD(PinEnvelope.outerAad(databaseUri, header))
            doFinal(PinEnvelope.joinOuterPlaintext(innerIv, innerCiphertext))
        }

        val envelope = PinEnvelope(
            V2_MEMORY_KIB, V2_ITERATIONS, V2_PARALLELISM, salt, outerIv, outerCiphertext
        ).toBinary()

        val entity = PinDatabaseEntity(
            databaseUri = databaseUri,
            encryptedValue = PinEnvelope.PREFIX + Base64.encodeToString(envelope, Base64.URL_SAFE or Base64.NO_WRAP),
            ivInner = "",
            ivOuter = "",
            pinSalt = "",
            failedAttempts = 0,
            lockedUntil = 0L
        )
        val dao = dao(context)
        if (dao.getByDatabaseUri(databaseUri) == null) dao.add(entity) else dao.update(entity)
    }

    private fun recordFailure(context: Context, databaseUri: String): Nothing {
        val state = dao(context).recordPinFailure(databaseUri, PinPolicy.MAX_PIN_FAILURES)
            ?: throw IllegalStateException("No PIN set for this database")
        if (state.wiped) {
            // The PIN is gone: the master password is required, with a short allowance
            // before the database itself is removed from the app.
            PinPolicyStore.startMasterLockout(context, Uri.parse(databaseUri))
            throw PinWipedException()
        }
        throw WrongPinException(PinPolicy.MAX_PIN_FAILURES - state.failedAttempts)
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    suspend fun isPinSet(context: Context, databaseUri: Uri): Boolean = withContext(Dispatchers.IO) {
        dao(context).getByDatabaseUri(databaseUri.toString()) != null
    }

    /**
     * Stores [password] wrapped by [pin]. Call right after a successful full unlock.
     * [pin] is wiped on return. [password] is not modified.
     */
    suspend fun setPin(
        context: Context,
        databaseUri: Uri,
        pin: CharArray,
        password: ByteArray
    ) = withContext(Dispatchers.IO) {
        try {
            validatePin(pin)
            pinLock.withLock {
                val pinBytes = encodePin(pin)
                try {
                    encryptAndStore(context, databaseUri.toString(), pinBytes, password)
                } finally {
                    pinBytes.fill(0)
                }
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    /**
     * Recovers the stored password for [databaseUri]. [pin] is wiped on return.
     * The caller owns the returned bytes and should wipe them after use.
     *
     * @throws WrongPinException for a wrong PIN, before the limit is reached
     * @throws PinWipedException when this failure wiped the entry
     * @throws PinCorruptedException when the entry cannot be used (not counted as a failure)
     */
    suspend fun unlockWithPin(
        context: Context,
        databaseUri: Uri,
        pin: CharArray
    ): ByteArray = withContext(Dispatchers.IO) {
        try {
            pinLock.withLock {
                val uri = databaseUri.toString()
                val entity = dao(context).getByDatabaseUri(uri)
                    ?: throw IllegalStateException("No PIN set for this database")

                val pinBytes = encodePin(pin)
                try {
                    if (PinEnvelope.isV2(entity.encryptedValue)) {
                        decryptV2(context, uri, entity.encryptedValue, pinBytes)
                    } else {
                        decryptLegacy(context, uri, entity, pinBytes)
                    }
                } catch (e: AEADBadTagException) {
                    recordFailure(context, uri)
                } catch (e: BadPaddingException) {
                    recordFailure(context, uri)
                } finally {
                    pinBytes.fill(0)
                }
            }
        } finally {
            pin.fill('\u0000')
        }
    }

    /** Throws AEADBadTagException on a wrong PIN, which the caller counts as a failure. */
    private fun decryptV2(context: Context, uri: String, stored: String, pinBytes: ByteArray): ByteArray {
        val envelope = try {
            PinEnvelope.fromBinary(
                Base64.decode(stored.removePrefix(PinEnvelope.PREFIX), Base64.URL_SAFE or Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            throw PinCorruptedException("Malformed PIN entry", e)
        }

        val pinKey = deriveKey(
            pinBytes, envelope.salt, envelope.memoryKiB, envelope.iterations, envelope.parallelism
        )
        val outerPlaintext = Cipher.getInstance(AES_GCM).run {
            init(Cipher.DECRYPT_MODE, pinKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.outerIv))
            updateAAD(PinEnvelope.outerAad(uri, envelope.headerBytes()))
            doFinal(envelope.ciphertext)
        }

        // The PIN is verified from here on. Any failure below is a storage problem, not a wrong PIN.
        val deviceKey = existingDeviceKey()
            ?: throw PinCorruptedException("Device key is missing; the PIN must be set again")
        return try {
            val (innerIv, innerCiphertext) = PinEnvelope.splitOuterPlaintext(outerPlaintext)
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, deviceKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, innerIv))
                updateAAD(PinEnvelope.innerAad(uri))
                doFinal(innerCiphertext)
            }.also {
                dao(context).resetFailureState(uri)
            }
        } catch (e: Exception) {
            throw PinCorruptedException("PIN entry failed integrity check", e)
        }
    }

    /** v1 entries (previous revision). Re-wrapped as v2 after a successful unlock. */
    private fun decryptLegacy(context: Context, uri: String, entity: PinDatabaseEntity, pinBytes: ByteArray): ByteArray {
        val (salt, ivOuter, outerCiphertext, ivInner) = try {
            listOf(
                Base64.decode(entity.pinSalt, Base64.NO_WRAP),
                Base64.decode(entity.ivOuter, Base64.NO_WRAP),
                Base64.decode(entity.encryptedValue, Base64.NO_WRAP),
                Base64.decode(entity.ivInner, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            throw PinCorruptedException("Malformed legacy PIN entry", e)
        }

        val pinKey = deriveKey(pinBytes, salt, LEGACY_MEMORY_KIB, LEGACY_ITERATIONS, LEGACY_PARALLELISM)
        val innerCiphertext = Cipher.getInstance(AES_GCM).run {
            init(Cipher.DECRYPT_MODE, pinKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivOuter))
            doFinal(outerCiphertext)
        }

        val deviceKey = existingDeviceKey()
            ?: throw PinCorruptedException("Device key is missing; the PIN must be set again")
        val password = try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, deviceKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivInner))
                doFinal(innerCiphertext)
            }
        } catch (e: Exception) {
            throw PinCorruptedException("Legacy PIN entry failed integrity check", e)
        }

        dao(context).resetFailureState(uri)
        try {
            encryptAndStore(context, uri, pinBytes, password)
        } catch (e: Exception) {
            // Keep the legacy entry usable; the upgrade is retried on the next unlock
            NoOpLog.e(TAG, "Unable to upgrade legacy PIN entry", e)
        }
        return password
    }

    /**
     * Changes the PIN. The old PIN must be correct; a wrong old PIN counts as a failure.
     * [oldPin] and [newPin] are wiped on return.
     */
    suspend fun changePin(
        context: Context,
        databaseUri: Uri,
        oldPin: CharArray,
        newPin: CharArray
    ) = withContext(Dispatchers.IO) {
        val password = unlockWithPin(context, databaseUri, oldPin)
        try {
            setPin(context, databaseUri, newPin, password)
        } finally {
            password.fill(0)
        }
    }

    suspend fun listPinDatabaseUris(context: Context): List<Uri> = withContext(Dispatchers.IO) {
        dao(context).getAll().map { Uri.parse(it.databaseUri) }
    }

    suspend fun removePin(context: Context, databaseUri: Uri) = withContext(Dispatchers.IO) {
        pinLock.withLock {
            dao(context).deleteByDatabaseUri(databaseUri.toString())
        }
    }

    suspend fun removeAllPins(context: Context) = withContext(Dispatchers.IO) {
        pinLock.withLock {
            dao(context).deleteAll()
            deleteDeviceKey()
        }
    }
}
