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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.keepasspn.biometric.PinPolicyManager
import com.keepasspn.utils.NoOpLog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.snackbar.Snackbar
import com.keepasspn.R
import com.keepasspn.activities.dialogs.SetMainCredentialDialogFragment
import com.keepasspn.activities.helpers.ExternalFileHelper
import com.keepasspn.activities.helpers.setOpenDocumentClickListener
import com.keepasspn.activities.legacy.DatabaseModeActivity
import com.keepasspn.adapters.FileDatabaseHistoryAdapter
import com.keepasspn.app.database.FileDatabaseHistoryAction
import com.keepasspn.credentialprovider.EntrySelectionHelper
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.credentialprovider.TypeMode
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.MainCredential
import com.keepasspn.education.FileDatabaseSelectActivityEducation
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.model.RegisterInfo
import com.keepasspn.model.SearchInfo
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_CREATE_TASK
import com.keepasspn.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.keepasspn.settings.PreferencesUtil
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.utils.DexUtil
import com.keepasspn.utils.MagikeyboardUtil
import com.keepasspn.utils.MenuUtil
import com.keepasspn.utils.UriUtil.openUrl
import com.keepasspn.utils.getParcelableCompat
import com.keepasspn.view.asError
import com.keepasspn.view.showActionErrorIfNeeded
import com.keepasspn.viewmodels.DatabaseFilesViewModel
import com.keepasspn.viewmodels.SetMainCredentialViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

class FileDatabaseSelectActivity : DatabaseModeActivity() {

    // Views
    private lateinit var coordinatorLayout: CoordinatorLayout
    private var createDatabaseButtonView: View? = null
    private var openDatabaseButtonView: View? = null

    private val databaseFilesViewModel: DatabaseFilesViewModel by viewModels()

    private val mFileDatabaseSelectActivityEducation = FileDatabaseSelectActivityEducation(this)

    // Adapter to manage database history list
    private var mAdapterDatabaseHistory: FileDatabaseHistoryAdapter? = null

    private var mFileDatabaseHistoryAction: FileDatabaseHistoryAction? = null

    private var mDatabaseFileUri: Uri? = null

    private var mExternalFileHelper: ExternalFileHelper? = null

    private val mSetMainCredentialViewModel: SetMainCredentialViewModel by viewModels()

