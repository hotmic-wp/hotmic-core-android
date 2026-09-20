package io.hotmic.core.publicsample

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.hotmic.core.HotMicClient
import io.hotmic.core.HotMicStreamQuery
import io.hotmic.core.LogLevel
import io.hotmic.core.models.HotMicStreamSummary
import kotlinx.coroutines.launch

/**
 * README "Get Streams": HotMicClient + HotMicStreamQuery with every field exposed as a control,
 * plus fetchStream / fetchUser by id. Launch extra `HEM96_AUTORUN=N4,N5,…` hands off to [QaRunner].
 */
class StreamsActivity : AppCompatActivity() {
    private lateinit var config: AppConfig
    private var page = 1
    private var streams: List<HotMicStreamSummary> = emptyList()
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var live: CheckBox
    private lateinit var scheduled: CheckBox
    private lateinit var vod: CheckBox
    private lateinit var userId: EditText
    private lateinit var limit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig.load(this)
        Hem96.init(applicationContext, listOf(config.apiKey, config.accessToken))

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this)
        root.addView(status)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        live = CheckBox(this).apply { text = "Live"; isChecked = true }
        scheduled = CheckBox(this).apply { text = "Scheduled"; isChecked = true }
        vod = CheckBox(this).apply { text = "VOD"; isChecked = true }
        filters.addView(live); filters.addView(scheduled); filters.addView(vod)
        root.addView(filters)
        val params = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        userId = EditText(this).apply { hint = "userId (optional)" }
        limit = EditText(this).apply { hint = "limit"; setText("20") }
        params.addView(userId, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        params.addView(limit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(params)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply { text = "Fetch"; setOnClickListener { page = 1; fetch() } })
        buttons.addView(Button(this).apply { text = "Prev"; setOnClickListener { if (page > 1) { page--; fetch() } } })
        buttons.addView(Button(this).apply { text = "Next"; setOnClickListener { page++; fetch() } })
        buttons.addView(Button(this).apply { text = "Settings"; setOnClickListener { startActivity(Intent(this@StreamsActivity, SettingsActivity::class.java)) } })
        root.addView(buttons)
        val byId = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val idInput = EditText(this).apply { hint = "stream or user id" }
        byId.addView(idInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
        byId.addView(Button(this).apply { text = "Stream"; setOnClickListener { fetchStream(idInput.text.toString().trim()) } })
        byId.addView(Button(this).apply { text = "User"; setOnClickListener { fetchUser(idInput.text.toString().trim()) } })
        root.addView(byId)
        list = ListView(this)
        list.setOnItemClickListener { _, _, position, _ ->
            val stream = streams[position]
            startActivity(
                Intent(this, SessionActivity::class.java)
                    .putExtra(SessionActivity.EXTRA_STREAM_ID, stream.id)
                    .putExtra(SessionActivity.EXTRA_TITLE, stream.title ?: stream.id),
            )
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // N7 media rows: SessionActivity is not exported, so the launcher forwards the media extras.
        val media = intent.getStringExtra("HEM96_MEDIA")
        if (media != null) {
            val forward = Intent(this, SessionActivity::class.java).putExtras(intent.extras ?: Bundle())
            forward.putExtra(SessionActivity.EXTRA_STREAM_ID, intent.getStringExtra("HEM96_STREAM_ID").orEmpty())
            forward.putExtra(SessionActivity.EXTRA_TITLE, "N7-$media")
            startActivity(forward)
            status.text = "N7 $media — see logcat HEM96"
            return
        }
        val autorun = intent.getStringExtra("HEM96_AUTORUN")
        if (autorun != null) {
            Hem96.log("AUTORUN start rows=$autorun")
            QaRunner.start(applicationContext, config, autorun, intent.extras)
            status.text = "AUTORUN $autorun — see logcat HEM96"
            return
        }
        if (!config.isComplete) {
            status.text = "Add your API key and access token in Settings."
        } else {
            fetch()
        }
    }

    override fun onResume() {
        super.onResume()
        config = AppConfig.load(this)
    }

    private fun client() = HotMicClient(
        context = applicationContext,
        apiKey = config.apiKey,
        accessToken = config.accessToken,
        logLevel = LogLevel.DEBUG,
    )

    private fun fetch() {
        if (!config.isComplete) { status.text = "Add credentials in Settings."; return }
        val query = HotMicStreamQuery(
            includesLive = live.isChecked,
            includesScheduled = scheduled.isChecked,
            includesVOD = vod.isChecked,
            userId = userId.text.toString().trim().ifEmpty { null },
            page = page,
            limit = limit.text.toString().toIntOrNull() ?: 20,
        )
        status.text = "Loading…"
        lifecycleScope.launch {
            try {
                val result = client().fetchStreams(query)
                streams = result.streams
                val p = result.pagination
                status.text = "${result.streams.size} streams · page ${p.currentPage}/${p.totalPages} · total ${p.totalCount} · hasNext=${p.hasNext}"
                list.adapter = ArrayAdapter(
                    this@StreamsActivity, android.R.layout.simple_list_item_2, android.R.id.text1,
                    streams.map { "${it.title ?: it.id}\n${it.state.rawValue} · ${it.type.rawValue} · ${it.user.displayName ?: it.user.id.take(8)}" },
                )
            } catch (error: Exception) {
                status.text = "fetchStreams failed: ${error.label()}"
            }
        }
    }

    private fun fetchStream(id: String) {
        lifecycleScope.launch {
            status.text = try {
                val s = client().fetchStream(id)
                "stream ${s.id.take(8)} ${s.state.rawValue} hls=${s.hlsUrl != null} vod=${s.vodUrl != null} title=${s.title}"
            } catch (error: Exception) { "fetchStream failed: ${error.label()}" }
        }
    }

    private fun fetchUser(id: String) {
        lifecycleScope.launch {
            status.text = try {
                val u = client().fetchUser(id)
                "user ${u.id.take(8)} ${u.displayName} role=${u.role?.rawValue} blocked=${u.blockedUsers.size}"
            } catch (error: Exception) { "fetchUser failed: ${error.label()}" }
        }
    }
}
