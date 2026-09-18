@file:Suppress("unused", "UNUSED_VARIABLE")

package io.hotmic.core.publicsample

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.hotmic.core.HotMicClient
import io.hotmic.core.HotMicError
import io.hotmic.core.HotMicStreamQuery
import io.hotmic.core.HotMicStreamSession
import io.hotmic.core.LogLevel
import io.hotmic.core.models.HotMicChatMessage
import io.hotmic.core.models.HotMicPoll
import io.hotmic.core.models.HotMicStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * N22: every Kotlin block in README.md and MIGRATION.md, pasted verbatim (only wrapped in a
 * function/class and given the names the snippet leaves free), so the build proves they compile
 * against the published Core. `allWarningsAsErrors` is on for this module.
 */
object ReadmeSamples {
    // README "Create a Client"
    fun createClient(applicationContext: Context, apiKey: String, accessToken: String): HotMicClient {
        val hotMic = HotMicClient(
            context = applicationContext,
            apiKey = apiKey,
            accessToken = accessToken,
        )
        return hotMic
    }

    fun createClientWithLogging(apiKey: String, accessToken: String) =
        HotMicClient(apiKey = apiKey, accessToken = accessToken, logLevel = LogLevel.DEBUG)

    // README "Get Streams"
    suspend fun getStreams(hotMic: HotMicClient, streamId: String) {
        val query = HotMicStreamQuery(
            includesLive = true,
            includesScheduled = true,
            includesVOD = true,
            userId = null,
            page = 1,
            limit = 20,
        )

        val page = hotMic.fetchStreams(query)
        val streams = page.streams

        if (page.pagination.hasNext) {
            val nextPage = hotMic.fetchStreams(query.copy(page = page.pagination.currentPage + 1))
        }

        val stream = hotMic.fetchStream(streamId)
    }

    // README "Stream Session"
    class StreamController(
        private val hotMic: HotMicClient,
        private val scope: CoroutineScope, // e.g. lifecycleScope or viewModelScope
    ) {
        private var session: HotMicStreamSession? = null
        private var eventsJob: Job? = null

        suspend fun open(streamId: String): HotMicStreamSession.Snapshot {
            val session = hotMic.makeStreamSession(streamId)
            this.session = session

            eventsJob = scope.launch {
                session.events.collect { event ->
                    // Update your app's state for the event.
                }
            }

            return try {
                session.start()
            } catch (error: HotMicError) {
                close()
                throw error
            }
        }

        suspend fun close() {
            session?.stop()
            eventsJob?.cancel()
            eventsJob = null
            session = null
        }
    }

    // README "Media Playback"
    class Playback(context: Context, playerView: PlayerView) {
        val player = ExoPlayer.Builder(context).build()
        init { playerView.player = player }

        fun play(stream: HotMicStream) {
            val url = when (stream.state) {
                HotMicStream.State.Live -> stream.hlsUrl
                HotMicStream.State.Vod -> stream.vodUrl
                else -> null
            } ?: return
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    // README "Chat", "Reactions", "Polls", "Participants and Users", "User Blocking", "Moderation"
    suspend fun chatAndMore(session: HotMicStreamSession, hotMic: HotMicClient, poll: HotMicPoll, option: HotMicPoll.Option, userId: String) {
        val message = session.chat.sendChatMessage("Hello!")
        session.chat.deleteChatMessage(message.id)
        session.chat.reportChatMessage(message.id)

        val reaction = session.chat.addChatMessageReaction(
            HotMicChatMessage.Reaction.ReactionType.Like,
            message.id,
        )

        session.chat.removeChatMessageReaction(
            HotMicChatMessage.Reaction.ReactionType.Like,
            message.id,
        )

        val reactionDetails = session.chat.fetchChatMessageReactions(message.id)

        session.polls.submitResponse(pollId = poll.id, optionId = option.id)

        val user = hotMic.fetchUser(userId)

        session.blockUser(userId)
        session.unblockUser(userId)

        session.makeUserModerator(userId)
        session.blockUserFromStreamChat(userId)
    }

    // README "Event Listener"
    fun eventListener(hotMic: HotMicClient, streamId: String, lifecycleScope: CoroutineScope) {
        val session = hotMic.makeStreamSession(streamId)

        session.eventListener = { event ->
            // Update your app's state for the event.
        }

        lifecycleScope.launch {
            try {
                val snapshot = session.start()
                // Display the initial session state.
            } catch (error: HotMicError) {
                // Handle HotMicError.
            }
        }
    }

    // MIGRATION "Stream Discovery"
    suspend fun migrationDiscovery(hotMic: HotMicClient, streamId: String) {
        val page = hotMic.fetchStreams(
            HotMicStreamQuery(
                includesLive = true,
                includesScheduled = true,
                includesVOD = true,
                userId = null,
                page = 1,
                limit = 20,
            ),
        )
        val streams = page.streams
        val stream = hotMic.fetchStream(streamId)
    }

    // MIGRATION "Replace the Player Fragment with a Stream Session" / "User Blocking and Moderation"
    suspend fun migrationSession(hotMic: HotMicClient, streamId: String, userId: String) {
        val session = hotMic.makeStreamSession(streamId)
        session.blockUser(userId)
        session.unblockUser(userId)
        session.makeUserModerator(userId)
        session.blockUserFromStreamChat(userId)
        session.stop()
        val state = session.state
    }

    // README "Errors" table: all eight cases are exhaustively matched (compile-time check of the sealed class).
    fun describe(error: HotMicError): String = when (error) {
        HotMicError.InvalidRequest -> "InvalidRequest"
        HotMicError.Cancelled -> "Cancelled"
        is HotMicError.Transport -> "Transport"
        HotMicError.Unauthorized -> "Unauthorized"
        is HotMicError.Forbidden -> "Forbidden"
        is HotMicError.Server -> "Server ${error.statusCode}"
        is HotMicError.Decoding -> "Decoding"
        HotMicError.InvalidSessionState -> "InvalidSessionState"
    }

    // README event list: all twelve events (exhaustive `when` = compile-time check).
    fun describe(event: HotMicStreamSession.Event): String = when (event) {
        is HotMicStreamSession.Event.StateChanged -> "StateChanged"
        is HotMicStreamSession.Event.ConnectionChanged -> "ConnectionChanged"
        is HotMicStreamSession.Event.StreamUpdated -> "StreamUpdated"
        HotMicStreamSession.Event.StreamEnded -> "StreamEnded"
        HotMicStreamSession.Event.StreamDeleted -> "StreamDeleted"
        is HotMicStreamSession.Event.ChatBatchReceived -> "ChatBatchReceived"
        is HotMicStreamSession.Event.ChatMessageDeleted -> "ChatMessageDeleted"
        is HotMicStreamSession.Event.ChatMessageReactionDeleted -> "ChatMessageReactionDeleted"
        is HotMicStreamSession.Event.PollCreated -> "PollCreated"
        is HotMicStreamSession.Event.PollUpdated -> "PollUpdated"
        is HotMicStreamSession.Event.PollDeleted -> "PollDeleted"
        is HotMicStreamSession.Event.ParticipantsUpdated -> "ParticipantsUpdated"
    }
}
