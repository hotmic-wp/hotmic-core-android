# HotMicCore (Android)

HotMicCore allows you to integrate the HotMic stream experience into your Android app with a fully custom user interface. Use this library to get streams, create a stream session, and build your live stream experience. Your app owns the video player and every pixel of UI; Core handles the HotMic platform (streams, session, presence, chat, polls, moderation).

See [MIGRATION.md](MIGRATION.md) to migrate to HotMicCore from the HotMic Android SDK (`HotMicPlayer`).

## Features

- Fetch live, scheduled, and video-on-demand replay streams
- Retrieve stream and user information
- Observe stream updates
- Build a chat interface using the HotMic service
- Perform moderation and user-blocking actions
- Observe and answer polls
- Observe participant groups

## Requirements

- Android **minSdk 23** (Android 6.0) or newer
- **JDK 17** for building
- Kotlin 2.1+ with `kotlinx.coroutines` (the API is `suspend`-based; a callback listener is available for session events)
- A video player owned by your app — the Example uses [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)

Core targets `compileSdk 34` and is built with Gradle 8.11.1, AGP 8.10.1, and Kotlin 2.1.0.

## Example

The `Example/` app demonstrates loading streams, starting a stream session, monitoring session state, playing video with Media3, sending and receiving chat, answering polls, blocking users, and stopping a session cleanly. Add your API key and access token in the Settings screen.

Core is consumed from the HotMic Maven repository, so the Example builds from a plain clone:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :example:installDebug
```

Optionally prefill the Settings screen for local QA by copying `local.properties.example` to `local.properties` and filling in `hotmic.apiKey` / `hotmic.accessToken`. `local.properties` is gitignored — never commit real credentials.

## Installation

HotMicCore is served from the HotMic Maven repository at `https://d3ec5su7pmd3cn.cloudfront.net/`. Add it next to `mavenCentral()` — no token and no account are needed:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://d3ec5su7pmd3cn.cloudfront.net/") } // HotMic Core
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.hotmic.core:hotmic-core-android:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") // the API is suspend-based

    // Your player — Core does not bundle one.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
```

Declare the `INTERNET` permission in your manifest (Core does not merge one in). Core ships consumer ProGuard/R8 rules, so no extra keep rules are needed when you enable minification.

## Usage

### Create a Client

Initialize a `HotMicClient` with your API key and access token:

```kotlin
import io.hotmic.core.HotMicClient

