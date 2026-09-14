package io.hotmic.core.example

import io.hotmic.core.HotMicError
import io.hotmic.core.HotMicStreamSession

/** Human-readable description of a [HotMicError] for status lines and toasts. */
internal fun HotMicError.displayMessage(): String = when (this) {
    HotMicError.InvalidRequest -> "Invalid request"
    HotMicError.Cancelled -> "Cancelled"
    is HotMicError.Transport -> "Transport: $description"
    HotMicError.Unauthorized -> "Unauthorized — check your API key and access token"
    is HotMicError.Forbidden -> "Forbidden${detail?.let { ": $it" }.orEmpty()}"
    is HotMicError.Server -> "Server $statusCode${detail?.let { ": $it" }.orEmpty()}"
    is HotMicError.Decoding -> "Decoding: $description"
    HotMicError.InvalidSessionState -> "Invalid session state"
}

/** Message for any failure surfaced by the sample, HotMic or otherwise. */
internal fun Throwable.displayMessage(): String = when (this) {
    is HotMicError -> displayMessage()
    else -> message ?: javaClass.simpleName
}

internal fun HotMicStreamSession.State.displayName(): String = when (this) {
    HotMicStreamSession.State.Idle -> "Idle"
    HotMicStreamSession.State.Starting -> "Starting"
    HotMicStreamSession.State.Active -> "Active"
    HotMicStreamSession.State.Stopping -> "Stopping"
    HotMicStreamSession.State.Stopped -> "Stopped"
    is HotMicStreamSession.State.Failed -> "Failed: ${error.displayMessage()}"
}

internal fun HotMicStreamSession.ConnectionState.displayName(): String = when (this) {
    HotMicStreamSession.ConnectionState.Connected -> "Connected"
    is HotMicStreamSession.ConnectionState.Reconnecting -> "Reconnecting (attempt $attempt)"
    HotMicStreamSession.ConnectionState.Disconnected -> "Disconnected"
}

/** Show only the edges of a credential so the UI never displays a full key or JWT. */
internal fun maskSecret(value: String): String = when {
    value.isBlank() -> "not set"
    value.length <= 8 -> "••••"
    else -> value.take(4) + "…" + value.takeLast(4)
}

/** Strip query strings (which may carry signed tokens) before logging a playback URL. */
internal fun maskUrl(url: String): String {
    val query = url.indexOf('?')
    return if (query >= 0) url.substring(0, query) + "?…" else url
}
