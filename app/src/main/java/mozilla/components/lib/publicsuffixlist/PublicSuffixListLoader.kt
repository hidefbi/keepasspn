/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.publicsuffixlist

import android.content.Context

private const val PUBLIC_SUFFIX_RULES_FILE = "publicsuffix_rules.txt"
private const val PUBLIC_SUFFIX_EXCEPTIONS_FILE = "publicsuffix_exceptions.txt"

internal object PublicSuffixListLoader {
    fun load(context: Context): PublicSuffixListData {
        val publicSuffixBytes = context.assets.open(PUBLIC_SUFFIX_RULES_FILE).use { it.readBytes() }
        val exceptionBytes = context.assets.open(PUBLIC_SUFFIX_EXCEPTIONS_FILE).use { it.readBytes() }

        return PublicSuffixListData(publicSuffixBytes, exceptionBytes)
    }
}
