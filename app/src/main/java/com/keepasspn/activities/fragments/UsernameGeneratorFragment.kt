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
package com.keepasspn.activities.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.Slider
import com.keepasspn.R
import com.keepasspn.database.ContextualDatabase
import com.keepasspn.password.UsernameGenerator
import com.keepasspn.timeout.timeoutCopyToClipboard
import com.keepasspn.viewmodels.KeyGeneratorViewModel
import kotlinx.coroutines.launch

class UsernameGeneratorFragment : DatabaseFragment() {

    private lateinit var usernameView: EditText
    private lateinit var lengthSlider: Slider
    private lateinit var lengthValueText: TextView
    private lateinit var lowercaseCheck: CheckBox
    private lateinit var uppercaseCheck: CheckBox
    private lateinit var digitsCheck: CheckBox

    private val mKeyGeneratorViewModel: KeyGeneratorViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_generate_username, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usernameView = view.findViewById(R.id.username_view)
        val usernameCopyView: ImageView? = view.findViewById(R.id.username_copy_button)
        lengthSlider = view.findViewById(R.id.slider_username_length)
        lengthValueText = view.findViewById(R.id.username_length_value)
        lowercaseCheck = view.findViewById(R.id.lowercase_filter)
        uppercaseCheck = view.findViewById(R.id.uppercase_filter)
        digitsCheck = view.findViewById(R.id.digits_filter)

        // A username is not a protected field, so the copy button is always available
        context?.let { context ->
            usernameCopyView?.setOnClickListener {
                context.timeoutCopyToClipboard(
                    label = getString(R.string.entry_user_name),
                    value = usernameView.text.toString(),
                    sensitive = false
                )
            }
        }

        // Set the range before the value, otherwise the slider rejects the value
        lengthSlider.valueTo = UsernameGenerator.MAX_LENGTH.toFloat()
        lengthSlider.valueFrom = UsernameGenerator.MIN_LENGTH.toFloat()
        lengthSlider.stepSize = 1f
        lengthSlider.value = UsernameGenerator.DEFAULT_LENGTH.toFloat()
        lengthValueText.text = UsernameGenerator.DEFAULT_LENGTH.toString()

        lengthSlider.addOnChangeListener { _, value, _ ->
            lengthValueText.text = value.toInt().toString()
            generateUsername()
        }
        lowercaseCheck.setOnCheckedChangeListener { _, _ -> generateUsername() }
        uppercaseCheck.setOnCheckedChangeListener { _, _ -> generateUsername() }
        digitsCheck.setOnCheckedChangeListener { _, _ -> generateUsername() }

        generateUsername()

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    mKeyGeneratorViewModel.requireUsernameGeneration.collect {
                        generateUsername()
                    }
                }
            }
        }

        resetAppTimeoutWhenViewFocusedOrChanged(view)
    }

    /**
     * Replace the result with a new random username built from the current settings.
     * Any manual edit in the result field is discarded by this.
     */
    private fun generateUsername() {
        if (!::usernameView.isInitialized) return
        usernameView.setText(
            UsernameGenerator.generate(
                length = lengthSlider.value.toInt(),
                lowercase = lowercaseCheck.isChecked,
                uppercase = uppercaseCheck.isChecked,
                digits = digitsCheck.isChecked
            )
        )
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        // Nothing here
    }
}
