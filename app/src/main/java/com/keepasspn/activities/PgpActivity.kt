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
 *
 */
package com.keepasspn.activities

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.keepasspn.R
import com.keepasspn.activities.helpers.ExternalFileHelper
import com.keepasspn.activities.legacy.DatabaseLockActivity
import com.keepasspn.credentialprovider.UserVerificationActionType
import com.keepasspn.credentialprovider.UserVerificationData
import com.keepasspn.credentialprovider.UserVerificationHelper.Companion.checkUserVerification
import com.keepasspn.viewmodels.UserVerificationViewModel
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.EntryId
import com.keepasspn.encrypt.pgp.PgpCore
import com.keepasspn.model.EntryInfo
import com.keepasspn.pgp.PgpKeyEntries
import com.keepasspn.pgp.PgpWorkspace
import com.keepasspn.services.DatabaseTaskNotificationService
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.timeout.timeoutCopyToClipboard
import com.keepasspn.view.showActionErrorIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * PGP tools: keys kept in the database, encrypt, decrypt, sign and verify.
 * Files and text are streamed through the PGP cache folder, nothing is kept in memory
 * beyond the key being used. Secret keys and passphrases are read only after user verification.
 */
class PgpActivity : DatabaseLockActivity() {

    private class KeyRow(
        val entryId: EntryId,
        val title: String,
        val fingerprint: String,
        val publicArmored: String,
        val canEncrypt: Boolean,
        val hasSecret: Boolean
    )

    /** A file on the page, always inside the PGP cache folder. */
    private class Source(
        val file: File,
        val isText: Boolean,
        val name: String
    )

    private enum class Section { TOOLS, SIGNATURE }

    private enum class Tab(val labelRes: Int) {
        ENCRYPT(R.string.pgp_encrypt),
        DECRYPT(R.string.pgp_decrypt),
        SIGN(R.string.pgp_sign),
        VERIFY(R.string.pgp_verify)
    }

    /** A public key added for one check. It stays on this page unless it is saved to the database. */
    private class SessionKey(
        val fingerprint: String,
        val title: String,
        val publicArmored: String
    )

    private enum class Action { DECRYPT, SIGN }

    private val mUserVerificationViewModel: UserVerificationViewModel by viewModels()
    private lateinit var mExternalFileHelper: ExternalFileHelper
    private lateinit var mImportFileHelper: ExternalFileHelper

    private var keyRows: List<KeyRow> = emptyList()
    private var selectedRow: KeyRow? = null
    private var keysExpanded = false
    private var pendingSecretOperation: (() -> Unit)? = null
    private var pendingSection = Section.TOOLS

    private var toolsFile: Source? = null
    // A system file picker is open. It stops this screen, which must not drop its files then.
    private var awaitingPicker = false
    // Fingerprint of the key just added, selected once the list is read again
    private var pendingSelectFingerprint: String? = null
    private lateinit var renewKeyButton: Button
    private var signatureFile: Source? = null
    private val sessionKeys = mutableListOf<SessionKey>()
    private var currentTab = Tab.ENCRYPT
    // The import dialog was opened from the verify tab
    private var pendingImportForCheck = false
    private var textFile: File? = null
    private var output: File? = null
    private var outputName = "output.asc"
    private var outputIsText = false

    private var busy = false
    // A key is being generated or saved to the database
    private var generatingKey = false
    @Volatile private var cancelRequested = false
    private var workTotal = 1L

    private lateinit var keysContainer: LinearLayout
    private lateinit var keysEmpty: TextView
    private lateinit var keysHeader: View
    private lateinit var keysChevron: View
    private lateinit var selectedTitle: TextView
    private lateinit var selectedFingerprint: TextView
    private lateinit var copyKeyButton: Button
    private lateinit var inputText: EditText
    private lateinit var toolsFileName: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var runButton: Button
    private lateinit var signaturePanel: View
    private lateinit var signatureInput: EditText
    private lateinit var signatureFileName: TextView
    private lateinit var sessionKeysView: TextView
    private lateinit var outputView: TextView
    private lateinit var outputActions: View
    private lateinit var copyOutputButton: Button
    private lateinit var statusView: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var cancelButton: Button
    private lateinit var clearButton: Button
    private lateinit var actionButtons: List<Button>

    override fun manageDatabaseInfo(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pgp)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        keysContainer = findViewById(R.id.pgp_keys_container)
        keysEmpty = findViewById(R.id.pgp_keys_empty)
        keysHeader = findViewById(R.id.pgp_keys_header)
        keysChevron = findViewById(R.id.pgp_keys_chevron)
        selectedTitle = findViewById(R.id.pgp_key_selected_title)
        selectedFingerprint = findViewById(R.id.pgp_key_selected_fingerprint)
        copyKeyButton = findViewById(R.id.pgp_key_copy_button)
        keysHeader.setOnClickListener { toggleKeys() }
        copyKeyButton.setOnClickListener { copySelectedKey() }
        renewKeyButton = findViewById(R.id.pgp_key_renew_button)
        renewKeyButton.setOnClickListener { renewSelectedKey() }
        inputText = findViewById(R.id.pgp_input)
        toolsFileName = findViewById(R.id.pgp_file_name)
        signaturePanel = findViewById(R.id.pgp_signature_panel)
        signatureInput = findViewById(R.id.pgp_signature_input)
        signatureFileName = findViewById(R.id.pgp_signature_file_name)
        sessionKeysView = findViewById(R.id.pgp_verify_session_list)
        runButton = findViewById(R.id.pgp_run_button)
        tabLayout = findViewById(R.id.pgp_tabs)
        outputView = findViewById(R.id.pgp_output)
        outputActions = findViewById(R.id.pgp_output_actions)
        copyOutputButton = findViewById(R.id.pgp_output_copy)
        statusView = findViewById(R.id.pgp_status)
        progressBar = findViewById(R.id.pgp_progress)
        cancelButton = findViewById(R.id.pgp_cancel_button)
        clearButton = findViewById(R.id.pgp_clear_button)
        actionButtons = listOf(
            findViewById<Button>(R.id.pgp_file_button),
            runButton,
            findViewById<Button>(R.id.pgp_signature_file_button),
            findViewById<Button>(R.id.pgp_verify_import_button),
            findViewById<Button>(R.id.pgp_generate_button),
            findViewById<Button>(R.id.pgp_import_button)
        )

