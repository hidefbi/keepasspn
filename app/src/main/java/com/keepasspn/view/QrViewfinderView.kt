/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Dims the camera preview outside a centered square, and draws the corners of that square.
 * The square is [VIEWFINDER_FRACTION] of the view width.
 */
class QrViewfinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = Color.argb(0x99, 0, 0, 0) }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val cornerLength = 28 * density
    private val frame = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = w * VIEWFINDER_FRACTION
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        frame.set(left, top, left + side, top + side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        canvas.drawRect(0f, 0f, viewWidth, frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, viewWidth, viewHeight, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, viewWidth, frame.bottom, dimPaint)

        val l = frame.left
        val t = frame.top
        val r = frame.right
        val b = frame.bottom
        val c = cornerLength
        canvas.drawLine(l, t, l + c, t, cornerPaint)
        canvas.drawLine(l, t, l, t + c, cornerPaint)
        canvas.drawLine(r, t, r - c, t, cornerPaint)
        canvas.drawLine(r, t, r, t + c, cornerPaint)
        canvas.drawLine(l, b, l + c, b, cornerPaint)
        canvas.drawLine(l, b, l, b - c, cornerPaint)
        canvas.drawLine(r, b, r - c, b, cornerPaint)
        canvas.drawLine(r, b, r, b - c, cornerPaint)
    }

    companion object {
        const val VIEWFINDER_FRACTION = 0.7f
    }
}
