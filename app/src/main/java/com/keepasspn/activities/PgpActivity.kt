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

    private enum class Section { TOOLS, DECRYPT }

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
    private var decryptFile: Source? = null
    private var textFile: File? = null
    private var output: File? = null
    private var outputName = "output.asc"
    private var outputIsText = false

    private var busy = false
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
    private lateinit var decryptText: EditText
    private lateinit var decryptFileName: TextView
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
        inputText = findViewById(R.id.pgp_input)
        toolsFileName = findViewById(R.id.pgp_file_name)
        decryptText = findViewById(R.id.pgp_decrypt_input)
        decryptFileName = findViewById(R.id.pgp_decrypt_file_name)
        outputView = findViewById(R.id.pgp_output)
        outputActions = findViewById(R.id.pgp_output_actions)
        copyOutputButton = findViewById(R.id.pgp_output_copy)
        statusView = findViewById(R.id.pgp_status)
        progressBar = findViewById(R.id.pgp_progress)
        cancelButton = findViewById(R.id.pgp_cancel_button)
        clearButton = findViewById(R.id.pgp_clear_button)
        actionButtons = listOf(
            findViewById<Button>(R.id.pgp_file_button),
            findViewById<Button>(R.id.pgp_encrypt_button),
            findViewById<Button>(R.id.pgp_sign_button),
            findViewById<Button>(R.id.pgp_verify_button),
            findViewById<Button>(R.id.pgp_decrypt_file_button),
            findViewById<Button>(R.id.pgp_decrypt_button)
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
            uri?.let { importFile(it) }
        }

        findViewById<Button>(R.id.pgp_generate_button).setOnClickListener { showGenerateDialog() }
        findViewById<Button>(R.id.pgp_import_button).setOnClickListener { showImportDialog() }
        findViewById<Button>(R.id.pgp_file_button).setOnClickListener {
            pendingSection = Section.TOOLS
            awaitingPicker = true
            mExternalFileHelper.openDocument()
        }
        findViewById<Button>(R.id.pgp_decrypt_file_button).setOnClickListener {
            pendingSection = Section.DECRYPT
            awaitingPicker = true
            mExternalFileHelper.openDocument()
        }
        findViewById<Button>(R.id.pgp_encrypt_button).setOnClickListener { showRecipientsDialog() }
        findViewById<Button>(R.id.pgp_sign_button).setOnClickListener { showSecretKeyDialog(Action.SIGN, Section.TOOLS) }
        findViewById<Button>(R.id.pgp_verify_button).setOnClickListener { showVerifyDialog() }
        findViewById<Button>(R.id.pgp_decrypt_button).setOnClickListener {
            showSecretKeyDialog(Action.DECRYPT, Section.DECRYPT)
        }
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
        decryptFile?.let {
            outState.putString(KEY_DECRYPT_PATH, it.file.path)
            outState.putString(KEY_DECRYPT_NAME, it.name)
        }
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
        state.getString(KEY_DECRYPT_PATH)?.let { path ->
            val file = File(path)
            if (file.exists()) setPicked(Section.DECRYPT, Source(file, false, state.getString(KEY_DECRYPT_NAME) ?: "file"))
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
        if (actionTask == DatabaseTaskNotificationService.ACTION_DATABASE_ADD_PGP_ENTRY_TASK && result.isSuccess) {
            loadKeyRows(database)
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
        if (mDatabase == null) {
            passphrase.fill('\u0000')
            return
        }
        Toast.makeText(this, R.string.pgp_generating, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
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
                Toast.makeText(this@PgpActivity, R.string.pgp_key_created, Toast.LENGTH_SHORT).show()
            } catch (e: PgpCore.PgpError) {
                showPgpError(e)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    private fun showImportDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.pgp_import_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_import_title)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                importPublicKey(editText.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.pgp_import_choose_file) { _, _ ->
                awaitingPicker = true
                mImportFileHelper.openDocument()
            }
            .show()
    }

    private fun importPublicKey(armored: String) {
        if (mDatabase == null) return
        if (armored.length > PgpCore.MAX_KEY_CHARS) {
            Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
            return
        }
        try {
            saveImportedKey(armored, PgpCore.inspect(armored))
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
    private fun importFile(uri: Uri) {
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
                    saveImportedKey(picked.first, picked.second)
                }
            }
        )
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
        val input = currentInput(Section.TOOLS) ?: return
        val checked = BooleanArray(candidates.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_recipients_title)
            .setMultiChoiceItems(candidates.map { it.title }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = candidates.filterIndexed { index, _ -> checked[index] }.map { it.publicArmored }
                if (chosen.isEmpty()) {
                    Toast.makeText(this, R.string.pgp_no_recipient_keys, Toast.LENGTH_SHORT).show()
                } else {
                    encryptInput(input, chosen)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun encryptInput(input: Source, recipients: List<String>) {
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

    private fun showSecretKeyDialog(action: Action, section: Section) {
        if (busy) return
        val candidates = keyRows.filter { it.hasSecret }
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.pgp_no_secret_keys, Toast.LENGTH_SHORT).show()
            return
        }
        val input = currentInput(section) ?: return
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
    private fun useSecretKey(entryId: EntryId, ready: (String, CharArray) -> Unit) {
        val database = mDatabase ?: return
        val run: () -> Unit = {
            lifecycleScope.launch {
                val info: EntryInfo? = database.getEntryInfoById(entryId, raw = true)
                val secret = info?.let { PgpKeyEntries.secretKeyOf(it) }
                val passphrase = info?.let { PgpKeyEntries.passphraseOf(it) }
                when {
                    secret == null -> {
                        passphrase?.fill('\u0000')
                        Toast.makeText(this@PgpActivity, R.string.pgp_error_no_secret, Toast.LENGTH_SHORT).show()
                    }
                    busy -> passphrase?.fill('\u0000')
                    passphrase != null -> ready(secret, passphrase)
                    // Not kept, or the Keystore can't open it here: ask for it
                    else -> askPassphrase { typed -> ready(secret, typed) }
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

    private fun showVerifyDialog() {
        if (busy) return
        val input = currentInput(Section.TOOLS) ?: return
        val signatureEdit = EditText(this).apply {
            hint = getString(R.string.pgp_signature_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pgp_signature_title)
            .setView(signatureEdit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val signature = signatureEdit.text.toString().trim()
                if (signature.length > PgpCore.MAX_SIGNATURE_CHARS) {
                    discardInput(input)
                    Toast.makeText(this, R.string.pgp_key_too_large, Toast.LENGTH_LONG).show()
                } else {
                    verifyInput(input, signature)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun verifyInput(input: Source, signature: String) {
        val publicKeys = keyRows.map { it.publicArmored }
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
                    result.valid -> getString(
                        R.string.pgp_verify_good,
                        keyRows.firstOrNull { it.fingerprint == result.signerFingerprint }?.title
                            ?: result.signerFingerprint.orEmpty()
                    )
                    !result.keyUsable -> getString(R.string.pgp_verify_key_unusable)
                    else -> getString(R.string.pgp_verify_bad)
                }
            }
        )
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
        if (busy) {
            produced?.delete()
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
                produced?.delete()
                if (e.reason == PgpCore.Reason.CANCELLED) {
                    statusView.text = getString(R.string.error_cancel_by_user)
                } else {
                    showPgpError(e)
                }
            } catch (e: IOException) {
                produced?.delete()
                statusView.text = getString(ioError)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(working: Boolean) {
        busy = working
        progressBar.progress = 0
        progressBar.visibility = if (working) View.VISIBLE else View.GONE
        cancelButton.visibility = if (working) View.VISIBLE else View.GONE
        clearButton.isEnabled = !working
        actionButtons.forEach { it.isEnabled = !working }
        if (working) statusView.text = getString(R.string.pgp_working)
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
                toolsFile?.file?.takeIf { it != source.file }?.delete()
                toolsFile = source
                toolsFileName.text = getString(R.string.pgp_file_name, source.name)
                toolsFileName.visibility = View.VISIBLE
            }
            Section.DECRYPT -> {
                decryptFile?.file?.takeIf { it != source.file }?.delete()
                decryptFile = source
                decryptFileName.text = getString(R.string.pgp_file_name, source.name)
                decryptFileName.visibility = View.VISIBLE
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
    private fun currentInput(section: Section): Source? {
        val picked = if (section == Section.TOOLS) toolsFile else decryptFile
        if (picked != null) return picked
        val text = (if (section == Section.TOOLS) inputText else decryptText).text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.pgp_nothing_to_process, Toast.LENGTH_SHORT).show()
            return null
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > PgpCore.MAX_TEXT_BYTES) {
            Toast.makeText(this, R.string.pgp_text_too_large, Toast.LENGTH_LONG).show()
            return null
        }
        textFile?.delete()
        val file = PgpWorkspace.newFile(this, "text")
        file.writeBytes(bytes)
        bytes.fill(0)
        textFile = file
        return Source(file, isText = true, name = "message.txt")
    }

    /** Typed text is used once, the temp file goes right after. Picked files stay until replaced. */
    private fun discardInput(input: Source) {
        if (input.isText) input.file.delete()
    }

    /**
     * Clears what is on this page: text boxes, picked files and the result.
     * Keys stay in the database.
     */
    private fun clearPage() {
        releaseFiles()
        inputText.setText("")
        decryptText.setText("")
        statusView.text = ""
    }

    /**
     * Deletes the picked files, the temp text and the result, and hides them. The text boxes stay.
     */
    private fun releaseFiles() {
        toolsFile?.file?.delete()
        toolsFile = null
        decryptFile?.file?.delete()
        decryptFile = null
        textFile?.delete()
        textFile = null
        output?.delete()
        output = null
        outputIsText = false
        toolsFileName.visibility = View.GONE
        decryptFileName.visibility = View.GONE
        outputView.text = ""
        outputView.visibility = View.GONE
        outputActions.visibility = View.GONE
    }

    private fun setOutput(file: File, name: String) {
        output?.takeIf { it != file }?.delete()
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
        private const val KEY_DECRYPT_PATH = "pgp_decrypt_path"
        private const val KEY_DECRYPT_NAME = "pgp_decrypt_name"
        private const val KEY_OUTPUT_PATH = "pgp_output_path"
        private const val KEY_OUTPUT_NAME = "pgp_output_name"
    }
}
