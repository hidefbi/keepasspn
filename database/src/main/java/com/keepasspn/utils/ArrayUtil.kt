/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
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

package com.keepasspn.utils

const val charNull = '\u0000'

/**
 * Extension function to clear a CharArray by filling it with null characters.
 */
fun CharArray.clear() {
    this.fill(charNull)
}

/**
 * Extension function to clear a StringBuilder by filling it with null characters.
 */
fun StringBuilder.clear() {
    for (i in indices) {
        setCharAt(i, charNull)
    }
}

/**
 * Extension function to clear a ByteArray by filling it with zeros.
 */
fun ByteArray.clear() {
    this.fill(0)
}

/**
 * Overwrite a secret CharArray with null characters, as soon as it is no longer needed.
 */
fun CharArray.wipe() {
    this.fill(charNull)
}

/**
 * Overwrite a secret ByteArray with zeros, as soon as it is no longer needed.
 */
fun ByteArray.wipe() {
    this.fill(0)
}

/**
 * Run [block] with this CharArray, then wipe it. The array is wiped even if [block] throws.
 */
inline fun <T> CharArray.useAndWipe(block: (CharArray) -> T): T {
    try {
        return block(this)
    } finally {
        wipe()
    }
}

/**
 * Run [block] with this ByteArray, then wipe it. The array is wiped even if [block] throws.
 */
inline fun <T> ByteArray.useAndWipe(block: (ByteArray) -> T): T {
    try {
        return block(this)
    } finally {
        wipe()
    }
}

/**
 * Copy the characters of a text into a new CharArray, without a String.
 */
fun CharSequence?.toCharArrayCopy(): CharArray {
    if (this == null) return CharArray(0)
    val chars = CharArray(length)
    for (index in chars.indices) {
        chars[index] = this[index]
    }
    return chars
}

/**
 * Extension function to check if a CharArray contains a string, ignoring case.
 */
fun CharArray.contains(string: String, ignoreCase: Boolean = false): Boolean {
    if (string.isEmpty()) return true
    if (this.size < string.length) return false
    for (i in 0..this.size - string.length) {
        var match = true
        for (j in string.indices) {
            if (!this[i + j].equals(string[j], ignoreCase = ignoreCase)) {
                match = false
                break
            }
        }
        if (match) return true
    }
    return false
}
