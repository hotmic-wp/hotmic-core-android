# Migrating from the HotMic Android SDK (HotMicPlayer)

HotMicCore makes the HotMic service available to your app without a prebuilt user interface. The HotMic Android SDK (`io.hotmic.player:hotmic-android-sdk`) embeds the player, chat, polls, and panels in one `HotMicPlayer` fragment; Core exposes the same platform as data and a session, and your app owns the player and every screen. Migration requires replacing the networking and session APIs, and implementing a player and interface appropriate for your app.

## Requirements and Installation

HotMicCore requires `minSdk 23` and JDK 17. It is a plain AAR on Maven Central — no CodeArtifact or GitHub Packages token is needed.

Remove the HotMic Maven repository (CodeArtifact or `maven.pkg.github.com/hotmic-wp/android-sdk`, with its credentials) and the SDK dependency:

```kotlin
// remove
implementation("io.hotmic.player:hotmic-android-sdk:<version>")
```

Add Core (see [README.md](README.md#installation)) and the player of your choice:

```kotlin
implementation("io.hotmic.core:hotmic-core-android:1.0.0")
implementation("androidx.media3:media3-exoplayer:1.4.1")
implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
```

Replace imports of `io.hotmic.player.*` with `io.hotmic.core.*` and `io.hotmic.core.models.*`.

The SDK's RxJava and Glide transitive dependencies go away with it; Core depends only on Kotlin coroutines, OkHttp, and kotlinx.serialization.

## Initialization

The SDK took credentials per call (`credential(apiKey)` on the builder, `getPlatformToken()` on `PlayerCallbacks`, or `HotMicPlayer.Builder(context, apiKey, accessToken)` in data-only mode). Replace that with an explicit client instance:

```kotlin
val hotMic = HotMicClient(
    context = applicationContext,
    apiKey = apiKey,
    accessToken = accessToken,
)
```

Retain the client for as long as its credentials remain valid. Create a new client when the access token changes.

## Stream Discovery

Replace `HotMicPlayer.getStreams(context, apiKey)` (an RxJava `Single<List<HMStreamBasic>>`) with:

```kotlin
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
```

Use `page.pagination.hasNext` and increment `HotMicStreamQuery.page` to request additional pages.

Fetch one stream by ID with:

```kotlin
val stream = hotMic.fetchStream(streamId)
```

All Core operations are `suspend` functions. Call them from a coroutine (`lifecycleScope`, `viewModelScope`) instead of subscribing on a scheduler.

## Replace the Player Fragment with a Stream Session

Replace

```kotlin
HotMicPlayer.Builder(activity)
    .setStreamId(stream.id)
    .credential(apiKey)
    .setUICallback(playerCallbacks)
    .setChatFragment(customChatFragment)
    .show(R.id.player_fragment_container)
```

with:

```kotlin
val session = hotMic.makeStreamSession(streamId)
```

Present your own interface that utilizes the session to get data and perform operations.

Starting a session (`session.start()`) creates the backend session and loads the initial stream, authenticated user, chats, polls, and participants. It then polls for changes until stopped.

Replace `HotMicPlayer.closePlayer(activity, reason)` and `HotMicPlayer.isPlayerOpen(activity)` with `session.stop()` and `session.state`. Call `stop()` when the stream experience closes; there is no fragment to remove.

`setDataOnlyMode(true)` is no longer needed — Core is always headless.

## Replace Callbacks with Events

Replace `PlayerCallbacks`, the custom chat fragment's `HMChatViewModel` LiveData (`chatTipsData`, `streamChatStateLiveData`, `pollActiveData`, `roomActiveCountData`), and the `ChatDelegatesListener` plumbing with session events. Collect `HotMicStreamSession.events` (a `SharedFlow`) or assign `HotMicStreamSession.eventListener`.

Handle each event by updating the corresponding app state:

| HotMicCore event | State to update |
| --- | --- |
| `StateChanged` | Reflect session lifecycle changes if desired. |
| `ConnectionChanged` | Show connected or reconnecting state if desired. |
| `StreamUpdated` | Replace displayed stream data and handle changes. |
| `StreamEnded` | Transition to your end-of-stream experience. |
| `StreamDeleted` | Close the stream experience (the session stops itself). |
| `ChatBatchReceived` | Add the messages and reactions into local chat state. |
| `ChatMessageDeleted` | Remove the matching local message. |
| `ChatMessageReactionDeleted` | Remove the matching local reaction. |
| `PollCreated`, `PollUpdated`, `PollDeleted` | Update local poll state. |
| `ParticipantsUpdated` | Refresh the participants. |

`PlayerCallbacks.onPlayerClosed()` has no equivalent: your app decides when the experience closes and calls `stop()`.

## Media Playback

Replace the SDK-provided player (and `useCustomPlayer(...)`) with a player owned by your app.

Select a playback URL from `HotMicStream` based on its state as follows:

- Live: `hlsUrl`
- Video on demand: `vodUrl`
- Scheduled or ended: no active playback, display a thumbnail

The Example app uses Media3 ExoPlayer with `PlayerView`; any HLS-capable player works.

Picture-in-picture, casting, and player chrome are now your app's responsibility.

## Chat

Replace `HMChatViewModel.sendChat(...)`, `sendReaction(...)`, and the chat panel actions with `session.chat`:

| HotMic Android SDK action | HotMicCore replacement |
| --- | --- |
| `HMChatViewModel.sendChat(text)` | `sendChatMessage(text)` |
| Report a chat message | `reportChatMessage(chatId)` |
| Delete a chat message | `deleteChatMessage(chatId)` |
| `sendReaction(chat, reaction, true)` | `addChatMessageReaction(type, chatId)` |
| `sendReaction(chat, reaction, false)` | `removeChatMessageReaction(type, chatId)` |
| Fetch users who reacted | `fetchChatMessageReactions(chatId)` |

`ChatReaction.LIKE` / `FIRE` / `LAUGH` / `ANGER` / `SADNESS` map to `HotMicChatMessage.Reaction.ReactionType.Like` / `Fire` / `Laugh` / `Anger` / `Sadness`.

Chat and reaction updates arrive in `ChatBatchReceived` events instead of `chatTipsData`. Messages you send, delete, or react to are not echoed back through events — apply them locally immediately.

Tips (`Tip`, `SampleTipDelegate`) are not part of Core.

## Polls

Replace `HotMicPlayer.showPollPanel(activity, source)` with your own poll UI. Use the polls in the initial session snapshot and update them using session events. Replace the answer action with:

```kotlin
session.polls.submitResponse(pollId = poll.id, optionId = option.id)
```

## Participants and Users

Replace `HotMicPlayer.showPeoplePanel(activity)` and `HotMicPlayer.showProfilePanel(activity, userId)` with your own UI. Use the participants in the initial snapshot and update them using `ParticipantsUpdated`.

To get a user's information, call:

```kotlin
val user = hotMic.fetchUser(userId)
```

## User Blocking and Moderation

Personal block-list and elevated moderation actions, previously reached through the SDK's profile and chat option panels, are available from the stream session:

```kotlin
session.blockUser(userId)
session.unblockUser(userId)
session.makeUserModerator(userId)
session.blockUserFromStreamChat(userId)
```

## Not Available in Core

The following SDK features have no Core equivalent and remain your app's responsibility (or a reason to keep the full SDK):

- Tips and in-app purchases
- Guest / co-host video (OpenTok) UI
- Ads, theming (`HMPlayerSdkTheme`), and panels (`showPollPanel`, `showPeoplePanel`, `showProfilePanel`)
- Picture-in-picture and leaderboards
- Built-in analytics callbacks (`ANVideoCloseReason`, `setAnalyticHandler`)

## Type Mapping

| HotMic Android SDK concept | HotMicCore type |
| --- | --- |
| `HotMicPlayer` (static entry point) | `HotMicClient` |
| `HotMicPlayer.Builder` / player fragment | `HotMicStreamSession` |
| `HMStreamBasic` (list result) | `HotMicStreamSummary` in `HotMicStreamPage` |
| Full stream used by the player | `HotMicStream` |
| Initial player data | `HotMicStreamSession.Snapshot` |
| `Chat` | `HotMicChatMessage` |
| `ChatReaction` | `HotMicChatMessage.Reaction.ReactionType` |
| Poll (poll panel) | `HotMicPoll` |
| Participant (people panel) | `HotMicParticipant` |
| User details (profile panel) | `HotMicUser` |
| RxJava error / `Throwable` | `HotMicError` |
