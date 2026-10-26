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
package com.keepasspn.activities.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.keepasspn.R
import com.keepasspn.activities.IconPickerActivity
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.icons.brand.BrandIcon
import com.keepasspn.icons.brand.BrandIconRenderer
import com.keepasspn.icons.brand.BrandIconRepository
import com.keepasspn.icons.brand.BrandIconStore
import com.keepasspn.viewmodels.IconPickerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the brand icon matching the web address of the entry being edited.
 * Selecting it stores the drawn brand as a custom icon of the database.
 */
class IconBrandFragment : DatabaseFragment() {

    private val iconPickerViewModel: IconPickerViewModel by activityViewModels()

    private var database: ContextualDatabase? = null
    private var brandIcon: BrandIcon? = null

    private lateinit var emptyView: TextView
    private lateinit var cardView: View
    private lateinit var imageView: ImageView
    private lateinit var nameView: TextView

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_icon_brand, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.icon_brand_empty)
        cardView = view.findViewById(R.id.icon_brand_card)
        imageView = view.findViewById(R.id.icon_brand_image)
        nameView = view.findViewById(R.id.icon_brand_name)
        cardView.setOnClickListener { selectBrandIcon() }
        resetAppTimeoutWhenViewFocusedOrChanged(view)
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        this.database = database
        val webDomain = activity?.intent?.getStringExtra(IconPickerActivity.EXTRA_WEB_DOMAIN)
        if (webDomain.isNullOrBlank()) {
            showNoBrandIcon()
            return
        }
        lifecycleScope.launch {
            val icon = BrandIconRepository.brandIconForWebDomain(requireContext(), webDomain)
            brandIcon = icon
            if (icon == null) {
                showNoBrandIcon()
            } else {
                showBrandIcon(icon)
            }
        }
    }

    private fun showNoBrandIcon() {
        emptyView.visibility = View.VISIBLE
        cardView.visibility = View.GONE
    }

    /** Card background and text color of the current theme, used when a brand color is too dark */
    private fun themeColors(): Pair<Int?, Int?> {
        val view = view ?: return Pair(null, null)
        return Pair(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface),
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        )
    }

    private fun showBrandIcon(icon: BrandIcon) {
        emptyView.visibility = View.GONE
        cardView.visibility = View.VISIBLE
        nameView.text = icon.title
        val (background, foreground) = themeColors()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                BrandIconRenderer.toBitmap(icon, PREVIEW_SIZE_PX, background, foreground)
            }
            imageView.setImageBitmap(bitmap)
        }
    }

    private fun selectBrandIcon() {
        val icon = brandIcon ?: return
        val database = database ?: return
        cardView.isEnabled = false
        val (background, foreground) = themeColors()
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                BrandIconStore.findOrStore(database, icon, background, foreground)
            }
            cardView.isEnabled = true
            if (stored == null) {
                iconPickerViewModel.addCustomIcon(
                    IconPickerViewModel.IconCustomState(null, true, R.string.error_upload_file)
                )
                return@launch
            }
            // An icon stored by an earlier selection is already in the custom list
            if (stored.created) {
                iconPickerViewModel.addCustomIcon(
                    IconPickerViewModel.IconCustomState(stored.icon, false)
                )
            }
            iconPickerViewModel.pickCustomIcon(stored.icon)
        }
    }

    companion object {
        private const val PREVIEW_SIZE_PX = 96
    }
}
