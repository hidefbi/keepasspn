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
package com.keepasspn.activities.dialogs

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.keepasspn.R
import com.keepasspn.database.element.GroupId

/**
 * Lets the user choose the destination folder of a node, with a search field.
 * The user confirms the move before it happens.
 */
object MoveDestinationDialog {

    class Destination(
        val groupId: GroupId,
        val title: String,
        val path: String,
        val depth: Int,
        val enabled: Boolean
    )

    fun show(
        activity: Activity,
        title: String,
        nodeTitle: String,
        destinations: List<Destination>,
        onSelected: (GroupId, String) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.search)
            setSingleLine(true)
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val adapter = DestinationAdapter(activity, destinations)
        val list = ListView(activity).apply {
            this.adapter = adapter
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(search)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (400 * density).toInt()))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        list.setOnItemClickListener { _, _, position, _ ->
            val destination = adapter.getItem(position)
            if (!destination.enabled) return@setOnItemClickListener
            AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.move_nodes_confirm, nodeTitle, destination.path))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.move_confirm) { _, _ ->
                    dialog.dismiss()
                    onSelected(destination.groupId, destination.path)
                }
                .show()
        }
        dialog.show()
    }

    private class DestinationAdapter(
        private val context: Context,
        private val all: List<Destination>
    ) : BaseAdapter() {

        private var items: List<Destination> = all

        fun filter(query: String) {
            items = if (query.isBlank()) {
                all
            } else {
                all.filter {
                    it.title.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true)
                }
            }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Destination = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun isEnabled(position: Int): Boolean = items[position].enabled

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val destination = items[position]
            val view = (convertView as? TextView) ?: TextView(context).apply {
                textSize = 16f
                val rowPadding = (16 * context.resources.displayMetrics.density).toInt()
                setPadding(rowPadding, rowPadding / 2, rowPadding, rowPadding / 2)
            }
            view.text = "    ".repeat(destination.depth) + destination.title
            view.isEnabled = destination.enabled
            view.alpha = if (destination.enabled) 1f else 0.4f
            return view
        }
    }
}
