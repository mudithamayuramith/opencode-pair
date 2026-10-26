<p align="center">
  <img src="docs/branding/opencode-logo-light.svg" alt="OpenCode Pair" width="104">
</p>

<h1 align="center">OpenCode Pair</h1>

<p align="center">
  A tiny native Android client for <a href="https://opencode.ai">OpenCode</a>.<br>
  Scan the QR from <code>opencode pair</code>, browse sessions, and chat with the agent on your PC.
</p>

<p align="center">
  <a href="https://github.com/mudithamayuramith/opencode-pair/actions/workflows/build.yml">
    <img src="https://github.com/mudithamayuramith/opencode-pair/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-211E1E.svg" alt="MIT">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/APK-~0.4%20MB-211E1E.svg" alt="APK ~0.4 MB">
</p>

OpenCode Pair is a small, dependency-light Android shell for the OpenCode server running on your
computer. It keeps the official web UI as a fallback, but gives you a native session list, a
native chat view and QR pairing without opening a browser.

- **Native chat** — Markdown, fenced code, reasoning, tool calls, retries and permissions.
- **Sessions** — browse, create, switch models and delete sessions.
- **Pairing that works** — scans the `opencode pair` QR code and handles the loopback-address
  problem for you.
- **Multiple computers** — save LAN and OpenTunnel connections and switch between them.
- **Tiny** — plain Java, no Kotlin, no AndroidX, no Play Services, no telemetry. The only
  dependency is the pure-Java ZXing QR decoder.

## Install

