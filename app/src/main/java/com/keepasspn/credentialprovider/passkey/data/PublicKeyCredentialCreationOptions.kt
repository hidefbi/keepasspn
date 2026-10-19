/*
 * Copyright 2025 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.credentialprovider.passkey.data

import com.keepasspn.encrypt.Base64Helper
import com.keepasspn.credentialprovider.passkey.data.AuthenticationExtensionsClientInputs.Companion.getAuthenticationExtensionsClientInputs
import com.keepasspn.credentialprovider.passkey.data.AuthenticatorSelectionCriteria.Companion.getAuthenticatorSelectionCriteria
import com.keepasspn.credentialprovider.passkey.data.PublicKeyCredentialDescriptor.Companion.getPublicKeyCredentialDescriptorList
import com.keepasspn.credentialprovider.passkey.data.PublicKeyCredentialParameters.Companion.getPublicKeyCredentialParametersList
import com.keepasspn.credentialprovider.passkey.data.PublicKeyCredentialRpEntity.Companion.getPublicKeyCredentialRpEntity
import com.keepasspn.credentialprovider.passkey.data.PublicKeyCredentialUserEntity.Companion.getPublicKeyCredentialUserEntity
import org.json.JSONObject

class PublicKeyCredentialCreationOptions(
    requestJson: String,
    var clientDataHash: ByteArray?
) {
    private val json: JSONObject = JSONObject(requestJson)

    val relyingPartyEntity: PublicKeyCredentialRpEntity =
        json.getPublicKeyCredentialRpEntity("rp")

    val userEntity: PublicKeyCredentialUserEntity =
        json.getPublicKeyCredentialUserEntity("user")

    val challenge: ByteArray =
        Base64Helper.b64Decode(json.getString("challenge"))

    val pubKeyCredParams: List<PublicKeyCredentialParameters> =
        json.getPublicKeyCredentialParametersList("pubKeyCredParams")

    var timeout: Long =
        json.optLong("timeout", 0)

    var excludeCredentials: List<PublicKeyCredentialDescriptor> =
        json.getPublicKeyCredentialDescriptorList("excludeCredentials")

    var authenticatorSelection: AuthenticatorSelectionCriteria =
        json.getAuthenticatorSelectionCriteria("authenticatorSelection")

    var attestation: String =
        json.optString("attestation", "none")

    val extensions: AuthenticationExtensionsClientInputs =
        json.getAuthenticationExtensionsClientInputs("extensions")

    companion object {
        private val TAG = PublicKeyCredentialCreationOptions::class.simpleName
    }
}
