# HotMicCore Android — go-public checklist (exact steps for Chris)

Tracker: https://github.com/mchusma/hotmic-engineering-manager/issues/96 · written 2026-09-18 from the
`hem-96-public-sample` QA pass. Nothing below has been run against GitHub Packages or `main`; every step is a
proposal for Chris to execute. Where a code change is needed it is listed as a PR to open, not merged.

Repos: **`-source`** = `hotmic-wp/hotmic-core-android-source` (private, Core code). **`dist`** =
`hotmic-wp/hotmic-core-android` (this repo, the one that flips public).

## 0. Decide the distribution channel first (blocks everything else)

GitHub Packages (Maven) **requires a GitHub personal access token with `read:packages` for every consumer,
even for a package in a public repository** — GitHub documents this explicitly for the Maven/Gradle registry
(only the Container registry allows anonymous pulls). The old UI SDK already shipped this way
(`maven.pkg.github.com/hotmic-wp/android-sdk` in the 1.9.x sample's `settings.gradle`, with a token pasted into
the build) and before that via a 12-hour CodeArtifact token; MIGRATION.md sells Core as "no CodeArtifact token
is needed", which Packages would contradict.

**Recommendation: do not ship a public SDK on GitHub Packages.** Two alternatives, either is fine:

| Channel | Consumer experience | Effort (one person) | Notes |
| --- | --- | --- | --- |
| **Maven Central** (Sonatype Central Portal) | `mavenCentral()` — already in every Android project, no auth | ~1.5–2 days: Central Portal account + verify the `io.hotmic` namespace (DNS TXT record on hotmic.io), GPG key, add `com.vanniktech.maven.publish` (or `signing` + `maven-publish` with the Central publisher), POM needs `licenses`/`developers`/`scm` (a proprietary licence text is accepted, but it must be declared), sources + javadoc (Dokka) jars, first release is manually "published" in the portal. | Matches iOS's "public package manager, no auth" story (SPM). Recommended. |
| **Self-hosted Maven on S3 + CloudFront** (e.g. `https://maven.hotmic.io`) | `maven { url = uri("https://maven.hotmic.io") }`, no auth | ~0.5 day: bucket + CloudFront (HotMic already runs CloudFront), `publishing.repositories.maven { url = uri("s3://…") }` with the AWS credentials provider, a CI job. | No namespace verification, no signing requirement, HotMic controls availability. Consumers must add one repo line. |

If Chris still wants GitHub Packages for 1.0.0 (fastest: ~1 hour), the rest of this checklist covers it and the
README must say the token requirement out loud (section 3).

## 1. Publish `io.hotmic.core:hotmic-core-android:1.0.0` from `-source`

`core/build.gradle.kts` **has** a `publishing { publications { release … } }` block (groupId/artifactId/POM)
but **no `repositories {}`** — `publishToMavenLocal` is the only target today, and CI (`.github/workflows/ci.yml`)
only does `publishToMavenLocal`. PR to open on `-source` (not merged):

1. Version: `version = "1.0.0"` in `core/build.gradle.kts` (currently `0.1.0-SNAPSHOT`; `SDK_VERSION` in
   `BuildConfig` and the `hm-sdk-version` header follow it). Proposal: **1.0.0** to match iOS 1.0.0.
2. Add the repository (GitHub Packages variant — **the URL must be the public dist repo**, because a Maven
   package's visibility is the visibility of the repository it is published under; publishing under
   `-source` would make the package private even after `dist` flips public):

   ```kotlin
   publishing {
       repositories {
           maven {
               name = "GitHubPackages"
               url = uri("https://maven.pkg.github.com/hotmic-wp/hotmic-core-android")
               credentials {
                   username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                   password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
               }
           }
       }
       publications { /* existing `release` publication */ }
   }
   ```
   For Maven Central replace the block with the `com.vanniktech.maven.publish` plugin configuration
   (`mavenPublishing { publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL); signAllPublications(); coordinates("io.hotmic.core", "hotmic-core-android", "1.0.0"); pom { … } }`).
