/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.password

import java.security.SecureRandom

/**
 * Builds a random username from letters and digits.
 */
object UsernameGenerator {

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 32
    const val DEFAULT_LENGTH = 16

    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"

    /**
     * Generate a random username.
     *
     * When no character set is selected, lower-case letters are used so the
     * result is never empty.
     */
    fun generate(
        length: Int,
        lowercase: Boolean,
        uppercase: Boolean,
        digits: Boolean,
        random: SecureRandom = SecureRandom()
    ): String {
        val pool = buildString {
            if (lowercase) append(LOWERCASE)
            if (uppercase) append(UPPERCASE)
            if (digits) append(DIGITS)
            if (isEmpty()) append(LOWERCASE)
        }
        val size = length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        return buildString(size) {
            repeat(size) {
                append(pool[random.nextInt(pool.length)])
            }
        }
    }
}
