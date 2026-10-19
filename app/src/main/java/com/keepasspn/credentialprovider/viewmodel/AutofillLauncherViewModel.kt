package com.keepasspn.credentialprovider.viewmodel

import android.app.Activity.RESULT_CANCELED
import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Intent
import android.os.Build
import com.keepasspn.utils.NoOpLog
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import androidx.lifecycle.viewModelScope
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveAndRemoveEntries
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveRegisterInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveSearchInfo
import com.keepasspn.credentialprovider.EntrySelectionHelper.retrieveSpecialMode
import com.keepasspn.credentialprovider.SpecialMode
import com.keepasspn.credentialprovider.TypeMode
import com.keepasspn.credentialprovider.autofill.AutofillComponent
import com.keepasspn.credentialprovider.autofill.AutofillHelper
import com.keepasspn.credentialprovider.autofill.AutofillHelper.retrieveAutofillComponent
import com.keepasspn.credentialprovider.autofill.KeeAutofillService
import com.keepasspn.credentialprovider.magikeyboard.MagikeyboardService
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.exception.RegisterInReadOnlyDatabaseException
import com.keepasspn.database.helper.SearchHelper
import com.keepasspn.model.RegisterInfo
import com.keepasspn.model.SearchInfo
import com.keepasspn.services.ClipboardEntryNotificationService
import com.keepasspn.settings.PreferencesUtil
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@RequiresApi(api = Build.VERSION_CODES.O)
class AutofillLauncherViewModel(application: Application): CredentialLauncherViewModel(application) {

    private var mAutofillComponent: AutofillComponent? = null

    private var mLockDatabaseAfterSelection: Boolean = false
    private var mAutofillSharedToMagikeyboard: Boolean = false
    private var mSwitchToMagikeyboard: Boolean = false

    private val mUiState = MutableStateFlow<UIState>(UIState.Loading)
    val uiState: StateFlow<UIState> = mUiState.asStateFlow()

    fun initialize() {
        mLockDatabaseAfterSelection = PreferencesUtil.isAutofillCloseDatabaseEnable(getApplication())
        mAutofillSharedToMagikeyboard = PreferencesUtil.isAutofillSharedToMagikeyboardEnable(getApplication())
        mSwitchToMagikeyboard = PreferencesUtil.isAutoSwitchToMagikeyboardEnable(getApplication())
    }

    override fun onResult() {
        super.onResult()
        mAutofillComponent = null
    }

    override suspend fun launchAction(
        intent: Intent,
        specialMode: SpecialMode,
        database: ContextualDatabase?
    ) {
        // Retrieve selection mode
        when (intent.retrieveSpecialMode()) {
            SpecialMode.SELECTION -> {
                val searchInfo =
                    intent.retrieveSearchInfo()
                        ?: throw IOException("Search info is null")
                mAutofillComponent = intent.retrieveAutofillComponent()
                // Build search param
                launchSelection(database, mAutofillComponent, searchInfo)
            }
            SpecialMode.REGISTRATION -> {
                // To register info
                val registerInfo =
                    intent.retrieveRegisterInfo()
                        ?: throw IOException("Register info is null")
                launchRegistration(database, registerInfo)
            }
            else -> {
                // Not an autofill call
                cancelResult()
            }
        }
    }

    private suspend fun launchSelection(
        database: ContextualDatabase?,
        autofillComponent: AutofillComponent?,
        searchInfo: SearchInfo
    ) {
        withContext(Dispatchers.IO) {
            if (autofillComponent == null) {
                throw IOException("Autofill component is null")
            }
            if (KeeAutofillService.autofillAllowedFor(
                    applicationId = searchInfo.applicationId,
                    webDomain = searchInfo.webDomain,
                    context = getApplication()
                )
            ) {
                // If database is open
                SearchHelper.checkAutoSearchInfo(
                    context = getApplication(),
                    database = database,
                    searchInfo = searchInfo,
                    onItemsFound = { openedDatabase, items ->
                        // Items found
                        if (autofillComponent.compatInlineSuggestionsRequest != null) {
                            mUiState.value = UIState.ShowAutofillSuggestionMessage
                        }
                        AutofillHelper.buildResponse(
                            context = getApplication(),
                            autofillComponent = autofillComponent,
                            database = openedDatabase,
                            entriesInfo = items
                        ) { intent ->
                            setResult(intent, lockDatabase = mLockDatabaseAfterSelection)
                        }
                    },
                    onItemNotFound = { openedDatabase ->
                        // Show the database UI to select the entry
                        mCredentialUiState.value =
                            CredentialState.LaunchGroupActivityForSelection(
                                database = openedDatabase,
                                searchInfo = searchInfo,
                                typeMode = TypeMode.AUTOFILL
                            )
                    },
                    onDatabaseClosed = {
                        // If database not open
                        mCredentialUiState.value =
                            CredentialState.LaunchFileDatabaseSelectActivityForSelection(
                                searchInfo = searchInfo,
                                typeMode = TypeMode.AUTOFILL
                            )
                    }
                )
            } else {
                mUiState.value = UIState.ShowBlockRestartMessage
            }
        }
    }

