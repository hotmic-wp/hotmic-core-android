# HotMicCore public sample (Android) — HEM-96 QA

A host app written **only from `README.md` and `MIGRATION.md`** of `hotmic-core-android`, the way an outside
developer would build it, plus a scripted QA runner so every row of the HEM-96 matrix is reproducible on an
emulator. Gaps met on the way are in `README-GAPS.md`.

## Build (exactly the README's instructions)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
# Core is not published yet: publish it to ~/.m2 from the private source repo first
(cd ../../../hotmic-core-android-source && ./gradlew :core:publishToMavenLocal)
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug        # allWarningsAsErrors = true → 0 warnings
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`settings.gradle.kts`, `app/build.gradle.kts` and `AndroidManifest.xml` contain the README's Installation
block verbatim (`mavenLocal()`, `io.hotmic.core:hotmic-core-android:0.1.0-SNAPSHOT`, Media3 1.4.1, `INTERNET`).
`ReadmeSamples.kt` is every README/MIGRATION Kotlin block pasted verbatim so the build proves they compile.

## Screens

- **Streams** — every `HotMicStreamQuery` field as a control (live/scheduled/VOD toggles, `userId`, `limit`,
  prev/next page), fetch-by-id for streams and users, tap a row to open a session.
- **Session** — host-owned Media3 `ExoPlayer` on `hlsUrl` (LIVE) / `vodUrl` (VOD), chat list with
  send / like / unlike / who-reacted / report / delete, polls with answer, participants, block / unblock /
  make-moderator / chat-ban, Stop / Start-again / Release, live event log.
- **Settings** — API key + access token (the README's "add your API key and access token in Settings").

## QA runner

Credentials are pushed into the app's private storage (never on the command line, never in logcat):

```bash
tools/push-creds.sh /tmp/hem96-core-load.env HEM96_VOD_STREAM_ID=<id> HEM96_CLIENT_B_USER_ID=<id> ...
tools/run-sample.sh "N4,N5,N6" out.log 300          # rows → logcat tag HEM96 → redacted out.log
tools/run-sample.sh HOLD out.log 400 --es HEM96_HOLD_SECONDS 300 --es HEM96_SEND_EVERY 20
```

Rows: `N4` list/filter/page · `N5` detail/user · `N6` start latency ×3 · `N8` chat · `N10` polls · `N11`
participants · `N12` block · `N15` bad credentials · `N15MID` mid-session expiry (`HEM96_SHORT_TOKEN`) · `N16`
token rotation (`HEM96_ROTATED_TOKEN`) · `N17` lifecycle · `N19` 10 open/close cycles with PSS · `N20` soak
(`HEM96_SOAK_MIN`) · `N21` `eventListener` + Context-free client · `N24` field coverage · `HOLD` keep a session
up for `HEM96_HOLD_SECONDS`, optionally sending every `HEM96_SEND_EVERY` s, logging every event with a UTC stamp
(used for cross-client, background/Doze, airplane and F1 probes driven from the host).

Media (N7) is driven by launching `SessionActivity` directly:

```bash
adb shell am start -n io.hotmic.core.publicsample/.SessionActivity --es stream_id <id> \
  --es HEM96_MEDIA hls --ei HEM96_MEDIA_SECONDS 90            # or: --es HEM96_MEDIA vod --ei HEM96_SEEK_AT 15 --ei HEM96_SEEK_TO 45
```

Every row logs `ROW <id> PASS|FAIL|INFO …` and the run ends with `AUTORUN COMPLETE`. `Hem96.redact` strips the
API key, the token and anything JWT-shaped before a line leaves the process; `run-sample.sh` redacts again.