val hotMic = HotMicClient(
    context = applicationContext,
    apiKey = apiKey,
    accessToken = accessToken,
)
```

Pass an application `Context` so requests carry a stable device identifier. A `Context`-free constructor is also available.

Create the access token on your backend for the authenticated user by signing an HS256 JWT with your API secret using a payload in this format:

```json
{
  "identity": {
    "user_id": "stable-user-id",
    "display_name": "Username",
    "profile_pic": "https://example.com/profile.jpg",
    "badge": "https://example.com/badge.png"
  },
  "iat": 1787072400,
  "exp": 1787158800
}
```

`profile_pic` and `badge` are optional. Create a new `HotMicClient` when the access token changes.

Logging is disabled by default. To enable diagnostics, pass a `logLevel` (`LogLevel.ERROR`, `INFO`, or `DEBUG`) to the constructor.

### Get Streams

Fetch live, scheduled, and video-on-demand streams, optionally limiting results to streams created by a specific user:

```kotlin
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
```

Fetch the next page when needed:

```kotlin
if (page.pagination.hasNext) {
    val nextPage = hotMic.fetchStreams(query.copy(page = page.pagination.currentPage + 1))
}
```

Fetch one stream by ID:

```kotlin
val stream = hotMic.fetchStream(streamId)
```

These requests return `HotMicStreamSummary`; full `HotMicStream` details are provided when a stream session is started.

All requests are `suspend` functions and throw `HotMicError` on failure.

### Stream Session

Create a stream session to manage the backend session, polling, presence, and interactive services for a stream:

```kotlin
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
```

Keep a strong reference to the session and begin collecting `events`, or assign `eventListener`, before starting it.

`start()` returns a `Snapshot` with the initial stream, authenticated user, chat messages, polls, and participants. Session events report subsequent changes.

You can monitor `Event.StateChanged` for transitions of `session.state` (`Idle`, `Starting`, `Active`, `Stopping`, `Stopped`, `Failed`) and `Event.ConnectionChanged` to determine whether polling is `Connected`, `Reconnecting`, or `Disconnected`.

Events also report changes to session content:

- `StreamUpdated`, `StreamEnded`, and `StreamDeleted` report changes to the stream.
- `ChatBatchReceived`, `ChatMessageDeleted`, and `ChatMessageReactionDeleted` report chat and reaction changes.
- `PollCreated`, `PollUpdated`, and `PollDeleted` report poll changes.
- `ParticipantsUpdated` reports changes to participant groups.

Call `stop()` and release the session when the experience closes. A stopped or failed session cannot be started again.

### Media Playback

Core does not play video. Select a playback URL from the `HotMicStream` in the snapshot and in `StreamUpdated` events, and hand it to your player:

- Live: `hlsUrl`
- Video on demand: `vodUrl`
- Scheduled or ended: no active playback — display `thumbnail`

```kotlin
val player = ExoPlayer.Builder(context).build()
playerView.player = player

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
```

Release the player when the session stops.

### Chat

Chat messages are included in the initial session snapshot and updated through chat events.

Send a message after the session has started:

```kotlin
val message = session.chat.sendChatMessage("Hello!")
```

Insert the chat message immediately as it will not be delivered through session events.

Delete a chat message:

```kotlin
session.chat.deleteChatMessage(message.id)
```

Remove the message immediately as it will not be delivered through session events.

Report a chat message as inappropriate:

```kotlin
session.chat.reportChatMessage(message.id)
```

### Reactions

Add or remove a reaction to a chat message:

```kotlin
val reaction = session.chat.addChatMessageReaction(
    HotMicChatMessage.Reaction.ReactionType.Like,
    message.id,
)

session.chat.removeChatMessageReaction(
    HotMicChatMessage.Reaction.ReactionType.Like,
    message.id,
)
```

Apply the change immediately as they will not be delivered through session events.

Fetch reaction details:

```kotlin
val reactionDetails = session.chat.fetchChatMessageReactions(message.id)
```

### Polls

Polls are included in the initial session snapshot and updated through poll events.

Submit an answer to a poll:

```kotlin
session.polls.submitResponse(pollId = poll.id, optionId = option.id)
```

### Participants and Users

The initial participant snapshot and subsequent events provide information about users in the stream.

Fetch information for a user by ID:

```kotlin
val user = hotMic.fetchUser(userId)
```

### User Blocking

Add or remove a user from the authenticated user's block list:

```kotlin
session.blockUser(userId)
session.unblockUser(userId)
```

When a user is blocked, their new chat messages are excluded from subsequent session events.

### Moderation

Authorized users can perform elevated moderation operations:

```kotlin
session.makeUserModerator(userId)
session.blockUserFromStreamChat(userId)
```

### Event Listener

To handle events in a stream session without collecting a `Flow`, assign `eventListener` before starting the session:

```kotlin
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
```

### Errors

Every operation throws a `HotMicError`:

| Error | Meaning |
| --- | --- |
| `InvalidRequest` | The request could not be constructed (for example, `page` or `limit` ≤ 0). |
| `Cancelled` | The request or session was cancelled. |
| `Transport` | The request failed before an HTTP response was received. |
| `Unauthorized` | The API key or access token was rejected. |
| `Forbidden` | The authenticated user may not perform the operation. |
| `Server` | The HotMic service returned an unsuccessful response (`statusCode`). |
| `Decoding` | The response could not be decoded. |
| `InvalidSessionState` | The session operation is not valid in its current state. |

## Distribution

`io.hotmic.core:hotmic-core-android` is published to the HotMic Maven repository (`https://d3ec5su7pmd3cn.cloudfront.net/io/hotmic/core/hotmic-core-android/`). This repository holds the documentation and the Example app; it does not publish anything.