3. Also in that PR: make `io.hotmic.core.BuildConfig` non-public (it is in `core.api` today; `android.buildFeatures.buildConfig = false` and pass the version as a Kotlin `const` generated at build time, or accept it and document it), and merge `hem-96-fixes` first if Chris wants 1.0.0 to include the F1–F4 fixes (recommended — without them a null-id chat from any other client stalls every Android session on that stream).
4. Commands (from a clean `-source` checkout of the release commit):

   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
   ./gradlew :core:testDebugUnitTest :core:apiCheck :core:assembleRelease :demo:assembleRelease
   # GitHub Packages: a classic PAT with write:packages (+ repo, since -source is private) — never in the repo
   export GITHUB_ACTOR=<gh-user> GITHUB_TOKEN=<pat-with-write:packages>
   ./gradlew :core:publishReleasePublicationToGitHubPackagesRepository
   # Maven Central instead: ./gradlew :core:publishAndReleaseToMavenCentral --no-configuration-cache
   git tag -a v1.0.0 -m "HotMicCore Android 1.0.0" && git push origin v1.0.0
   ```
5. Verify from a scratch project with **no** `mavenLocal()`: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep hotmic` resolves `1.0.0` from the remote (section 5 re-test does this end to end).

## 2. Consumer-side README (what the public README must say)

Replace the current "Installation" (which says `mavenLocal()` + `0.1.0-SNAPSHOT`) and the "Distribution" and
"Example" sections. GitHub Packages variant — **say the token requirement explicitly**:

> HotMicCore is published to GitHub Packages. GitHub requires a personal access token with the
> `read:packages` scope to download Maven packages **even though the package is public**. Create one at
> github.com → Settings → Developer settings → Tokens (classic), then put it in `~/.gradle/gradle.properties`
> (never in the repository):
>
> ```properties
> gpr.user=<your-github-username>
> gpr.key=<token-with-read:packages>
> ```
>
> ```kotlin
> // settings.gradle.kts
> dependencyResolutionManagement {
>     repositories {
>         google()
>         mavenCentral()
>         maven {
>             url = uri("https://maven.pkg.github.com/hotmic-wp/hotmic-core-android")
>             credentials {
>                 username = providers.gradleProperty("gpr.user").get()
>                 password = providers.gradleProperty("gpr.key").get()
>             }
>         }
>     }
> }
>
> // app/build.gradle.kts
> dependencies {
>     implementation("io.hotmic.core:hotmic-core-android:1.0.0")
>     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") // suspend API
>     implementation("androidx.media3:media3-exoplayer:1.4.1")                  // your player
>     implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
>     implementation("androidx.media3:media3-ui:1.4.1")
> }
> ```

