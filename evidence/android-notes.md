# HEM-96 Android — running notes (changes, iOS↔Android API differences, opinions)

Tracker: https://github.com/mchusma/hotmic-engineering-manager/issues/96 · branch `mchusma/hem-96-public-sample`
· fixes on `hotmic-core-android-source` `mchusma/hem-96-fixes` · load harness on `mchusma/hem-96-core-load`.
Date: 2026-09-18. Evidence: `evidence/android-2026-09-18/` (logs redacted; no tokens in git).

## Environment

- macOS 26.5.2 (Darwin 25.5.0), JDK 17.0.20 (`/opt/homebrew/opt/openjdk@17`), Android command-line tools at
  `/opt/homebrew/share/android-commandlinetools`, emulator AVD `hem74_api34` (API 34 arm64, headless).
- SDK: `hotmic-core-android-source` `main` @ `51de67e` (`0.1.0-SNAPSHOT`) published to `~/.m2` from the
  `hem-96-fixes` worktree *before* any change (baseline); the fixed build is published from the same
  worktree after `c340770` and the affected rows are re-run (see the evidence file).
- Backend: prod `https://api.hotmic.io`, tenant "Bleacher Report Staging", LIVE stream `71e76bb9-…` ("Jordan
  Test", owner `a533e186-…`), test user `fff4c1ac-…`. Second identity `hem96-android-client-b-0001` minted with
  `qa/HotMicCorePublicSampleAndroid/tools/mint-jwt.py` (copied from iOS). Credentials came from
  `/tmp/hem96-core-load.env`; on the emulator they live only in the sample's private `files/hem96.env`
  (pushed with `adb push` + `run-as`, never on the `am` command line, never in logcat).
- The prompt refers to an `INTERNAL-TESTING.md` in this repo. There is none (`find` = 0 hits); the README's
  "Example" section is the only `publishToMavenLocal` guide and it was followed verbatim.

## Every change and why

### `hotmic-core-android` (`mchusma/hem-96-public-sample`, this repo)
- `qa/HotMicCorePublicSampleAndroid/` — README-only sample (Settings, Streams with every `HotMicStreamQuery`
  field, Session with host-owned Media3 player, chat/reactions/report/delete, polls, participants,
  block/unblock/moderator/chat-ban, stop/start-again/release) + `QaRunner` (scripted rows `N4…N24`, `HOLD`,
  logcat tag `HEM96`) + `ReadmeSamples.kt` (every README/MIGRATION Kotlin block compiled verbatim,
  `allWarningsAsErrors = true`) + `FieldCoverage` (reflection) + `tools/` (`push-creds.sh`, `run-sample.sh`,
  `mint-jwt.py`). `README-GAPS.md` lists what an outside developer had to guess.
- `evidence/` — this file, `android-2026-09-18.md`, `android-go-public-checklist.md`, raw logs.
- Nothing under `Example/`, `README.md`, `MIGRATION.md` was changed on this branch (doc fixes are listed as
  proposals in README-GAPS and the checklist; the manager decides whether they go in a separate PR).

### `hotmic-core-android-source` `mchusma/hem-96-fixes` @ `c340770` (one commit, not merged)
| Change | Why |
| --- | --- |
| `WireChatMessage` decoded through `WireChatMessageSerializer`: `id` falls back to `_id`; poll-data delta rows (`chats`, `deleted_chats`, `chats_reactions`, `deleted_reactions`) decoded row-by-row with `Failable`, malformed rows dropped and counted | **F1**: a chat posted by another client without a client `id` (`{"id": null, "_id": "6aad…"}`) failed the whole `WireStreamPollData` decode → `Reconnecting(1…41+)` forever (run 5). Deleting the row did not help: the backend kept serving it in `chats` (F1b, backend). |
| `WireStreamPollData.Stream.polls` is `null` when absent / partially malformed; controller keeps last known polls | A partial poll list would otherwise be delivered as `PollDeleted` for every poll not in it. |
| `StreamPollManager`: after 3 consecutive `Decoding` failures on the same window the window is skipped | Belt and braces for F1-like payloads the row-tolerant decoder still cannot read. |
| `RecentKeySet` + de-dup of chat ids, deleted-chat ids, reaction identity keys (`id`/`_id`, else tuple + `created_at`/`deleted_at`, else per-poll) | **F2**: server windows overlap (`delta.window` is ~6 s wide for a 3 s cursor step) → 60 % of B's messages were delivered twice to A in run 3 (12 dup / 20), reactions and deletes too. |
| Deleted-chat rows are no longer filtered by the author's user+device; they are filtered by the ids *this session* deleted (pending/confirmed tracking, buffered remote deletions if the local DELETE fails) | **F3**: `deleted_chats` rows carry the *author*, so the author's own-device filter hid a moderator's deletion. Run 3: B deleted A's `b832fb3c` (200) — A never got `ChatMessageDeleted`; B got it twice. |
| `HotMicError.Unauthorized` from presence / viewers / poll-data / feed → `cancelSessionWork()`, `ConnectionChanged(Disconnected)`, `sessionFailureHandler` → `HotMicStreamSession.fail()` → `StateChanged(Failed(Unauthorized))`; chat throws `InvalidSessionState` afterwards; startup 401 on feed/viewers propagates from `start()` | **F4**: presence 401 was `runCatching`-swallowed; the session stayed `Active` with no event. |
| `Disconnected` reported once at 20 consecutive poll failures (`DISCONNECTED_FAILURE_THRESHOLD`), then back-off 6/12/24/48/60 s; `Connected` on recovery | Baseline never emits `Disconnected` outside `stop()` and retries every 3 s forever (run 5 reached attempt 41 in 2 min; iOS reached 50+). Same numbers as the iOS fix. |
| Cursor follows `delta.window.to` and polls again immediately while behind; gaps > 5 min dropped (logged) | **New, Android-found**: the backend caps every poll-data window at ~6 s after `from` and ignores `to` (curl: `from=22:24:30 to=22:30:00` → window `22:24:29…22:24:35`, 1 chat; `to` is never honoured). Baseline advances the cursor to `to` → every message between `from+5 s` and `now` is lost after *any* gap (background/Doze, offline, one poll slower than ~3 s). See N13. iOS has the same SDK logic (its `hem-96-fixes` does not address it) — the manager should port this. |
| `HotMicLogger` (tag `HotMicCore`): request finished/failed, decoding failed, skipped window, connection lost, authorization lost — method + path + status only | `logLevel` was accepted by both public constructors, stored in `ApiClient`, and **never read**; the README promises "diagnostics". Android equivalent of iOS F14 but worse (nothing at all). |
| `stream_poll_timeout` applied as OkHttp `callTimeout` on poll-data | It was put in a `TimeoutTag` on the request and never read; OkHttp's 10 s defaults happened to match the served value. |

Public API: `core/api/core.api` unchanged (`apiCheck` clean, no `apiDump` needed). Tests: 80 (63 + 17 new in
`SessionResilienceTest`, `PollCursorCatchUpTest`), 3 skipped live suites.

### `hotmic-core-android-source` `mchusma/hem-96-core-load` (load harness, not merged)
- `core/src/test/kotlin/io/hotmic/core/LiveLoadTest.kt` — N real `HotMicClient`+`HotMicStreamSession` pairs in
  one JVM (internal constructor seam: own `OkHttpClient` per session with one logging interceptor and its own
  `hm-device-id`; `baseUrl` default). Ramp 5/s · hold 300 s · drain 10/s · idle 120 s; chat share/cadence;
  distinct-user tokens file; presence checks; stop rules identical to iOS-B; per-request JSONL, sessions /
  timeline / chat-received CSV, events log, summary. Skipped unless `HOTMIC_CORE_LIVE_QA=1` and `HOTMIC_CORE_LOAD_N`.
- `core/build.gradle.kts` — forwards every `HOTMIC_CORE_*` env var to the test JVM (was a 4-key allow-list),
  2 GB heap for load runs, never up-to-date when `HOTMIC_CORE_LOAD_N` is set.
- `LoadTest/run-tier.sh` (preflight LIVE check, runs the tier), `LoadTest/summarize-tier.py` (port of iOS's; every
  table number recomputed from the raw files).

## iOS ↔ Android public-surface differences (`core/api/core.api` vs the iOS `.swiftinterface` list)

| iOS | Android | Note |
| --- | --- | --- |
| `HotMicClient(apiKey:accessToken:logLevel:)` | `HotMicClient(context, apiKey, accessToken, logLevel)` **and** `HotMicClient(apiKey, accessToken, logLevel)` | Android adds a `Context` overload so `hm-device-id` = `ANDROID_ID`; the Context-free one uses a process-stable fallback. iOS uses `identifierForVendor`. |
| `LogLevel.off/error/info/debug` | `LogLevel.OFF/ERROR/INFO/DEBUG` | Same four levels; **Android emits nothing at any level** (fixed on `hem-96-fixes`). |
| async + completion-handler variants of every call | `suspend` only | No callback overloads and no `Future`/`ListenableFuture` — Java hosts cannot call the API without writing coroutine glue (README-GAPS 5). `eventListener` is the only callback surface. |
| `fetchStreams(matching:)` | `fetchStreams(query = HotMicStreamQuery())` | Default argument instead of an overload. |
| `HotMicStreamQuery` 6 fields | same 6 fields, `data class` (`copy`) | Same defaults (`limit = 20`). |
| `HotMicStreamSession.state` (value) | `state: StateFlow<State>` | Android exposes a `StateFlow`; `state.value` for the synchronous read. |
| `events: AsyncStream`, `eventHandler` | `events: SharedFlow<Event>` (buffer 64), `eventListener` | Same 12 events, same names (`StateChanged`, `ConnectionChanged`, `StreamUpdated`, `StreamEnded`, `StreamDeleted`, `ChatBatchReceived`, `ChatMessageDeleted`, `ChatMessageReactionDeleted`, `PollCreated`, `PollUpdated`, `PollDeleted`, `ParticipantsUpdated`). `SharedFlow` **drops** events for a slow collector once the 64-slot buffer is full (`tryEmit`); iOS's `AsyncStream` buffers unboundedly. `eventListener` is nulled after `Stopped`/`Failed` (iOS keeps `eventHandler`). |
| `State.failed(HotMicError)` etc. | `State.Failed(error)` data class, others `data object` | Same six states. |
| `ConnectionState.reconnecting(attempt:)` | `Reconnecting(attempt)` | Same three states. |
| `HotMicError` 8 cases, `LocalizedError` | `sealed class` with 8 subclasses; `Transport(description)`, `Forbidden(detail)`, `Server(statusCode, detail)`, `Decoding(description)`; `InvalidRequest`/`Cancelled`/`Unauthorized`/`InvalidSessionState` are `object`s | Same 8; `message` populated on all. |
| `HotMicStream.State` enum (`.scheduled/.live/.vod/.ended`) | `@JvmInline value class State(rawValue)` with companion constants | Unknown raw values survive on Android (`State("X")`); iOS decodes unknown as nil/fails. Same for `StreamType`, `VideoOrientation`, `ReactionType`, `Role`, `Restriction`. |
| `HotMicChatMessage.Reaction.ReactionType.init(rawValue:)` | `ReactionType(rawValue)` constructor (value class) | Equivalent. |
| `Equatable`/`Hashable`/`Decodable` | `data class` `equals`/`hashCode`/`copy`/`componentN` | Android additionally exposes `copy()`/`componentN()` on every model (public API surface is larger; changing a constructor parameter list is a binary-incompatible change for Kotlin callers using `copy`). Models are not `Parcelable`/`Serializable`. |
| `BuildConfig` not exposed | `io.hotmic.core.BuildConfig` is **public** (`SDK_VERSION`, `DEBUG`, `BUILD_TYPE`, `LIBRARY_PACKAGE_NAME`) | Leaks a class into the public API dump; harmless but should be `internal`/removed before 1.0 (`buildConfig` could be disabled and the version passed via a resource). |
| Session deallocates when the app drops it (A17: 10 s) | Session is **never** collected while active (N17: `WeakReference` still set after 10× `System.gc()`): the polling/presence coroutines in the client's `sessionScope` hold the controller → session | Not a leak the app can see if it calls `stop()` as documented, but an app that drops an active session keeps polling until the process dies. Opinion: `HotMicClient` should expose `close()` that cancels `sessionScope`, or sessions should hold their coroutines in a per-session scope cancelled on `stop()`. Not changed (public API). |

## Opinions not coded
- **Poll-data window cap** (above) is the most consequential backend finding of this pass: with the shipped
  SDK, every background/foreground cycle loses the chat sent while away, and any poll slower than ~3 s can
  drop messages. The fix on `hem-96-fixes` is client-side mitigation; the API should honour `to` (or document
  the cap and return `window` so clients can page — it already returns `window`).
- The README says "Create a new `HotMicClient` when the access token changes" but gives no way to hand the
  new token to an *active* session; token rotation means stop → new client → new session (N16). A
  `HotMicClient.updateAccessToken()` (or an `accessTokenProvider` lambda) would avoid tearing the session
  down. Not coded (public API).
- `SharedFlow(extraBufferCapacity = 64)` + `tryEmit`: a host that collects `events` on a busy dispatcher and
  falls 64 events behind silently loses events (no error). Either document it or use a suspending `emit` /
  unbounded buffer. Not coded (behavioural change).
- `PollUpdated` ×3 fires on every session start for the polls the user already answered (the feed's poll
  object differs from the poll-data one — `responses`/`myAnswer` representation); harmless, same on iOS (A6).
- `Example/` and the README pin `media3 1.4.1` while `compileSdk 34`; fine, but Media3 1.5+ requires
  `compileSdk 35`, so the pin should be called out.

## Corrections made during the day (kept for the reviewer)

- **Sample, N7 VOD**: the first VOD run "failed" because the sample paused the player on `StreamEnded`, which the
  session emits on the first poll of every VOD stream (`end_video=true`). The handler now pauses only when the
  stream is LIVE; the row was re-run and passes. The first run's log was overwritten; the screenshot of the paused
  player was replaced by the re-run's. Recorded as README-GAPS #24.
- **Sample, N7 launch**: `SessionActivity` is not exported, so `am start` cannot target it; `StreamsActivity` now
  forwards `HEM96_MEDIA*` extras. One aborted media attempt (SecurityException from `am`) is not evidence.
- **Load harness, chat latency**: the first harness revision parsed `created_at` with one `SimpleDateFormat`
  shared by the event-listener threads; it is not thread-safe and corrupted a minority of `created_ts` values
  (2–25 % of rows, growing with N). `summarize-tier.py` now takes the majority `created_ts` per message id
  (it is a server timestamp, identical for every receiver) and excludes disagreeing rows, reporting the count;
  the harness formats per call from `b36a88b` on. Request/poll/duplicate numbers come from the interceptor and
  the id set and are unaffected. The `fixes` tiers ran with the pre-fix copy of the harness (the copy was taken
  before the fix), so their latency rows are filtered the same way.
- **Soak**: N20 ran concurrently with the three baseline tiers (time), which makes it a heavier soak than iOS's
  (516 chats received in 30 min) but means its PSS swings are chat-volume driven.
- The `hem-96-fixes` worktree briefly held uncommitted copies of the harness files (`LoadTest/`, `LiveLoadTest.kt`,
  the env-forwarding `build.gradle.kts`) to run the `fixes` tiers; they were removed and `git status` is clean
  (`run12.out`).
