/*
 * Copyright 2021 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.database.helper

import android.content.Context
import android.content.res.Resources
import com.keepasspn.R
import com.keepasspn.database.element.database.CompressionAlgorithm
import com.keepasspn.database.element.template.TemplateEngine
import com.keepasspn.database.element.template.TemplateField
import com.keepasspn.database.exception.CopyEntryDatabaseException
import com.keepasspn.database.exception.CopyGroupDatabaseException
import com.keepasspn.database.exception.CorruptedDatabaseException
import com.keepasspn.database.exception.DatabaseInputException
import com.keepasspn.database.exception.DatabaseOutputException
import com.keepasspn.database.exception.DuplicateUuidDatabaseException
import com.keepasspn.database.exception.EmptyKeyDatabaseException
import com.keepasspn.database.exception.FileNotFoundDatabaseException
import com.keepasspn.database.exception.HardwareKeyDatabaseException
import com.keepasspn.database.exception.InvalidAlgorithmDatabaseException
import com.keepasspn.database.exception.InvalidCredentialsDatabaseException
import com.keepasspn.database.exception.KDFIterationDatabaseException
import com.keepasspn.database.exception.KDFMemoryDatabaseException
import com.keepasspn.database.exception.KDFParallelismDatabaseException
import com.keepasspn.database.exception.LocalizedException
import com.keepasspn.database.exception.MergeDatabaseKDBException
import com.keepasspn.database.exception.MissingParentDatabaseException
import com.keepasspn.database.exception.MoveEntryDatabaseException
import com.keepasspn.database.exception.MoveGroupDatabaseException
import com.keepasspn.database.exception.NoMemoryDatabaseException
import com.keepasspn.database.exception.RegisterInReadOnlyDatabaseException
import com.keepasspn.database.exception.SignatureDatabaseException
import com.keepasspn.database.exception.UnknownDatabaseLocationException
import com.keepasspn.database.exception.VersionDatabaseException
import com.keepasspn.database.exception.XMLMalformedDatabaseException
import com.keepasspn.model.PasskeyEntryFields.FIELD_CREDENTIAL_ID
import com.keepasspn.model.PasskeyEntryFields.FIELD_FLAG_BE
import com.keepasspn.model.PasskeyEntryFields.FIELD_FLAG_BS
import com.keepasspn.model.PasskeyEntryFields.FIELD_PRF
import com.keepasspn.model.PasskeyEntryFields.FIELD_PRIVATE_KEY
import com.keepasspn.model.PasskeyEntryFields.FIELD_RELYING_PARTY
import com.keepasspn.model.PasskeyEntryFields.FIELD_USERNAME
import com.keepasspn.model.PasskeyEntryFields.FIELD_USER_HANDLE
import com.keepasspn.model.PasskeyEntryFields.PASSKEY_FIELD
import com.keepasspn.otp.OtpEntryFields.OTP_TOKEN_FIELD

fun LocalizedException.getLocalizedMessage(resources: Resources): String? =
    when (this) {
        is FileNotFoundDatabaseException -> resources.getString(R.string.file_not_found_content)
        is CorruptedDatabaseException -> resources.getString(R.string.corrupted_file)
        is InvalidAlgorithmDatabaseException -> resources.getString(R.string.invalid_algorithm)
        is UnknownDatabaseLocationException -> resources.getString(R.string.error_location_unknown)
        is RegisterInReadOnlyDatabaseException -> resources.getString(R.string.error_save_read_only)
        is HardwareKeyDatabaseException -> resources.getString(R.string.error_hardware_key_unsupported)
        is EmptyKeyDatabaseException -> resources.getString(R.string.error_empty_key)
        is SignatureDatabaseException -> resources.getString(R.string.invalid_db_sig)
        is VersionDatabaseException -> resources.getString(R.string.unsupported_db_version)
        is InvalidCredentialsDatabaseException -> resources.getString(R.string.invalid_credentials)
        is KDFIterationDatabaseException -> resources.getString(R.string.error_load_database_KDF_iteration)
        is KDFParallelismDatabaseException -> resources.getString(R.string.error_load_database_KDF_parallelism)
        is KDFMemoryDatabaseException -> resources.getString(R.string.error_load_database_KDF_memory)
        is NoMemoryDatabaseException -> resources.getString(R.string.error_out_of_memory)
        is DuplicateUuidDatabaseException -> resources.getString(R.string.invalid_db_same_uuid, parameters[0], parameters[1])
        is XMLMalformedDatabaseException -> resources.getString(R.string.error_XML_malformed)
        is MergeDatabaseKDBException -> resources.getString(R.string.error_unable_merge_database_kdb)
        is MoveEntryDatabaseException -> resources.getString(R.string.error_move_entry_here)
        is MoveGroupDatabaseException -> resources.getString(R.string.error_move_group_here)
        is CopyEntryDatabaseException -> resources.getString(R.string.error_copy_entry_here)
        is CopyGroupDatabaseException -> resources.getString(R.string.error_copy_group_here)
        is MissingParentDatabaseException -> resources.getString(R.string.error_missing_parent)
        is DatabaseInputException -> resources.getString(R.string.error_load_database)
        is DatabaseOutputException -> resources.getString(R.string.error_save_database)
        else -> localizedMessage
    }

fun CompressionAlgorithm.getLocalizedName(resources: Resources): String {
    return when (this) {
        CompressionAlgorithm.NONE -> resources.getString(R.string.compression_none)
        CompressionAlgorithm.GZIP -> resources.getString(R.string.compression_gzip)
    }
}

fun TemplateField.isStandardPasswordName(context: Context, name: String): Boolean {
    return name.equals(LABEL_PASSWORD, true)
            || name == getLocalizedName(context, LABEL_PASSWORD)
}

fun TemplateField.isOtpLabel(context: Context, name: String): Boolean {
    return name.equals(OTP_TOKEN_FIELD, true)
            || name == getLocalizedName(context, OTP_TOKEN_FIELD)
}

fun TemplateField.isPasskeyLabel(context: Context, name: String): Boolean {
    return name.equals(PASSKEY_FIELD, true)
            || name == getLocalizedName(context, PASSKEY_FIELD)
}

fun TemplateField.getLocalizedName(context: Context?, name: String): String {
    if (context == null
        || TemplateEngine.containsTemplateDecorator(name)
    )
        return name

    return when {
        LABEL_STANDARD.equals(name, true) -> context.getString(R.string.standard)
        LABEL_TEMPLATE.equals(name, true) -> context.getString(R.string.template)
        LABEL_VERSION.equals(name, true) -> context.getString(R.string.version)

        LABEL_TITLE.equals(name, true) -> context.getString(R.string.entry_title)
        LABEL_USERNAME.equals(name, true) -> context.getString(R.string.entry_user_name)
        LABEL_PASSWORD.equals(name, true) -> context.getString(R.string.entry_password)
        LABEL_URL.equals(name, true) -> context.getString(R.string.entry_url)
        LABEL_EXPIRATION.equals(name, true) -> context.getString(R.string.entry_expires)
        LABEL_NOTES.equals(name, true) -> context.getString(R.string.entry_notes)

        LABEL_DEBIT_CREDIT_CARD.equals(name, true) -> context.getString(R.string.debit_credit_card)
        LABEL_HOLDER.equals(name, true) -> context.getString(R.string.holder)
        LABEL_NUMBER.equals(name, true) -> context.getString(R.string.number)
        LABEL_CVV.equals(name, true) -> context.getString(R.string.card_verification_value)
        LABEL_PIN.equals(name, true) -> context.getString(R.string.personal_identification_number)
        LABEL_ID_CARD.equals(name, true) -> context.getString(R.string.id_card)
        LABEL_NAME.equals(name, true) -> context.getString(R.string.name)
        LABEL_PLACE_OF_ISSUE.equals(name, true) -> context.getString(R.string.place_of_issue)
        LABEL_DATE_OF_ISSUE.equals(name, true) -> context.getString(R.string.date_of_issue)
        LABEL_EMAIL.equals(name, true) -> context.getString(R.string.email)
        LABEL_EMAIL_ADDRESS.equals(name, true) -> context.getString(R.string.email_address)
        LABEL_WIRELESS.equals(name, true) -> context.getString(R.string.wireless)
        LABEL_SSID.equals(name, true) -> context.getString(R.string.ssid)
        LABEL_TYPE.equals(name, true) -> context.getString(R.string.type)
        LABEL_CRYPTOCURRENCY.equals(name, true) -> context.getString(R.string.cryptocurrency)
        LABEL_TOKEN.equals(name, false) -> context.getString(R.string.token)
        LABEL_PUBLIC_KEY.equals(name, true) -> context.getString(R.string.public_key)
        LABEL_PRIVATE_KEY.equals(name, true) -> context.getString(R.string.private_key)
        LABEL_SEED.equals(name, true) -> context.getString(R.string.seed)
        LABEL_ACCOUNT.equals(name, true) -> context.getString(R.string.account)
        LABEL_BANK.equals(name, true) -> context.getString(R.string.bank)
        LABEL_BIC.equals(name, true) -> context.getString(R.string.bank_identifier_code)
        LABEL_IBAN.equals(name, true) -> context.getString(R.string.international_bank_account_number)
        LABEL_SECURE_NOTE.equals(name, true) -> context.getString(R.string.secure_note)
        LABEL_MEMBERSHIP.equals(name, true) -> context.getString(R.string.membership)

        PASSKEY_FIELD.equals(name, true) -> context.getString(R.string.passkey)
        FIELD_USERNAME.equals(name, true) -> context.getString(R.string.passkey_username)
        FIELD_PRIVATE_KEY.equals(name, true) -> context.getString(R.string.passkey_private_key)
        FIELD_CREDENTIAL_ID.equals(name, true) -> context.getString(R.string.passkey_credential_id)
        FIELD_USER_HANDLE.equals(name, true) -> context.getString(R.string.passkey_user_handle)
        FIELD_RELYING_PARTY.equals(name, true) -> context.getString(R.string.passkey_relying_party)
        FIELD_FLAG_BE.equals(name, true) -> context.getString(R.string.passkey_backup_eligibility)
        FIELD_FLAG_BS.equals(name, true) -> context.getString(R.string.passkey_backup_state)
        FIELD_PRF.equals(name, true) -> context.getString(R.string.passkey_pseudo_random_function)

        else -> name
    }
}


