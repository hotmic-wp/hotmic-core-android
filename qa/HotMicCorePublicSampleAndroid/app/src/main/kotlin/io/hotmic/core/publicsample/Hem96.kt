package io.hotmic.core.publicsample

import android.content.Context
import android.util.Log
import io.hotmic.core.HotMicError
import io.hotmic.core.HotMicStreamSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * QA log: every line goes to logcat tag `HEM96` and to `files/hem96.log` so an `adb logcat -s HEM96`
 * capture and the on-device file agree. Never log credentials; [redact] strips anything that looks like
 * a JWT or the API key before the line leaves the process.
 */
object Hem96 {
    const val TAG = "HEM96"
    private var file: File? = null
    private val secrets = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun init(context: Context, secretValues: List<String>) {
        file = File(context.filesDir, "hem96.log")
        secrets.clear()
        secrets += secretValues.filter { it.length >= 8 }
    }

    fun log(line: String) {
        val clean = redact(line)
        Log.i(TAG, clean)
        runCatching { file?.appendText("${fmt.format(Date())} $clean\n") }
    }

    fun redact(text: String): String {
        var out = text
        secrets.forEach { out = out.replace(it, "<redacted>") }
        return out.replace(Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"), "<redacted-jwt>")
    }

    /** Wall clock in UTC ISO-8601 for cross-client latency math. */
    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}

/** `HotMicError` → short label with the same names the README error table uses. */
fun Throwable.label(): String = when (this) {
    HotMicError.InvalidRequest -> "InvalidRequest"
    HotMicError.Cancelled -> "Cancelled"
    is HotMicError.Transport -> "Transport($description)"
    HotMicError.Unauthorized -> "Unauthorized"
    is HotMicError.Forbidden -> "Forbidden($detail)"
    is HotMicError.Server -> "Server($statusCode, $detail)"
    is HotMicError.Decoding -> "Decoding($description)"
    HotMicError.InvalidSessionState -> "InvalidSessionState"
    else -> "${javaClass.simpleName}($message)"
}

fun HotMicStreamSession.State.label(): String = when (this) {
    HotMicStreamSession.State.Idle -> "Idle"
    HotMicStreamSession.State.Starting -> "Starting"
    HotMicStreamSession.State.Active -> "Active"
    HotMicStreamSession.State.Stopping -> "Stopping"
    HotMicStreamSession.State.Stopped -> "Stopped"
    is HotMicStreamSession.State.Failed -> "Failed(${error.label()})"
}

fun HotMicStreamSession.ConnectionState.label(): String = when (this) {
    HotMicStreamSession.ConnectionState.Connected -> "Connected"
    is HotMicStreamSession.ConnectionState.Reconnecting -> "Reconnecting($attempt)"
    HotMicStreamSession.ConnectionState.Disconnected -> "Disconnected"
}

fun HotMicStreamSession.Event.label(): String = when (this) {
    is HotMicStreamSession.Event.StateChanged -> "StateChanged(${state.label()})"
    is HotMicStreamSession.Event.ConnectionChanged -> "ConnectionChanged(${connection.label()})"
    is HotMicStreamSession.Event.StreamUpdated -> "StreamUpdated(state=${stream.state.rawValue} viewers=${stream.viewers})"
    HotMicStreamSession.Event.StreamEnded -> "StreamEnded"
    HotMicStreamSession.Event.StreamDeleted -> "StreamDeleted"
    is HotMicStreamSession.Event.ChatBatchReceived ->
        "ChatBatchReceived(messages=${batch.messages.size} reactions=${batch.reactions.size} ids=${batch.messages.joinToString(",") { it.id.take(8) }})"
    is HotMicStreamSession.Event.ChatMessageDeleted -> "ChatMessageDeleted(${id.take(8)})"
    is HotMicStreamSession.Event.ChatMessageReactionDeleted -> "ChatMessageReactionDeleted(chat=${chatId.take(8)} user=${userId.take(8)} type=${type.rawValue})"
    is HotMicStreamSession.Event.PollCreated -> "PollCreated(${poll.id.take(8)})"
    is HotMicStreamSession.Event.PollUpdated -> "PollUpdated(${poll.id.take(8)} closed=${poll.closed} responses=${poll.responses} my=${poll.myAnswer?.take(8)})"
    is HotMicStreamSession.Event.PollDeleted -> "PollDeleted(${id.take(8)})"
    is HotMicStreamSession.Event.ParticipantsUpdated ->
        "ParticipantsUpdated(host=${participants.host.id.take(8)} cohosts=${participants.cohosts.size} guests=${participants.guests.size} waiting=${participants.waiting.size} room=${participants.room.map { it.id.take(8) }})"
}
