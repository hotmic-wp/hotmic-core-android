package io.hotmic.core.publicsample

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** README "Example": "Add your API key and access token in the Settings screen." */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AppConfig.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        root.addView(TextView(this).apply { text = "API key" })
        val apiKey = EditText(this).apply { setText(config.apiKey) }
        root.addView(apiKey)
        root.addView(TextView(this).apply { text = "Access token (HS256 JWT minted by your backend)" })
        val token = EditText(this).apply { setText(config.accessToken) }
        root.addView(token)
        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                AppConfig.save(this@SettingsActivity, apiKey.text.toString(), token.text.toString())
                finish()
            }
        })
        setContentView(root)
    }
}
