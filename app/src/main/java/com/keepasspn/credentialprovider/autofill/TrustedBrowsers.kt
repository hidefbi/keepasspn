package com.keepasspn.credentialprovider.autofill

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.keepasspn.encrypt.Signature.getAllFingerprints

/**
 * Browsers allowed to tell autofill which website the user is on.
 *
 * A window may supply a web domain only when its package is trusted AND the installed package
 * is signed with one of the fingerprints expected for it: built-in (release certificates of the
 * privileged browser list) or pinned by the user when the browser was confirmed. Declaring a
 * web link handler is never enough to be trusted.
 *
 * Trust is not granted below Android 9, where signatures cannot be verified.
 */
object TrustedBrowsers {

    private const val PREFS_NAME = "autofill_trusted_browsers"
    private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"

    private val BUILTIN_BROWSERS: Map<String, Set<String>> = mapOf(
        "com.android.chrome" to setOf("F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83"),
        "com.chrome.beta" to setOf("3D:7A:12:23:01:9A:A3:9D:9E:A0:E3:43:6A:B7:C0:89:6B:FB:4F:B6:79:F4:DE:5F:E7:C2:3F:32:6C:8F:99:4A", "DA:63:3D:34:B6:9E:63:AE:21:03:B4:9D:53:CE:05:2F:C5:F7:F3:C5:3A:AB:94:FD:C2:A2:08:BD:FD:14:24:9C"),
        "com.chrome.dev" to setOf("3D:7A:12:23:01:9A:A3:9D:9E:A0:E3:43:6A:B7:C0:89:6B:FB:4F:B6:79:F4:DE:5F:E7:C2:3F:32:6C:8F:99:4A", "90:44:EE:5F:EE:4B:BC:5E:21:DD:44:66:54:31:C4:EB:1F:1F:71:A3:27:16:A0:BC:92:7B:CB:B3:92:33:CA:BF"),
        "com.chrome.canary" to setOf("20:19:DF:A1:FB:23:EF:BF:70:C5:BC:D1:44:3C:5B:EA:B0:4F:3F:2F:F4:36:6E:9A:C1:E3:45:76:39:A2:4C:FC"),
        "org.chromium.chrome" to setOf("C6:AD:B8:B8:3C:6D:4C:17:D2:92:AF:DE:56:FD:48:8A:51:D3:16:FF:8F:2C:11:C5:41:02:23:BF:F8:A7:DB:B3"),
        "org.mozilla.firefox" to setOf("A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04"),
        "org.mozilla.firefox_beta" to setOf("A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04"),
        "org.mozilla.fenix" to setOf("50:04:77:90:88:E7:F9:88:D5:BC:5C:C5:F8:79:8F:EB:F4:F8:CD:08:4A:1B:2A:46:EF:D4:C8:EE:4A:EA:F2:11"),
        "com.microsoft.emmx" to setOf("01:E1:99:97:10:A8:2C:27:49:B4:D5:0C:44:5D:C8:5D:67:0B:61:36:08:9D:0A:76:6A:73:82:7C:82:A1:EA:C9"),
        "com.brave.browser" to setOf("9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"),
        "com.brave.browser_beta" to setOf("9C:2D:B7:05:13:51:5F:DB:FB:BC:58:5B:3E:DF:3D:71:23:D4:DC:67:C9:4F:FD:30:63:61:C1:D7:9B:BF:18:AC"),
        "com.vivaldi.browser" to setOf("E8:A7:85:44:65:5B:A8:C0:98:17:F7:32:76:8F:56:89:B1:66:2E:C4:B2:BC:5A:0B:C0:EC:13:8D:33:CA:3D:1E"),
        "com.sec.android.app.sbrowser" to setOf("34:DF:0E:7A:9F:1C:F1:89:2E:45:C0:56:B4:97:3C:D8:1C:CF:14:8A:40:50:D1:1A:EA:4A:C5:A6:5F:90:0A:42", "C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8"),
        "com.duckduckgo.mobile.android" to setOf("BB:7B:B3:1C:57:3C:46:A1:DA:7F:C5:C5:28:A6:AC:F4:32:10:84:56:FE:EC:50:81:0C:7F:33:69:4E:B3:D2:D4"),
        "com.yandex.browser" to setOf("AC:A4:05:DE:D8:B2:5C:B2:E8:C6:DA:69:42:5D:2B:43:07:D0:87:C1:27:6F:C0:6A:D5:94:27:31:CC:C5:1D:BA"),
    )

    /** Packages of the built-in browsers, excluded from the user list */
    val builtinPackages: Set<String>
        get() = BUILTIN_BROWSERS.keys

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Installed apps that open any https link: the browser candidates for the user list.
     * Apps that only handle specific links (a GitHub client, for example) are not browsers,
     * and Google Play services is not a browser either.
     */
    fun candidatePackages(context: Context): List<String> {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val packageManager = context.packageManager
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return resolveInfos
            .map { it.activityInfo.packageName }
            .distinct()
            .filter {
                it != context.packageName
                    && it != GOOGLE_PLAY_SERVICES_PACKAGE
                    && it !in builtinPackages
            }
    }

    /** Browsers added by the user, with the fingerprints pinned when they were confirmed */
    fun userBrowsers(context: Context): Map<String, Set<String>> {
        return prefs(context).all.mapNotNull { (packageName, value) ->
            (value as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { packageName to it }
        }.toMap()
    }

    /**
     * Pin the current signature of an installed browser as trusted by the user.
     * Returns false when the package or its signature cannot be read.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun addUserBrowser(context: Context, packageName: String): Boolean {
        val fingerprints = installedFingerprints(context, packageName)
        if (fingerprints.isEmpty()) return false
        return prefs(context).edit().putStringSet(packageName, fingerprints).commit()
    }

    fun removeUserBrowser(context: Context, packageName: String) {
        prefs(context).edit().remove(packageName).apply()
    }

    /**
     * Packages allowed to supply web domains right now: installed, and signed with one of the
     * fingerprints expected for that package.
     */
    fun verifiedPackages(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptySet()
        val expected = HashMap<String, Set<String>>()
        BUILTIN_BROWSERS.forEach { (packageName, fingerprints) ->
            expected[packageName] = fingerprints
        }
        userBrowsers(context).forEach { (packageName, fingerprints) ->
            expected[packageName] = (expected[packageName] ?: emptySet()) + fingerprints
        }
        return expected.filter { (packageName, fingerprints) ->
            installedFingerprints(context, packageName).any { it in fingerprints }
        }.keys
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun installedFingerprints(context: Context, packageName: String): Set<String> {
        return try {
            val info = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.getAllFingerprints() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