Download the latest APK from **[Releases](https://github.com/mudithamayuramith/opencode-pair/releases/latest)**:

| File | Use it for |
| --- | --- |
| `app-release.apk` | Normal sideloading (recommended) |
| `app-debug.apk` | Debugging and bug reports |

Android 8.0 (API 26) or newer. The release APK is signed with the debug key so it can be
sideloaded without a keystore; replace `signingConfigs.debug` before publishing your own build.

## Pair

Run this in the project you want to open:

```bash
opencode pair
```

It prints three one-time links (valid for 5 minutes) and a QR code of the **first** link:

| Link | Reachable from |
| --- | --- |
| `http://127.0.0.1:<port>/auth/connect/<code>` | the PC itself only |
| `http://192.168.x.x:<port>/auth/connect/<code>` | the same Wi-Fi |
| `https://<id>.opentunnel.xyz/auth/connect/<code>` | anywhere, via OpenTunnel |

The first link is loopback, so a phone cannot open it. The app detects this and asks once for the
LAN/tunnel link (or just the computer address), then redeems the same one-time code there. If you
already saved a connection to that computer, it reuses that address automatically.

To make the QR directly scannable, tell the CLI which address to advertise:

```bash
opencode pair --url http://192.168.x.x:<port>
```

### In the app

1. Open **OpenCode Pair** and tap **Scan QR code**.
2. Point the camera at the terminal. If the QR contains `127.0.0.1`, paste the LAN/tunnel link
   printed underneath it (or type the computer's address) when asked.
3. The token is stored with the connection. Add as many computers as you like from
   **Connections**.

## What works

| Area | Details |
| --- | --- |
| Sessions | Newest first, pull to refresh, project picker, create/delete, current model on each card |
| Chat | Polling, merged messages, Markdown/code/tool/reasoning cards, retries, stop/interrupt |
| Models | Change the session model from the chat header; user choice is pinned |
| Permissions | Allow once / Always / Reject cards |
| Connections | LAN and tunnel addresses, rename/delete, active connection |
| Settings | System/light/dark theme, open the web UI, disconnect |
| Web fallback | Full official UI in a WebView with the session cookie already set |

## Build

### Windows (PowerShell)

```powershell
$env:JAVA_HOME="C:\Users\<you>\AppData\Local\jdk21"   # JDK 17+
$env:ANDROID_HOME="C:\Users\<you>\AppData\Local\Android\Sdk"
.\gradlew.bat test assembleDebug assembleRelease
```

### Linux / macOS / WSL

```bash
./build.sh          # downloads the Android SDK if needed, then builds
```

### GitHub Actions

The workflow in [`.github/workflows/build.yml`](.github/workflows/build.yml) runs the tests and
builds both APKs on every push. No local setup is needed.

| Output | Notes |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | Debug build |
| `app/build/outputs/apk/release/app-release.apk` | Release build, debug-signed |

### Toolchain

| Component | Version |
| --- | --- |
| Gradle | 8.14.3 |
| Android Gradle Plugin | 8.13.0 |
| compileSdk / targetSdk | 36 (Android 16) |
| minSdk | 26 (Android 8.0) |
| Java | 17+ source/target; JDK 21 works |

`local.properties` points at your SDK and is not committed.

## Project layout

```text
app/src/main/java/app/opencodepair/
  MainActivity.java          sessions / connections / settings shell
  ChatActivity.java          native chat, polling, permissions, model picker
  AddConnectionActivity.java QR and manual pairing
  ScanActivity.java          Camera + ZXing QR scanner
  WebActivity.java           official web UI fallback
  ApiClient.java             plain HttpURLConnection API client
  ConnectionStore.java       saved connections and tokens
  PairPayload.java           QR/link parsing and loopback rewriting
  Theme.java / Ui.java       platform-widget theme and helpers
app/src/test/                JVM and Robolectric tests
docs/branding/               source SVG for the app icon
```

## Tests

```bash
./gradlew test
```

19 local tests cover message/part/retry parsing, change signatures, pairing-link parsing, QR
payloads, loopback rewriting, deduplication and IPv6 loopback detection. Robolectric launch tests
cover the main shell and the pairing screen, so startup crashes are caught without a device.

## Contributing

Contributions are very welcome — bug reports, device testing, docs, translations and features.
Please read [CONTRIBUTING.md](CONTRIBUTING.md) first.

Good first issues:

- Test on more Android versions and OEM launchers.
- Add screenshots/GIFs of the native UI.
- Improve tablet and landscape layouts.
- Render images and diffs in the native chat.
- Add more unit tests around the API client.

If an AI assistant is helping you, point it at [AGENTS.md](AGENTS.md) (OpenCode and other agents)
or [CLAUDE.md](CLAUDE.md) (Claude Code). Both describe the build, architecture and constraints.

## License

[MIT](LICENSE). OpenCode Pair is an unofficial community client and is not affiliated with the
OpenCode project. The OpenCode name and logo belong to their respective owners.

<details>
<summary>Implementation notes</summary>

- Authentication uses HTTP Basic `opencode:<token>`. Pairing redeems
  `GET /auth/connect/<code>` with `Accept: application/json` and stores the returned token.
- All HTTP calls are plain `HttpURLConnection`; JSON is `org.json`. No networking library and no
  AndroidX in the app.
- `targetSdk 36` means Android 16 no longer delivers `onBackPressed()`. The app registers a
  platform `OnBackInvokedCallback` on API 33+ and keeps the legacy path for API 26–32.
- Edge-to-edge is applied manually from API 30 up. `Ui.systemBars()` must be called **after**
  `setContentView()`: on Android 11+ `Window.getInsetsController()` dereferences the decor view.
- Cleartext HTTP and user-installed CA certificates are trusted on purpose — the pairing server
  usually runs on a LAN address with a self-signed certificate.
- QR scanning uses the framework `android.hardware.Camera` and `com.google.zxing:core`; frames are
  decoded on a background thread with one recycled preview buffer.
- `PairPayload` parses QR links, printed links, JSON payloads and pasted text, orders loopback
  addresses last and recognises IPv4/IPv6 loopback. A scanned loopback code can be rewritten onto
  a saved or manually entered address without consuming the code twice.
- Messages are polled, not streamed over SSE: the latest 60 on open, then the latest 30 every
  1.3–3.6 seconds. Only changed message views are re-rendered.

</details>
