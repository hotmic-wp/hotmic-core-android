package io.hotmic.core.publicsample

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.hotmic.core.HotMicClient
import io.hotmic.core.HotMicError
import io.hotmic.core.HotMicStreamSession
import io.hotmic.core.LogLevel
import io.hotmic.core.models.HotMicChatMessage
import io.hotmic.core.models.HotMicPoll
import io.hotmic.core.models.HotMicStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * README "Stream Session" + "Media Playback" + "Chat" + "Reactions" + "Polls" + "User Blocking" +
 * "Moderation", host-owned: Media3 ExoPlayer plays `hlsUrl` (LIVE) or `vodUrl` (VOD); everything
 * else is text and buttons so every public session operation is reachable from the UI.
 *
 * QA mode: extras `HEM96_MEDIA=hls|vod`, `HEM96_MEDIA_SECONDS=n`, `HEM96_SEEK_AT=s`, `HEM96_SEEK_TO=s`
 * sample the player every 5 s, log `MEDIA …` lines and a `ROW N7-… PASS|FAIL` verdict, then stop.
 */
class SessionActivity : AppCompatActivity() {
    private var session: HotMicStreamSession? = null
    private var eventsJob: Job? = null
    private var player: ExoPlayer? = null
    private var stream: HotMicStream? = null
    private val messages = linkedMapOf<String, HotMicChatMessage>()
    private val polls = linkedMapOf<String, HotMicPoll>()
    private val log = ArrayDeque<String>()

