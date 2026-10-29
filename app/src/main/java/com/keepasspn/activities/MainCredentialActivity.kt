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
package com.keepasspn.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.keepasspn.utils.NoOpLog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.keepasspn.R
import com.keepasspn.activities.helpers.ExternalFileHelper
import com.keepasspn.activities.legacy.DatabaseModeActivity
import com.keepasspn.app.database.FileDatabaseHistoryAction
import com.keepasspn.biometric.DeviceUnlockFragment
import com.keepasspn.biometric.DeviceUnlockManager
import com.keepasspn.biometric.deviceUnlockError
import com.keepasspn.biometric.PinCryptoHelper
import com.keepasspn.biometric.PinPolicyManager
import com.keepasspn.biometric.PinPolicyStore
import com.keepasspn.biometric.PinWipedException
import com.keepasspn.biometric.WrongPinException
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.keepasspn.credentialprovider.EntrySelectionHelper
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.credentialprovider.TypeMode
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.MainCredential
import com.keepasspn.database.element.Database.Companion.DEFAULT_PASSWORD_ENCODING
import com.keepasspn.database.exception.DuplicateUuidDatabaseException
import com.keepasspn.database.exception.FileNotFoundDatabaseException
import com.keepasspn.database.exception.InvalidCredentialsDatabaseException
import com.keepasspn.database.pin.PinPolicy
import com.keepasspn.education.PasswordActivityEducation
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.CipherDecryptDatabase
import com.keepasspn.model.CipherEncryptDatabase
import com.keepasspn.model.CredentialStorage
import com.keepasspn.model.DatabaseFile
import com.keepasspn.model.RegisterInfo
import com.keepasspn.model.SearchInfo
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.CIPHER_DATABASE_KEY
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.DATABASE_URI_KEY
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.MAIN_CREDENTIAL_KEY
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.READ_ONLY_KEY
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.USER_VERIFICATION_KEY
import com.keepasspn.settings.AppearanceSettingsActivity
import com.keepasspn.settings.DeviceUnlockSettingsActivity
import com.keepasspn.settings.PreferencesUtil
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.utils.BACK_PREVIOUS_KEYBOARD_ACTION
import com.keepasspn.utils.LocalDatabaseBackupManager
import com.keepasspn.utils.MenuUtil
import com.keepasspn.utils.UriUtil.getUri
import com.keepasspn.utils.getParcelableCompat
import com.keepasspn.utils.getParcelableExtraCompat
import com.keepasspn.view.MainCredentialView
import com.keepasspn.view.asError
import com.keepasspn.view.showActionErrorIfNeeded
import com.keepasspn.viewmodels.DeviceUnlockViewModel
import com.keepasspn.viewmodels.MainCredentialViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.CharBuffer


class MainCredentialActivity : DatabaseModeActivity() {

    // Views
    private var toolbar: Toolbar? = null
    private var filenameView: TextView? = null
    private var logotypeButton: View? = null
    private var deviceUnlockButton: View? = null
    private var pinUnlockButton: View? = null
    // Prevent repeated automatic PIN prompts during a single Activity instance.
    // A new MainCredentialActivity is created after the database is locked/closed,
    // so the PIN prompt will be shown again on the next unlock cycle.
    private var pinAutoUnlockPrompted = false

    // True when this unlock screen was opened by the autofill service
    private val mAutofillPinUnlock: Boolean by lazy {
        intent.getBooleanExtra(AUTOFILL_PIN_UNLOCK_KEY, false)
    }
    // Applies the PIN validity period before any PIN prompt is shown
    private var appOpenPolicyJob: Job? = null
    private var databaseFileLoadRequested = false
    // Password bytes from the attempt currently being verified, kept only long
    // enough to offer "enable PIN unlock" after a *successful* master-password
    // open. Cleared immediately after use or on destroy - never written to disk
    // except via PinCryptoHelper's own encrypted storage if the user opts in.
    private var lastPasswordAttemptBytes: ByteArray? = null
    private lateinit var mainCredentialView: MainCredentialView
    private var confirmButtonView: Button? = null
    private var infoContainerView: ViewGroup? = null
    private lateinit var coordinatorLayout: CoordinatorLayout
    private var deviceUnlockFragment: DeviceUnlockFragment? = null