Maven Central variant: drop the whole token paragraph and the `maven {}` block; `mavenCentral()` is enough.
Either way also: remove "Until it is published to GitHub Packages, publish it to your local Maven repository
from the private `hotmic-core-android-source` repo" and the `JAVA_HOME`/`publishToMavenLocal` steps (they name
a private repo), and fold in the README-GAPS items marked "High" (`qa/HotMicCorePublicSampleAndroid/README-GAPS.md`
#3, #4, #8, #10, #11).

## 3. Repo hygiene before flipping `dist` public (verified 2026-09-18)

| Check | Result | Action |
| --- | --- | --- |
| No Core sources in `dist` | PASS — no `package io.hotmic.core` file outside `qa/` (the sample only *uses* the API) | — |
| Secrets in history: `git log -p --all \| grep -iE "apikey\|api_key\|accesstoken"` → 22 hits; JWT-shaped strings (`eyJ…`) → **0**; `hotmic.apiKey=`/`hotmic.accessToken=` → present once each, **value length 0** (`local.properties.example`) | PASS — every hit is a variable name, doc prose, or an empty example value | — |
| `Example/` hardcoded credentials (iOS F8 equivalent) | PASS — `Example/build.gradle.kts` reads `hotmic.apiKey`/`hotmic.accessToken` from the gitignored `local.properties`; committed defaults are empty; `Credentials.kt` masks values in the UI | — |
| `.gitignore` covers `local.properties`, `*.env`, keystores | PASS | Add `qa/**/local.properties` is already covered by the root rule; the sample has its own `.gitignore` too |
| LICENSE matches iOS | PASS — both are the 44-byte "Copyright 2026 HotMic. All Rights Reserved." | If publishing to Maven Central, the same text is declared in the POM `licenses` block (proprietary is allowed). Consider whether a public SDK repo should carry a real licence/EULA — "All Rights Reserved" technically forbids consumers from *using* the Example code. |
| `Example/` builds without `mavenLocal()` once the package exists | **TODO** — `settings.gradle.kts` lists `mavenLocal()` first with a comment naming the private repo; `Example/build.gradle.kts` depends on `0.1.0-SNAPSHOT` | PR on `dist`: replace `mavenLocal()` with the chosen repository, bump to `1.0.0`, re-run `./gradlew :example:assembleDebug` from a fresh clone (section 5) |
| README install section | **TODO** — see section 2 | Same PR |
| `MIGRATION.md` reviewed against the old UI SDK | PASS with one note — every old-SDK symbol it names (`HotMicPlayer.getStreams/Builder/closePlayer/isPlayerOpen/showPollPanel/showPeoplePanel/showProfilePanel`, `setStreamId/credential/setUICallback/setChatFragment/useCustomPlayer/setDataOnlyMode`, `PlayerCallbacks`/`getPlatformToken`/`onPlayerClosed`, `HMChatViewModel.sendChat/sendReaction`, `chatTipsData/streamChatStateLiveData/pollActiveData/roomActiveCountData`, `ChatDelegatesListener`, `ChatReaction.LIKE…SADNESS`, `HMStreamBasic`, `Tip`/`SampleTipDelegate`, `HMPlayerSdkTheme`, `ANVideoCloseReason`, `setAnalyticHandler`) is used by the old sample at `/Volumes/PNYRP60PSSD/orca/hotmic-android-sdk` (`io.hotmic.player:hotmic-android-sdk:1.9.1-alpha1`). **1.10.3 itself is not available locally** (`hotmic-ui-android` checkout is empty), so the check is against the 1.9.1 sample; nothing in MIGRATION contradicts it. Every Core symbol MIGRATION maps to exists in `core/api/core.api` and compiles (`ReadmeSamples.kt`). | Note the old repository line: MIGRATION says "Remove the CodeArtifact repository" — the 1.9.x sample used **GitHub Packages** (`maven.pkg.github.com/hotmic-wp/android-sdk`), so say "remove the HotMic Maven repository (CodeArtifact or GitHub Packages)". |
| `README.md` install/build instructions name the private repo | **TODO** — "publish it … from the private `hotmic-core-android-source` repo", `Distribution` section | Remove in the same PR |
| Branch protection / default branch | Not checked (needs org admin) | Enable branch protection on `main` before flipping |
| GitHub Packages visibility (if used) | The package inherits `dist` visibility once linked to it; publishing from `-source` CI needs a PAT with access to `dist` | Set in the `-source` CI secrets, never in the repo |

## 4. README / MIGRATION vs iOS — what iOS documents that Android does not (and the reverse)

- iOS README documents **completion-handler variants** and **`Task` cancellation** (`try await` cancellation →
  `.cancelled`). Android has no callback overloads; the README does not say what cancelling the calling
  coroutine does (it throws `HotMicError.Cancelled`, N17). Add one line under "Errors".
- iOS README "Installation" has a one-line SPM story with no credentials; Android's has none yet (section 2).
- Android README has sections iOS lacks: "Media Playback" (Media3 sample), "Event Listener", "Errors" table,
  "Distribution" (remove), the `Context` constructor note, ProGuard/R8 note — keep them.
- MIGRATION: Android has a "Not Available in Core" section iOS lacks (good); the mapping tables are equivalent.
  iOS's "Replace Delegate Updates with Events" ≙ Android's "Replace Callbacks with Events".
- Neither README documents: base URL / no staging switch, polling cadence & duplicates, guest fallback on a bad
  token, background/Doze behaviour, the `events` buffer (Android) — README-GAPS #1, #3, #4, #6, #11.

## 5. Post-publish re-test (run only when Chris says the repo is public)

```bash
cd /tmp && rm -rf hotmic-core-android && git clone https://github.com/hotmic-wp/hotmic-core-android.git && cd hotmic-core-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
grep -n mavenLocal settings.gradle.kts && echo "FAIL: mavenLocal still present"
rm -rf ~/.m2/repository/io/hotmic            # no local copy may satisfy the dependency
./gradlew :example:assembleDebug            # README instructions only (plus gpr.* if Packages)
./gradlew :example:installDebug && adb shell am start -n io.hotmic.core.example/.StreamsActivity
```
Record PASS/FAIL in `evidence/android-public-<date>.md` with the resolved artifact URL from
`./gradlew :example:dependencies --configuration debugRuntimeClasspath | grep hotmic`.
