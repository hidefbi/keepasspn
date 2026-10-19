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
package com.keepasspn.activities.stylish

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.keepasspn.utils.NoOpLog
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.keepasspn.R
import com.keepasspn.settings.NestedAppSettingsFragment.Companion.DATABASE_PREFERENCE_CHANGED
import com.keepasspn.settings.PreferencesUtil
import com.keepasspn.utils.AppUtil

/**
 * Stylish Hide Activity that apply a dynamic style and sets FLAG_SECURE to prevent screenshots / from
 * appearing in the recent app preview
 */
abstract class StylishActivity : AppCompatActivity() {

    @StyleRes
    private var themeId: Int = 0
    private var customStyle = true

    /* (non-Javadoc) Workaround for HTC Linkify issues
     * @see android.app.Activity#startActivity(android.content.Intent)
     */
    override fun startActivity(intent: Intent) {
        try {
            intent.component?.let {
                if (it.shortClassName == ".HtcLinkifyDispatcherActivity")
                    intent.component = null
            }
            super.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            /* Catch the bad HTC implementation case */
            super.startActivity(Intent.createChooser(intent, null))
        }
    }

    open fun applyCustomStyle(): Boolean = true

    open fun finishActivityIfReloadRequested(): Boolean = false

    open fun reloadActivity() {
        if (finishActivityIfReloadRequested()) finish() else recreate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        customStyle = applyCustomStyle()
        if (customStyle) {
            // Preconfigured themes
            this.themeId = Stylish.getThemeId(this)
            setTheme(themeId)
            if (Stylish.isDynamic(this)) {
                // Material You theme
                DynamicColors.applyToActivityIfAvailable(this)
            }
        }

        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(onScreenshotModePrefListener)
    }
    private val onScreenshotModePrefListener = OnSharedPreferenceChangeListener { _, key ->
        if (key != getString(R.string.enable_screenshot_mode_key)) return@OnSharedPreferenceChangeListener

        setScreenshotMode(PreferencesUtil.isScreenshotModeEnabled(this))
    }

    private fun setScreenshotMode(isEnabled: Boolean) {
        findViewById<View>(R.id.screenshot_mode_banner)?.visibility = if (isEnabled) VISIBLE else GONE
        AppUtil.setScreenshotMode(window, isEnabled)
    }

    override fun onResume() {
        super.onResume()

        if ((customStyle && Stylish.getThemeId(this) != this.themeId)
            || DATABASE_PREFERENCE_CHANGED) {
            DATABASE_PREFERENCE_CHANGED = false
            NoOpLog.d(this.javaClass.name, "Theme change detected, restarting activity")
            // To prevent KitKat bugs
            Handler(Looper.getMainLooper()).post { reloadActivity() }
        }
        setScreenshotMode(PreferencesUtil.isScreenshotModeEnabled(this))
    }
}
