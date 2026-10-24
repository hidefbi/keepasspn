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
 *
 */
package com.keepasspn.activities

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import com.keepasspn.R
import com.keepasspn.activities.legacy.DatabaseLockActivity
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.file.importer.ImportFileError
import com.keepasspn.database.file.importer.ImportFileException
import com.keepasspn.database.file.importer.ImportIssueReason
import com.keepasspn.database.file.importer.ImportParser
import com.keepasspn.database.file.importer.ImportRowIssue
import com.keepasspn.services.DatabaseTaskNotificationService
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.utils.ImportTempFile
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Imports entries from a CSV or JSON file chosen by the user.
 *
 * The file is copied into a private, non-backed-up location and read there. The user sees a
 * preview (number of entries, skipped rows with their reason) before anything is written.
 * Only the path of the private copy is handed to the background task, which parses it again,
 * writes the entries into a new group and deletes the copy. The original file is never modified.
 */
class ImportEntriesActivity : DatabaseLockActivity() {

    private lateinit var statusView: TextView
    private lateinit var chooseButton: Button
    private lateinit var previewContainer: LinearLayout
    private lateinit var summaryView: TextView
    private lateinit var issuesView: TextView
    private lateinit var groupNameView: EditText
    private lateinit var importButton: Button
    private lateinit var cancelButton: Button

    private val readExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    // Private copy waiting for the user's confirmation, only touched on the UI thread
    private var pendingFile: File? = null
    private var reading = false
    private var skippedCount = 0
    @Volatile private var destroyed = false

    override fun manageDatabaseInfo(): Boolean = true

    override fun viewToInvalidateTimeout(): View? {
        return findViewById(R.id.import_entries_root)
    }

    override fun finishActivityIfReloadRequested(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_entries)

        val toolbar: Toolbar = findViewById(R.id.import_entries_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.import_entries_title_bar)

        statusView = findViewById(R.id.import_entries_status)
        chooseButton = findViewById(R.id.import_entries_choose)
        previewContainer = findViewById(R.id.import_entries_preview)
        summaryView = findViewById(R.id.import_entries_summary)
        issuesView = findViewById(R.id.import_entries_issues)
        groupNameView = findViewById(R.id.import_entries_group_name)
        importButton = findViewById(R.id.import_entries_confirm)
        cancelButton = findViewById(R.id.import_entries_cancel)

