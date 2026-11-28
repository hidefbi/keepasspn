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
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
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
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector
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
import java.io.IOException
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
    // Encoded S2K count byte, 255 is the largest. It means 65011712 hashed bytes, the most the format stores.
    private const val S2K_COUNT = 0xFF
    // What S2K_COUNT decodes to, older secret keys have less
    private const val S2K_ITERATIONS = 65011712L
    // New keys expire after three years. A renewal flow is still to be done.
    private const val KEY_VALIDITY_SECONDS = 3L * 365 * 24 * 60 * 60
    /** Largest file or decrypted result the screen handles. */
    const val MAX_DATA_BYTES = 50L * 1024 * 1024

    /** Largest text typed into a box. */
    const val MAX_TEXT_BYTES = 1024 * 1024

    /** Armored keys and signatures are checked for length before the parser sees them. */
    const val MAX_KEY_CHARS = 2 * 1024 * 1024
    const val MAX_SIGNATURE_CHARS = 64 * 1024

    private const val MAX_COMPRESSION_LAYERS = 2
    private const val MAX_SUBKEYS = 32
    private const val MAX_USER_IDS = 64

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
        NO_INTEGRITY,
        KEY_UNUSABLE,
        AMBIGUOUS_KEY,
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
        val keyUsable: Boolean,
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

            val signSubpackets = primarySubpackets(KEY_VALIDITY_SECONDS)
            val encSubpackets = PGPSignatureSubpacketGenerator()
            encSubpackets.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
            encSubpackets.setKeyExpirationTime(false, KEY_VALIDITY_SECONDS)

            val generator = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                signPair,
                userId,
                checksum,
                signSubpackets,
                null,
                JcaPGPContentSignerBuilder(signPair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(PROVIDER),
                JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, s2k, S2K_COUNT)
                    .setProvider(PROVIDER)
                    .build(passphrase)
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
     * Moves the expiry of a key to three years from now. The fingerprint stays the same and the
     * secret key is re-protected with the current S2K count. The passphrase is checked first.
     */
    fun renew(secretArmored: String, passphrase: CharArray, now: Date = Date()): GeneratedKey {
        if (passphrase.isEmpty()) throw PgpError(Reason.EMPTY_PASSPHRASE)
        try {
            val ring = readSecretRing(secretArmored)
            val master = ring.secretKey
            val privateKey = extractPrivateKey(master, passphrase)
            val publics = HashMap<Long, PGPPublicKey>()

            var masterPublic = master.publicKey
            // Expiry in the signatures counts from the key creation time
            val expiry = expiryFrom(masterPublic.creationTime, now)
            for (userId in masterPublic.userIDs.asSequence().toList()) {
                val own = masterPublic.getSignaturesForID(userId).asSequence()
                    .filter {
                        it.keyID == masterPublic.keyID && it.signatureType == PGPSignature.POSITIVE_CERTIFICATION
                    }
                    .toList()
                for (sig in own) masterPublic = PGPPublicKey.removeCertification(masterPublic, userId, sig)
                val certifier = PGPSignatureGenerator(
                    JcaPGPContentSignerBuilder(masterPublic.algorithm, HashAlgorithmTags.SHA256).setProvider(PROVIDER)
                )
                certifier.init(PGPSignature.POSITIVE_CERTIFICATION, privateKey)
                certifier.setHashedSubpackets(primarySubpackets(expiry))
                masterPublic = PGPPublicKey.addCertification(
                    masterPublic, userId, certifier.generateCertification(userId, masterPublic)
                )
            }
            publics[masterPublic.keyID] = masterPublic

            for (sub in ring.secretKeys.asSequence()) {
                if (sub.isMasterKey) continue
                var subPublic = sub.publicKey
                val oldBindings = subPublic.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).asSequence().toList()
                val flags = oldBindings.lastOrNull()?.hashedSubPackets?.keyFlags ?: continue
                for (old in oldBindings) subPublic = PGPPublicKey.removeCertification(subPublic, old)
                val binder = PGPSignatureGenerator(
                    JcaPGPContentSignerBuilder(masterPublic.algorithm, HashAlgorithmTags.SHA256).setProvider(PROVIDER)
                )
                binder.init(PGPSignature.SUBKEY_BINDING, privateKey)
                binder.setHashedSubpackets(subkeySubpackets(flags, expiry))
                subPublic = PGPPublicKey.addCertification(
                    subPublic, binder.generateCertification(masterPublic, subPublic)
                )
                publics[subPublic.keyID] = subPublic
            }

            val renewedRing = reprotectRing(ring, publics, passphrase, passphrase)
            return GeneratedKey(
                secretArmored = armorSecret(renewedRing),
                publicArmored = armorPublic(PGPPublicKeyRing(renewedRing.secretKeys.asSequence().map { it.publicKey }.toList())),
                fingerprint = fingerprintOf(masterPublic)
            )
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Re-encrypts an older secret key with the current S2K count. Returns null when every key
     * in the armored secret key already has it.
     */
    fun reprotect(secretArmored: String, passphrase: CharArray): String? {
        if (passphrase.isEmpty()) throw PgpError(Reason.EMPTY_PASSPHRASE)
        try {
            val ring = readSecretRing(secretArmored)
            if (ring.secretKeys.asSequence().none { isOlderS2k(it) }) return null
            extractPrivateKey(ring.secretKey, passphrase)
            return armorSecret(reprotectRing(ring, emptyMap(), passphrase, passphrase))
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    private fun isOlderS2k(key: PGPSecretKey): Boolean {
        val s2k = key.s2K ?: return true
        return s2k.iterationCount < S2K_ITERATIONS
    }

    private fun reprotectRing(
        ring: PGPSecretKeyRing,
        publics: Map<Long, PGPPublicKey>,
        oldPassphrase: CharArray,
        newPassphrase: CharArray
    ): PGPSecretKeyRing {
        val decryptor = JcePBESecretKeyDecryptorBuilder(
            JcaPGPDigestCalculatorProviderBuilder().setProvider(PROVIDER).build()
        ).setProvider(PROVIDER).build(oldPassphrase)
        val encryptor = JcePBESecretKeyEncryptorBuilder(
            SymmetricKeyAlgorithmTags.AES_256, digestCalculator(HashAlgorithmTags.SHA256), S2K_COUNT
        ).setProvider(PROVIDER).build(newPassphrase)
        val keys = ring.secretKeys.asSequence().map { key ->
            val replaced = PGPSecretKey.replacePublicKey(key, publics[key.keyID] ?: key.publicKey)
            PGPSecretKey.copyWithNewPassword(replaced, decryptor, encryptor)
        }.toList()
        return PGPSecretKeyRing(keys)
    }

    private fun expiryFrom(creation: Date, now: Date): Long {
        return (now.time + KEY_VALIDITY_SECONDS * 1000 - creation.time) / 1000
    }

    private fun primarySubpackets(expirySeconds: Long): PGPSignatureSubpacketVector {
        val subpackets = PGPSignatureSubpacketGenerator()
        subpackets.setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
        subpackets.setKeyExpirationTime(false, expirySeconds)
        // Preferences tell other OpenPGP software what to use with this key, the MDC feature
        // asks for the integrity protected message format
        subpackets.setPreferredSymmetricAlgorithms(
            false, intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128)
        )
        subpackets.setPreferredHashAlgorithms(
            false, intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256)
        )
        subpackets.setPreferredCompressionAlgorithms(
            false, intArrayOf(CompressionAlgorithmTags.ZLIB, CompressionAlgorithmTags.UNCOMPRESSED)
        )
        subpackets.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
        return subpackets.generate()
    }

    private fun subkeySubpackets(flags: Int, expirySeconds: Long): PGPSignatureSubpacketVector {
        val subpackets = PGPSignatureSubpacketGenerator()
        subpackets.setKeyFlags(false, flags)
        subpackets.setKeyExpirationTime(false, expirySeconds)
        return subpackets.generate()
    }

    /**
     * Reads the first key of an armored public key and tells if it can encrypt.
     */
    fun inspect(publicArmored: String, now: Date = Date()): KeyInfo {
        try {
            val ring = readPublicRings(publicArmored).firstOrNull() ?: throw PgpError(Reason.INVALID_DATA)
            val userIds = ring.publicKey.userIDs.asSequence().toList()
            val canEncrypt = ring.publicKeys.asSequence().any {
                it.isEncryptionKey && usable(ring.publicKey, it, now) && allowsEncryption(it)
            }
            return KeyInfo(fingerprintOf(ring.publicKey), userIds, canEncrypt)
        } catch (e: PGPException) {
            throw PgpError(Reason.INVALID_DATA, e)
        } catch (e: IOException) {
            throw PgpError(Reason.INVALID_DATA, e)
        }
    }

    /**
     * Reads a public key file, armored or binary, and returns it as armored text.
     * Only the first key is kept. Stops with TOO_LARGE past [MAX_KEY_CHARS] bytes.
     */
    fun publicKeyFromFile(source: InputStream, control: Control = Control()): String {
        val raw = ByteArrayOutputStream()
        copy(source, raw, control, MAX_KEY_CHARS.toLong())
        val bytes = raw.toByteArray()
        // Armored text starts with a dash, a binary OpenPGP packet has the top bit set
        if (bytes.isEmpty() || (bytes[0].toInt() and 0x80) == 0) {
            return String(bytes, Charsets.UTF_8)
        }
        try {
            val collection = PGPPublicKeyRingCollection(
                ByteArrayInputStream(bytes),
                JcaKeyFingerprintCalculator()
            )
            val ring = collection.keyRings.asSequence().firstOrNull() ?: throw PgpError(Reason.INVALID_DATA)
            return armorPublic(ring)
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
        control: Control = Control(),
        now: Date = Date()
    ) {
        if (recipientsArmored.isEmpty()) throw PgpError(Reason.NO_MATCHING_KEY)
        try {
            val recipients = recipientsArmored.map { armored ->
                val ring = readPublicRings(armored).firstOrNull() ?: throw PgpError(Reason.INVALID_DATA)
                val subkeys = ring.publicKeys.asSequence().filter { it.isEncryptionKey }.toList()
                if (subkeys.isEmpty()) throw PgpError(Reason.NO_MATCHING_KEY)
                // The newest encryption key that is still usable, a revoked or expired key is refused
                subkeys.filter { usable(ring.publicKey, it, now) && allowsEncryption(it) }
                    .maxByOrNull { it.creationTime.time }
                    ?: throw PgpError(Reason.KEY_UNUSABLE)
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
     *
     * The plain data is written before the integrity check runs at the end of the stream.
     * When this throws, the sink may already hold unverified data, so the caller must drop it.
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
                // Without an MDC or AEAD tag nothing checks the plain data, so it is not read at all
                if (!pubEncrypted.isIntegrityProtected) throw PgpError(Reason.NO_INTEGRITY)
                val privateKey = extractPrivateKey(secretKey, passphrase)
                val clear = pubEncrypted.getDataStream(
                    JcePublicKeyDataDecryptorFactoryBuilder().setProvider(PROVIDER).build(privateKey)
                )
                writeLiteral(clear, sink, control)
                // The integrity check runs once the whole stream has been read
                if (!pubEncrypted.verify()) {
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
        control: Control = Control(),
        now: Date = Date()
    ): String {
        try {
            val ring = readSecretRing(secretArmored)
            val signingKey = ring.secretKeys.asSequence().firstOrNull { it.isSigningKey }
                ?: throw PgpError(Reason.NO_MATCHING_KEY)
            if (!usable(ring.publicKey, signingKey.publicKey, now)) throw PgpError(Reason.KEY_UNUSABLE)
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
        control: Control = Control(),
        now: Date = Date()
    ): VerifyResult {
        checkLength(signatureArmored, MAX_SIGNATURE_CHARS)
        return verify(publicArmored, source, signatureArmored.toByteArray(Charsets.UTF_8), control, now)
    }

    /**
     * Same check for a signature in bytes, armored or binary. At most [MAX_SIGNATURE_CHARS] bytes.
     */
    fun verify(
        publicArmored: List<String>,
        source: InputStream,
        signatureBytes: ByteArray,
        control: Control = Control(),
        now: Date = Date()
    ): VerifyResult {
        if (signatureBytes.size > MAX_SIGNATURE_CHARS) throw PgpError(Reason.TOO_LARGE)
        try {
            val factory = PGPObjectFactory(
                PGPUtil.getDecoderStream(ByteArrayInputStream(signatureBytes)),
                JcaKeyFingerprintCalculator()
            )
            val sig = when (val found = factory.nextObject()) {
                is PGPSignatureList -> if (found.isEmpty) null else found[0]
                is PGPSignature -> found
                else -> null
            } ?: throw PgpError(Reason.INVALID_DATA)
            // SHA-1 and MD5 are broken for signatures, refuse them instead of showing a valid result
            if (sig.hashAlgorithm == HashAlgorithmTags.SHA1 || sig.hashAlgorithm == HashAlgorithmTags.MD5) {
                throw PgpError(Reason.INVALID_DATA)
            }

            // Every key with this key ID. Two different keys with one ID can't tell who signed.
            val candidates = publicArmored
                .flatMap { readPublicRings(it) }
                .mapNotNull { ring -> ring.getPublicKey(sig.keyID)?.let { ring.publicKey to it } }
            if (candidates.map { fingerprintOf(it.second) }.toSet().size > 1) {
                throw PgpError(Reason.AMBIGUOUS_KEY)
            }
            val (primary, signerKey) = candidates.firstOrNull()
                ?: return VerifyResult(signerFound = false, valid = false, keyUsable = false, signerFingerprint = null)
            val keyUsable = usable(primary, signerKey, now)

            sig.init(JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER), signerKey)
            copy(countingInput(source, control), updater { b, off, len -> sig.update(b, off, len) }, control)
            val intact = sig.verify()
            return VerifyResult(
                signerFound = true,
                valid = intact && keyUsable,
                keyUsable = keyUsable,
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
        try {
            while (true) {
                if (control.isCancelled()) throw PgpError(Reason.CANCELLED)
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw PgpError(Reason.TOO_LARGE)
                sink.write(buffer, 0, read)
            }
        } finally {
            // The buffer holds plain data while it is in use
            buffer.fill(0)
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
        var layers = 0
        while (message is PGPCompressedData) {
            if (++layers > MAX_COMPRESSION_LAYERS) throw PgpError(Reason.INVALID_DATA)
            message = PGPObjectFactory(message.dataStream, JcaKeyFingerprintCalculator()).nextObject()
        }
        val literal = message as? PGPLiteralData ?: throw PgpError(Reason.INVALID_DATA)
        copy(literal.inputStream, sink, control, MAX_DATA_BYTES)
    }

    private fun readPublicRings(armored: String): List<PGPPublicKeyRing> {
        checkLength(armored, MAX_KEY_CHARS)
        val collection = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8))),
            JcaKeyFingerprintCalculator()
        )
        val rings = collection.keyRings.asSequence().toList()
        rings.forEach { ring ->
            val subkeys = ring.publicKeys.asSequence().count()
            val userIds = ring.publicKey.userIDs.asSequence().count()
            if (subkeys > MAX_SUBKEYS || userIds > MAX_USER_IDS) throw PgpError(Reason.INVALID_DATA)
        }
        return rings
    }

    private fun checkLength(text: String, maxChars: Int) {
        if (text.length > maxChars) throw PgpError(Reason.TOO_LARGE)
    }

    private fun readSecretRing(armored: String): PGPSecretKeyRing {
        checkLength(armored, MAX_KEY_CHARS)
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

    /**
     * A key can be used when nothing revokes it, it has not expired and, for a subkey, the
     * binding signature from the primary key checks out. When in doubt it is not usable.
     */
    private fun usable(primary: PGPPublicKey, key: PGPPublicKey, now: Date): Boolean {
        if (hasValidRevocation(primary, null)) return false
        if (expired(primary, now)) return false
        if (key.keyID == primary.keyID) return true
        if (hasValidRevocation(primary, key)) return false
        if (expired(key, now)) return false
        return key.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).asSequence().any { binding ->
            try {
                binding.init(JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER), primary)
                binding.verifyCertification(primary, key)
            } catch (e: PGPException) {
                false
            }
        }
    }

    /**
     * A revocation counts only when its signature checks out against the key it revokes.
     * [key] is null for a revocation of the primary key, otherwise a subkey revocation.
     */
    private fun hasValidRevocation(primary: PGPPublicKey, key: PGPPublicKey?): Boolean {
        val type = if (key == null) PGPSignature.KEY_REVOCATION else PGPSignature.SUBKEY_REVOCATION
        return (key ?: primary).getSignaturesOfType(type).asSequence().any { revocation ->
            try {
                revocation.init(JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER), primary)
                if (key == null) revocation.verifyCertification(primary)
                else revocation.verifyCertification(primary, key)
            } catch (e: PGPException) {
                false
            }
        }
    }

    /**
     * A subkey with a key flags packet must allow encryption. Without the packet the key type decides.
     */
    private fun allowsEncryption(key: PGPPublicKey): Boolean {
        val flags = key.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).asSequence()
            .map { it.hashedSubPackets?.keyFlags ?: 0 }
            .toList()
        if (flags.all { it == 0 }) return true
        return flags.any { it and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0 }
    }

    private fun expired(key: PGPPublicKey, now: Date): Boolean {
        val seconds = key.validSeconds
        return seconds > 0 && key.creationTime.time + seconds * 1000 <= now.time
    }

    private fun fingerprintOf(key: PGPPublicKey): String {
        return key.fingerprint.joinToString("") { "%02X".format(it) }
    }
}
