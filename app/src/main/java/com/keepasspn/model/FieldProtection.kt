package com.keepasspn.model

import android.os.Parcelable
import com.keepasspn.database.element.Field
import kotlinx.parcelize.Parcelize

/**
 * Wrapped class for field to manage the current protection state in views
 */
@Parcelize
data class FieldProtection(
    val field: Field,
    var isRevealed: Boolean,
    var needUserVerificationToReveal: Boolean = true
): Parcelable