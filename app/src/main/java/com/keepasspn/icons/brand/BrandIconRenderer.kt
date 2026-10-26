/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 * KeePassPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * KeePassPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.keepasspn.icons.brand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

object BrandIconRenderer {

    /** Simple Icons paths use a 24 x 24 viewBox */
    private const val VIEW_BOX = 24f

    /** Below this contrast the brand color is hard to see, so the fallback color is used */
    private const val MIN_CONTRAST = 3.0

    /**
     * Draws the brand path in its brand color on a transparent bitmap.
     * When a background is given and the brand color is too close to it
     * (for example a near-black logo on a dark theme), the fallback color is used instead.
     */
    fun toBitmap(icon: BrandIcon, sizePx: Int, background: Int? = null, fallbackColor: Int? = null): Bitmap? {
        if (sizePx <= 0) return null
        val path: Path = try {
            PathParser.createPathFromPathData(icon.pathData)
        } catch (e: Exception) {
            return null
        }
        val brandColor = try {
            Color.parseColor("#" + icon.hexColor)
        } catch (e: IllegalArgumentException) {
            Color.BLACK
        }
        val color = if (background != null && fallbackColor != null
                && ColorUtils.calculateContrast(brandColor, background) < MIN_CONTRAST) {
            fallbackColor
        } else {
            brandColor
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = sizePx / VIEW_BOX
        val matrix = Matrix().apply { setScale(scale, scale) }
        path.transform(matrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        canvas.drawPath(path, paint)
        return bitmap
    }
}
