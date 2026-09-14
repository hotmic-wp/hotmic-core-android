package io.hotmic.core.example

import android.content.Context
import androidx.core.content.edit

/** HotMic API credentials entered in Settings (or prefilled from `local.properties`). */
data class Credentials(
    val apiKey: String,
    val accessToken: String,
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && accessToken.isNotBlank()
}

/**
 * Sample-only credential storage.
 *
 * Values entered on the Settings screen are kept in private SharedPreferences so they
 * survive relaunches during QA. If nothing has been saved, the optional `BuildConfig`
 * prefill (from the gitignored `local.properties`) is used; committed builds have empty
 * defaults. Production hosts should mint short-lived access tokens on their backend
 * rather than persisting them on the device.
 */
object CredentialStore {
    private const val PREFS = "hotmic_core_example"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ACCESS_TOKEN = "access_token"

    fun load(context: Context): Credentials {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Credentials(
            apiKey = prefs.getString(KEY_API_KEY, null) ?: BuildConfig.HOTMIC_API_KEY,
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: BuildConfig.HOTMIC_ACCESS_TOKEN,
        )
    }

    fun save(context: Context, credentials: Credentials) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_API_KEY, credentials.apiKey.trim())
            putString(KEY_ACCESS_TOKEN, credentials.accessToken.trim())
        }
    }
}
