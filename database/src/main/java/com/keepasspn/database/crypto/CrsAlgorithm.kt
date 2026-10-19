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
package com.keepasspn.database.crypto

import com.keepasspn.encrypt.HashManager
import com.keepasspn.encrypt.StreamCipher

enum class CrsAlgorithm(val id: UInt) {

    Null(0u),
    ArcFourVariant(1u),
    Salsa20(2u),
    ChaCha20(3u);

    companion object {

        @Throws(Exception::class)
        fun getCipher(algorithm: CrsAlgorithm?, key: ByteArray): StreamCipher {
            return when (algorithm) {
                Salsa20 -> HashManager.getSalsa20(key)
                ChaCha20 -> HashManager.getChaCha20(key)
                else -> throw Exception("Invalid random cipher")
            }
        }

        fun fromId(num: UInt): CrsAlgorithm? {
            for (e in values()) {
                if (e.id == num) {
                    return e
                }
            }
            return null
        }
    }

}
