package com.keepasspn.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.keepasspn.R
import com.keepasspn.activities.fragments.KeyGeneratorFragment
import com.keepasspn.activities.legacy.DatabaseLockActivity
import com.keepasspn.settings.PreferencesUtil
import com.keepasspn.view.updateButtonPaddingStart
import com.keepasspn.viewmodels.KeyGeneratorViewModel
import kotlinx.coroutines.launch

class KeyGeneratorActivity : DatabaseLockActivity() {

    private lateinit var toolbar: Toolbar
    private lateinit var coordinatorLayout: CoordinatorLayout
    private lateinit var validationButton: View
    private var lockView: View? = null
    private var isStandalone: Boolean = false
    private var isUsernameOnly: Boolean = false

    override fun manageDatabaseInfo(): Boolean = true

    private val keyGeneratorViewModel: KeyGeneratorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_key_generator)

        isStandalone = intent.getBooleanExtra(STANDALONE_EXTRA, false)
        isUsernameOnly = intent.getBooleanExtra(USERNAME_ONLY_EXTRA, false)
        keyGeneratorViewModel.isStandalone = isStandalone

        toolbar = findViewById(R.id.toolbar)
        toolbar.title = " "
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        coordinatorLayout = findViewById(R.id.key_generator_coordinator)

        lockView = findViewById(R.id.lock_button)
        lockView?.setOnClickListener {
            lockAndExit()
        }

        validationButton = findViewById(R.id.key_generator_validation)
        validationButton.setOnClickListener {
            keyGeneratorViewModel.validateKeyGenerated()
        }
        // Nothing to hand back to an entry when opened on its own
        validationButton.visibility = if (isStandalone) View.GONE else View.VISIBLE

        supportFragmentManager.commit {
            replace(R.id.key_generator_fragment, KeyGeneratorFragment.getInstance(
                    // Default selection tab
                    KeyGeneratorFragment.KeyGeneratorTab.PASSWORD,
                    isStandalone,
                    isUsernameOnly
                ), KEY_GENERATED_FRAGMENT_TAG
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                keyGeneratorViewModel.keyGenerated.collect { keyGenerated ->
                    keyGenerated?.let {
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(KEY_GENERATED, keyGenerated)
                        })
                        finish()
                    }
                }
            }
        }
    }

    override fun viewToInvalidateTimeout(): View? {
        return findViewById<ViewGroup>(R.id.key_generator_container)
    }

    override fun onResume() {
        super.onResume()

        // Show the lock button
        lockView?.visibility = if (PreferencesUtil.showLockDatabaseButton(this)) {
            View.VISIBLE
        } else {
            View.GONE
        }

        // Padding if lock button visible
        toolbar.updateButtonPaddingStart()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.key_generator, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onDatabaseBackPressed()
            }
            R.id.menu_generate -> {
                keyGeneratorViewModel.requireKeyGeneration()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDatabaseBackPressed() {
        setResult(RESULT_CANCELED, Intent())
        super.onDatabaseBackPressed()
    }

    companion object {
        private const val KEY_GENERATED = "KEY_GENERATED"
        private const val KEY_GENERATED_FRAGMENT_TAG = "KEY_GENERATED_FRAGMENT_TAG"
        private const val STANDALONE_EXTRA = "STANDALONE_EXTRA"
        private const val USERNAME_ONLY_EXTRA = "USERNAME_ONLY_EXTRA"

        fun registerForGeneratedKeyResult(
            activity: FragmentActivity,
            keyGeneratedListener: (CharArray?) -> Unit
        ): ActivityResultLauncher<Intent> {
            return activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    keyGeneratedListener.invoke(
                        result.data?.getCharArrayExtra(KEY_GENERATED)
                    )
                } else {
                    keyGeneratedListener.invoke(null)
                }
            }
        }

        fun launch(
            context: FragmentActivity,
            resultLauncher: ActivityResultLauncher<Intent>
        ) {
            // Create an instance to return the picker icon
            resultLauncher.launch(
                Intent(context, KeyGeneratorActivity::class.java)
            )
        }

        /**
         * Open the generator on its own, from the add menu. Nothing is returned to the caller.
         */
        fun launchStandalone(context: Context) {
            context.startActivity(
                Intent(context, KeyGeneratorActivity::class.java).apply {
                    putExtra(STANDALONE_EXTRA, true)
                }
            )
        }

        /**
         * Open the generator for a username field of an entry. Only the username tab is shown
         * and the generated value is returned to the caller.
         */
        fun launchForUsername(
            context: FragmentActivity,
            resultLauncher: ActivityResultLauncher<Intent>
        ) {
            resultLauncher.launch(
                Intent(context, KeyGeneratorActivity::class.java).apply {
                    putExtra(USERNAME_ONLY_EXTRA, true)
                }
            )
        }
    }
}