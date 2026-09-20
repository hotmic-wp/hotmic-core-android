package io.hotmic.core.publicsample

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * Credentials and QA parameters.
 *
 * Priority: values saved on the Settings screen, then `files/hem96.env` (pushed by
 * `tools/push-creds.sh` with `adb push` + `run-as`, so nothing is baked into the APK or passed on
 * the command line where `ps`/logcat could see it). Production hosts mint short-lived tokens on
 * their backend; this sample only keeps them in app-private storage for QA.
 */
class AppConfig private constructor(private val values: Map<String, String>) {
    val apiKey: String get() = values["HOTMIC_CORE_API_KEY"].orEmpty()
    val accessToken: String get() = values["HOTMIC_CORE_ACCESS_TOKEN"].orEmpty()
    val liveStreamId: String get() = values["HOTMIC_CORE_LIVE_STREAM_ID"].orEmpty()
    val vodStreamId: String get() = values["HEM96_VOD_STREAM_ID"].orEmpty()
    val testUserId: String get() = values["HOTMIC_CORE_TEST_USER_ID"].orEmpty()
    val clientBUserId: String get() = values["HEM96_CLIENT_B_USER_ID"].orEmpty()
    val isComplete: Boolean get() = apiKey.isNotBlank() && accessToken.isNotBlank()
    operator fun get(key: String): String? = values[key]

    companion object {
        private const val PREFS = "public_sample"

        fun load(context: Context): AppConfig {
            val map = mutableMapOf<String, String>()
            val env = File(context.filesDir, "hem96.env")
            if (env.exists()) {
                env.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val eq = line.indexOf('=')
                    if (eq > 0) map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim().trim('"', '\'')
                }
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString("apiKey", null)?.takeIf { it.isNotBlank() }?.let { map["HOTMIC_CORE_API_KEY"] = it }
            prefs.getString("accessToken", null)?.takeIf { it.isNotBlank() }?.let { map["HOTMIC_CORE_ACCESS_TOKEN"] = it }
            return AppConfig(map)
        }

        fun save(context: Context, apiKey: String, accessToken: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("apiKey", apiKey.trim())
                putString("accessToken", accessToken.trim())
            }
        }
    }
}
