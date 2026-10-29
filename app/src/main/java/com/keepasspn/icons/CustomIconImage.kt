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
package com.keepasspn.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/**
 * Turns a picture chosen by the user into the icon stored in the database.
 *
 * It is stored the same way as a recognized brand: a PNG of [STORED_SIZE_PX] pixels,
 * so it stays sharp in every list, and cropped to a circle, so it fits the round
 * frame of an entry icon instead of showing square corners.
 */
object CustomIconImage {

    /** Same size as the brand icons, so both kinds of icon are equally sharp */
    const val STORED_SIZE_PX = 128

    /**
     * Crops [source] to its centered square, scales it to [STORED_SIZE_PX] and keeps
     * only the inscribed circle. Pixels outside the circle are transparent.
     */
    fun toCircle(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side
        )
        val scaled = Bitmap.createScaledBitmap(square, STORED_SIZE_PX, STORED_SIZE_PX, true)
        val output = Bitmap.createBitmap(STORED_SIZE_PX, STORED_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val bounds = RectF(0f, 0f, STORED_SIZE_PX.toFloat(), STORED_SIZE_PX.toFloat())
        canvas.drawOval(bounds, circlePaint)
        circlePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, circlePaint)
        return output
    }
}