        groupNameView.setText(defaultGroupTitle())
        chooseButton.setOnClickListener { pickFile.launch(IMPORT_MIME_TYPES) }
        importButton.setOnClickListener { confirmImport() }
        cancelButton.setOnClickListener { finish() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadFile(uri: Uri) {
        if (reading) return
        reading = true
        discardPending()
        chooseButton.isEnabled = false
        previewContainer.visibility = View.GONE
        statusView.setText(R.string.import_entries_reading)
        readExecutor.execute {
            val outcome = readAndPreview(uri)
            runOnUiThread { onPreviewReady(outcome) }
        }
    }

    /**
     * Runs on the reader thread. Copies the file privately, checks it and counts the entries.
     * Passwords read for the count are wiped straight away.
     */
    private fun readAndPreview(uri: Uri): PreviewOutcome {
        var copy: File? = null
        return try {
            val file = contentResolver.openInputStream(uri)?.use { ImportTempFile.copyFrom(this, it) }
                ?: return PreviewOutcome.Failed(R.string.import_entries_read_failed)
            copy = file
            val result = file.inputStream().use { ImportParser.parse(it) }
            result.entries.forEach { it.wipe() }
            if (result.entries.isEmpty()) {
                ImportTempFile.delete(file)
                PreviewOutcome.Failed(R.string.import_entries_none)
            } else {
                PreviewOutcome.Ready(file, result.entries.size, result.skipped)
            }
        } catch (e: ImportFileException) {
            ImportTempFile.delete(copy)
            PreviewOutcome.Failed(fileErrorMessage(e.error))
        } catch (e: IOException) {
            ImportTempFile.delete(copy)
            PreviewOutcome.Failed(R.string.import_entries_read_failed)
        } catch (e: Exception) {
            ImportTempFile.delete(copy)
            PreviewOutcome.Failed(R.string.import_entries_read_failed)
        }
    }

    private fun onPreviewReady(outcome: PreviewOutcome) {
        reading = false
        if (destroyed) {
            // The screen was closed while reading, nothing must stay on disk
            if (outcome is PreviewOutcome.Ready) ImportTempFile.delete(outcome.file)
            return
        }
        chooseButton.isEnabled = true
        when (outcome) {
            is PreviewOutcome.Failed -> {
                statusView.setText(outcome.messageId)
            }
            is PreviewOutcome.Ready -> {
                pendingFile = outcome.file
                skippedCount = outcome.skipped.size
                statusView.setText(R.string.import_entries_status_ready)
                summaryView.text = getString(
                    R.string.import_entries_summary_count,
                    outcome.count,
                    outcome.skipped.size
                )
                issuesView.text = formatIssues(outcome.skipped)
                previewContainer.visibility = View.VISIBLE
                importButton.isEnabled = true
            }
        }
    }

    private fun confirmImport() {
        val file = pendingFile ?: return
        val groupTitle = groupNameView.text.toString().trim()
        if (groupTitle.isEmpty()
            || groupTitle.length > MAX_GROUP_TITLE_LENGTH
            || groupTitle.any { it.isISOControl() }
        ) {
            showToast(R.string.import_entries_group_invalid)
            return
        }
        // From here the background task owns the copy and deletes it
        pendingFile = null
        importButton.isEnabled = false
        importEntries(file.absolutePath, groupTitle)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        if (actionTask == DatabaseTaskNotificationService.ACTION_DATABASE_IMPORT_ENTRIES_TASK) {
            if (result.isSuccess) {
                Toast.makeText(
                    this,
                    getString(R.string.import_entries_done, skippedCount),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                showToast(R.string.import_entries_failed)
            }
            finish()
        } else {
            super.onDatabaseActionFinished(database, actionTask, result)
        }
    }

    override fun onDestroy() {
        destroyed = true
        readExecutor.shutdownNow()
        discardPending()
        super.onDestroy()
    }

    private fun discardPending() {
        ImportTempFile.delete(pendingFile)
        pendingFile = null
    }

    private fun formatIssues(issues: List<ImportRowIssue>): String {
        if (issues.isEmpty()) return getString(R.string.import_entries_no_issues)
        val shown = issues.take(MAX_LISTED_ISSUES).joinToString("\n") { issue ->
            getString(
                R.string.import_entries_issue_line,
                issue.position,
                getString(reasonMessage(issue.reason))
            )
        }
        val hidden = issues.size - MAX_LISTED_ISSUES
        return if (hidden > 0) {
            shown + "\n" + getString(R.string.import_entries_issues_more, hidden)
        } else {
            shown
        }
    }

    @StringRes
    private fun reasonMessage(reason: ImportIssueReason): Int {
        return when (reason) {
            ImportIssueReason.MISSING_TITLE_AND_URL -> R.string.import_entries_reason_missing_title
            ImportIssueReason.FIELD_TOO_LONG -> R.string.import_entries_reason_too_long
            ImportIssueReason.INVALID_CHARACTER -> R.string.import_entries_reason_invalid_character
            ImportIssueReason.INVALID_URL -> R.string.import_entries_reason_invalid_url
            ImportIssueReason.INVALID_VALUE -> R.string.import_entries_reason_invalid_value
        }
    }

    @StringRes
    private fun fileErrorMessage(error: ImportFileError): Int {
        return when (error) {
            ImportFileError.FILE_TOO_LARGE -> R.string.import_entries_error_too_large
            ImportFileError.INVALID_ENCODING -> R.string.import_entries_error_encoding
            ImportFileError.ENCRYPTED_NOT_SUPPORTED -> R.string.import_entries_error_encrypted
            ImportFileError.MALFORMED_STRUCTURE -> R.string.import_entries_error_malformed
            ImportFileError.TOO_DEEP -> R.string.import_entries_error_too_deep
            ImportFileError.TOO_MANY_ENTRIES -> R.string.import_entries_error_too_many
            ImportFileError.MISSING_REQUIRED_COLUMNS -> R.string.import_entries_error_columns
            ImportFileError.EMPTY_FILE -> R.string.import_entries_none
        }
    }

    private fun defaultGroupTitle(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return getString(R.string.import_entries_group_default, date)
    }

    private fun showToast(@StringRes messageId: Int) {
        Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show()
    }

    private sealed class PreviewOutcome {
        class Ready(
            val file: File,
            val count: Int,
            val skipped: List<ImportRowIssue>
        ) : PreviewOutcome()

        class Failed(@StringRes val messageId: Int) : PreviewOutcome()
    }

    companion object {
        private const val MAX_LISTED_ISSUES = 100
        private const val MAX_GROUP_TITLE_LENGTH = 128
        // Any type is offered: exports often carry a generic or non-standard MIME type, and the
        // content is checked by the parser, so the picker's filter is not a security boundary
        private val IMPORT_MIME_TYPES = arrayOf("*/*")
    }
}
