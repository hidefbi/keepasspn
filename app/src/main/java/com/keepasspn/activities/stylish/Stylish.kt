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

import android.content.Context
import android.content.res.Configuration
import com.keepasspn.utils.NoOpLog
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import com.keepasspn.R
import com.keepasspn.settings.PreferencesUtil

/**
 * Class that provides functions to retrieve and assign a theme to a module
 */
object Stylish {

    private var themeString: String? = null

    /**
     * Initialize the class with a theme preference
     * @param context Context to retrieve the theme preference
     */
    fun load(context: Context) {
        NoOpLog.d(Stylish::class.java.name, "Attaching to " + context.packageName)
        try {
            themeString = PreferencesUtil.getStyle(context)
        } catch (e: Exception) {
            NoOpLog.e("Stylish", "Unable to get preference style", e)
        }
    }

    fun retrieveEquivalentSystemStyle(context: Context, styleString: String?): String {
        val systemNightMode = when (PreferencesUtil.getStyleBrightness(context)) {
            context.getString(R.string.list_style_brightness_light) -> false
            context.getString(R.string.list_style_brightness_night) -> true
            else -> {
                when (context.resources.configuration.uiMode.and(Configuration.UI_MODE_NIGHT_MASK)) {
                    Configuration.UI_MODE_NIGHT_YES -> true
                    else -> false
                }
            }
        }
        return if (systemNightMode) {
            retrieveEquivalentNightStyle(
                context,
                styleString ?: context.getString(R.string.list_style_name_night)
            )
        } else {
            retrieveEquivalentLightStyle(
                context,
                styleString ?: context.getString(R.string.list_style_name_light)
            )
        }
    }

    fun retrieveEquivalentLightStyle(context: Context, styleString: String): String {
        return when (styleString) {
            context.getString(R.string.list_style_name_dynamic_night) -> context.getString(R.string.list_style_name_dynamic_light)
            context.getString(R.string.list_style_name_night) -> context.getString(R.string.list_style_name_light)
            context.getString(R.string.list_style_name_black) -> context.getString(R.string.list_style_name_white)
            context.getString(R.string.list_style_name_dark) -> context.getString(R.string.list_style_name_clear)
            context.getString(R.string.list_style_name_simple_night) -> context.getString(R.string.list_style_name_simple)
            context.getString(R.string.list_style_name_blue_night) -> context.getString(R.string.list_style_name_blue)
            context.getString(R.string.list_style_name_red_night) -> context.getString(R.string.list_style_name_red)
            context.getString(R.string.list_style_name_reply_night) -> context.getString(R.string.list_style_name_reply)
            context.getString(R.string.list_style_name_purple_dark) -> context.getString(R.string.list_style_name_purple)
            else -> styleString
        }
    }

    private fun retrieveEquivalentNightStyle(context: Context, styleString: String): String {
        return when (styleString) {
            context.getString(R.string.list_style_name_dynamic_light) -> context.getString(R.string.list_style_name_dynamic_night)
            context.getString(R.string.list_style_name_light) -> context.getString(R.string.list_style_name_night)
            context.getString(R.string.list_style_name_white) -> context.getString(R.string.list_style_name_black)
            context.getString(R.string.list_style_name_clear) -> context.getString(R.string.list_style_name_dark)
            context.getString(R.string.list_style_name_simple) -> context.getString(R.string.list_style_name_simple_night)
            context.getString(R.string.list_style_name_blue) -> context.getString(R.string.list_style_name_blue_night)
            context.getString(R.string.list_style_name_red) -> context.getString(R.string.list_style_name_red_night)
            context.getString(R.string.list_style_name_reply) -> context.getString(R.string.list_style_name_reply_night)
            context.getString(R.string.list_style_name_purple) -> context.getString(R.string.list_style_name_purple_dark)
            else -> styleString
        }
    }

    fun defaultStyle(context: Context): String {
        return context.getString(R.string.list_style_name_light)
    }

    /**
     * Assign the style to the class attribute
     * @param styleString Style id String
     */
    fun assignStyle(context: Context, styleString: String) {
        PreferencesUtil.setStyle(context, styleString)
    }

    fun isDynamic(context: Context): Boolean {
        return DynamicColors.isDynamicColorAvailable() && (
                themeString == context.getString(R.string.list_style_name_dynamic_night)
                        || themeString == context.getString(R.string.list_style_name_dynamic_light)
                )
    }

    /**
     * Function that returns the current id of the style selected in the preference
     * @param context Context to retrieve the id
     * @return Id of the style
     */
    @StyleRes
    fun getThemeId(context: Context): Int {
        return when (retrieveEquivalentSystemStyle(context, themeString)) {
            context.getString(R.string.list_style_name_night) -> R.style.KeePassPNStyle_Night
            context.getString(R.string.list_style_name_white) -> R.style.KeePassPNStyle_White
            context.getString(R.string.list_style_name_black) -> R.style.KeePassPNStyle_Black
            context.getString(R.string.list_style_name_clear) -> R.style.KeePassPNStyle_Clear
            context.getString(R.string.list_style_name_dark) -> R.style.KeePassPNStyle_Dark
            context.getString(R.string.list_style_name_simple) -> R.style.KeePassPNStyle_Simple
            context.getString(R.string.list_style_name_simple_night) -> R.style.KeePassPNStyle_Simple_Night
            context.getString(R.string.list_style_name_blue) -> R.style.KeePassPNStyle_Blue
            context.getString(R.string.list_style_name_blue_night) -> R.style.KeePassPNStyle_Blue_Night
            context.getString(R.string.list_style_name_red) -> R.style.KeePassPNStyle_Red
            context.getString(R.string.list_style_name_red_night) -> R.style.KeePassPNStyle_Red_Night
            context.getString(R.string.list_style_name_reply) -> R.style.KeePassPNStyle_Reply
            context.getString(R.string.list_style_name_reply_night) -> R.style.KeePassPNStyle_Reply_Night
            context.getString(R.string.list_style_name_purple) -> R.style.KeePassPNStyle_Purple
            context.getString(R.string.list_style_name_purple_dark) -> R.style.KeePassPNStyle_Purple_Dark
            context.getString(R.string.list_style_name_dynamic_light) -> R.style.KeePassPNStyle_Light_Dynamic
            context.getString(R.string.list_style_name_dynamic_night) -> R.style.KeePassPNStyle_Night_Dynamic
            else -> R.style.KeePassPNStyle_Light
        }
    }
}
