package io.hotmic.core.example

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.hotmic.core.HotMicClient
import io.hotmic.core.HotMicStreamQuery
import io.hotmic.core.LogLevel
import io.hotmic.core.example.databinding.ActivityStreamsBinding
import io.hotmic.core.example.databinding.ItemStreamBinding
import io.hotmic.core.models.HotMicStreamPage
import io.hotmic.core.models.HotMicStreamSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lists live, scheduled, and VOD streams for the credentials saved in Settings.
 * Tapping a stream opens [StreamSessionActivity].
 */
class StreamsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStreamsBinding
    private val adapter = StreamAdapter { summary -> openSession(summary) }

    private var credentials = Credentials(apiKey = "", accessToken = "")
    private var client: HotMicClient? = null
    private val streams = mutableListOf<HotMicStreamSummary>()
    private var pagination: HotMicStreamPage.Pagination? = null
    private var loadJob: Job? = null

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                reloadCredentials()
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.streamList.layoutManager = LinearLayoutManager(this)
        binding.streamList.adapter = adapter
        binding.streamList.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL),
        )
        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.loadMore.setOnClickListener { loadNextPage() }
        listOf(binding.includesLive, binding.includesScheduled, binding.includesVod).forEach { box ->
            box.setOnCheckedChangeListener { _, _ -> refresh() }
        }

        reloadCredentials()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_streams, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_refresh -> {
            refresh()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Rebuild the client when the saved credentials change. */
    private fun reloadCredentials() {
        val next = CredentialStore.load(this)
        if (next != credentials || client == null) {
            credentials = next
            client = if (next.isComplete) {
                HotMicClient(
                    context = applicationContext,
                    apiKey = next.apiKey,
                    accessToken = next.accessToken,
                    logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.OFF,
                )
            } else {
                null
            }
        }
        binding.credentialsSummary.text = getString(
            R.string.credentials_summary,
            maskSecret(next.apiKey),
            maskSecret(next.accessToken),
        )
    }

    private fun refresh() = load(page = 1, replacing = true)

    private fun loadNextPage() {
        val current = pagination ?: return
        if (!current.hasNext) return
        load(page = current.currentPage + 1, replacing = false)
    }

    private fun load(page: Int, replacing: Boolean) {
        val client = client
        if (client == null) {
            loadJob?.cancel()
            streams.clear()
            pagination = null
            adapter.submit(emptyList())
            binding.status.text = getString(R.string.credentials_missing)
            binding.loadMore.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            return
        }

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.status.text = getString(R.string.streams_loading)
            binding.loadMore.isEnabled = false
            try {
                val query = HotMicStreamQuery(
                    includesLive = binding.includesLive.isChecked,
                    includesScheduled = binding.includesScheduled.isChecked,
                    includesVOD = binding.includesVod.isChecked,
                    page = page,
                    limit = 20,
                )
                val result = client.fetchStreams(query)

                if (replacing) streams.clear()
                result.streams.forEach { summary ->
                    val index = streams.indexOfFirst { it.id == summary.id }
                    if (index >= 0) streams[index] = summary else streams += summary
                }
                pagination = result.pagination
                adapter.submit(streams.toList())

                binding.status.text = if (streams.isEmpty()) {
                    getString(R.string.streams_empty)
                } else {
                    getString(
                        R.string.streams_count,
                        streams.size,
                        result.pagination.currentPage,
                        result.pagination.totalPages,
                    )
                }
                binding.loadMore.visibility =
                    if (result.pagination.hasNext) View.VISIBLE else View.GONE
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                binding.status.text = getString(R.string.error_format, error.displayMessage())
            } finally {
                binding.swipeRefresh.isRefreshing = false
                binding.loadMore.isEnabled = true
            }
        }
    }

    private fun openSession(summary: HotMicStreamSummary) {
        startActivity(
            Intent(this, StreamSessionActivity::class.java)
                .putExtra(StreamSessionActivity.EXTRA_STREAM_ID, summary.id)
                .putExtra(StreamSessionActivity.EXTRA_STREAM_TITLE, summary.title ?: summary.id),
        )
    }
}

private class StreamAdapter(
    private val onClick: (HotMicStreamSummary) -> Unit,
) : RecyclerView.Adapter<StreamAdapter.Holder>() {
    private var items: List<HotMicStreamSummary> = emptyList()

    fun submit(streams: List<HotMicStreamSummary>) {
        items = streams
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemStreamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        private val binding: ItemStreamBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: HotMicStreamSummary, onClick: (HotMicStreamSummary) -> Unit) {
            val context = binding.root.context
            binding.title.text = summary.title ?: summary.id
            binding.meta.text = context.getString(
                R.string.stream_meta,
                summary.state.rawValue,
                summary.type.rawValue,
                summary.viewers,
            )
            binding.host.text = summary.user.displayName ?: summary.user.id
            binding.root.setOnClickListener { onClick(summary) }
        }
    }
}