        mExternalFileHelper = ExternalFileHelper(this)
        mExternalFileHelper.buildOpenDocument { uri ->
            awaitingPicker = false
            uri?.let { pickFile(it, pendingSection) }
        }
        mExternalFileHelper.buildCreateDocument("application/octet-stream") { uri ->
            awaitingPicker = false
            uri?.let { saveOutput(it) }
        }

        mImportFileHelper = ExternalFileHelper(this)
        mImportFileHelper.buildOpenDocument { uri ->
            awaitingPicker = false
            uri?.let { importFile(it, pendingImportForCheck) }
        }

        findViewById<Button>(R.id.pgp_generate_button).setOnClickListener { showGenerateDialog() }
        findViewById<Button>(R.id.pgp_import_button).setOnClickListener { showImportDialog(forCheck = false) }
        findViewById<Button>(R.id.pgp_file_button).setOnClickListener {
            pendingSection = Section.TOOLS
            awaitingPicker = true
            mExternalFileHelper.openDocument()
        }
        findViewById<Button>(R.id.pgp_signature_file_button).setOnClickListener {
            pendingSection = Section.SIGNATURE
            awaitingPicker = true
            mExternalFileHelper.openDocument()
        }
        findViewById<Button>(R.id.pgp_verify_import_button).setOnClickListener { showImportDialog(forCheck = true) }
        runButton.setOnClickListener { runAction() }
        Tab.values().forEach { tabLayout.addTab(tabLayout.newTab().setText(it.labelRes)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.position?.let { showTab(Tab.values()[it]) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        cancelButton.setOnClickListener { cancelRequested = true }
        clearButton.setOnClickListener { if (!busy) clearPage() }
        copyOutputButton.setOnClickListener { copyOutput() }
        findViewById<Button>(R.id.pgp_output_save).setOnClickListener {
            if (output != null) {
                awaitingPicker = true
                mExternalFileHelper.createDocument(outputName)
            }
        }

        if (savedInstanceState == null) {
            // Leftovers from an earlier run that did not finish cleanly
            PgpWorkspace.clearAll(this)
        } else {
            restorePage(savedInstanceState)
        }
        selectTab(Tab.values().getOrElse(savedInstanceState?.getInt(KEY_TAB, 0) ?: 0) { Tab.ENCRYPT })
        renderSessionKeys()

        lifecycleScope.launch {
            mUserVerificationViewModel.onUserVerificationCanceled.collect {
                pendingSecretOperation = null
                Toast.makeText(this@PgpActivity, R.string.error_cancel_by_user, Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            mUserVerificationViewModel.onUserVerificationSucceeded.collect { data ->
                if (data.actionType == UserVerificationActionType.PGP_USE_KEY) {
                    val operation = pendingSecretOperation
                    pendingSecretOperation = null
                    operation?.invoke()
                }
            }
        }
    }

    override fun viewToInvalidateTimeout(): View? {
        return findViewById(R.id.pgp_coordinator)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_pgp, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.pgp_menu_clear_cache -> {
                if (!busy) {
                    clearPage()
                    PgpWorkspace.clearAll(this)
                    Toast.makeText(this, R.string.pgp_cache_cleared, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        toolsFile?.let {
            outState.putString(KEY_TOOLS_PATH, it.file.path)
            outState.putString(KEY_TOOLS_NAME, it.name)
        }
        signatureFile?.let {
            outState.putString(KEY_SIGNATURE_PATH, it.file.path)
            outState.putString(KEY_SIGNATURE_NAME, it.name)
        }
        outState.putInt(KEY_TAB, currentTab.ordinal)
        outState.putStringArrayList(KEY_SESSION_KEYS, ArrayList(sessionKeys.map { it.publicArmored }))
        output?.let {
            outState.putString(KEY_OUTPUT_PATH, it.path)
            outState.putString(KEY_OUTPUT_NAME, outputName)
        }
    }

    private fun restorePage(state: Bundle) {
        state.getString(KEY_TOOLS_PATH)?.let { path ->
            val file = File(path)
            if (file.exists()) setPicked(Section.TOOLS, Source(file, false, state.getString(KEY_TOOLS_NAME) ?: "file"))
        }
        state.getString(KEY_SIGNATURE_PATH)?.let { path ->
            val file = File(path)
            if (file.exists()) setPicked(Section.SIGNATURE, Source(file, false, state.getString(KEY_SIGNATURE_NAME) ?: "file"))
        }
        state.getStringArrayList(KEY_SESSION_KEYS)?.forEach { armored ->
            try {
                val info = PgpCore.inspect(armored)
                sessionKeys.add(SessionKey(info.fingerprint, info.userIds.firstOrNull() ?: info.fingerprint, armored))
            } catch (e: PgpCore.PgpError) {
                // A key that no longer reads is dropped
            }
        }
        state.getString(KEY_OUTPUT_PATH)?.let { path ->
            val file = File(path)
            if (file.exists()) {
                outputName = state.getString(KEY_OUTPUT_NAME) ?: outputName
                output = file
                showOutputPreview()
            }
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        loadKeyRows(database)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)
        // A key was added (generated or imported), the list has to show it
        if (actionTask == DatabaseTaskNotificationService.ACTION_DATABASE_UPDATE_ENTRY_TASK && generatingKey) {
            setGenerating(false)
            if (result.isSuccess) {
                loadKeyRows(database)
                Toast.makeText(this, R.string.pgp_key_renewed, Toast.LENGTH_SHORT).show()
            } else {
                pendingSelectFingerprint = null
                showActionErrorIfNeeded(result)
            }
        }
        if (actionTask == DatabaseTaskNotificationService.ACTION_DATABASE_ADD_PGP_ENTRY_TASK) {
            if (result.isSuccess) loadKeyRows(database)
            if (generatingKey) {
                setGenerating(false)
                if (result.isSuccess) {
                    statusView.text = ""
                    Toast.makeText(this, R.string.pgp_key_created, Toast.LENGTH_SHORT).show()
                } else {
                    pendingSelectFingerprint = null
                    showActionErrorIfNeeded(result)
                }
            }
        }
    }

    private fun loadKeyRows(database: ContextualDatabase) {
        keyRows = database.getEntryInfosWithPgpKey(PgpKeyEntries.FIELD_PUBLIC_KEY).mapNotNull { info ->
            val publicArmored = PgpKeyEntries.publicKeyOf(info) ?: return@mapNotNull null
            val canEncrypt = try {
                PgpCore.inspect(publicArmored).canEncrypt
            } catch (e: PgpCore.PgpError) {
                false
            }
            KeyRow(
                entryId = info.nodeId,
                title = info.title,
                fingerprint = info.username,
                publicArmored = publicArmored,
                canEncrypt = canEncrypt,
                hasSecret = PgpKeyEntries.secretKeyOf(info) != null
            )
        }
        val wanted = pendingSelectFingerprint ?: selectedRow?.fingerprint
        pendingSelectFingerprint = null
        selectedRow = keyRows.firstOrNull { it.fingerprint == wanted } ?: keyRows.firstOrNull()
        renderKeys()
    }

    override fun onResume() {
        super.onResume()
        // The picker result comes back before this, so a picker that never answered is not left open
        awaitingPicker = false
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen (another app, the lock screen) cancels a run and drops the files.
        // Rotation keeps them, and so does a system picker, which stops the screen too.
        if (!isChangingConfigurations && !awaitingPicker) {
            cancelRequested = true
            releaseFiles()
            sessionKeys.clear()
            renderSessionKeys()
            PgpWorkspace.clearAll(this)
        }
    }

    override fun onDestroy() {
        // Exit clears the PGP cache, nothing is saved unless the user saved it
        if (isFinishing) PgpWorkspace.clearAll(this)
        super.onDestroy()
    }

    private fun renderKeys() {
        keysContainer.removeAllViews()
        keysEmpty.visibility = if (keyRows.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        keyRows.forEach { row ->
            val item = inflater.inflate(R.layout.item_pgp_key, keysContainer, false)
            item.findViewById<TextView>(R.id.pgp_key_title).text = row.title
            item.findViewById<TextView>(R.id.pgp_key_fingerprint).text = row.fingerprint
            item.findViewById<View>(R.id.pgp_key_check).visibility =
                if (row.fingerprint == selectedRow?.fingerprint) View.VISIBLE else View.INVISIBLE
            item.setOnClickListener { selectKey(row) }
            keysContainer.addView(item)
        }
        renderSelectedKey()
    }

    private fun renderSelectedKey() {
        val row = selectedRow
        selectedTitle.text = row?.title.orEmpty()
        selectedFingerprint.text = row?.fingerprint.orEmpty()
        copyKeyButton.isEnabled = row != null
        renewKeyButton.visibility = if (row?.hasSecret == true) View.VISIBLE else View.GONE
    }

    private fun selectKey(row: KeyRow) {
        selectedRow = row
        setKeysExpanded(false)
        renderKeys()
    }

    private fun toggleKeys() {
        if (keyRows.isEmpty()) return
        setKeysExpanded(!keysExpanded)
    }

    private fun setKeysExpanded(expanded: Boolean) {
        keysExpanded = expanded
        TransitionManager.beginDelayedTransition(keysContainer.parent as ViewGroup)
        keysContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        keysChevron.rotation = if (expanded) 180f else 0f
    }

    private fun copySelectedKey() {
        val row = selectedRow ?: return
        timeoutCopyToClipboard(getString(R.string.pgp_key_copy_public), row.publicArmored, sensitive = false)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /**
     * Copies the text into a char array. Only clears the field when asked, so a mismatch keeps what was typed.
     */
    private fun charsOf(text: Editable, clear: Boolean): CharArray {
        val chars = CharArray(text.length)
        text.getChars(0, text.length, chars, 0)
        if (clear) text.clear()
        return chars
    }

    private fun showGenerateDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pgp_generate, null)
        val nameEdit: EditText = view.findViewById(R.id.pgp_generate_name)
        val passEdit: EditText = view.findViewById(R.id.pgp_generate_passphrase)
        val repeatEdit: EditText = view.findViewById(R.id.pgp_generate_passphrase_repeat)
        val typeGroup: RadioGroup = view.findViewById(R.id.pgp_generate_type)
        val rememberBox: CheckBox = view.findViewById(R.id.pgp_generate_remember)
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_generate_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val userId = nameEdit.text.toString().trim()
                        val passphrase = charsOf(passEdit.text, clear = false)
                        val repeatPassphrase = charsOf(repeatEdit.text, clear = false)
                        val keyType = when (typeGroup.checkedRadioButtonId) {
                            R.id.pgp_generate_type_rsa3072 -> PgpCore.KeyType.RSA_3072
                            R.id.pgp_generate_type_rsa4096 -> PgpCore.KeyType.RSA_4096
                            else -> PgpCore.KeyType.CURVE25519
                        }
                        val error = when {
                            userId.isEmpty() -> R.string.pgp_error_name_empty
                            passphrase.isEmpty() -> R.string.pgp_error_passphrase_empty
                            !passphrase.contentEquals(repeatPassphrase) -> R.string.pgp_error_passphrase_mismatch
                            else -> null
                        }
                        repeatPassphrase.fill('\u0000')
                        if (error == null) {
                            // generateKey wipes the passphrase when done
                            dialog.dismiss()
                            passEdit.text.clear()
                            repeatEdit.text.clear()
                            generateKey(userId, passphrase, keyType, rememberBox.isChecked)
                        } else {
                            passphrase.fill('\u0000')
                            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun generateKey(userId: String, passphrase: CharArray, keyType: PgpCore.KeyType, remember: Boolean) {
        if (mDatabase == null || busy || generatingKey) {
            passphrase.fill('\u0000')
            return
        }
        setGenerating(true, R.string.pgp_generating)
        lifecycleScope.launch {
            var saving = false
            try {
                val key = withContext(Dispatchers.Default) {
                    PgpCore.generate(userId, passphrase, keyType)
                }
                val entryInfo = PgpKeyEntries.newEntry(
                    userId = userId,
                    fingerprint = key.fingerprint,
                    publicArmored = key.publicArmored,
                    secretArmored = key.secretArmored,
                    passphrase = if (remember) passphrase else null
                )
                pendingSelectFingerprint = key.fingerprint
                addPgpEntry(entryInfo)
                saving = true
                statusView.text = getString(R.string.pgp_working)
                // onDatabaseActionFinished ends the wait and shows the result
            } catch (e: PgpCore.PgpError) {
                showPgpError(e)
            } finally {
                passphrase.fill('\u0000')
                if (!saving) setGenerating(false)
            }
        }
    }

    /**
     * Opens the paste box for a key. [forCheck] is the verify tab: the key is kept for that check only.
     */
    private fun showImportDialog(forCheck: Boolean) {
        val editText = EditText(this).apply {
            hint = getString(R.string.pgp_import_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_import_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                importPublicKey(editText.text.toString().trim(), forCheck)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.pgp_import_choose_file) { _, _ ->
                pendingImportForCheck = forCheck
                awaitingPicker = true
                mImportFileHelper.openDocument()
            }
            .show()
    }

    private fun importPublicKey(armored: String, forCheck: Boolean) {
        if (mDatabase == null) return
        if (armored.length > PgpCore.MAX_KEY_CHARS) {
            Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
            return
        }
        try {
            confirmImportedKey(armored, PgpCore.inspect(armored), forCheck)
        } catch (e: PgpCore.PgpError) {
            if (e.reason == PgpCore.Reason.TOO_LARGE) {
                Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
            } else {
                showPgpError(e)
            }
        }
    }

    /**
     * Reads the picked key file off the main thread. A file over the limit is refused.
     */
    private fun importFile(uri: Uri, forCheck: Boolean) {
        if (mDatabase == null || busy) return
        runWork(
            total = PgpCore.MAX_KEY_CHARS.toLong(),
            produced = null,
            work = { control ->
                val src = contentResolver.openInputStream(uri) ?: throw IOException("no input")
                try {
                    src.use {
                        val armored = PgpCore.publicKeyFromFile(PgpCore.countingInput(it, control), control).trim()
                        armored to PgpCore.inspect(armored)
                    }
                } catch (e: PgpCore.PgpError) {
                    if (e.reason == PgpCore.Reason.TOO_LARGE) null else throw e
                }
            },
            done = { picked ->
                if (picked == null) {
                    Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
                } else {
                    confirmImportedKey(picked.first, picked.second, forCheck)
                }
            }
        )
    }

    /**
     * Shows the fingerprint and asks before the key is used. The verify tab keeps the key for this
     * check, unless the box is ticked and it goes to the database like the keys card import.
     */
    private fun confirmImportedKey(armored: String, info: PgpCore.KeyInfo, forCheck: Boolean) {
        if (mDatabase == null) return
        if (forCheck && armored.length > SESSION_KEY_CHARS) {
            Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
            return
        }
        if (keyRows.any { it.fingerprint == info.fingerprint } || sessionKeys.any { it.fingerprint == info.fingerprint }) {
            Toast.makeText(this, R.string.pgp_key_known, Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pgp_import_confirm, null)
        view.findViewById<TextView>(R.id.pgp_import_confirm_user).text = info.userIds.firstOrNull() ?: info.fingerprint
        view.findViewById<TextView>(R.id.pgp_import_confirm_fingerprint).text =
            info.fingerprint.chunked(4).joinToString(" ")
        val saveBox: CheckBox = view.findViewById(R.id.pgp_import_confirm_save)
        saveBox.visibility = if (forCheck) View.VISIBLE else View.GONE
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_import_confirm_title)
            .setView(view)
            .setPositiveButton(if (forCheck) R.string.pgp_import_confirm_use else R.string.pgp_import_confirm_add) { _, _ ->
                if (forCheck && !saveBox.isChecked) {
                    addSessionKey(armored, info)
                } else {
                    saveImportedKey(armored, info)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSessionKey(armored: String, info: PgpCore.KeyInfo) {
        sessionKeys.add(SessionKey(info.fingerprint, info.userIds.firstOrNull() ?: info.fingerprint, armored))
        renderSessionKeys()
        Toast.makeText(this, R.string.pgp_key_imported, Toast.LENGTH_SHORT).show()
    }

    private fun renderSessionKeys() {
        sessionKeysView.text = if (sessionKeys.isEmpty()) {
            getString(R.string.pgp_verify_session_empty)
        } else {
            sessionKeys.joinToString("\n\n") { "${it.title}\n${it.fingerprint.chunked(4).joinToString(" ")}" }
        }
    }

    private fun saveImportedKey(armored: String, info: PgpCore.KeyInfo) {
        if (mDatabase == null) return
        val userId = info.userIds.firstOrNull() ?: info.fingerprint
        val entryInfo = PgpKeyEntries.newEntry(
            userId = userId,
            fingerprint = info.fingerprint,
            publicArmored = armored,
            secretArmored = null,
            passphrase = null
        )
        pendingSelectFingerprint = info.fingerprint
        addPgpEntry(entryInfo)
        Toast.makeText(this, R.string.pgp_key_imported, Toast.LENGTH_SHORT).show()
    }

    private fun showRecipientsDialog() {
        if (busy) return
        val candidates = keyRows.filter { it.canEncrypt }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.pgp_no_recipient_keys, Toast.LENGTH_SHORT).show()
            return
        }
        val input = currentInput() ?: return
        val checked = BooleanArray(candidates.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_recipients_title)
            .setMultiChoiceItems(candidates.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = candidates.filterIndexed { index, _ -> checked[index] }.map { it.publicArmored }
                // Your own keys are always added, so the message can be read again on this app
                val own = keyRows.filter { it.hasSecret && it.canEncrypt }.map { it.publicArmored }
                val recipients = (chosen + own).distinct()
                if (chosen.isEmpty()) {
                    Toast.makeText(this, R.string.pgp_no_recipient_keys, Toast.LENGTH_SHORT).show()
                } else {
                    encryptInput(input, recipients)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun encryptInput(input: Source, recipients: List<String>) {
        if (busy) {
            // runWork skips the work when busy, so the typed text is dropped here
            discardInput(input)
            return
        }
        val out = PgpWorkspace.newFile(this, "output")
        runWork(
            total = input.file.length(),
            produced = out,
            work = { control ->
                try {
                    input.file.inputStream().use { src ->
                        out.outputStream().use { dst ->
                            PgpCore.encrypt(recipients, src, input.name, input.isText, input.file.length(), dst, control)
                        }
                    }
                } finally {
                    discardInput(input)
                }
            },
            done = { setOutput(out, "message.asc") }
        )
    }

    private fun showSecretKeyDialog(action: Action) {
        if (busy) return
        val candidates = keyRows.filter { it.hasSecret }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.pgp_no_secret_keys, Toast.LENGTH_SHORT).show()
            return
        }
        val input = currentInput() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_key_to_use_title)
            .setItems(candidates.map { it.title }.toTypedArray()) { _, which ->
                useSecretKey(candidates[which].entryId) { secret, passphrase ->
                    when (action) {
                        Action.DECRYPT -> decryptInput(input, secret, passphrase)
                        Action.SIGN -> signInput(input, secret, passphrase)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Reads the secret key of the entry, after user verification when the database asks for it.
     * The callback gets the passphrase and must wipe it when done.
     */
    private fun useSecretKey(entryId: EntryId, upgrade: Boolean = true, ready: (String, CharArray) -> Unit) {
        val database = mDatabase ?: return
        val run: () -> Unit = {
            lifecycleScope.launch {
                val info: EntryInfo? = database.getEntryInfoById(entryId, raw = true)
                val secret = info?.let { PgpKeyEntries.secretKeyOf(it) }
                val passphrase = info?.let { PgpKeyEntries.passphraseOf(it) }
                if (info == null || secret == null) {
                    passphrase?.fill('\u0000')
                    Toast.makeText(this@PgpActivity, R.string.pgp_error_no_secret, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                when {
                    busy -> passphrase?.fill('\u0000')
                    passphrase != null -> useWithPassphrase(info, secret, passphrase, upgrade, ready)
                    // Not kept, or the Keystore can't open it here: ask for it
                    else -> askPassphrase { typed -> useWithPassphrase(info, secret, typed, upgrade, ready) }
                }
            }
        }
        if (mDatabaseAllowUserVerification) {
            pendingSecretOperation = run
            checkUserVerification(
                userVerificationViewModel = mUserVerificationViewModel,
                dataToVerify = UserVerificationData(
                    actionType = UserVerificationActionType.PGP_USE_KEY,
                    database = database,
                    entryId = entryId
                )
            )
        } else {
            run()
        }
    }

    /**
     * The passphrase is in hand, so old protection can be fixed before the action runs. A wrong
     * passphrase fails the fix quietly and the action reports it.
     */
    private fun useWithPassphrase(
        info: EntryInfo,
        secret: String,
        passphrase: CharArray,
        upgrade: Boolean,
        ready: (String, CharArray) -> Unit
    ) {
        if (upgrade && PgpKeyEntries.upgradeProtection(info, passphrase)) updateEntry(info)
        ready(secret, passphrase)
    }

    private fun renewSelectedKey() {
        val row = selectedRow ?: return
        if (!row.hasSecret) return
        useSecretKey(row.entryId, upgrade = false) { secret, passphrase ->
            renewKey(row.entryId, secret, passphrase)
        }
    }

    private fun renewKey(entryId: EntryId, secret: String, passphrase: CharArray) {
        val database = mDatabase
        if (database == null || generatingKey) {
            passphrase.fill('\u0000')
            return
        }
        setGenerating(true, R.string.pgp_working)
        lifecycleScope.launch {
            var saving = false
            try {
                val renewed = withContext(Dispatchers.Default) { PgpCore.renew(secret, passphrase) }
                val info = database.getEntryInfoById(entryId, raw = true)
                if (info != null) {
                    PgpKeyEntries.setSecretKey(info, renewed.secretArmored)
                    PgpKeyEntries.setPublicKey(info, renewed.publicArmored)
                    pendingSelectFingerprint = renewed.fingerprint
                    updateEntry(info)
                    saving = true
                }
            } catch (e: PgpCore.PgpError) {
                showPgpError(e)
            } finally {
                passphrase.fill('\u0000')
                if (!saving) setGenerating(false)
            }
        }
    }

    /**
     * Asks for the passphrase of a key that does not keep one. The callback gets it and must wipe it.
     */
    private fun askPassphrase(ready: (CharArray) -> Unit) {
        val editText = EditText(this).apply {
            hint = getString(R.string.pgp_passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_passphrase_prompt_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = charsOf(editText.text, clear = true)
                if (typed.isEmpty()) {
                    Toast.makeText(this, R.string.pgp_error_passphrase_empty, Toast.LENGTH_SHORT).show()
                } else {
                    ready(typed)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun decryptInput(input: Source, secret: String, passphrase: CharArray) {
        if (busy) {
            passphrase.fill('\u0000')
            discardInput(input)
            return
        }
        // Plain data is written to a staging file first. It only becomes the output after decrypt
        // has checked the integrity of the whole message, so unchecked data is never shown or saved.
        val staging = PgpWorkspace.newFile(this, "staging")
        val out = PgpWorkspace.newFile(this, "output")
        runWork(
            total = input.file.length(),
            produced = staging,
            work = { control ->
                try {
                    input.file.inputStream().use { src ->
                        staging.outputStream().use { dst ->
                            PgpCore.decrypt(secret, passphrase, src, dst, control)
                        }
                    }
                    if (!staging.renameTo(out)) throw IOException("cannot move the output")
                } finally {
                    passphrase.fill('\u0000')
                    discardInput(input)
                }
            },
            done = { setOutput(out, "decrypted.bin") }
        )
    }

    private fun signInput(input: Source, secret: String, passphrase: CharArray) {
        if (busy) {
            passphrase.fill('\u0000')
            discardInput(input)
            return
        }
        val out = PgpWorkspace.newFile(this, "signature")
        runWork(
            total = input.file.length(),
            produced = out,
            work = { control ->
                try {
                    val armored = input.file.inputStream().use { src ->
                        PgpCore.sign(secret, passphrase, src, control)
                    }
                    out.writeText(armored, Charsets.UTF_8)
                } finally {
                    passphrase.fill('\u0000')
                    discardInput(input)
                }
            },
            done = { setOutput(out, "signature.asc") }
        )
    }

    /** The button under the input does what the tab says. */
    private fun runAction() {
        when (currentTab) {
            Tab.ENCRYPT -> showRecipientsDialog()
            Tab.DECRYPT -> showSecretKeyDialog(Action.DECRYPT)
            Tab.SIGN -> showSecretKeyDialog(Action.SIGN)
            Tab.VERIFY -> runVerify()
        }
    }

    private fun selectTab(tab: Tab) {
        tabLayout.getTabAt(tab.ordinal)?.select()
        showTab(tab)
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        signaturePanel.visibility = if (tab == Tab.VERIFY) View.VISIBLE else View.GONE
        runButton.setText(tab.labelRes)
    }

    private fun runVerify() {
        if (busy || generatingKey) return
        val signature = signatureBytes() ?: return
        val input = currentInput() ?: return
        verifyInput(input, signature)
    }

    /** The picked signature file wins, otherwise the pasted text. Nothing past the limit is read. */
    private fun signatureBytes(): ByteArray? {
        val picked = signatureFile
        if (picked != null) {
            if (picked.file.length() > PgpCore.MAX_SIGNATURE_CHARS) {
                Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
                return null
            }
            return picked.file.readBytes()
        }
        val text = signatureInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.pgp_nothing_to_process, Toast.LENGTH_SHORT).show()
            return null
        }
        if (text.length > PgpCore.MAX_SIGNATURE_CHARS) {
            Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
            return null
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun verifyInput(input: Source, signature: ByteArray) {
        if (busy || generatingKey) {
            discardInput(input)
            return
        }
        // Keys from the database and the keys added for this check
        val publicKeys = keyRows.map { it.publicArmored } + sessionKeys.map { it.publicArmored }
        runWork(
            total = input.file.length(),
            produced = null,
            work = { control ->
                try {
                    input.file.inputStream().use { src ->
                        PgpCore.verify(publicKeys, src, signature, control)
                    }
                } finally {
                    discardInput(input)
                }
            },
            done = { result ->
                statusView.text = when {
                    !result.signerFound -> getString(R.string.pgp_verify_unknown)
                    result.valid -> getString(R.string.pgp_verify_good, titleOf(result.signerFingerprint))
                    !result.keyUsable -> getString(R.string.pgp_verify_key_unusable)
                    else -> getString(R.string.pgp_verify_bad)
                }
            }
        )
    }

    private fun titleOf(fingerprint: String?): String {
        return keyRows.firstOrNull { it.fingerprint == fingerprint }?.title
            ?: sessionKeys.firstOrNull { it.fingerprint == fingerprint }?.title
            ?: fingerprint.orEmpty()
    }

    /**
     * Runs [work] off the main thread with the progress bar on. [produced] is removed when the
     * work fails or is cancelled. Only one job runs at a time.
     */
    private fun <T> runWork(
        total: Long,
        produced: File?,
        ioError: Int = R.string.pgp_error_invalid,
        work: (PgpCore.Control) -> T,
        done: (T) -> Unit
    ) {
        if (busy || generatingKey) {
            produced?.let { PgpWorkspace.wipe(it) }
            return
        }
        cancelRequested = false
        workTotal = total.coerceAtLeast(1L)
        val control = PgpCore.Control(
            onBytes = { count -> runOnUiThread { updateProgress(count) } },
            isCancelled = { cancelRequested }
        )
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work(control) }
                done(result)
            } catch (e: PgpCore.PgpError) {
                produced?.let { PgpWorkspace.wipe(it) }
                if (e.reason == PgpCore.Reason.CANCELLED) {
                    statusView.text = getString(R.string.error_cancel_by_user)
                } else {
                    showPgpError(e)
                }
            } catch (e: IOException) {
                produced?.let { PgpWorkspace.wipe(it) }
                statusView.text = getString(ioError)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(working: Boolean) {
        busy = working
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        progressBar.visibility = if (working) View.VISIBLE else View.GONE
        cancelButton.visibility = if (working) View.VISIBLE else View.GONE
        updateControls()
        if (working) statusView.text = getString(R.string.pgp_working)
    }

    private fun setGenerating(working: Boolean, message: Int = R.string.pgp_working) {
        generatingKey = working
        progressBar.isIndeterminate = working
        progressBar.visibility = if (working) View.VISIBLE else View.GONE
        if (working) statusView.text = getString(message)
        updateControls()
    }

    // Buttons stay off while a run or a key job is going
    private fun updateControls() {
        val locked = busy || generatingKey
        clearButton.isEnabled = !locked
        actionButtons.forEach { it.isEnabled = !locked }
    }

    private fun updateProgress(count: Long) {
        if (!busy) return
        progressBar.progress = (count * 100 / workTotal).coerceIn(0L, 100L).toInt()
    }

    private fun pickFile(uri: Uri, section: Section) {
        if (busy) return
        val name = displayName(uri) ?: "file"
        val file = PgpWorkspace.newFile(this, "picked")
        runWork(
            total = sizeOf(uri),
            produced = file,
            ioError = R.string.pgp_error_invalid,
            work = { control ->
                val src = contentResolver.openInputStream(uri) ?: throw IOException("no input")
                src.use { input ->
                    file.outputStream().use { dst ->
                        PgpCore.copy(
                            PgpCore.countingInput(input, control),
                            dst,
                            control,
                            PgpCore.MAX_DATA_BYTES
                        )
                    }
                }
            },
            done = { setPicked(section, Source(file, isText = false, name = name)) }
        )
    }

    private fun setPicked(section: Section, source: Source) {
        when (section) {
            Section.TOOLS -> {
                toolsFile?.file?.takeIf { it != source.file }?.let { PgpWorkspace.wipe(it) }
                toolsFile = source
                toolsFileName.text = getString(R.string.pgp_file_name, source.name)
                toolsFileName.visibility = View.VISIBLE
            }
            Section.SIGNATURE -> {
                signatureFile?.file?.takeIf { it != source.file }?.let { PgpWorkspace.wipe(it) }
                signatureFile = source
                signatureFileName.text = getString(R.string.pgp_file_name, source.name)
                signatureFileName.visibility = View.VISIBLE
            }
        }
    }

    private fun displayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun sizeOf(uri: Uri): Long {
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
        return if (size > 0) size else PgpCore.MAX_DATA_BYTES
    }

    /**
     * The file wins when one is picked. Typed text is written to a temp file, it must fit in 1 MB.
     */
    private fun currentInput(): Source? {
        toolsFile?.let { return it }
        val text = inputText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.pgp_nothing_to_process, Toast.LENGTH_SHORT).show()
            return null
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > PgpCore.MAX_TEXT_BYTES) {
            Toast.makeText(this, R.string.pgp_text_too_large, Toast.LENGTH_LONG).show()
            return null
        }
        textFile?.let { PgpWorkspace.wipe(it) }
        val file = PgpWorkspace.newFile(this, "text")
        file.writeBytes(bytes)
        bytes.fill(0)
        textFile = file
        return Source(file, isText = true, name = "message.txt")
    }

    /** Typed text is used once, the temp file goes right after. Picked files stay until replaced. */
    private fun discardInput(input: Source) {
        if (input.isText) PgpWorkspace.wipe(input.file)
    }

    /**
     * Clears what is on this page: text boxes, picked files and the result.
     * Keys stay in the database.
     */
    private fun clearPage() {
        releaseFiles()
        inputText.setText("")
        signatureInput.setText("")
        sessionKeys.clear()
        renderSessionKeys()
        statusView.text = ""
    }

    /**
     * Deletes the picked files, the temp text and the result, and hides them. The text boxes stay.
     */
    private fun releaseFiles() {
        toolsFile?.file?.let { PgpWorkspace.wipe(it) }
        toolsFile = null
        signatureFile?.file?.let { PgpWorkspace.wipe(it) }
        signatureFile = null
        textFile?.let { PgpWorkspace.wipe(it) }
        textFile = null
        output?.let { PgpWorkspace.wipe(it) }
        output = null
        outputIsText = false
        toolsFileName.visibility = View.GONE
        signatureFileName.visibility = View.GONE
        outputView.text = ""
        outputView.visibility = View.GONE
        outputActions.visibility = View.GONE
    }

    private fun setOutput(file: File, name: String) {
        output?.takeIf { it != file }?.let { PgpWorkspace.wipe(it) }
        output = file
        outputName = name
        showOutputPreview()
    }

    private fun showOutputPreview() {
        val file = output ?: return
        val size = file.length()
        val text = if (size <= PgpCore.MAX_TEXT_BYTES) decodeFile(file) else null
        outputIsText = text != null
        outputView.text = when {
            text != null -> text
            size > PgpCore.MAX_TEXT_BYTES -> getString(R.string.pgp_result_too_large)
            else -> getString(R.string.pgp_binary_result, size)
        }
        outputView.visibility = View.VISIBLE
        outputActions.visibility = View.VISIBLE
        copyOutputButton.isEnabled = outputIsText
        statusView.text = ""
    }

    /** Reads a result file as text. The bytes are cleared once they are decoded. */
    private fun decodeFile(file: File): String? {
        val bytes = file.readBytes()
        return try {
            decodeText(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun decodeText(bytes: ByteArray): String? {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun copyOutput() {
        val file = output ?: return
        if (!outputIsText || file.length() > PgpCore.MAX_TEXT_BYTES) return
        val text = decodeFile(file) ?: return
        timeoutCopyToClipboard(getString(R.string.pgp_title), text, sensitive = true)
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun saveOutput(uri: Uri) {
        val file = output ?: return
        runWork(
            total = file.length(),
            produced = null,
            ioError = R.string.error_save_database,
            work = { control ->
                file.inputStream().use { src ->
                    val dst = contentResolver.openOutputStream(uri) ?: throw IOException("no output")
                    dst.use { PgpCore.copy(PgpCore.countingInput(src, control), it, control) }
                }
            },
            done = { Toast.makeText(this, R.string.pgp_saved, Toast.LENGTH_SHORT).show() }
        )
    }

    private fun showPgpError(e: PgpCore.PgpError) {
        val message = when (e.reason) {
            PgpCore.Reason.EMPTY_PASSPHRASE -> R.string.pgp_error_passphrase_empty
            PgpCore.Reason.WRONG_PASSPHRASE -> R.string.pgp_error_wrong_passphrase
            PgpCore.Reason.NO_MATCHING_KEY -> R.string.pgp_error_no_matching_key
            PgpCore.Reason.INVALID_DATA -> R.string.pgp_error_invalid
            PgpCore.Reason.NO_INTEGRITY -> R.string.pgp_error_no_integrity
            PgpCore.Reason.KEY_UNUSABLE -> R.string.pgp_error_key_unusable
            PgpCore.Reason.AMBIGUOUS_KEY -> R.string.pgp_error_ambiguous_key
            PgpCore.Reason.TOO_LARGE -> R.string.pgp_file_too_large
            PgpCore.Reason.CANCELLED -> R.string.error_cancel_by_user
        }
        statusView.text = getString(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val KEY_TOOLS_PATH = "pgp_tools_path"
        private const val KEY_TOOLS_NAME = "pgp_tools_name"
        private const val KEY_SIGNATURE_PATH = "pgp_signature_path"
        private const val KEY_SIGNATURE_NAME = "pgp_signature_name"
        private const val KEY_TAB = "pgp_tab"
        private const val KEY_SESSION_KEYS = "pgp_session_keys"
        // Keys for one check are saved with the screen state, so they stay small
        private const val SESSION_KEY_CHARS = 64 * 1024
        private const val KEY_OUTPUT_PATH = "pgp_output_path"
        private const val KEY_OUTPUT_NAME = "pgp_output_name"
    }
}
