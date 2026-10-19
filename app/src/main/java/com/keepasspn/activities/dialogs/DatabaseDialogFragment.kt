package com.keepasspn.activities.dialogs

import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.keepasspn.activities.legacy.DatabaseRetrieval
import com.keepasspn.activities.legacy.resetAppTimeoutWhenViewTouchedOrFocused
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.settings.PreferencesUtil
import com.keepasspn.tasks.ActionRunnable
import com.keepasspn.timeout.TimeoutHelper
import com.keepasspn.utils.AppUtil
import com.keepasspn.viewmodels.DatabaseViewModel
import kotlinx.coroutines.launch

abstract class DatabaseDialogFragment : DialogFragment(), DatabaseRetrieval {

    protected val mDatabaseViewModel: DatabaseViewModel by activityViewModels()
    private val mDatabase: ContextualDatabase?
        get() = mDatabaseViewModel.database

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mDatabaseViewModel.databaseState.collect { database ->
                        database?.let {
                            onDatabaseRetrieved(database)
                        }
                    }
                }
                launch {
                    mDatabaseViewModel.actionState.collect { uiState ->
                        when (uiState) {
                            is DatabaseViewModel.ActionState.OnDatabaseActionFinished -> {
                                onDatabaseActionFinished(
                                    uiState.database,
                                    uiState.actionTask,
                                    uiState.result
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Screenshot mode or hide views
        context?.let {
            AppUtil.setScreenshotMode(dialog?.window, PreferencesUtil.isScreenshotModeEnabled(it))
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated(message = "")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        resetAppTimeoutOnTouchOrFocus()
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        // Can be overridden by a subclass
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        // Can be overridden by a subclass
    }

    fun resetAppTimeout() {
        context?.let {
            TimeoutHelper.checkTimeAndLockIfTimeoutOrResetTimeout(it,
                mDatabase?.loaded ?: false)
        }
    }

    open fun overrideTimeoutTouchAndFocusEvents(): Boolean {
        return false
    }

    private fun resetAppTimeoutOnTouchOrFocus() {
        if (!overrideTimeoutTouchAndFocusEvents()) {
            context?.let {
                dialog?.window?.decorView?.resetAppTimeoutWhenViewTouchedOrFocused(
                    it,
                    mDatabase?.loaded
                )
            }
        }
    }
}