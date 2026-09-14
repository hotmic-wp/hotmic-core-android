package io.hotmic.core.example

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.hotmic.core.example.databinding.ActivitySettingsBinding

/** Edit the API key and access token used to create the [io.hotmic.core.HotMicClient]. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val current = CredentialStore.load(this)
        binding.apiKey.setText(current.apiKey)
        binding.accessToken.setText(current.accessToken)
        binding.save.setOnClickListener { save() }
    }

    private fun save() {
        CredentialStore.save(
            this,
            Credentials(
                apiKey = binding.apiKey.text?.toString().orEmpty(),
                accessToken = binding.accessToken.text?.toString().orEmpty(),
            ),
        )
        Toast.makeText(this, R.string.credentials_saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
