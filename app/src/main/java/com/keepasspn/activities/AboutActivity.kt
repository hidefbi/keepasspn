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
package com.keepasspn.activities

import android.content.pm.PackageManager.NameNotFoundException
import android.os.Bundle
import android.text.method.LinkMovementMethod
import com.keepasspn.timeout.copyToClipboard
import com.keepasspn.utils.NoOpLog
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.text.HtmlCompat
import com.keepasspn.R
import com.keepasspn.activities.stylish.StylishActivity
import com.keepasspn.utils.getPackageInfoCompat

class AboutActivity : StylishActivity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_about)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.about)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // The build has no separate paid tier, so the plain app name is always shown.
        val appName = getString(R.string.app_name)
        findViewById<TextView>(R.id.activity_about_app_name).text = appName

        var version: String
        try {
            version = packageManager.getPackageInfoCompat(packageName).versionName ?: ""
        } catch (e: NameNotFoundException) {
            NoOpLog.w(javaClass.simpleName, "Unable to get the app version", e)
            version = "Unable to get the app version"
        }

        version = getString(R.string.version_label, version)
        val versionTextView = findViewById<TextView>(R.id.activity_about_version)
        versionTextView.text = version

        bindDonationAddress(R.id.activity_about_donation_btc, R.id.activity_about_donation_btc_address, "BTC", R.string.donation_btc_address)
        bindDonationAddress(R.id.activity_about_donation_eth, R.id.activity_about_donation_eth_address, "ETH", R.string.donation_eth_address)
        bindDonationAddress(R.id.activity_about_donation_xmr, R.id.activity_about_donation_xmr_address, "XMR", R.string.donation_xmr_address)
    }

    /**
     * Show a donation address and copy it to the clipboard when its row is tapped.
     * Addresses are public, so they are not marked as sensitive.
     */
    private fun bindDonationAddress(rowId: Int, addressId: Int, coin: String, addressResId: Int) {
        val address = getString(addressResId)
        findViewById<TextView>(addressId).text = address
        findViewById<View>(rowId).setOnClickListener { copyToClipboard(coin, address, sensitive = false) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        when (item.itemId) {
            android.R.id.home -> finish()
        }
        return super.onOptionsItemSelected(item)
    }
}
