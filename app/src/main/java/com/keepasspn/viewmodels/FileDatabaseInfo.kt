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
package com.keepasspn.viewmodels

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.documentfile.provider.DocumentFile
import com.keepasspn.utils.UriUtil.getDocumentFile
import com.keepasspn.utils.UriUtil.takeUriPermission
import com.keepasspn.utils.parseUri
import java.io.Serializable
import java.text.DateFormat
import java.util.Date

class FileDatabaseInfo : Serializable {

    private var context: Context
    private var documentFile: DocumentFile? = null
    var fileUri: Uri?
        private set

    constructor(context: Context, fileUri: Uri) {
        this.context = context
        this.fileUri = fileUri
        init()
    }

    constructor(context: Context, filePath: String) {
        this.context = context
        this.fileUri = filePath.parseUri()
        init()
    }

    fun init() {
        // Check permission
        context.contentResolver.takeUriPermission(fileUri)
        documentFile = fileUri?.getDocumentFile(context)
    }

    var exists: Boolean = false
        get() {
            return documentFile?.exists() ?: field
        }
        private set

    fun getLastModification(): Long? {
        return documentFile?.lastModified()
    }

    fun getLastModificationString(): String? {
        return documentFile?.lastModified()?.let {
            if (it != 0L) {
                DateFormat.getDateTimeInstance()
                        .format(Date(it))
            } else {
                null
            }
        }
    }

    fun getSize(): Long? {
        return documentFile?.length()
    }

    fun getSizeString(): String? {
        return documentFile?.let {
            Formatter.formatFileSize(context, it.length())
        }
    }

    fun retrieveDatabaseAlias(alias: String): String? {
        return when {
            alias.isNotEmpty() -> alias
            else -> if (exists) documentFile?.name else fileUri?.path
        }
    }
}