    private val mMainCredentialViewModel: MainCredentialViewModel by viewModels()
    private val mDeviceUnlockViewModel: DeviceUnlockViewModel? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ViewModelProvider(this)[DeviceUnlockViewModel::class.java]
        } else null
    }

    private val mPasswordActivityEducation = PasswordActivityEducation(this)
    private var mExternalFileHelper: ExternalFileHelper? = null

    override fun manageDatabaseInfo(): Boolean  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main_credential)

        toolbar = findViewById(R.id.toolbar)
        toolbar?.title = getString(R.string.app_name)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        filenameView = findViewById(R.id.filename)
        logotypeButton = findViewById(R.id.activity_password_logotype)
        deviceUnlockButton = findViewById(R.id.fragment_device_unlock_container_view)
        pinUnlockButton = findViewById(R.id.activity_password_pin_unlock_button)
        mainCredentialView = findViewById(R.id.activity_password_credentials)
        confirmButtonView = findViewById(R.id.activity_password_open_button)
        infoContainerView = findViewById(R.id.activity_password_info_container)
        coordinatorLayout = findViewById(R.id.activity_password_coordinator_layout)

        // Build elements to manage keyfile selection
        mExternalFileHelper = ExternalFileHelper(this)
        mExternalFileHelper?.buildOpenDocument { uri ->
            if (uri != null) {
                mainCredentialView.populateKeyFileView(uri)
            }
        }
        mainCredentialView.setOpenKeyfileClickListener(mExternalFileHelper)

        // If is a view intent
        getUriFromIntent(intent)

        // PIN quick-unlock (independent from biometric/device-credential "Advanced Unlock")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pinUnlockButton?.setOnClickListener {
                showPinUnlockDialog()
            }
            pinUnlockButton?.setOnLongClickListener {
                showRemovePinConfirmDialog()
                true
            }
            appOpenPolicyJob = lifecycleScope.launch {
                try {
                    PinPolicyManager.onAppOpened(applicationContext)
                } catch (e: Exception) {
                    NoOpLog.e(TAG, "Unable to apply PIN validity policy", e)
                }
                refreshPinUnlockButtonVisibility()
            }
        }

        // Show appearance
        logotypeButton?.setOnClickListener {
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }

        // Listen password checkbox to init advanced unlock and confirmation button
        mainCredentialView.onConditionToStoreCredentialChanged = { _, verified ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mDeviceUnlockViewModel?.checkConditionToStoreCredential(condition = verified)
            }
            mMainCredentialViewModel.onConditionChanged(mainCredentialView.isFill())
        }

        // Encapsulate function to load database
        fun loadDatabase() {
            // Remember the password bytes of *this* attempt so that, if it succeeds,
            // we can offer to cache it behind a PIN. Only meaningful for manual
            // password entry - PIN/biometric-driven unlocks overwrite this via
            // onCredentialDecrypted() with a null capture (see below), so we never
            // re-prompt "enable PIN" for an unlock that already came from a PIN.
            lastPasswordAttemptBytes = mainCredentialView.getMainCredential().password
                ?.let { credentialStorageListener.passwordToStore(it) }
            mMainCredentialViewModel.loadDatabase(
                mainCredential = mainCredentialView.getMainCredential(),
                specialMode = mSpecialMode
            )
        }

        // The activity is exported (VIEW intents for database files). It never accepts a
        // password or an auto-unlock request from the caller: the user always types the
        // master password or confirms the PIN / biometric prompt themselves.
        // Init Biometric elements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mDeviceUnlockViewModel?.connect(mMainCredentialViewModel.databaseFileUri)
        }
        // To validate with the keyboard
        mainCredentialView.onValidateListener = {
            loadDatabase()
        }
        // Define listener for validate button
        confirmButtonView?.setOnClickListener {
            loadDatabase()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mMainCredentialViewModel.databaseFileUIState.collect { state ->
                        infoContainerView?.isVisible = state.showFileRevokedErrorMessage
                        filenameView?.text = state.fileName
                        state.keyFileUri?.let {
                            mainCredentialView.populateKeyFileView(it)
                        }
                        state.hardwareKey?.let {
                            mainCredentialView.populateHardwareKeyView(it)
                        }
                        // Enable or not the open button if setting is checked
                        confirmButtonView?.isEnabled = state.confirmButtonEnabled
                        invalidateOptionsMenu()

                        // Only act after the asynchronous database metadata
                        // lookup has completed. This is important because a
                        // remembered key file / hardware key must be restored
                        // before PIN unlock starts.
                        if (databaseFileLoadRequested && !state.loading) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (!maybeAutoUnlockWithPin()) {
                                    mainCredentialView.focusPasswordFieldAndOpenKeyboard()
                                }
                            } else {
                                mainCredentialView.focusPasswordFieldAndOpenKeyboard()
                            }
                        }
                    }
                }
                launch {
                    mMainCredentialViewModel.databaseFileEvent.collect { event ->
                        when (event) {
                            is MainCredentialViewModel.DatabaseFileEvent.ClearCredentialsView -> {
                                clearCredentialsViews(
                                    clearKeyFile = event.clearKeyFile,
                                    clearHardwareKey = event.clearHardwareKey
                                )
                            }
                            is MainCredentialViewModel.DatabaseFileEvent.LoadDatabase -> {
                                mDatabaseViewModel.loadDatabase(
                                    databaseUri = event.databaseUri,
                                    logicalDatabaseUri = event.logicalDatabaseUri,
                                    mainCredential = event.mainCredential,
                                    readOnly = event.readOnly,
                                    allowUserVerification = event.allowUserVerification,
                                    cipherEncryptDatabase = event.cipherEncryptDatabase
                                )
                            }
                            is MainCredentialViewModel.DatabaseFileEvent.OpenGroup -> {
                                clearCredentialsViews(
                                    clearKeyFile = true,
                                    clearHardwareKey = true
                                )
                                GroupActivity.launch(
                                    this@MainCredentialActivity,
                                    event.database,
                                    onValidateSpecialMode = { onValidateSpecialMode() },
                                    onCancelSpecialMode = { onCancelSpecialMode() },
                                    onLaunchActivitySpecialMode = { onLaunchActivitySpecialMode() },
                                    mCredentialActivityResultLauncher
                                )
                            }
                            is MainCredentialViewModel.DatabaseFileEvent.ShowErrorSaveReadOnlyDatabase -> {
                                Snackbar.make(coordinatorLayout,
                                    R.string.error_save_read_only,
                                    Snackbar.LENGTH_LONG).asError().show()
                            }
                            is MainCredentialViewModel.DatabaseFileEvent.ShowLoadDatabaseDuplicateUuidMessage -> {
                                AlertDialog.Builder(this@MainCredentialActivity).apply {
                                    val message = getString(R.string.contains_duplicate_uuid) +
                                            "\n\n" + getString(R.string.contains_duplicate_uuid_procedure)
                                    setMessage(message)
                                    setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                                        event.databaseUri?.let { databaseFileUri ->
                                            mDatabaseViewModel.loadDatabase(
                                                databaseUri = databaseFileUri,
                                                mainCredential = event.mainCredential,
                                                readOnly = event.readOnly,
                                                allowUserVerification = event.allowUserVerification,
                                                cipherEncryptDatabase = event.cipherEncryptDatabase,
                                                fixDuplicateUuid = true,
                                            )
                                        }
                                    }
                                    setNegativeButton(getString(android.R.string.cancel)) { _, _ -> }
                                    create().show()
                                }
                            }
                            is MainCredentialViewModel.DatabaseFileEvent.ShowWarningDatabaseAlreadyOpened -> {
                                Toast.makeText(this@MainCredentialActivity,
                                    R.string.warning_database_already_opened,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                launch {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        mDeviceUnlockViewModel?.let { deviceUnlockViewModel ->
                            deviceUnlockViewModel.uiState.collect { uiState ->
                                // New value received
                                uiState.credentialRequiredCipher?.let { cipher ->
                                    deviceUnlockViewModel.encryptCredential(
                                        credential = getCredentialForEncryption(),
                                        cipher = cipher
                                    )
                                }
                                uiState.cipherEncryptDatabase?.let { cipherEncryptDatabase ->
                                    onCredentialEncrypted(cipherEncryptDatabase)
                                    deviceUnlockViewModel.consumeCredentialEncrypted()
                                }
                                uiState.cipherDecryptDatabase?.let { cipherDecryptDatabase ->
                                    onCredentialDecrypted(cipherDecryptDatabase)
                                    deviceUnlockViewModel.consumeCredentialDecrypted()
                                }
                                uiState.exception?.let { error ->
                                    Snackbar.make(
                                        coordinatorLayout,
                                        deviceUnlockError(error, this@MainCredentialActivity),
                                        Snackbar.LENGTH_LONG
                                    ).asError().show()
                                    deviceUnlockViewModel.exceptionShown()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Init Biometric elements only if allowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && PreferencesUtil.isDeviceUnlockEnable(this)) {
            deviceUnlockFragment = supportFragmentManager
                .findFragmentByTag(UNLOCK_FRAGMENT_TAG) as? DeviceUnlockFragment?
            if (deviceUnlockFragment == null) {
                deviceUnlockFragment = DeviceUnlockFragment().also {
                    supportFragmentManager.commit {
                        replace(
                            R.id.fragment_device_unlock_container_view,
                            it,
                            UNLOCK_FRAGMENT_TAG
                        )
                    }
                }
            }
        }

        mMainCredentialViewModel.refreshPreferences()
        mMainCredentialViewModel.onConditionChanged(mainCredentialView.isFill())

        // Back to previous keyboard is setting activated
        if (PreferencesUtil.isKeyboardPreviousDatabaseCredentialsEnable(this@MainCredentialActivity)) {
            sendBroadcast(Intent(BACK_PREVIOUS_KEYBOARD_ACTION))
        }

        databaseFileLoadRequested = true
        mMainCredentialViewModel.loadDatabaseFile(
            mainCredential = mainCredentialView.getMainCredential(),
            specialMode = mSpecialMode,
            typeMode = mTypeMode
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            refreshPinUnlockButtonVisibility()
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        // Trying to load another database
        mMainCredentialViewModel.onDatabaseRetrieved(database)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)
        when (actionTask) {
            ACTION_DATABASE_LOAD_TASK -> {
                if (result.isSuccess) {
                    // A successful master-password unlock ends any PIN lockout
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        mMainCredentialViewModel.databaseFileUri?.let { logicalUri ->
                            lifecycleScope.launch {
                                PinPolicyManager.onMasterPasswordSuccess(applicationContext, logicalUri)
                            }
                        }
                    }
                    // Persist a private KDBX copy after the first successful
                    // open. This is the source used if the original document
                    // URI is later deleted or becomes unavailable.
                    mMainCredentialViewModel.databaseFileUri?.let { logicalUri ->
                        LocalDatabaseBackupManager.syncFromUri(
                            applicationContext,
                            logicalUri,
                            database.fileUri
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && lastPasswordAttemptBytes != null) {
                        // Delay entering the group/vault screen until the
                        // "enable PIN?" flow (if any) is fully resolved -
                        // otherwise this activity finishes/navigates away
                        // before the async dialog has a chance to show.
                        maybeOfferPinSetup(database)
                    } else {
                        mMainCredentialViewModel.onDatabaseRetrieved(database)
                    }
                } else {
                    // Wrong master password while the PIN was deleted: count towards removal
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && result.exception is InvalidCredentialsDatabaseException) {
                        mMainCredentialViewModel.databaseFileUri?.let { logicalUri ->
                            lifecycleScope.launch {
                                handleMasterFailureDuringPinLockout(logicalUri)
                            }
                        }
                    }
                    // Wrong credentials: don't hold onto the attempted password bytes.
                    lastPasswordAttemptBytes?.fill(0)
                    lastPasswordAttemptBytes = null
                    mainCredentialView.requestPasswordFocus()
                    // Manage special exceptions
                    when (result.exception) {
                        is DuplicateUuidDatabaseException -> {
                            // Relaunch loading if we need to fix UUID
                            result.data?.let { resultData ->
                                mMainCredentialViewModel.showLoadDatabaseDuplicateUuidMessage(
                                    databaseUri = resultData.getParcelableCompat(DATABASE_URI_KEY),
                                    mainCredential = resultData.getParcelableCompat(MAIN_CREDENTIAL_KEY) ?: MainCredential(),
                                    readOnly = resultData.getBoolean(READ_ONLY_KEY),
                                    allowUserVerification = resultData.getBoolean(USER_VERIFICATION_KEY),
                                    cipherEncryptDatabase = resultData.getParcelableCompat(CIPHER_DATABASE_KEY)
                                )
                            }
                        }
                        is FileNotFoundDatabaseException -> {
                            // Remove this default database inaccessible
                            mMainCredentialViewModel.removeDefaultDatabase()
                        }
                    }
                }
            }
        }
        coordinatorLayout.showActionErrorIfNeeded(result)
        result.clear()
    }

    private fun getUriFromIntent(intent: Intent?) {
        // If is a view intent
        val action = intent?.action
        if (action == VIEW_INTENT) {
            fillCredentials(
                intent.data,
                intent.getUri(KEY_KEYFILE),
                HardwareKey.getHardwareKeyFromString(intent.getStringExtra(KEY_HARDWARE_KEY))
            )
        } else {
            fillCredentials(
                intent?.getParcelableExtraCompat(KEY_FILENAME),
                intent?.getParcelableExtraCompat(KEY_KEYFILE),
                HardwareKey.getHardwareKeyFromString(intent?.getStringExtra(KEY_HARDWARE_KEY))
            )
        }
        try {
            intent?.removeExtra(KEY_KEYFILE)
            intent?.removeExtra(KEY_HARDWARE_KEY)
        } catch (_: Exception) {}
    }

    private fun fillCredentials(
        databaseUri: Uri?,
        keyFileUri: Uri?,
        hardwareKey: HardwareKey?
    ) {
        if (mMainCredentialViewModel.databaseFileUri != databaseUri) {
            pinAutoUnlockPrompted = false
            databaseFileLoadRequested = false
        }
        mMainCredentialViewModel.assignDatabaseUri(databaseUri)
        mainCredentialView.populateKeyFileView(keyFileUri)
        mainCredentialView.populateHardwareKeyView(hardwareKey)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        getUriFromIntent(intent)
    }

    private val credentialStorageListener = object: MainCredentialView.CredentialStorageListener {
        override fun passwordToStore(password: CharArray?): ByteArray? {
            if (password == null) return null
            val byteBuffer = DEFAULT_PASSWORD_ENCODING.encode(
                CharBuffer.wrap(password)
            )
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            return bytes
        }

        override fun keyfileToStore(keyfile: Uri?): ByteArray? {
            // TODO create byte array to store keyfile
            return null
        }

        override fun hardwareKeyToStore(): ByteArray? {
            // TODO create byte array to store hardware key
            return null
        }
    }

    private fun getCredentialForEncryption(): ByteArray {
        return mainCredentialView.retrieveCredentialForStorage(credentialStorageListener)
            ?: byteArrayOf()
    }

    private fun onCredentialEncrypted(cipherEncryptDatabase: CipherEncryptDatabase) {
        // Load the database if password is registered with biometric
        mMainCredentialViewModel.loadDatabase(
            mainCredential = mainCredentialView.getMainCredential(),
            specialMode = mSpecialMode,
            cipherEncryptDatabase = cipherEncryptDatabase
        )
    }

    private fun onCredentialDecrypted(cipherDecryptDatabase: CipherDecryptDatabase) {
        // Load the database if password is retrieve from biometric
        // Retrieve from biometric
        // This unlock did not come from a fresh manual password entry (it came
        // from biometric/device-credential OR from our PIN cache), so never
        // offer "enable PIN unlock" for it - that offer only makes sense right
        // after the user has actually typed their master password.
        lastPasswordAttemptBytes = null
        val tempMainCredential = mainCredentialView.getMainCredential()
        when (cipherDecryptDatabase.credentialStorage) {
            CredentialStorage.PASSWORD -> {
                val charBuffer = DEFAULT_PASSWORD_ENCODING.decode(
                    ByteBuffer.wrap(cipherDecryptDatabase.decryptedValue)
                )
                val password = CharArray(charBuffer.remaining())
                charBuffer.get(password)
                tempMainCredential.password = password
            }
            CredentialStorage.KEY_FILE -> {
                // TODO advanced unlock key file
            }
            CredentialStorage.HARDWARE_KEY -> {
                // TODO advanced unlock hardware key
            }
        }
        mMainCredentialViewModel.loadDatabase(
            mainCredential = tempMainCredential,
            specialMode = mSpecialMode
        )
    }

    // -------------------------------------------------------------------
    // PIN quick-unlock (independent from biometric/device-credential
    // "Advanced Unlock" handled by DeviceUnlockManager/DeviceUnlockFragment).
    // See PinCryptoHelper for the crypto design and its threat-model notes.
    // -------------------------------------------------------------------

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private suspend fun handleMasterFailureDuringPinLockout(logicalUri: Uri) {
        when (val outcome = PinPolicyManager.onMasterPasswordFailure(applicationContext, logicalUri)) {
            is PinPolicy.MasterFailureOutcome.Retry -> {
                Snackbar.make(
                    coordinatorLayout,
                    getString(R.string.pin_master_lockout_remaining, outcome.remainingAttempts),
                    Snackbar.LENGTH_LONG
                ).asError().show()
            }
            is PinPolicy.MasterFailureOutcome.DeleteDatabase -> {
                AlertDialog.Builder(this@MainCredentialActivity)
                    .setMessage(R.string.pin_master_lockout_database_deleted)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .show()
            }
            null -> { /* No PIN lockout active: normal wrong-password handling */ }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun refreshPinUnlockButtonVisibility() {
        val uri = mMainCredentialViewModel.databaseFileUri ?: run {
            NoOpLog.d(TAG, "refreshPinUnlockButtonVisibility: no databaseFileUri yet, hiding PIN button")
            pinUnlockButton?.isVisible = false
            return
        }
        lifecycleScope.launch {
            val isSet = try {
                PinCryptoHelper.isPinSet(this@MainCredentialActivity, uri)
            } catch (e: Exception) {
                NoOpLog.e(TAG, "Unable to check PIN status", e)
                false
            }
            NoOpLog.d(TAG, "refreshPinUnlockButtonVisibility: uri=$uri isPinSet=$isSet")
            pinUnlockButton?.isVisible = isSet
        }
    }

    /**
     * Automatically start PIN authentication when this Activity represents a
     * normal database unlock.
     *
     * PIN is deliberately not auto-used for SEARCH/SELECTION/REGISTRATION or
     * other special-mode flows: those flows may be launched by external apps
     * and must retain KeePassPN's normal credential semantics.
     *
     * The database URI is already assigned synchronously from the launch Intent,
     * so this can safely run from onResume. A per-Activity guard prevents
     * repeated dialogs caused by lifecycle callbacks while the same credential
     * screen remains alive.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private suspend fun maybeAutoUnlockWithPin(): Boolean {
        appOpenPolicyJob?.join()
        if (pinAutoUnlockPrompted) return true
        // Autofill selection auto-starts PIN too; other special modes keep the normal credential flow
        val autofillSelection = mSpecialMode == SpecialMode.SELECTION && mAutofillPinUnlock
        if (mSpecialMode != SpecialMode.DEFAULT && !autofillSelection) return false

        val uri = mMainCredentialViewModel.databaseFileUri ?: return false
        val isPinSet = try {
            PinCryptoHelper.isPinSet(this@MainCredentialActivity, uri)
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to check PIN for automatic unlock", e)
            false
        }

        if (!isPinSet) return false
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return false
        }

        pinAutoUnlockPrompted = true
        NoOpLog.d(TAG, "Auto-starting PIN unlock for $uri")
        showPinUnlockDialog()
        return true
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun showPinUnlockDialog() {
        val uri = mMainCredentialViewModel.databaseFileUri ?: return
        val pinEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_unlock_dialog_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(pinEditText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pin_unlock_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.unlock, null) // set below to prevent auto-dismiss on error
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = pinEditText.text.toString().toCharArray()
                lifecycleScope.launch {
                    try {
                        val passwordBytes = PinCryptoHelper.unlockWithPin(
                            this@MainCredentialActivity, uri, pin
                        )
                        dialog.dismiss()
                        onCredentialDecrypted(CipherDecryptDatabase().apply {
                            this.databaseUri = uri
                            this.credentialStorage = CredentialStorage.PASSWORD
                            this.decryptedValue = passwordBytes
                        })
                    } catch (e: WrongPinException) {
                        pinEditText.error = getString(
                            R.string.pin_unlock_wrong_pin, e.remainingAttemptsBeforeLockout
                        )
                    } catch (e: PinWipedException) {
                        Snackbar.make(
                            coordinatorLayout,
                            R.string.pin_unlock_wiped,
                            Snackbar.LENGTH_LONG
                        ).asError().show()
                        dialog.dismiss()
                        refreshPinUnlockButtonVisibility()
                    } catch (e: Exception) {
                        NoOpLog.e(TAG, "Unable to unlock with PIN", e)
                        Snackbar.make(
                            coordinatorLayout,
                            deviceUnlockError(e, this@MainCredentialActivity),
                            Snackbar.LENGTH_LONG
                        ).asError().show()
                        dialog.dismiss()
                    } finally {
                        pin.fill('\u0000')
                    }
                }
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        // Focus the PIN field and open the keyboard as soon as the dialog appears
        pinEditText.post {
            pinEditText.requestFocus()
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(pinEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun showRemovePinConfirmDialog() {
        val uri = mMainCredentialViewModel.databaseFileUri ?: return
        lifecycleScope.launch {
            if (!PinCryptoHelper.isPinSet(this@MainCredentialActivity, uri)) return@launch
            AlertDialog.Builder(this@MainCredentialActivity)
                .setTitle(R.string.pin_unlock_remove_confirm_title)
                .setMessage(R.string.pin_unlock_remove_confirm_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        PinCryptoHelper.removePin(this@MainCredentialActivity, uri)
                        refreshPinUnlockButtonVisibility()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Called right after a *successful* database open, BEFORE navigating into
     * the vault. If this attempt used a freshly-typed master password (not
     * PIN, not biometric) and no PIN is cached yet for this database, offer
     * to set one up. Whatever the user chooses, [proceedIntoVault] always
     * runs exactly once at the end so the app never gets stuck on this screen.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun maybeOfferPinSetup(database: ContextualDatabase) {
        fun proceedIntoVault() {
            mMainCredentialViewModel.onDatabaseRetrieved(database)
        }
        val passwordBytes = lastPasswordAttemptBytes
        val uri = mMainCredentialViewModel.databaseFileUri
        if (passwordBytes == null || uri == null) {
            proceedIntoVault()
            return
        }
        lifecycleScope.launch {
            val offerWanted = withContext(Dispatchers.IO) {
                val alreadySet = try {
                    PinCryptoHelper.isPinSet(this@MainCredentialActivity, uri)
                } catch (e: Exception) {
                    NoOpLog.e(TAG, "Unable to check PIN status", e)
                    true // fail safe: don't prompt if we can't tell
                }
                !alreadySet
                        && PreferencesUtil.offerPinSetupAfterUnlock(applicationContext)
                        && !PinPolicyStore.isPinOfferDeclined(applicationContext, uri)
            }
            if (!offerWanted || isFinishing || isDestroyed) {
                releasePinSetupPassword(passwordBytes)
                proceedIntoVault()
                return@launch
            }
            AlertDialog.Builder(this@MainCredentialActivity)
                .setTitle(R.string.pin_setup_offer_title)
                .setMessage(R.string.pin_setup_offer_message)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    showSetPinDialog(uri, passwordBytes, onDone = ::proceedIntoVault)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    declinePinSetup(uri, passwordBytes)
                    proceedIntoVault()
                }
                .show()
        }
    }

    /** Refusing the offer is remembered for this database only, the switch in settings stays as is. */
    private fun declinePinSetup(uri: Uri, passwordBytes: ByteArray) {
        PinPolicyStore.setPinOfferDeclined(applicationContext, uri)
        releasePinSetupPassword(passwordBytes)
    }

    private fun releasePinSetupPassword(passwordBytes: ByteArray) {
        passwordBytes.fill(0)
        lastPasswordAttemptBytes = null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun showSetPinDialog(
        uri: Uri,
        passwordBytes: ByteArray,
        onDone: () -> Unit,
        errorMessage: Int? = null
    ) {
        val firstEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_setup_dialog_hint)
            errorMessage?.let { error = getString(it) }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(firstEditText)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_setup_dialog_title)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.unlock) { _, _ ->
                val firstPin = firstEditText.text.toString()
                if (firstPin.length < 6) {
                    // Ask again: a short PIN is a mistake, not a refusal of the offer
                    showSetPinDialog(uri, passwordBytes, onDone, R.string.pin_setup_too_short)
                    return@setPositiveButton
                }
                showConfirmPinDialog(uri, passwordBytes, firstPin.toCharArray(), onDone)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                declinePinSetup(uri, passwordBytes)
                onDone()
            }
            .show()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun showConfirmPinDialog(
        uri: Uri, passwordBytes: ByteArray, firstPin: CharArray, onDone: () -> Unit
    ) {
        val confirmEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_setup_confirm_dialog_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(confirmEditText)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_setup_confirm_dialog_title)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.unlock) { _, _ ->
                val confirmPin = confirmEditText.text.toString().toCharArray()
                if (!firstPin.contentEquals(confirmPin)) {
                    confirmPin.fill('\u0000')
                    firstPin.fill('\u0000')
                    // Start again with the first PIN, the password is still needed
                    showSetPinDialog(uri, passwordBytes, onDone, R.string.pin_setup_mismatch)
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        PinCryptoHelper.setPin(
                            this@MainCredentialActivity, uri, firstPin, passwordBytes
                        )
                        NoOpLog.d(TAG, "PIN set successfully for $uri")
                        refreshPinUnlockButtonVisibility()
                    } catch (e: Exception) {
                        NoOpLog.e(TAG, "Unable to set PIN", e)
                        Snackbar.make(
                            coordinatorLayout,
                            deviceUnlockError(e, this@MainCredentialActivity),
                            Snackbar.LENGTH_LONG
                        ).asError().show()
                    } finally {
                        confirmPin.fill('\u0000')
                        firstPin.fill('\u0000')
                        releasePinSetupPassword(passwordBytes)
                        onDone()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                firstPin.fill('\u0000')
                declinePinSetup(uri, passwordBytes)
                onDone()
            }
            .show()
    }

    private fun clearCredentialsViews(
        clearKeyFile: Boolean,
        clearHardwareKey: Boolean
    ) {
        mainCredentialView.populatePasswordTextView(null)
        if (clearKeyFile) {
            mainCredentialView.populateKeyFileView(null)
        }
        if (clearHardwareKey) {
            mainCredentialView.populateHardwareKeyView(null)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        // Read menu
        inflater.inflate(R.menu.open_file, menu)
        if (mMainCredentialViewModel.isReadOnlyToggleAllowed()) {
            changeDatabaseReadModeIcon(menu.findItem(R.id.menu_open_file_read_mode_key))
        } else {
            menu.removeItem(R.id.menu_open_file_read_mode_key)
        }

        if (mMainCredentialViewModel.isUserVerificationToggleAllowed()) {
            changeUserVerificationModeIcon(
                menu.findItem(R.id.menu_open_file_user_verification_mode_key)
            )
        } else {
            menu.removeItem(R.id.menu_open_file_user_verification_mode_key)
        }

        if (mSpecialMode == SpecialMode.DEFAULT) {
            MenuUtil.defaultMenuInflater(this, inflater, menu)
        }

        super.onCreateOptionsMenu(menu)

        launchEducation(menu)

        return true
    }

    // To fix multiple view education
    private var performedEductionInProgress = false
    private fun launchEducation(menu: Menu) {
        if (!performedEductionInProgress) {
            performedEductionInProgress = true
            // Show education views
            Handler(Looper.getMainLooper()).post {
                performedNextEducation(menu)
            }
        }
    }

    private fun performedNextEducation(menu: Menu) {
        val educationToolbar = toolbar
        val unlockEducationPerformed = educationToolbar != null
                && mPasswordActivityEducation.checkAndPerformedUnlockEducation(
                educationToolbar,
                        {
                            performedNextEducation(menu)
                        },
                        {
                            performedNextEducation(menu)
                        })
        if (!unlockEducationPerformed) {
            val readOnlyEducationPerformed =
                    educationToolbar?.findViewById<View>(R.id.menu_open_file_read_mode_key) != null
                    && mPasswordActivityEducation.checkAndPerformedReadOnlyEducation(
                    educationToolbar.findViewById(R.id.menu_open_file_read_mode_key),
                    {
                        try {
                            menu.findItem(R.id.menu_open_file_read_mode_key)
                        } catch (e: Exception) {
                            NoOpLog.e(TAG, "Unable to find read mode menu", e)
                        }
                        performedNextEducation(menu)
                    },
                    {
                        performedNextEducation(menu)
                    })
            if (!readOnlyEducationPerformed) {
                val userVerificationEducationPerformed =
                    educationToolbar?.findViewById<View>(R.id.menu_open_file_user_verification_mode_key) != null
                            && mPasswordActivityEducation.checkAndPerformedUserVerificationEducation(
                        educationToolbar.findViewById(R.id.menu_open_file_user_verification_mode_key),
                        {
                            try {
                                menu.findItem(R.id.menu_open_file_user_verification_mode_key)
                            } catch (e: Exception) {
                                NoOpLog.e(TAG, "Unable to find user verification mode menu", e)
                            }
                            performedNextEducation(menu)
                        },
                        {
                            performedNextEducation(menu)
                        })
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !userVerificationEducationPerformed
                    ) {
                        val biometricCanAuthenticate = DeviceUnlockManager.canAuthenticate(this)
                        if ((biometricCanAuthenticate == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                                    || biometricCanAuthenticate == BiometricManager.BIOMETRIC_SUCCESS)
                            && deviceUnlockButton != null
                        ) {
                            mPasswordActivityEducation.checkAndPerformedBiometricEducation(
                                deviceUnlockButton!!,
                                {
                                    startActivity(
                                        Intent(
                                            this,
                                            DeviceUnlockSettingsActivity::class.java
                                        )
                                    )
                                },
                                {

                                })
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun changeDatabaseReadModeIcon(readOnlyMenuItem: MenuItem) {
        if (mMainCredentialViewModel.readOnly) {
            readOnlyMenuItem.setTitle(R.string.menu_file_selection_read_only)
            readOnlyMenuItem.setIcon(R.drawable.ic_read_only_white_24dp)
        } else {
            readOnlyMenuItem.setTitle(R.string.menu_open_file_read_and_write)
            readOnlyMenuItem.setIcon(R.drawable.ic_read_write_white_24dp)
        }
    }

    private fun changeUserVerificationModeIcon(userVerificationMenuItem: MenuItem) {
        if (mMainCredentialViewModel.userVerificationMode) {
            userVerificationMenuItem.setTitle(R.string.menu_user_verification_enabled)
            userVerificationMenuItem.setIcon(R.drawable.ic_user_verification_enabled_white_24dp)
        } else {
            userVerificationMenuItem.setTitle(R.string.menu_user_verification_disabled)
            userVerificationMenuItem.setIcon(R.drawable.ic_user_verification_disabled_white_24dp)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.menu_open_file_read_mode_key -> {
                mMainCredentialViewModel.toggleReadOnly()
                changeDatabaseReadModeIcon(item)
                // Save the read-only state to database
                mMainCredentialViewModel.databaseFileUri?.let { databaseUri ->
                    FileDatabaseHistoryAction.getInstance(applicationContext).addOrUpdateDatabaseFile(
                        DatabaseFile(
                            databaseUri = databaseUri,
                            readOnly = mMainCredentialViewModel.readOnly
                        )
                    )
                }
            }
            R.id.menu_open_file_user_verification_mode_key -> {
                mMainCredentialViewModel.toggleUserVerificationMode()
                changeUserVerificationModeIcon(item)
                // Save the user verification state to database
                mMainCredentialViewModel.databaseFileUri?.let { databaseUri ->
                    FileDatabaseHistoryAction.getInstance(applicationContext).addOrUpdateDatabaseFile(
                        DatabaseFile(
                            databaseUri = databaseUri,
                            userVerification = mMainCredentialViewModel.userVerificationMode
                        )
                    )
                }
            }
            else -> MenuUtil.onDefaultMenuOptionsItemSelected(this, item)
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mDeviceUnlockViewModel?.disconnect()
        }
        // Hygiene: don't leave a copy of the just-typed master password
        // sitting in memory longer than necessary if the activity is torn
        // down mid-flow (e.g. user backgrounds the app during the "enable
        // PIN?" prompt).
        lastPasswordAttemptBytes?.fill(0)
        lastPasswordAttemptBytes = null
    }

    companion object {
        const val AUTOFILL_PIN_UNLOCK_KEY = "AUTOFILL_PIN_UNLOCK_KEY"

        private val TAG = MainCredentialActivity::class.java.name

        private const val UNLOCK_FRAGMENT_TAG = "UNLOCK_FRAGMENT_TAG"

        private const val KEY_FILENAME = "fileName"
        private const val KEY_KEYFILE = "keyFile"
        private const val KEY_HARDWARE_KEY = "hardwareKey"
        private const val VIEW_INTENT = "android.intent.action.VIEW"

        private fun buildAndLaunchIntent(
            activity: Activity,
            databaseFile: Uri,
            keyFile: Uri?,
            hardwareKey: HardwareKey?,
            intentBuildLauncher: (Intent) -> Unit
        ) {
            val intent = Intent(activity, MainCredentialActivity::class.java)
            intent.putExtra(KEY_FILENAME, databaseFile)
            if (keyFile != null)
                intent.putExtra(KEY_KEYFILE, keyFile)
            if (hardwareKey != null)
                intent.putExtra(KEY_HARDWARE_KEY, hardwareKey.toString())
            intentBuildLauncher.invoke(intent)
        }

        /*
         * -------------------------
         * 		Standard Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launch(
            activity: Activity,
            databaseFile: Uri,
            keyFile: Uri?,
            hardwareKey: HardwareKey?
        ) {
            buildAndLaunchIntent(activity, databaseFile, keyFile, hardwareKey) { intent ->
                activity.startActivity(intent)
            }
        }

        /*
         * -------------------------
         * 		Share Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launchForSearchResult(
            activity: Activity,
            databaseFile: Uri,
            keyFile: Uri?,
            hardwareKey: HardwareKey?,
            searchInfo: SearchInfo
        ) {
            buildAndLaunchIntent(activity, databaseFile, keyFile, hardwareKey) { intent ->
                EntrySelectionHelper.startActivityForSearchModeResult(
                    context = activity,
                    intent = intent,
                    searchInfo = searchInfo
                )
            }
        }

        /*
         * -------------------------
         * 		Selection Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launchForSelection(
            activity: Activity,
            databaseFile: Uri,
            keyFile: Uri?,
            hardwareKey: HardwareKey?,
            typeMode: TypeMode,
            searchInfo: SearchInfo?,
            activityResultLauncher: ActivityResultLauncher<Intent>?,
            autofillPinUnlock: Boolean = false
        ) {
            buildAndLaunchIntent(activity, databaseFile, keyFile, hardwareKey) { intent ->
                intent.putExtra(AUTOFILL_PIN_UNLOCK_KEY, autofillPinUnlock)
                EntrySelectionHelper.startActivityForSelectionModeResult(
                    context = activity,
                    intent = intent,
                    typeMode = typeMode,
                    searchInfo = searchInfo,
                    activityResultLauncher = activityResultLauncher
                )
            }
        }

        /*
         * -------------------------
         *      Registration Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launchForRegistration(
            activity: Activity,
            databaseFile: Uri,
            keyFile: Uri?,
            hardwareKey: HardwareKey?,
            typeMode: TypeMode,
            registerInfo: RegisterInfo?,
            activityResultLauncher: ActivityResultLauncher<Intent>?
        ) {
            buildAndLaunchIntent(activity, databaseFile, keyFile, hardwareKey) { intent ->
                EntrySelectionHelper.startActivityForRegistrationModeResult(
                    context = activity,
                    intent = intent,
                    typeMode = typeMode,
                    registerInfo = registerInfo,
                    activityResultLauncher = activityResultLauncher,
                )
            }
        }
    }
}
