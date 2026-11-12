# Pi Mobile app

Android client for the Pi LAN host in [`../server`](../server). Kotlin and
Jetpack Compose, package `dev.pi.mobile`, minSdk 29, targetSdk 36,
compileSdk 37.

## What it does

- Connect with the desktop address and the six-digit PIN; the PIN is masked by
  default, sealed with an Android Keystore key, and only private network
  addresses are accepted.
- Chat with the agent: streaming text, a markdown subset, collapsible thinking,
  tool cards with streamed output, queue/steer while a run is active, and Stop.
- Answer blocking requests (`confirm`, `select`, `input`, `editor`) in bottom
  sheets, with a persistent "Approval waiting" banner after a dismiss.
- Sessions: list the host's session store grouped by project, cold-resume one,
  start a new chat, rename or delete, and confirm before switching while a run
  is active.
- Model and thinking pickers, session token/cost statistics, theme and text
  size, forget server.
- Background alerts: a `connectedDevice` foreground service keeps the one
  WebSocket open, so approvals and finished/error updates reach the phone.

## Build

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest   # needs an Android device
./gradlew :app:lintDebug
```

Requires JDK 17 and the Android SDK with platform 37 and build-tools 36.0.0.

## Release build

`assembleRelease` is signed only when `release-signing.properties` exists in the
repository root (gitignored) with `storeFile`, `storePassword`, `keyAlias` and
`keyPassword`. Without it the release APK is unsigned. The published APK was
signed with a local key that is not in this repository; a rebuild with another
key cannot update an installed copy.

## Layout

| Path | Contents |
| --- | --- |
| `src/main/kotlin/dev/pi/mobile/net/` | Login and cookie handling, WebSocket JSONL transport, local-network policy, PIN rules |
| `src/main/kotlin/dev/pi/mobile/proto/` | RPC record parsing, lenient JSON helpers, command builders |
| `src/main/kotlin/dev/pi/mobile/session/` | `PiConnection`, `ChatReducer`, `ChatController`, sessions repository |
| `src/main/kotlin/dev/pi/mobile/service/` | Foreground alerts service and notification building |
| `src/main/kotlin/dev/pi/mobile/ui/` | Compose screens, sheets, theme, markdown renderer |
| `src/test/` | JVM tests and captured RPC fixtures (`src/test/resources/rpc/`) |
| `src/androidTest/` | Compose UI and screenshot tests |
| `src/debug/` | Debug-only intent extras (`pi_host`, `pi_pin`, `pi_debug_autoconnect`, `pi_debug_prompt`) |

The release variant compiles the debug-extra parser to a null stub.
