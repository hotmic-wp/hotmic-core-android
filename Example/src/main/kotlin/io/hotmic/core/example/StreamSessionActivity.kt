package io.hotmic.core.example

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import io.hotmic.core.HotMicClient
import io.hotmic.core.HotMicStreamSession
import io.hotmic.core.LogLevel
import io.hotmic.core.example.databinding.ActivitySessionBinding
import io.hotmic.core.models.HotMicParticipant
import io.hotmic.core.models.HotMicPoll
import io.hotmic.core.models.HotMicStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Host-owned stream experience built on a [HotMicStreamSession]:
 * Media3 ExoPlayer on `hlsUrl` / `vodUrl`, chat, polls, blocking, and a clean stop.
 */
class StreamSessionActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySessionBinding
    private val chatAdapter = ChatAdapter()

    private var session: HotMicStreamSession? = null
    private var eventsJob: Job? = null
    private var player: ExoPlayer? = null

    private var stream: HotMicStream? = null
    private var connection: HotMicStreamSession.ConnectionState? = null
    private var participantCount = 0
    private val polls = linkedMapOf<String, HotMicPoll>()
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        val credentials = CredentialStore.load(this)
        supportActionBar?.title = intent.getStringExtra(EXTRA_STREAM_TITLE) ?: streamId

        if (streamId.isEmpty() || !credentials.isComplete) {
            Toast.makeText(this, R.string.missing_session_args, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        player = ExoPlayer.Builder(this).build().also { binding.playerView.player = it }

        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = chatAdapter
        binding.sendChat.setOnClickListener { sendChat() }
        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else {
                false
            }
        }
        binding.blockUser.setOnClickListener { blockOrUnblock(block = true) }
        binding.unblockUser.setOnClickListener { blockOrUnblock(block = false) }
        binding.stopSession.setOnClickListener { stopAndFinish() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = stopAndFinish()
            },
        )

        renderPolls()
        startSession(credentials, streamId)
    }

    // region Session lifecycle

    private fun startSession(credentials: Credentials, streamId: String) {
        val client = HotMicClient(
            context = applicationContext,
            apiKey = credentials.apiKey,
            accessToken = credentials.accessToken,
            logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.OFF,
        )
        val streamSession = client.makeStreamSession(streamId)
        session = streamSession

        // Start consuming events before start() so no lifecycle event is missed.
        eventsJob = lifecycleScope.launch {
            streamSession.events.collect { event -> handle(event) }
        }

        lifecycleScope.launch {
            binding.sessionStatus.text = getString(R.string.session_starting)
            try {
                val snapshot = streamSession.start()
                stream = snapshot.stream
                participantCount = snapshot.participants.count()
                chatAdapter.replaceAll(snapshot.chatMessages)
                scrollChatToEnd()
                snapshot.polls.forEach { polls[it.id] = it }
                renderPolls()
                renderStreamStatus()
                playIfNeeded(snapshot.stream)
                log("started as ${snapshot.user.displayName ?: snapshot.user.id}")
            } catch (error: Exception) {
                binding.sessionStatus.text = getString(R.string.session_failed, error.displayMessage())
                log("start failed: ${error.displayMessage()}")
            }
        }
    }

    private fun handle(event: HotMicStreamSession.Event) {
        when (event) {
            is HotMicStreamSession.Event.StateChanged -> {
                renderSessionStatus(event.state)
                log("state ${event.state.displayName()}")
            }
            is HotMicStreamSession.Event.ConnectionChanged -> {
                connection = event.connection
                session?.let { renderSessionStatus(it.state.value) }
                log("connection ${event.connection.displayName()}")
            }
            is HotMicStreamSession.Event.StreamUpdated -> {
                stream = event.stream
                renderStreamStatus()
                playIfNeeded(event.stream)
            }
            HotMicStreamSession.Event.StreamEnded -> {
                log("stream ended")
                player?.pause()
            }
            HotMicStreamSession.Event.StreamDeleted -> {
                // Core stops the session automatically after this event.
                log("stream deleted")
                player?.pause()
            }
            is HotMicStreamSession.Event.ChatBatchReceived -> {
                val added = chatAdapter.append(event.batch.messages)
                if (added > 0) scrollChatToEnd()
                if (event.batch.reactions.isNotEmpty()) {
                    log("${event.batch.reactions.size} chat reaction(s)")
                }
            }
            is HotMicStreamSession.Event.ChatMessageDeleted -> {
                chatAdapter.remove(event.id)
                log("chat deleted ${event.id}")
            }
            is HotMicStreamSession.Event.ChatMessageReactionDeleted -> {
                log("reaction removed from chat ${event.chatId}")
            }
            is HotMicStreamSession.Event.PollCreated -> {
                polls[event.poll.id] = event.poll
                renderPolls()
                log("poll created ${event.poll.id}")
            }
            is HotMicStreamSession.Event.PollUpdated -> {
                polls[event.poll.id] = event.poll
                renderPolls()
            }
            is HotMicStreamSession.Event.PollDeleted -> {
                polls.remove(event.id)
                renderPolls()
                log("poll deleted ${event.id}")
            }
            is HotMicStreamSession.Event.ParticipantsUpdated -> {
                participantCount = event.participants.count()
                renderStreamStatus()
            }
        }
    }

    private fun stopAndFinish() {
        binding.stopSession.isEnabled = false
        val streamSession = session
        session = null
        lifecycleScope.launch {
            try {
                streamSession?.stop()
                log("stopped")
            } catch (error: Exception) {
                log("stop error: ${error.displayMessage()}")
            } finally {
                releasePlayer()
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        eventsJob?.cancel()
        // Back/stop already ended the session; this covers process-driven teardown.
        session?.let { streamSession ->
            session = null
            cleanupScope.launch { runCatching { streamSession.stop() } }
        }
        releasePlayer()
        super.onDestroy()
    }

    // endregion

    // region Player

    private fun playIfNeeded(stream: HotMicStream) {
        val url = when (stream.state) {
            HotMicStream.State.Live -> stream.hlsUrl
            HotMicStream.State.Vod -> stream.vodUrl
            else -> stream.hlsUrl ?: stream.vodUrl
        }?.takeIf { it.isNotBlank() } ?: return
        val exo = player ?: return
        val current = exo.currentMediaItem?.localConfiguration?.uri?.toString()
        if (current == url) return
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        log("player ← ${maskUrl(url)}")
    }

    private fun releasePlayer() {
        binding.playerView.player = null
        player?.release()
        player = null
    }

    // endregion

    // region Chat

    private fun sendChat() {
        val text = binding.chatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val streamSession = session ?: return
        binding.sendChat.isEnabled = false
        lifecycleScope.launch {
            try {
                // Sent messages are not echoed through events; insert immediately.
                val message = streamSession.chat.sendChatMessage(text)
                chatAdapter.append(listOf(message))
                scrollChatToEnd()
                binding.chatInput.setText("")
            } catch (error: Exception) {
                log("chat error: ${error.displayMessage()}")
                Toast.makeText(this@StreamSessionActivity, error.displayMessage(), Toast.LENGTH_SHORT).show()
            } finally {
                binding.sendChat.isEnabled = true
            }
        }
    }

    private fun scrollChatToEnd() {
        val last = chatAdapter.itemCount - 1
        if (last >= 0) binding.chatList.scrollToPosition(last)
    }

    // endregion

    // region Polls

    private fun renderPolls() {
        binding.pollContainer.removeAllViews()
        val open = polls.values.filter { !it.closed }
        if (open.isEmpty()) {
            binding.pollContainer.addView(
                TextView(this).apply { text = getString(R.string.no_open_polls) },
            )
            return
        }
        open.forEach { poll ->
            binding.pollContainer.addView(
                TextView(this).apply {
                    text = poll.question ?: "Poll ${poll.id}"
                    setPadding(0, 8, 0, 4)
                },
            )
            poll.options.forEach { option ->
                val answered = poll.myAnswer == option.id
                val button = MaterialButton(this).apply {
                    text = buildString {
                        append(getString(R.string.poll_option, option.answer, option.responses))
                        if (answered) append(" ✓")
                    }
                    isEnabled = poll.myAnswer == null
                    setOnClickListener { submitPoll(poll.id, option.id) }
                }
                binding.pollContainer.addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
    }

    private fun submitPoll(pollId: String, optionId: String) {
        val streamSession = session ?: return
        lifecycleScope.launch {
            try {
                streamSession.polls.submitResponse(pollId, optionId)
                polls[pollId]?.let { polls[pollId] = it.copy(myAnswer = optionId) }
                renderPolls()
                log("answered poll $pollId")
            } catch (error: Exception) {
                log("poll error: ${error.displayMessage()}")
                Toast.makeText(this@StreamSessionActivity, error.displayMessage(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion

    // region Blocking

    private fun blockOrUnblock(block: Boolean) {
        val userId = binding.blockUserId.text?.toString()?.trim().orEmpty()
        if (userId.isEmpty()) {
            Toast.makeText(this, R.string.enter_user_id, Toast.LENGTH_SHORT).show()
            return
        }
        val streamSession = session ?: return
        lifecycleScope.launch {
            try {
                if (block) {
                    streamSession.blockUser(userId)
                    log("blocked $userId")
                } else {
                    streamSession.unblockUser(userId)
                    log("unblocked $userId")
                }
            } catch (error: Exception) {
                log("block error: ${error.displayMessage()}")
                Toast.makeText(this@StreamSessionActivity, error.displayMessage(), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion

    // region Rendering

    private fun renderSessionStatus(state: HotMicStreamSession.State) {
        binding.sessionStatus.text = getString(
            R.string.session_status,
            state.displayName(),
            connection?.displayName() ?: "—",
        )
    }

    private fun renderStreamStatus() {
        val current = stream ?: return
        binding.streamStatus.text = getString(
            R.string.stream_status,
            current.state.rawValue,
            current.viewers,
            participantCount,
        )
    }

    private fun log(line: String) {
        while (logLines.size >= MAX_LOG_LINES) logLines.removeFirst()
        logLines.addLast(line)
        binding.eventLog.text = logLines.joinToString("\n")
    }

    // endregion

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_STREAM_TITLE = "stream_title"
        private const val MAX_LOG_LINES = 200

        /** Outlives the Activity so a session can still be stopped during teardown. */
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

private fun HotMicParticipant.Snapshot.count(): Int =
    1 + cohosts.size + guests.size + waiting.size + room.size