    override fun manageDatabaseInfo(): Boolean  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Record this app open and apply the PIN validity period
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            lifecycleScope.launch {
                try {
                    PinPolicyManager.onAppOpened(applicationContext)
                } catch (_: Exception) {
                    // Policy failures must not block the app
                }
            }
        }

        // Enabling/disabling MagikeyboardService is normally done by DexModeReceiver, but this
        // additional check will allow the keyboard to be reenabled more easily if the app crashes
        // or is force quit within DeX mode and then the user leaves DeX mode. Without this, the
        // user would need to enter and exit DeX mode once to reenable the service.
        val dexMode = DexUtil.isDexMode(resources.configuration)
        lifecycleScope.launch(Dispatchers.IO) {
            MagikeyboardUtil.setEnabled(applicationContext, !dexMode)
        }

        mFileDatabaseHistoryAction = FileDatabaseHistoryAction.getInstance(applicationContext)

        setContentView(R.layout.activity_file_selection)
        coordinatorLayout = findViewById(R.id.activity_file_selection_coordinator_layout)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = ""
        setSupportActionBar(toolbar)

        // Special title

        // Create database button
        createDatabaseButtonView = findViewById(R.id.create_database_button)
        createDatabaseButtonView?.setOnClickListener { createNewFile() }

        // Open database button
        mExternalFileHelper = ExternalFileHelper(this)
        mExternalFileHelper?.buildOpenDocument { uri ->
            uri?.let {
                launchMainCredentialActivityWithPath(uri)
            }
        }
        mExternalFileHelper?.buildCreateDatabaseDocument { databaseFileCreatedUri ->
            mDatabaseFileUri = databaseFileCreatedUri
            if (mDatabaseFileUri != null) {
                SetMainCredentialDialogFragment.getInstance(true)
                    .show(supportFragmentManager, "passwordDialog")
            } else {
                val error = getString(R.string.error_create_database)
                Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
                NoOpLog.e(TAG, error)
            }
        }
        openDatabaseButtonView = findViewById(R.id.open_database_button)
        openDatabaseButtonView?.setOpenDocumentClickListener(mExternalFileHelper)

        // History list
        val fileDatabaseHistoryRecyclerView = findViewById<RecyclerView>(R.id.file_list)
        fileDatabaseHistoryRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        // Removes blinks
        (fileDatabaseHistoryRecyclerView.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        // Construct adapter with listeners
        mAdapterDatabaseHistory = FileDatabaseHistoryAdapter(this)
        mAdapterDatabaseHistory?.setOnDefaultDatabaseListener { databaseFile ->
            databaseFilesViewModel.setDefaultDatabase(databaseFile)
        }
        mAdapterDatabaseHistory?.setOnFileDatabaseHistoryOpenListener { fileDatabaseHistoryEntityToOpen ->
            fileDatabaseHistoryEntityToOpen.databaseUri?.let { databaseFileUri ->
                launchMainCredentialActivity(
                    databaseFileUri,
                    fileDatabaseHistoryEntityToOpen.keyFileUri,
                    fileDatabaseHistoryEntityToOpen.hardwareKey
                )
            }
        }
        mAdapterDatabaseHistory?.setOnFileDatabaseHistoryDeleteListener { fileDatabaseHistoryToDelete ->
            databaseFilesViewModel.deleteDatabaseFile(fileDatabaseHistoryToDelete)
            true
        }
        mAdapterDatabaseHistory?.setOnSaveAliasListener { fileDatabaseHistoryWithNewAlias ->
            // Update in app database
            databaseFilesViewModel.updateDatabaseFile(fileDatabaseHistoryWithNewAlias)
        }
        fileDatabaseHistoryRecyclerView.adapter = mAdapterDatabaseHistory

        // Load default database the first time
        databaseFilesViewModel.doForDefaultDatabase { databaseFileUri ->
            launchMainCredentialActivityWithPath(databaseFileUri)
        }

        // Retrieve the database URI provided by file manager after an orientation change
        if (savedInstanceState != null
                && savedInstanceState.containsKey(EXTRA_DATABASE_URI)) {
            mDatabaseFileUri = savedInstanceState.getParcelableCompat(EXTRA_DATABASE_URI)
        }

        // Observe databases
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mSetMainCredentialViewModel.onMainCredentialAssigned.collect { mainCredential ->
                        onAssignKeyDialogPositiveClick(mainCredential)
                    }
                }
                launch {
                    databaseFilesViewModel.defaultDatabase.collect {
                        // Retrieve settings for default database
                        mAdapterDatabaseHistory?.setDefaultDatabase(it)
                    }
                }
                launch {
                    databaseFilesViewModel.databaseFilesLoaded.collect { databaseFiles ->
                        try {
                            when (databaseFiles.databaseFileAction) {
                                DatabaseFilesViewModel.DatabaseFileAction.NONE -> {
                                    mAdapterDatabaseHistory?.replaceAllDatabaseFileHistoryList(databaseFiles.databaseFileList)
                                }

                                DatabaseFilesViewModel.DatabaseFileAction.ADD -> {
                                    databaseFiles.databaseFileToActivate?.let { databaseFileToAdd ->
                                        mAdapterDatabaseHistory?.addDatabaseFileHistory(databaseFileToAdd)
                                    }
                                }

                                DatabaseFilesViewModel.DatabaseFileAction.UPDATE -> {
                                    databaseFiles.databaseFileToActivate?.let { databaseFileToUpdate ->
                                        mAdapterDatabaseHistory?.updateDatabaseFileHistory(databaseFileToUpdate)
                                    }
                                }

                                DatabaseFilesViewModel.DatabaseFileAction.DELETE -> {
                                    databaseFiles.databaseFileToActivate?.let { databaseFileToDelete ->
                                        mAdapterDatabaseHistory?.deleteDatabaseFileHistory(databaseFileToDelete)
                                    }
                                }
                            }
                            databaseFilesViewModel.consumeAction()
                        } catch (e: Exception) {
                            NoOpLog.e(TAG, "Unable to observe database action", e)
                        }
                    }
                }
            }
        }

        // Remove all the remember locations if needed
        if (PreferencesUtil.rememberDatabaseLocations(applicationContext).not()) {
            FileDatabaseHistoryAction.getInstance(applicationContext)
                .deleteAll()
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        launchGroupActivityIfLoaded(database)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        if (result.isSuccess) {
            // Launch activity
            when (actionTask) {
                ACTION_DATABASE_CREATE_TASK -> {
                    GroupActivity.launch(
                        this@FileDatabaseSelectActivity,
                        database,
                        false
                    )
                    coordinatorLayout.showActionErrorIfNeeded(result)
                }
                ACTION_DATABASE_LOAD_TASK -> {
                    launchGroupActivityIfLoaded(database)
                }
            }
        }
    }

    /**
     * Create a new file by calling the content provider
     */
    private fun createNewFile() {
        mExternalFileHelper?.createDatabaseDocument(".kdbx")
    }

    private fun fileNoFoundAction(e: FileNotFoundException) {
        val error = getString(R.string.file_not_found_content)
        NoOpLog.e(TAG, error, e)
        Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
    }

    private fun launchMainCredentialActivity(databaseUri: Uri, keyFile: Uri?, hardwareKey: HardwareKey?) {
        try {
            EntrySelectionHelper.doSpecialAction(
                intent = this.intent,
                defaultAction = {
                    MainCredentialActivity.launch(
                        activity = this,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey
                    )
                },
                searchAction = { searchInfo ->
                    MainCredentialActivity.launchForSearchResult(
                        activity = this,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey,
                        searchInfo = searchInfo
                    )
                    onLaunchActivitySpecialMode()
                },
                selectionAction = { intentSenderMode, typeMode, searchInfo ->
                    MainCredentialActivity.launchForSelection(
                        activity = this,
                        activityResultLauncher = if (intentSenderMode)
                            mCredentialActivityResultLauncher else null,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey,
                        typeMode = typeMode,
                        searchInfo = searchInfo,
                        autofillPinUnlock = intent.getBooleanExtra(MainCredentialActivity.AUTOFILL_PIN_UNLOCK_KEY, false)
                    )
                    onLaunchActivitySpecialMode()
                },
                registrationAction = { intentSenderMode, typeMode, registerInfo ->
                    MainCredentialActivity.launchForRegistration(
                        activity = this,
                        activityResultLauncher = if (intentSenderMode)
                            mCredentialActivityResultLauncher else null,
                        databaseFile = databaseUri,
                        keyFile = keyFile,
                        hardwareKey = hardwareKey,
                        typeMode = typeMode,
                        registerInfo = registerInfo
                    )
                    onLaunchActivitySpecialMode()
                }
            )
        } catch (e: FileNotFoundException) {
            fileNoFoundAction(e)
        }
    }

    private fun launchGroupActivityIfLoaded(database: ContextualDatabase) {
        if (database.loaded) {
            GroupActivity.launch(this,
                database,
                { onValidateSpecialMode() },
                { onCancelSpecialMode() },
                { onLaunchActivitySpecialMode() },
                mCredentialActivityResultLauncher
            )
        }
    }

    private fun launchMainCredentialActivityWithPath(databaseUri: Uri) {
        launchMainCredentialActivity(databaseUri, null, null)
        // Delete flickering for kitkat <=
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()

        // Show open and create button or special mode
        when (mSpecialMode) {
            SpecialMode.DEFAULT -> {
                createDatabaseButtonView?.visibility = View.VISIBLE
            }
            else -> {
                // Disable create button if in selection mode or request for autofill
                createDatabaseButtonView?.visibility = View.GONE
            }
        }

        // Show recent files if allowed
        if (PreferencesUtil.showRecentFiles(this@FileDatabaseSelectActivity)) {
            databaseFilesViewModel.loadListOfDatabases()
        } else {
            mAdapterDatabaseHistory?.clearDatabaseFileHistoryList()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // to retrieve the URI of a created database after an orientation change
        outState.putParcelable(EXTRA_DATABASE_URI, mDatabaseFileUri)
    }

    private fun onAssignKeyDialogPositiveClick(mainCredential: MainCredential) {
        try {
            mDatabaseFileUri?.let { databaseUri ->
                // Create the new database
                mDatabaseViewModel.createDatabase(databaseUri, mainCredential)
            }
        } catch (e: Exception) {
            val error = getString(R.string.error_create_database_file)
            Snackbar.make(coordinatorLayout, error, Snackbar.LENGTH_LONG).asError().show()
            NoOpLog.e(TAG, error, e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        if (mSpecialMode == SpecialMode.DEFAULT) {
            MenuUtil.defaultMenuInflater(this, menuInflater, menu)
        }

        Handler(Looper.getMainLooper()).post {
            performedNextEducation()
        }

        return true
    }

    private fun performedNextEducation() {
        // If no recent files
        val createDatabaseEducationPerformed =
                createDatabaseButtonView != null
                && createDatabaseButtonView!!.visibility == View.VISIBLE
                && mFileDatabaseSelectActivityEducation.checkAndPerformedCreateDatabaseEducation(
                        createDatabaseButtonView!!,
                {
                    createNewFile()
                },
                {
                    // But if the user cancel, it can also select a database
                    performedNextEducation()
                })
        if (!createDatabaseEducationPerformed) {
            // selectDatabaseEducationPerformed
            openDatabaseButtonView != null
            && mFileDatabaseSelectActivityEducation.checkAndPerformedSelectDatabaseEducation(
                openDatabaseButtonView!!,
            { tapTargetView ->
                tapTargetView?.let {
                    mExternalFileHelper?.openDocument()
                }
            },
            {

            })
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> this.openUrl(R.string.file_manager_explanation_url)
        }
        MenuUtil.onDefaultMenuOptionsItemSelected(this, item)
        return super.onOptionsItemSelected(item)
    }

    companion object {

        private const val TAG = "FileDbSelectActivity"
        private const val EXTRA_DATABASE_URI = "EXTRA_DATABASE_URI"

        /*
         * -------------------------
         * 		Standard Launch
         * -------------------------
         */

        fun launch(context: Context) {
            context.startActivity(Intent(context, FileDatabaseSelectActivity::class.java))
        }

        /*
         * -------------------------
         * 		Search Launch
         * -------------------------
         */

        fun launchForSearch(
            context: Context,
            searchInfo: SearchInfo
        ) {
            EntrySelectionHelper.startActivityForSearchModeResult(
                context = context,
                intent = Intent(context, FileDatabaseSelectActivity::class.java),
                searchInfo = searchInfo
            )
        }

        /*
         * -------------------------
         * 		Selection Launch
         * -------------------------
         */

        fun launchForSelection(
            context: Context,
            typeMode: TypeMode,
            searchInfo: SearchInfo? = null,
            activityResultLauncher: ActivityResultLauncher<Intent>?,
            autofillPinUnlock: Boolean = false,
        ) {
            EntrySelectionHelper.startActivityForSelectionModeResult(
                context = context,
                intent = Intent(context, FileDatabaseSelectActivity::class.java).apply {
                    putExtra(MainCredentialActivity.AUTOFILL_PIN_UNLOCK_KEY, autofillPinUnlock)
                },
                searchInfo = searchInfo,
                typeMode = typeMode,
                activityResultLauncher = activityResultLauncher
            )
        }

        /*
         * -------------------------
         * 		Registration Launch
         * -------------------------
         */
        fun launchForRegistration(
            context: Context,
            typeMode: TypeMode,
            registerInfo: RegisterInfo? = null,
            activityResultLauncher: ActivityResultLauncher<Intent>?,
        ) {
            EntrySelectionHelper.startActivityForRegistrationModeResult(
                context = context,
                intent = Intent(context, FileDatabaseSelectActivity::class.java),
                registerInfo = registerInfo,
                typeMode = typeMode,
                activityResultLauncher = activityResultLauncher
            )
        }
    }
}
