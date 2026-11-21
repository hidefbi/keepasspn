/*
 * Copyright 2020 Jeremy Jamet / Kunzisoft.
 * Modified by hidefbi, 2026.
 *
 * This file is part of KeePassPN.
 *
 *  KeePassPN is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassPN is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.biometric

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.keepasspn.utils.NoOpLog
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.ContextCompat
import com.keepasspn.R
import com.keepasspn.app.database.CipherDatabaseAction
import com.keepasspn.settings.PreferencesUtil
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec

@RequiresApi(api = Build.VERSION_CODES.M)
class DeviceUnlockManager(private var appContext: Context) {

    private var keyStore: KeyStore? = null
    private var keyGenerator: KeyGenerator? = null

    private var biometricUnlockEnable = isBiometricUnlockEnable(appContext)
    private var deviceCredentialUnlockEnable = isDeviceCredentialUnlockEnable(appContext)

    init {
        if (biometricUnlockEnable || deviceCredentialUnlockEnable) {
            if (isDeviceSecure(appContext)) {
                try {
                    this.keyStore = KeyStore.getInstance(DEVICE_UNLOCK_KEYSTORE)
                    this.keyGenerator = KeyGenerator.getInstance(
                        DEVICE_UNLOCK_KEY_ALGORITHM,
                        DEVICE_UNLOCK_KEYSTORE
                    )
                    if (keyStore == null) {
                        throw SecurityException("Unable to initialize the keystore")
                    }
                    if (keyGenerator == null) {
                        throw SecurityException("Unable to initialize the key generator")
                    }
                } catch (e: Exception) {
                    NoOpLog.e(TAG, "Unable to initialize the device unlock manager", e)
                    throw e
                }
            } else {
                throw SecurityException("Device not secure enough")
            }
        }
    }

    // Only the GCM key is ever created, the legacy key is read only
    @Synchronized private fun getSecretKey(alias: String, create: Boolean): SecretKey? {
        val keyStore = keyStore ?: return null
        try {
            keyStore.load(null)
            if (create && !keyStore.containsAlias(alias)) {
                try {
                    keyGenerator?.init(
                        KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(DEVICE_UNLOCK_BLOCK_MODE)
                            .setEncryptionPaddings(DEVICE_UNLOCK_ENCRYPTION_PADDING)
                            .apply {
                                if (biometricUnlockEnable || deviceCredentialUnlockEnable) {
                                    // Authenticate with biometric or device credential
                                    // to authorize every use of the key
                                    setUserAuthenticationRequired(true)
                                }
                                if (isDeviceCredentialBiometricOperation(deviceCredentialUnlockEnable)) {
                                    // Authent with device credential on Android R+
                                    // define a validity duration to fix "At least one biometric must be enrolled"
                                    // if no biometric enrolled
                                    setUserAuthenticationParameters(
                                        0,
                                        KeyProperties.AUTH_DEVICE_CREDENTIAL
                                    )
                                } else if (deviceCredentialUnlockEnable) {
                                    // Authent with device credential on Legacy Android M-Q
                                    // duration required for PIN/Pattern on older APIs.
                                    // LIMITATION (accepted): on API 23-29 the platform only offers
                                    // time-based authorization for device credentials, so after a
                                    // successful PIN/pattern/password unlock this app-private key
                                    // stays usable for 5 seconds. Per-use authorization (-1) is not
                                    // available with the keyguard flow used here. Any app-level
                                    // attacker able to run code in this process already has the
                                    // unlocked database, so the window adds no new exposure beyond
                                    // the process. See SECURITY_HARDENING.md.
                                    @Suppress("DEPRECATION")
                                    setUserAuthenticationValidityDurationSeconds(5)
                                }
                                // To store in the security chip
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                    && appContext.packageManager.hasSystemFeature(
                                        PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                                    setIsStrongBoxBacked(true)
                                }
                            }
                            .build())
                    keyGenerator?.generateKey()
                } catch (e: Exception) {
                    NoOpLog.e(TAG, "Unable to create a key in keystore", e)
                    throw e
                }
            }
            val secretKey = keyStore.getKey(alias, null) as SecretKey?
            if (secretKey == null && !create) {
                throw UnrecoverableKeyException("Device unlock key not found")
            }
            return secretKey
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to retrieve the key in keystore", e)
            throw e
        }
    }

    @Synchronized fun initEncryptData(
        actionIfCypherInit: (cryptoPrompt: DeviceUnlockCryptoPrompt) -> Unit
    ) {
        initEncryptData(true, actionIfCypherInit)
    }

    @Synchronized private fun initEncryptData(
        firstLaunch: Boolean,
        actionIfCypherInit: (cryptoPrompt: DeviceUnlockCryptoPrompt) -> Unit
    ) {
        try {
            getSecretKey(DEVICE_UNLOCK_KEYSTORE_KEY, create = true)?.let { secretKey ->
                val cipher = Cipher.getInstance(DEVICE_UNLOCK_TRANSFORMATION)
                try {
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                } catch (e: Exception) {
                    if (isUserNotAuthenticatedException(deviceCredentialUnlockEnable, e)) {
                        // Show the prompt without initialized cipher device credential legacy
                        NoOpLog.i(TAG, "User not authenticated, will initialize cipher after prompt")
                    } else {
                        throw e
                    }
                }
                actionIfCypherInit.invoke(
                    DeviceUnlockCryptoPrompt(
                        type = DeviceUnlockCryptoPromptType.CREDENTIAL_ENCRYPTION,
                        cipher = cipher,
                        titleId = R.string.device_unlock_prompt_store_credential_title,
                        descriptionId = R.string.device_unlock_prompt_store_credential_message,
                        isDeviceCredentialOperation = isDeviceCredentialOperation(
                            deviceCredentialUnlockEnable
                        ),
                        isBiometricOperation = isBiometricOperation(
                            biometricUnlockEnable, deviceCredentialUnlockEnable
                        )
                    )
                )
            }
        } catch (unrecoverableKeyException: UnrecoverableKeyException) {
            NoOpLog.e(TAG, "Unable to initialize encrypt data", unrecoverableKeyException)
            throw unrecoverableKeyException
        } catch (invalidKeyException: KeyPermanentlyInvalidatedException) {
            NoOpLog.e(TAG, "Unable to initialize encrypt data", invalidKeyException)
            if (firstLaunch) {
                deleteAllEntryKeysInKeystoreForBiometric(appContext)
                initEncryptData(false, actionIfCypherInit)
            } else {
                throw invalidKeyException
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to initialize encrypt data", e)
            throw e
        }
    }

    @Synchronized fun encryptData(
        value: ByteArray,
        cipher: Cipher?,
        handleEncryptedResult: (encryptedValue: ByteArray, ivSpec: ByteArray) -> Unit
    ) {
        if (cipher == null) {
            return
        }
        try {
            if (isDeviceCredentialOperation(deviceCredentialUnlockEnable)) {
                getSecretKey(DEVICE_UNLOCK_KEYSTORE_KEY, create = true)?.let { secretKey ->
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                }
            }
            val encrypted = cipher.doFinal(value)
            // the keystore picks the nonce, it is saved with the data
            val gcmSpec = cipher.parameters?.getParameterSpec(GCMParameterSpec::class.java)
                ?: throw IllegalStateException("Missing GCM parameters")
            handleEncryptedResult.invoke(encrypted, gcmSpec.iv)
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to encrypt data", e)
            throw e
        }
    }

    @Synchronized fun initDecryptData(
        ivSpecValue: ByteArray,
        actionIfCypherInit: (cryptoPrompt: DeviceUnlockCryptoPrompt) -> Unit
    ) {
        initDecryptData(ivSpecValue, true, actionIfCypherInit)
    }

    @Synchronized private fun initDecryptData(
        ivSpecValue: ByteArray,
        firstLaunch: Boolean = true,
        actionIfCypherInit: (cryptoPrompt: DeviceUnlockCryptoPrompt) -> Unit
    ) {
        try {
            // the spec has to match the one used for encryption
            val alias = keyAliasFor(ivSpecValue)
            getSecretKey(alias, create = false)?.let { secretKey ->
                val cipher = Cipher.getInstance(transformationFor(ivSpecValue))
                try {
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, decryptParameterSpec(ivSpecValue))
                } catch (e: Exception) {
                    if (isUserNotAuthenticatedException(deviceCredentialUnlockEnable, e)) {
                        NoOpLog.i(TAG, "User not authenticated, will initialize cipher after prompt")
                    } else {
                        throw e
                    }
                }
                actionIfCypherInit.invoke(
                    DeviceUnlockCryptoPrompt(
                        type = DeviceUnlockCryptoPromptType.CREDENTIAL_DECRYPTION,
                        cipher = cipher,
                        titleId = R.string.device_unlock_prompt_extract_credential_title,
                        descriptionId = R.string.device_unlock_prompt_extract_credential_message,
                        isDeviceCredentialOperation = isDeviceCredentialOperation(
                            deviceCredentialUnlockEnable
                        ),
                        isBiometricOperation = isBiometricOperation(
                            biometricUnlockEnable, deviceCredentialUnlockEnable
                        )
                    )
                )
            }
        } catch (unrecoverableKeyException: UnrecoverableKeyException) {
            NoOpLog.e(TAG, "Unable to initialize decrypt data", unrecoverableKeyException)
            if (firstLaunch) {
                deleteKeystoreKeys(keyAliasFor(ivSpecValue))
                initDecryptData(ivSpecValue, false, actionIfCypherInit)
            } else {
                throw unrecoverableKeyException
            }
        } catch (invalidKeyException: KeyPermanentlyInvalidatedException) {
            NoOpLog.e(TAG, "Unable to initialize decrypt data", invalidKeyException)
            if (firstLaunch) {
                deleteAllEntryKeysInKeystoreForBiometric(appContext)
                initDecryptData(ivSpecValue, false, actionIfCypherInit)
            } else {
                throw invalidKeyException
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to initialize decrypt data", e)
            throw e
        }
    }

    // 16 bytes IV means legacy CBC, 12 bytes means GCM
    @Synchronized fun decryptData(
        encryptedValue: ByteArray,
        cipher: Cipher?,
        ivSpecValue: ByteArray,
        handleDecryptedResult: (decryptedValue: ByteArray) -> Unit
    ) {
        try {
            if (isDeviceCredentialOperation(deviceCredentialUnlockEnable)) {
                getSecretKey(keyAliasFor(ivSpecValue), create = false)?.let { secretKey ->
                    cipher?.init(Cipher.DECRYPT_MODE, secretKey, decryptParameterSpec(ivSpecValue))
                }
            }
            // actual decryption here
            cipher?.doFinal(encryptedValue)?.let { decrypted ->
                handleDecryptedResult.invoke(decrypted)
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to decrypt data", e)
            throw e
        }
    }

    companion object {

        private val TAG = DeviceUnlockManager::class.java.name

        private const val DEVICE_UNLOCK_KEYSTORE = "AndroidKeyStore"
        // new credentials
        private const val DEVICE_UNLOCK_KEYSTORE_KEY = "com.keepasspn.biometric.gcm.key"
        // CBC, only to read credentials saved before GCM
        private const val DEVICE_UNLOCK_KEYSTORE_KEY_LEGACY = "com.keepasspn.biometric.key"
        private const val DEVICE_UNLOCK_KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val DEVICE_UNLOCK_BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val DEVICE_UNLOCK_ENCRYPTION_PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val DEVICE_UNLOCK_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val LEGACY_TRANSFORMATION = "AES/CBC/PKCS7Padding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val LEGACY_IV_LENGTH_BYTES = 16

        private fun isLegacyIv(ivSpecValue: ByteArray): Boolean {
            return ivSpecValue.size == LEGACY_IV_LENGTH_BYTES
        }

        private fun keyAliasFor(ivSpecValue: ByteArray): String {
            return if (isLegacyIv(ivSpecValue)) {
                DEVICE_UNLOCK_KEYSTORE_KEY_LEGACY
            } else {
                DEVICE_UNLOCK_KEYSTORE_KEY
            }
        }

        private fun transformationFor(ivSpecValue: ByteArray): String {
            return if (isLegacyIv(ivSpecValue)) {
                LEGACY_TRANSFORMATION
            } else {
                DEVICE_UNLOCK_TRANSFORMATION
            }
        }

        private fun decryptParameterSpec(ivSpecValue: ByteArray): AlgorithmParameterSpec {
            return if (isLegacyIv(ivSpecValue)) {
                IvParameterSpec(ivSpecValue)
            } else {
                require(ivSpecValue.size == GCM_IV_LENGTH_BYTES) { "Unexpected IV length" }
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, ivSpecValue)
            }
        }

        // Legacy rows have a 16 bytes IV
        fun isLegacyCipherParameters(specParametersBase64: String): Boolean {
            return try {
                Base64.decode(specParametersBase64, Base64.DEFAULT).size == LEGACY_IV_LENGTH_BYTES
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        private fun deleteKeystoreKeys(vararg aliases: String) {
            val keyStore = KeyStore.getInstance(DEVICE_UNLOCK_KEYSTORE)
            keyStore.load(null)
            aliases.forEach { alias ->
                keyStore.deleteEntry(alias)
            }
        }

        // Call it only when no legacy row is left
        fun deleteLegacyKeyInKeystore() {
            try {
                deleteKeystoreKeys(DEVICE_UNLOCK_KEYSTORE_KEY_LEGACY)
            } catch (e: Exception) {
                NoOpLog.e(TAG, "Unable to delete legacy key in keystore", e)
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        fun canAuthenticate(context: Context): Int {
            return try {
                BiometricManager.from(context).canAuthenticate(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && PreferencesUtil.isDeviceCredentialUnlockEnable(context)) {
                        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
                    } else {
                        BIOMETRIC_STRONG
                    }
                )
            } catch (e: Exception) {
                NoOpLog.e(TAG, "Unable to authenticate with strong biometric.", e)
                try {
                    BiometricManager.from(context).canAuthenticate(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                    && PreferencesUtil.isDeviceCredentialUnlockEnable(context)) {
                                BIOMETRIC_WEAK or DEVICE_CREDENTIAL
                            } else {
                                BIOMETRIC_WEAK
                            }
                    )
                } catch (e: Exception) {
                    NoOpLog.e(TAG, "Unable to authenticate with weak biometric.", e)
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                }
            }
        }

        fun isDeviceSecure(context: Context): Boolean {
            return ContextCompat.getSystemService(context, KeyguardManager::class.java)
                ?.isDeviceSecure ?: false
        }

        fun biometricUnlockSupported(context: Context): Boolean {
            val biometricCanAuthenticate = try {
                BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)
            } catch (e: Exception) {
                NoOpLog.e(TAG, "Unable to authenticate with strong biometric.", e)
                try {
                    BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK)
                } catch (e: Exception) {
                    NoOpLog.e(TAG, "Unable to authenticate with weak biometric.", e)
                    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                }
            }
            return (biometricCanAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
                    || biometricCanAuthenticate == BiometricManager.BIOMETRIC_STATUS_UNKNOWN
                    || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
                    || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                    || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            )
        }

        fun deviceCredentialUnlockSupported(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val biometricCanAuthenticate = BiometricManager.from(context).canAuthenticate(DEVICE_CREDENTIAL)
                (biometricCanAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
                        || biometricCanAuthenticate == BiometricManager.BIOMETRIC_STATUS_UNKNOWN
                        || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
                        || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                        || biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
                        )
            } else {
                true
            }
        }

        fun deleteAllEntryKeysInKeystoreForBiometric(appContext: Context) {
            try {
                deleteKeystoreKeys(DEVICE_UNLOCK_KEYSTORE_KEY, DEVICE_UNLOCK_KEYSTORE_KEY_LEGACY)
            } catch (e: Exception) {
                Toast.makeText(appContext,
                    deviceUnlockError(e, appContext),
                    Toast.LENGTH_SHORT).show()
            } finally {
                CipherDatabaseAction.getInstance(appContext).deleteAll()
            }
        }
    }
}

fun deviceUnlockError(error: Throwable, context: Context): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        && (error is UnrecoverableKeyException
                || error is KeyPermanentlyInvalidatedException
                // altered or wrong data
                || error is AEADBadTagException
                || error is BadPaddingException)) {
        context.getString(R.string.device_unlock_invalid_key)
    } else
        error.cause?.localizedMessage
            ?: error.localizedMessage
            ?: error.toString()
}

fun isBiometricUnlockEnable(appContext: Context) =
    PreferencesUtil.isBiometricUnlockEnable(appContext)

fun isDeviceCredentialUnlockEnable(appContext: Context) =
    PreferencesUtil.isDeviceCredentialUnlockEnable(appContext)

private fun isBiometricOperation(
    biometricUnlockEnable: Boolean,
    deviceCredentialUnlockEnable: Boolean
): Boolean {
    return biometricUnlockEnable
            || isDeviceCredentialBiometricOperation(deviceCredentialUnlockEnable)
}

// Since Android 30, device credential is also a biometric operation
private fun isDeviceCredentialOperation(
    deviceCredentialUnlockEnable: Boolean
): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            && deviceCredentialUnlockEnable
}

private fun isDeviceCredentialBiometricOperation(
    deviceCredentialUnlockEnable: Boolean
): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && deviceCredentialUnlockEnable
}

fun isDeviceCredentialBiometricOperation(context: Context?): Boolean {
    if (context == null) {
        return false
    }
    return isDeviceCredentialBiometricOperation(
        isDeviceCredentialUnlockEnable(context)
    )
}

private fun isUserNotAuthenticatedException(
    deviceCredentialUnlockEnable: Boolean,
    exception: Exception
): Boolean {
    return isDeviceCredentialOperation(deviceCredentialUnlockEnable)
            && ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && exception is UserNotAuthenticatedException)
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && exception.cause is UserNotAuthenticatedException))
}
