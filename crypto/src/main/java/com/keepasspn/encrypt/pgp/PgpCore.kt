/*
 * Copyright 2026 hidefbi.
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
package com.keepasspn.encrypt.pgp

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.PGPDigestCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.Date

/**
 * OpenPGP operations for the PGP screen: keys, encryption, decryption, signing and verification.
 *
 * Defaults are picked for compatibility with older OpenPGP software: v4 keys with Curve25519,
 * AES-256 with an integrity check (SEIPD v1). Output is ASCII armored.
 * Keys and data are kept by the caller, this object only holds the BouncyCastle provider.
 */
object PgpCore {

    private const val PROVIDER = BouncyCastleProvider.PROVIDER_NAME
    private const val RSA_KEY_SIZE_3072 = 3072
    private const val RSA_KEY_SIZE_4096 = 4096
    private const val BUFFER_SIZE = 65536
    /** Largest file or decrypted result the screen handles. */
    const val MAX_DATA_BYTES = 50L * 1024 * 1024

    /** Largest text typed into a box. */
    const val MAX_TEXT_BYTES = 1024 * 1024

    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }

    enum class KeyType { CURVE25519, RSA_3072, RSA_4096 }

    enum class Reason {
        EMPTY_PASSPHRASE,
        WRONG_PASSPHRASE,
        NO_MATCHING_KEY,
        INVALID_DATA,
        TOO_LARGE,
        CANCELLED
    }

    /**
     * Hooks for the streaming calls: [onBytes] gets the bytes read from the input so far,
     * [isCancelled] is checked between chunks.
     */
    class Control(
        val onBytes: (Long) -> Unit = {},
        val isCancelled: () -> Boolean = { false }
    )

    class PgpError(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause)

    class GeneratedKey(
        val secretArmored: String,
        val publicArmored: String,
        val fingerprint: String
    )

    class KeyInfo(
        val fingerprint: String,
        val userIds: List<String>,
        val canEncrypt: Boolean
    )

    class VerifyResult(
        val signerFound: Boolean,
        val valid: Boolean,
        val signerFingerprint: String?
    )

    /**
     * Generates a key pair with a signing key and an encryption subkey, protected by the passphrase.
     */
    fun generate(userId: String, passphrase: CharArray, keyType: KeyType): GeneratedKey {
        if (passphrase.isEmpty()) throw PgpError(Reason.EMPTY_PASSPHRASE)
        try {
            val now = Date()
            val (signPair, encPair) = newKeyPairs(keyType, now)
            // The key checksum needs SHA-1 in the OpenPGP format, the passphrase hash is SHA-256
            val checksum = digestCalculator(HashAlgorithmTags.SHA1)
            val s2k = digestCalculator(HashAlgorithmTags.SHA256)

            val signSubpackets = PGPSignatureSubpacketGenerator()
            signSubpackets.setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            val encSubpackets = PGPSignatureSubpacketGenerator()
            encSubpackets.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)

            val generator = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                signPair,
                userId,
                checksum,
                signSubpackets.generate(),
                null,
                JcaPGPContentSignerBuilder(signPair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(PROVIDER),
                JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, s2k).setProvider(PROVIDER).build(passphrase)
            )
            generator.addSubKey(encPair, encSubpackets.generate(), null)

            val secretRing = generator.generateSecretKeyRing()
            val publicRing = generator.generatePublicKeyRing()
            return GeneratedKey(
                secretArmored = armorSecret(secretRing),
                publicArmored = armorPublic(publicRing),
                fingerprint = fingerprintOf(publicRing.publicKey)
            )
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Reads the first key of an armored public key and tells if it can encrypt.
     */
    fun inspect(publicArmored: String): KeyInfo {
        try {
            val ring = readPublicRings(publicArmored).firstOrNull() ?: throw PgpError(Reason.INVALID_DATA)
            val userIds = ring.publicKey.userIDs.asSequence().toList()
            val canEncrypt = ring.publicKeys.asSequence().any { it.isEncryptionKey }
            return KeyInfo(fingerprintOf(ring.publicKey), userIds, canEncrypt)
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Encrypts to every recipient, each one with its encryption subkey.
     * Reads [source] and writes an armored message to [sink].
     */
    @Suppress("DEPRECATION")
    fun encrypt(
        recipientsArmored: List<String>,
        source: InputStream,
        fileName: String,
        isText: Boolean,
        length: Long,
        sink: OutputStream,
        control: Control = Control()
    ) {
        if (recipientsArmored.isEmpty()) throw PgpError(Reason.NO_MATCHING_KEY)
        try {
            val recipients = recipientsArmored.map { armored ->
                val ring = readPublicRings(armored).firstOrNull() ?: throw PgpError(Reason.INVALID_DATA)
                ring.publicKeys.asSequence().firstOrNull { it.isEncryptionKey }
                    ?: throw PgpError(Reason.NO_MATCHING_KEY)
            }
            val format = if (isText) PGPLiteralData.UTF8 else PGPLiteralData.BINARY
            val arm = ArmoredOutputStream(sink)
            // Drops the library version line, it shows a build placeholder
            arm.clearHeaders()
            val encryptedGenerator = PGPEncryptedDataGenerator(
                JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(SecureRandom())
                    .setProvider(PROVIDER)
            )
            recipients.forEach { key ->
                encryptedGenerator.addMethod(
                    JcePublicKeyKeyEncryptionMethodGenerator(key).setProvider(PROVIDER)
                )
            }
            val encOut = encryptedGenerator.open(arm, ByteArray(BUFFER_SIZE))
            val literalGenerator = PGPLiteralDataGenerator()
            val literalOut = literalGenerator.open(encOut, format, fileName, length, Date())
            copy(countingInput(source, control), literalOut, control)
            literalOut.close()
            literalGenerator.close()
            encOut.close()
            arm.close()
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Decrypts a message with the matching secret key and writes the plain data to [sink].
     * Stops with TOO_LARGE when the data is over [MAX_DATA_BYTES].
     */
    fun decrypt(
        secretArmored: String,
        passphrase: CharArray,
        source: InputStream,
        sink: OutputStream,
        control: Control = Control()
    ) {
        try {
            val factory = PGPObjectFactory(
                PGPUtil.getDecoderStream(countingInput(source, control)),
                JcaKeyFingerprintCalculator()
            )
            val list = factory.nextObject() as? PGPEncryptedDataList
                ?: factory.nextObject() as? PGPEncryptedDataList
                ?: throw PgpError(Reason.INVALID_DATA)
            val secretRing = readSecretRing(secretArmored)
            for (encrypted in list) {
                val pubEncrypted = encrypted as? PGPPublicKeyEncryptedData ?: continue
                val secretKey = secretRing.getSecretKey(pubEncrypted.keyID) ?: continue
                val privateKey = extractPrivateKey(secretKey, passphrase)
                val clear = pubEncrypted.getDataStream(
                    JcePublicKeyDataDecryptorFactoryBuilder().setProvider(PROVIDER).build(privateKey)
                )
                writeLiteral(clear, sink, control)
                // The integrity check runs once the whole stream has been read
                if (pubEncrypted.isIntegrityProtected && !pubEncrypted.verify()) {
                    throw PgpError(Reason.INVALID_DATA)
                }
                return
            }
            throw PgpError(Reason.NO_MATCHING_KEY)
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Makes a detached signature of the data, with the signing key. Returns it armored.
     */
    fun sign(
        secretArmored: String,
        passphrase: CharArray,
        source: InputStream,
        control: Control = Control()
    ): String {
        try {
            val ring = readSecretRing(secretArmored)
            val signingKey = ring.secretKeys.asSequence().firstOrNull { it.isSigningKey }
                ?: throw PgpError(Reason.NO_MATCHING_KEY)
            val privateKey = extractPrivateKey(signingKey, passphrase)
            val generator = PGPSignatureGenerator(
                JcaPGPContentSignerBuilder(signingKey.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(PROVIDER)
            )
            generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
            copy(countingInput(source, control), updater { b, off, len -> generator.update(b, off, len) }, control)
            val signature = generator.generate()
            return armor { arm -> signature.encode(arm) }
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Checks a detached signature against the given public keys.
     */
    fun verify(
        publicArmored: List<String>,
        source: InputStream,
        signatureArmored: String,
        control: Control = Control()
    ): VerifyResult {
        try {
            val factory = PGPObjectFactory(
                PGPUtil.getDecoderStream(ByteArrayInputStream(signatureArmored.toByteArray(Charsets.UTF_8))),
                JcaKeyFingerprintCalculator()
            )
            val signature = when (val found = factory.nextObject()) {
                is PGPSignatureList -> if (found.isEmpty) null else found[0]
                is PGPSignature -> found
                else -> null
            } ?: throw PgpError(Reason.INVALID_DATA)

            val signerKey: PGPPublicKey = publicArmored
                .flatMap { readPublicRings(it) }
                .asSequence()
                .mapNotNull { it.getPublicKey(signature.keyID) }
                .firstOrNull() ?: return VerifyResult(signerFound = false, valid = false, signerFingerprint = null)

            signature.init(JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER), signerKey)
            copy(countingInput(source, control), updater { b, off, len -> signature.update(b, off, len) }, control)
            return VerifyResult(
                signerFound = true,
                valid = signature.verify(),
                signerFingerprint = fingerprintOf(signerKey)
            )
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Copies from source to sink in chunks. Stops with CANCELLED when the control asks,
     * and with TOO_LARGE when more than [limit] bytes pass through.
     */
    fun copy(
        source: InputStream,
        sink: OutputStream,
        control: Control = Control(),
        limit: Long = Long.MAX_VALUE
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (control.isCancelled()) throw PgpError(Reason.CANCELLED)
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw PgpError(Reason.TOO_LARGE)
            sink.write(buffer, 0, read)
        }
    }

    /**
     * Wraps a stream so the control hears about the bytes read, and cancel works while reading.
     */
    fun countingInput(source: InputStream, control: Control): InputStream {
        return object : InputStream() {
            private var count = 0L

            override fun read(): Int {
                if (control.isCancelled()) throw PgpError(Reason.CANCELLED)
                val value = source.read()
                if (value >= 0) {
                    count++
                    control.onBytes(count)
                }
                return value
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (control.isCancelled()) throw PgpError(Reason.CANCELLED)
                val read = source.read(buffer, offset, length)
                if (read > 0) {
                    count += read
                    control.onBytes(count)
                }
                return read
            }
        }
    }

    private fun updater(update: (ByteArray, Int, Int) -> Unit): OutputStream {
        return object : OutputStream() {
            override fun write(b: Int) = update(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = update(b, off, len)
        }
    }

    private fun newKeyPairs(keyType: KeyType, now: Date): Pair<PGPKeyPair, PGPKeyPair> {
        return when (keyType) {
            KeyType.CURVE25519 -> Pair(
                JcaPGPKeyPair(PublicKeyAlgorithmTags.EDDSA_LEGACY, newKeyPair("Ed25519", 0), now),
                JcaPGPKeyPair(PublicKeyAlgorithmTags.ECDH, newKeyPair("X25519", 0), now)
            )
            KeyType.RSA_3072 -> Pair(
                JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, newKeyPair("RSA", RSA_KEY_SIZE_3072), now),
                JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, newKeyPair("RSA", RSA_KEY_SIZE_3072), now)
            )
            KeyType.RSA_4096 -> Pair(
                JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, newKeyPair("RSA", RSA_KEY_SIZE_4096), now),
                JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, newKeyPair("RSA", RSA_KEY_SIZE_4096), now)
            )
        }
    }

    private fun newKeyPair(algorithm: String, rsaBits: Int): KeyPair {
        val generator = KeyPairGenerator.getInstance(algorithm, PROVIDER)
        if (rsaBits > 0) generator.initialize(rsaBits, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun digestCalculator(algorithm: Int): PGPDigestCalculator {
        return JcaPGPDigestCalculatorProviderBuilder().build().get(algorithm)
    }

    private fun extractPrivateKey(secretKey: PGPSecretKey, passphrase: CharArray): PGPPrivateKey {
        try {
            return secretKey.extractPrivateKey(
                JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER).build(passphrase)
            )
        } catch (e: PGPException) {
            // A wrong passphrase fails the checksum of the secret key
            throw PgpError(Reason.WRONG_PASSPHRASE, e)
        }
    }

    private fun writeLiteral(stream: InputStream, sink: OutputStream, control: Control) {
        var message = PGPObjectFactory(stream, JcaKeyFingerprintCalculator()).nextObject()
        while (message is PGPCompressedData) {
            message = PGPObjectFactory(message.dataStream, JcaKeyFingerprintCalculator()).nextObject()
        }
        val literal = message as? PGPLiteralData ?: throw PgpError(Reason.INVALID_DATA)
        copy(literal.inputStream, sink, control, MAX_DATA_BYTES)
    }

    private fun readPublicRings(armored: String): List<PGPPublicKeyRing> {
        val collection = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8))),
            JcaKeyFingerprintCalculator()
        )
        return collection.keyRings.asSequence().toList()
    }

    private fun readSecretRing(armored: String): PGPSecretKeyRing {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8))),
            JcaKeyFingerprintCalculator()
        )
        return collection.keyRings.asSequence().firstOrNull() ?: throw PgpError(Reason.INVALID_DATA)
    }

    private fun armorSecret(ring: PGPSecretKeyRing): String = armor { ring.encode(it) }

    private fun armorPublic(ring: PGPPublicKeyRing): String = armor { ring.encode(it) }

    /**
     * Writes armored text without the library version header
     */
    @Suppress("DEPRECATION")
    private fun armor(write: (ArmoredOutputStream) -> Unit): String {
        val out = ByteArrayOutputStream()
        val arm = ArmoredOutputStream(out)
        // Drops the library version line, it shows a build placeholder
        arm.clearHeaders()
        arm.use { write(it) }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun fingerprintOf(key: PGPPublicKey): String {
        return key.fingerprint.joinToString("") { "%02X".format(it) }
    }
}