    private lateinit var statusView: TextView
    private lateinit var chatView: TextView
    private lateinit var pollsView: TextView
    private lateinit var participantsView: TextView
    private lateinit var logView: TextView
    private lateinit var input: EditText
    private lateinit var targetId: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AppConfig.load(this)
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE) ?: streamId

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        scroll.addView(root)
        val playerView = PlayerView(this)
        root.addView(playerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600))
        statusView = TextView(this); root.addView(statusView)
        val row1 = LinearLayout(this)
        row1.addView(Button(this).apply { text = "Stop"; setOnClickListener { lifecycleScope.launch { stopSession() } } })
        row1.addView(Button(this).apply { text = "Start again"; setOnClickListener { lifecycleScope.launch { startAgain() } } })
        row1.addView(Button(this).apply { text = "Release"; setOnClickListener { session = null; eventsJob?.cancel(); appendLog("released without stop()") } })
        root.addView(row1)
        root.addView(TextView(this).apply { text = "Chat" })
        chatView = TextView(this); root.addView(chatView)
        val row2 = LinearLayout(this)
        input = EditText(this).apply { hint = "message" }
        row2.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(Button(this).apply { text = "Send"; setOnClickListener { send() } })
        root.addView(row2)
        val row3 = LinearLayout(this)
        targetId = EditText(this).apply { hint = "chat id / user id / poll:option" }
        row3.addView(targetId, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row3)
        val row4 = LinearLayout(this)
        row4.addView(Button(this).apply { text = "Like"; setOnClickListener { chatOp("like") } })
        row4.addView(Button(this).apply { text = "Unlike"; setOnClickListener { chatOp("unlike") } })
        row4.addView(Button(this).apply { text = "Who"; setOnClickListener { chatOp("who") } })
        row4.addView(Button(this).apply { text = "Report"; setOnClickListener { chatOp("report") } })
        row4.addView(Button(this).apply { text = "Delete"; setOnClickListener { chatOp("delete") } })
        root.addView(row4)
        val row5 = LinearLayout(this)
        row5.addView(Button(this).apply { text = "Block"; setOnClickListener { userOp("block") } })
        row5.addView(Button(this).apply { text = "Unblock"; setOnClickListener { userOp("unblock") } })
        row5.addView(Button(this).apply { text = "Mod"; setOnClickListener { userOp("mod") } })
        row5.addView(Button(this).apply { text = "ChatBan"; setOnClickListener { userOp("chatban") } })
        row5.addView(Button(this).apply { text = "Answer"; setOnClickListener { answerPoll() } })
        root.addView(row5)
        root.addView(TextView(this).apply { text = "Polls" })
        pollsView = TextView(this); root.addView(pollsView)
        root.addView(TextView(this).apply { text = "Participants" })
        participantsView = TextView(this); root.addView(participantsView)
        root.addView(TextView(this).apply { text = "Events" })
        logView = TextView(this); root.addView(logView)
        setContentView(scroll)

        player = ExoPlayer.Builder(this).build().also { playerView.player = it }

        val client = HotMicClient(
            context = applicationContext,
            apiKey = config.apiKey,
            accessToken = config.accessToken,
            logLevel = LogLevel.DEBUG,
        )
        val mediaMode = intent.getStringExtra("HEM96_MEDIA")
        open(client, streamId)
        if (mediaMode != null) runMediaQa(mediaMode)
    }

    private fun open(client: HotMicClient, streamId: String) {
        val s = client.makeStreamSession(streamId)
        session = s
        // Keep a strong reference and collect events before start() (README "Stream Session").
        eventsJob = lifecycleScope.launch { s.events.collect { handle(it) } }
        lifecycleScope.launch {
            statusView.text = "Starting…"
            try {
                val snapshot = s.start()
                stream = snapshot.stream
                snapshot.chatMessages.forEach { messages[it.id] = it }
                snapshot.polls.forEach { polls[it.id] = it }
                render()
                renderParticipants(snapshot.participants)
                statusView.text = "Active as ${snapshot.user.displayName ?: snapshot.user.id.take(8)} · ${snapshot.stream.state.rawValue}"
                play(snapshot.stream)
            } catch (error: HotMicError) {
                statusView.text = "start failed: ${error.label()}"
                appendLog("start failed ${error.label()}")
            }
        }
    }

    private fun handle(event: HotMicStreamSession.Event) {
        appendLog(event.label())
        when (event) {
            is HotMicStreamSession.Event.StreamUpdated -> { stream = event.stream; play(event.stream) }
            is HotMicStreamSession.Event.ChatBatchReceived -> {
                event.batch.messages.forEach { messages[it.id] = it }
                render()
            }
            is HotMicStreamSession.Event.ChatMessageDeleted -> { messages.remove(event.id); render() }
            is HotMicStreamSession.Event.PollCreated -> { polls[event.poll.id] = event.poll; render() }
            is HotMicStreamSession.Event.PollUpdated -> { polls[event.poll.id] = event.poll; render() }
            is HotMicStreamSession.Event.PollDeleted -> { polls.remove(event.id); render() }
            is HotMicStreamSession.Event.ParticipantsUpdated -> renderParticipants(event.participants)
            // The backend reports end_video=true for every VOD stream, so StreamEnded arrives on the first
            // poll of a replay (README-GAPS 24); only a LIVE stream ending should stop playback here.
            HotMicStreamSession.Event.StreamEnded -> if (stream?.state == HotMicStream.State.Live) player?.pause()
            HotMicStreamSession.Event.StreamDeleted -> player?.pause()
            is HotMicStreamSession.Event.StateChanged -> statusView.text = "State ${event.state.label()}"
            else -> Unit
        }
    }

    // README "Media Playback", verbatim selection rule.
    private fun play(stream: HotMicStream) {
        val url = when (stream.state) {
            HotMicStream.State.Live -> stream.hlsUrl
            HotMicStream.State.Vod -> stream.vodUrl
            else -> null
        } ?: return
        val exo = player ?: return
        if (exo.currentMediaItem?.localConfiguration?.uri?.toString() == url) return
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
        appendLog("player ← ${url.substringBefore('?')}")
    }

    private suspend fun stopSession() {
        session?.stop()
        appendLog("stop() returned; state=${session?.state?.value?.label()}")
    }

    private suspend fun startAgain() {
        val s = session ?: return
        try { s.start(); appendLog("second start() succeeded (unexpected)") } catch (e: HotMicError) { appendLog("second start() → ${e.label()}") }
    }

    private fun send() {
        val s = session ?: return
        val text = input.text.toString()
        lifecycleScope.launch {
            try {
                val m = s.chat.sendChatMessage(text)
                messages[m.id] = m // not echoed through events — insert immediately
                input.setText(""); render()
            } catch (e: HotMicError) { appendLog("send → ${e.label()}") }
        }
    }

    private fun chatOp(op: String) {
        val s = session ?: return
        val id = targetId.text.toString().trim().ifEmpty { messages.keys.lastOrNull() ?: return }
        lifecycleScope.launch {
            try {
                val like = HotMicChatMessage.Reaction.ReactionType.Like
                val result = when (op) {
                    "like" -> s.chat.addChatMessageReaction(like, id).toString()
                    "unlike" -> s.chat.removeChatMessageReaction(like, id).toString()
                    "who" -> s.chat.fetchChatMessageReactions(id).joinToString { "${it.type.rawValue}:${it.user.displayName}" }
                    "report" -> { s.chat.reportChatMessage(id); "reported" }
                    else -> { s.chat.deleteChatMessage(id); messages.remove(id); render(); "deleted" }
                }
                appendLog("$op ${id.take(8)} → $result")
            } catch (e: HotMicError) { appendLog("$op → ${e.label()}") }
        }
    }

    private fun userOp(op: String) {
        val s = session ?: return
        val id = targetId.text.toString().trim()
        lifecycleScope.launch {
            try {
                when (op) {
                    "block" -> s.blockUser(id)
                    "unblock" -> s.unblockUser(id)
                    "mod" -> s.makeUserModerator(id)
                    else -> s.blockUserFromStreamChat(id)
                }
                appendLog("$op ${id.take(8)} ok")
            } catch (e: HotMicError) { appendLog("$op → ${e.label()}") }
        }
    }

    private fun answerPoll() {
        val s = session ?: return
        val (pollId, optionId) = targetId.text.toString().split(":").let {
            if (it.size == 2) it[0] to it[1] else {
                val p = polls.values.firstOrNull { poll -> !poll.closed } ?: return
                p.id to (p.options.firstOrNull()?.id ?: return)
            }
        }
        lifecycleScope.launch {
            try { s.polls.submitResponse(pollId = pollId, optionId = optionId); appendLog("answered ${pollId.take(8)}") }
            catch (e: HotMicError) { appendLog("submitResponse → ${e.label()}") }
        }
    }

    private fun render() {
        chatView.text = messages.values.toList().takeLast(12).joinToString("\n") { "${it.userName}: ${it.message} [${it.id.take(8)}]" }
        pollsView.text = polls.values.joinToString("\n") { p ->
            "${p.question} closed=${p.closed} my=${p.myAnswer?.take(8)} " + p.options.joinToString { "${it.answer}(${it.responses})" }
        }
    }

    private fun renderParticipants(p: io.hotmic.core.models.HotMicParticipant.Snapshot) {
        participantsView.text = "host=${p.host.name} cohosts=${p.cohosts.size} guests=${p.guests.size} waiting=${p.waiting.size} room=${p.room.map { it.name ?: it.id.take(8) }}"
    }

    private fun appendLog(line: String) {
        Hem96.log("UI $line")
        while (log.size >= 40) log.removeFirst()
        log.addLast(line)
        logView.text = log.joinToString("\n")
    }

    // region N7 media QA
    private fun runMediaQa(mode: String) {
        val seconds = intent.getIntExtra("HEM96_MEDIA_SECONDS", 60)
        val seekAt = intent.getIntExtra("HEM96_SEEK_AT", -1)
        val seekTo = intent.getIntExtra("HEM96_SEEK_TO", 45)
        val exo = player ?: return
        var lastError: String? = null
        var maxPosition = 0L
        var playingSamples = 0
        var stalls = 0
        var seekDone = false
        var seekObservedPos = -1L
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastError = "${error.errorCodeName} ${error.message}"
                Hem96.log("MEDIA error ${lastError}")
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                Hem96.log("MEDIA playbackState=${stateName(playbackState)}")
                if (playbackState == Player.STATE_BUFFERING && playingSamples > 0) stalls++
            }
        })
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < seconds * 1000L) {
                delay(5_000)
                val t = (System.currentTimeMillis() - started) / 1000
                val pos = exo.currentPosition
                if (exo.isPlaying) playingSamples++
                if (pos > maxPosition) maxPosition = pos
                Hem96.log("MEDIA t=${t}s state=${stateName(exo.playbackState)} playing=${exo.isPlaying} pos=${pos}ms buffered=${exo.bufferedPosition}ms live=${exo.isCurrentMediaItemLive} dur=${exo.duration}")
                if (seekAt >= 0 && !seekDone && t >= seekAt) {
                    seekDone = true
                    exo.seekTo(seekTo * 1000L)
                    Hem96.log("MEDIA seekTo(${seekTo}s) at t=${t}s from pos=${pos}ms")
                } else if (seekDone && seekObservedPos < 0 && exo.isPlaying) {
                    seekObservedPos = exo.currentPosition
                    Hem96.log("MEDIA after-seek pos=${seekObservedPos}ms")
                }
            }
            val verdict = when {
                lastError != null -> "FAIL error=$lastError"
                mode == "hls" && playingSamples * 5 < seconds * 0.9 -> "FAIL played ${playingSamples * 5}s of ${seconds}s"
                mode == "vod" && seekAt >= 0 && (seekObservedPos < seekTo * 1000L) -> "FAIL seek not observed pos=$seekObservedPos"
                else -> "PASS"
            }
            Hem96.log("ROW N7-$mode $verdict playingSamples=$playingSamples stalls=$stalls maxPos=${maxPosition}ms seekObserved=${seekObservedPos}ms")
            stopSession()
            Hem96.log("AUTORUN COMPLETE")
        }
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; else -> "ENDED"
    }
    // endregion

    override fun onDestroy() {
        eventsJob?.cancel()
        player?.release(); player = null
        session?.let { s -> QaRunner.scope.launch { runCatching { s.stop() } } }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_ID = "stream_id"
        const val EXTRA_TITLE = "title"
    }
}
