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
 */
package com.keepasspn.database

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.keepasspn.database.element.Database
import com.keepasspn.database.element.Entry
import com.keepasspn.database.element.Group
import com.keepasspn.database.element.node.NodeHandler
import com.keepasspn.model.SearchInfo
import com.keepasspn.utils.NoOpLog
import kotlinx.coroutines.runBlocking
import mozilla.components.lib.publicsuffixlist.PublicSuffixList
import java.security.KeyStore
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Keeps the sites and apps that have at least one saved entry.
 * The database is closed most of the time, and the autofill service needs to know if a site
 * is saved without opening it. Only keyed hashes are stored, the key stays in the Android Keystore.
 * Web sites are compared by registrable domain, as the search does.
 */
object SavedSitesIndex {

    private const val PREFS_NAME = "saved_sites_index"
    private const val PREFS_KEY_TOKENS = "tokens"
    private const val KEY_ALIAS = "keepasspn_saved_sites_index"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val WEB_PREFIX = "w|"
    private const val APP_PREFIX = "a|"

    private val TAG = SavedSitesIndex::class.java.name

    /**
     * Rebuild the index from the entries of the database, out of the recycle bin.
     * Call it from a background thread, it is replaced by the next rebuild.
     */
    fun rebuild(context: Context, database: Database) {
        // The Keystore HMAC key needs API 23
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val key = secretKey()
            val rawKeys = HashSet<String>()
            database.rootGroup?.doForEachChild(
                object : NodeHandler<Entry>() {
                    override fun operate(node: Entry): Boolean {
                        rawKeys.addAll(keysOf(node))
                        return true
                    }
                },
                object : NodeHandler<Group>() {
                    override fun operate(node: Group): Boolean {
                        return !database.groupIsInRecycleBin(node)
                    }
                },
                stopIterationWhenGroupHandlerOperateFalse = false
            )
            val publicSuffixList = PublicSuffixList(context)
            val tokens = HashSet<String>()
            rawKeys.forEach { rawKey ->
                tokens.add(token(key, normalize(publicSuffixList, rawKey)))
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(PREFS_KEY_TOKENS, tokens)
                .apply()
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to rebuild the saved sites index", e)
        }
    }

    /**
     * True if the web domain (or the app id when there is no web domain) has a saved entry.
     * Call it from a background thread.
     */
    fun contains(context: Context, webDomain: String?, applicationId: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        try {
            val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(PREFS_KEY_TOKENS, null)
            if (stored.isNullOrEmpty()) return false
            val host = hostOf(webDomain)
            val rawKey = when {
                host != null -> WEB_PREFIX + host
                !applicationId.isNullOrBlank() -> APP_PREFIX + applicationId.lowercase(Locale.ROOT)
                else -> return false
            }
            val normalized = normalize(PublicSuffixList(context), rawKey)
            return token(secretKey(), normalized) in stored
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to check the saved sites index", e)
            return false
        }
    }

    private fun keysOf(entry: Entry): Set<String> {
        val keys = HashSet<String>()
        hostOf(entry.url)?.let { keys.add(WEB_PREFIX + it) }
        entry.getAppOrigin()?.let { appOrigin ->
            appOrigin.webOrigins.forEach { webOrigin ->
                hostOf(webOrigin.origin)?.let { keys.add(WEB_PREFIX + it) }
            }
            appOrigin.androidOrigins.forEach { androidOrigin ->
                keys.add(APP_PREFIX + androidOrigin.packageName.lowercase(Locale.ROOT))
            }
        }
        return keys
    }

    private fun normalize(publicSuffixList: PublicSuffixList, rawKey: String): String {
        if (!rawKey.startsWith(WEB_PREFIX)) return rawKey
        val host = rawKey.removePrefix(WEB_PREFIX)
        return WEB_PREFIX + registrableOf(publicSuffixList, host)
    }

    private fun registrableOf(publicSuffixList: PublicSuffixList, host: String): String {
        if (Regex(SearchInfo.WEB_IP_REGEX).matches(host)) return host
        return runBlocking {
            publicSuffixList.getPublicSuffixPlusOne(host).await()
        } ?: host
    }

    private fun hostOf(address: String?): String? {
        var rest = address?.trim() ?: return null
        if (rest.contains("://")) rest = rest.substringAfter("://")
        rest = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        rest = rest.substringAfterLast('@').substringBefore(':')
        val host = rest.trim().trimEnd('.').lowercase(Locale.ROOT).removePrefix("www.")
        return host.takeIf { it.isNotEmpty() }
    }

    private fun token(key: SecretKey, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }
}