    override fun manageSelectionResult(
        database: ContextualDatabase,
        activityResult: ActivityResult
    ) {
        super.manageSelectionResult(database, activityResult)
        val intent = activityResult.data
        viewModelScope.launch(CoroutineExceptionHandler { _, e ->
            NoOpLog.e(TAG, "Unable to create selection response for autofill", e)
            showError(e)
        }) {
            when (activityResult.resultCode) {
                RESULT_OK -> {
                    withContext(Dispatchers.IO) {
                        NoOpLog.d(TAG, "Autofill selection result")
                        if (intent == null)
                            throw IOException("Intent is null")
                        val entries = intent.retrieveAndRemoveEntries(database)
                        val autofillComponent = mAutofillComponent
                                ?: throw IOException("Autofill component is null")
                        withContext(Dispatchers.Main) {
                            // Add Autofill entries to Magic Keyboard #2024 #995
                            if (mAutofillSharedToMagikeyboard) {
                                MagikeyboardService.addEntries(
                                    context = getApplication(),
                                    entryList = entries,
                                    autoSwitchKeyboard = mSwitchToMagikeyboard,
                                    from = TypeMode.AUTOFILL
                                )
                            } else {
                                ClipboardEntryNotificationService.launchOtpNotificationIfAllowed(
                                    context = getApplication(),
                                    entries = entries
                                )
                            }
                            AutofillHelper.buildResponse(
                                context = getApplication(),
                                autofillComponent = autofillComponent,
                                database = database,
                                entriesInfo = entries
                            ) { intent ->
                                setResult(intent, lockDatabase = mLockDatabaseAfterSelection)
                            }
                        }
                    }
                }
                RESULT_CANCELED -> {
                    withContext(Dispatchers.Main) {
                        cancelResult()
                    }
                }
            }
        }
    }

    // -------------
    //  Registration
    // -------------

    private fun launchRegistration(
        database: ContextualDatabase?,
        registerInfo: RegisterInfo
    ) {
        val searchInfo = registerInfo.searchInfo
        if (KeeAutofillService.autofillAllowedFor(
                applicationId = searchInfo.applicationId,
                webDomain = searchInfo.webDomain,
                context = getApplication()
            )) {
            val readOnly = database?.isReadOnly != false
            SearchHelper.checkAutoSearchInfo(
                context = getApplication(),
                database = database,
                searchInfo = searchInfo,
                onItemsFound = { openedDatabase, _ ->
                    if (!readOnly) {
                        // Show the database UI to select the entry
                        mCredentialUiState.value =
                            CredentialState.LaunchGroupActivityForRegistration(
                                database = openedDatabase,
                                registerInfo = registerInfo,
                                typeMode = TypeMode.AUTOFILL
                            )
                    } else {
                        mCredentialUiState.value = CredentialState.ShowError(
                            RegisterInReadOnlyDatabaseException()
                        )
                    }
                },
                onItemNotFound = { openedDatabase ->
                    if (!readOnly) {
                        // Show the database UI to select the entry
                        mCredentialUiState.value =
                            CredentialState.LaunchGroupActivityForRegistration(
                                database = openedDatabase,
                                registerInfo = registerInfo,
                                typeMode = TypeMode.AUTOFILL
                            )
                    } else {
                        mCredentialUiState.value = CredentialState.ShowError(
                            RegisterInReadOnlyDatabaseException()
                        )
                    }
                },
                onDatabaseClosed = {
                    // If database not open
                    mCredentialUiState.value =
                        CredentialState.LaunchFileDatabaseSelectActivityForRegistration(
                            registerInfo = registerInfo,
                            typeMode = TypeMode.AUTOFILL
                        )
                }
            )
        } else {
            mUiState.value = UIState.ShowBlockRestartMessage
        }
    }

    override fun manageRegistrationResult(activityResult: ActivityResult) {
        viewModelScope.launch(CoroutineExceptionHandler { _, e ->
            NoOpLog.e(TAG, "Unable to create registration response for autofill", e)
            showError(e)
        }) {
            val responseIntent = Intent()
            when (activityResult.resultCode) {
                RESULT_OK -> {
                    NoOpLog.d(TAG, "Autofill registration result")
                    withContext(Dispatchers.Main) {
                        setResult(responseIntent)
                    }
                }
                RESULT_CANCELED -> {
                    withContext(Dispatchers.Main) {
                        cancelResult()
                    }
                }
            }
        }
    }

    sealed class UIState {
        object Loading: UIState()
        object ShowBlockRestartMessage: UIState()
        object ShowAutofillSuggestionMessage: UIState()
    }

    companion object {
        private val TAG = AutofillLauncherViewModel::class.java.name
    }
}