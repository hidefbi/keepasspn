package com.keepasspn.activities.legacy

import com.keepasspn.database.ContextualDatabase
import com.keepasspn.tasks.ActionRunnable

interface DatabaseRetrieval {
    fun onDatabaseRetrieved(database: ContextualDatabase)

    fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    )
}