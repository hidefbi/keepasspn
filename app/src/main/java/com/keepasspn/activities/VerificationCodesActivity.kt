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
package com.keepasspn.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.keepasspn.R
import com.keepasspn.activities.legacy.DatabaseLockActivity
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.model.EntryInfo
import com.keepasspn.otp.OtpElement
import com.keepasspn.timeout.timeoutCopyToClipboard
import com.keepasspn.view.OtpDisplayView

/**
 * Lists every entry of the database that has a one-time password (TOTP),
 * whatever group it belongs to. Tapping a row copies the current code.
 */
class VerificationCodesActivity : DatabaseLockActivity() {

    private var entries: List<EntryInfo> = emptyList()
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: View
    private val adapter = VerificationCodesAdapter()

    override fun manageDatabaseInfo(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_verification_codes)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        listView = findViewById(R.id.verification_codes_list)
        listView.adapter = adapter
        emptyView = findViewById(R.id.verification_codes_empty_container)
        findViewById<Button>(R.id.verification_codes_import).setOnClickListener { ImportOtpPrompt.show(this) }
    }

    override fun viewToInvalidateTimeout(): View? {
        return findViewById(R.id.verification_codes_coordinator)
    }

    override fun finishActivityIfReloadRequested(): Boolean = true

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        entries = database.getEntryInfosWithOtp()
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.verification_codes, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.menu_import_otp -> {
                ImportOtpPrompt.show(this)
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        // Drop the secrets kept in memory for the list
        entries.forEach { it.otpModel?.clear() }
        super.onDestroy()
    }

    private fun copyCode(entryInfo: EntryInfo) {
        entryInfo.otpModel?.let { otpModel ->
            val token = OtpElement(otpModel).token
            timeoutCopyToClipboard(
                label = getString(R.string.entry_otp),
                value = token,
                sensitive = true
            )
        }
    }

    private inner class VerificationCodesAdapter : RecyclerView.Adapter<VerificationCodeViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerificationCodeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_verification_code, parent, false)
            return VerificationCodeViewHolder(view)
        }

        override fun onBindViewHolder(holder: VerificationCodeViewHolder, position: Int) {
            val entryInfo = entries[position]
            holder.title.text = entryInfo.title.ifEmpty { entryInfo.url }
            holder.username.text = entryInfo.username
            holder.otp.setOtpModel(entryInfo.otpModel)
            holder.itemView.setOnClickListener { copyCode(entryInfo) }
        }

        override fun getItemCount(): Int = entries.size
    }

    private class VerificationCodeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.item_verification_code_title)
        val username: TextView = view.findViewById(R.id.item_verification_code_username)
        val otp: OtpDisplayView = view.findViewById(R.id.item_verification_code_otp)
    }
}
