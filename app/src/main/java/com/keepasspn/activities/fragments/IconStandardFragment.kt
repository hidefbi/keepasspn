/*
 * Copyright 2021 Jeremy Jamet / Kunzisoft.
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
package com.keepasspn.activities.fragments

import com.keepasspn.R
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.database.element.icon.IconImageStandard


class IconStandardFragment : IconFragment<IconImageStandard>() {

    override fun retrieveMainLayoutId(): Int {
        return R.layout.fragment_icon_grid
    }

    override fun defineIconList(database: ContextualDatabase?) {
        database?.doForEachStandardIcons { standardIcon ->
            iconPickerAdapter.addIcon(standardIcon, false)
        }
    }

    override fun onIconClickListener(icon: IconImageStandard) {
        iconPickerViewModel.pickStandardIcon(icon)
    }

    override fun onIconLongClickListener(icon: IconImageStandard) {}
}