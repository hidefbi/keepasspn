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
package com.keepasspn.services

import android.content.Intent
import com.keepasspn.timeout.TimeoutHelper
import com.keepasspn.utils.LockReceiver
import com.keepasspn.utils.registerLockReceiver
import com.keepasspn.utils.unregisterLockReceiver


typealias LockNotificationService = LockNotificationServiceParam<Void>

abstract class LockNotificationServiceParam<T> : NotificationServiceParam<T>() {

    private var mLockReceiver: LockReceiver = LockReceiver {
        stopService()
    }

    override fun onCreate() {
        super.onCreate()
        // Register a lock receiver to stop notification service when lock on keyboard is performed
        registerLockReceiver(mLockReceiver)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!TimeoutHelper.temporarilyDisableLock) {
            stopService()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterLockReceiver(mLockReceiver)
        super.onDestroy()
    }
}