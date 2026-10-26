# AGENTS.md

Instructions for AI coding agents (OpenCode, Claude Code, Cursor, Codex, …) working on OpenCode
Pair. Read this before changing code.

## What this is

A native Android client for an OpenCode server running on a PC. The phone pairs by scanning the
QR code printed by `opencode pair`, then uses the HTTP API with a bearer-style Basic token. The
official web UI is kept as a WebView fallback.

## Build and test

```bash
./gradlew test assembleDebug assembleRelease
```

Windows: `.\gradlew.bat ...`. JDK 17+ and Android SDK 36 are required. `local.properties` points
at the local SDK and is not committed.

Run the unit tests before every PR. `MainActivityLaunchTest` uses Robolectric to catch startup
crashes; it caught a real `getInsetsController()` NPE, so do not remove it.

## Constraints

- **Plain Java only.** No Kotlin, no AndroidX, no Compose, no networking library. The only runtime
  dependency is `com.google.zxing:core`.
- **minSdk 26, targetSdk 36, compileSdk 36.** Guard newer APIs with `Build.VERSION.SDK_INT`.
- **Keep the APK small** (~0.4 MB). Prefer platform widgets and `HttpURLConnection`.
- **No telemetry, analytics, ads or background services.**
- **Call `Ui.systemBars()` after `setContentView()`.** On Android 11+
  `Window.getInsetsController()` dereferences the decor view and throws before it is installed.
- **Pairing codes are single-use** and expire after 5 minutes. Do not redeem the same code twice.
  A loopback QR code is rewritten onto a reachable address with `PairPayload.withBase()`.
- **Edge-to-edge** is manual from API 30 up; keep the inset handling in the activities.
- Do not commit `local.properties`, `dist/`, build outputs or signing keys.

## Layout

| File | Responsibility |
| --- | --- |
| `MainActivity.java` | Sessions, connections and settings shell |
| `ChatActivity.java` | Native chat, polling, permissions, model picker |
| `AddConnectionActivity.java` | QR and manual pairing, loopback fallback dialog |
| `ScanActivity.java` | Camera + ZXing scanner |
| `WebActivity.java` | Official web UI fallback |
| `ApiClient.java` | Plain `HttpURLConnection` API client (no Android imports) |
| `PairPayload.java` | QR/link parsing, loopback detection and rewriting |
| `ConnectionStore.java` | Connections, tokens, legacy migration |
| `Theme.java`, `Ui.java` | Theme palette and platform-widget builders |
| `ChatMessage.java`, `Markdown.java` | Message parsing and Markdown rendering |

## API notes

- Auth: HTTP Basic `opencode:<token>`.
- Pair: `GET /auth/connect/<code>` with `Accept: application/json` returns `{"token":"..."}`.
- Sessions: `/api/session`, `/api/session/{id}`, `/api/session/{id}/message`,
  `/api/session/{id}/prompt`, `/api/session/{id}/interrupt`, `/api/session/{id}/model`.
- Models: `/api/model`; projects: `/api/project`; permissions: `/api/permission`.
- Messages are polled, not SSE. Merge by message ID and only re-render changed views.

## PR checklist

- [ ] `./gradlew test assembleDebug assembleRelease` passes.
- [ ] The change is focused; no unrelated reformatting.
- [ ] New APIs are guarded for minSdk 26.
- [ ] `Ui.systemBars()` stays after `setContentView()`.
- [ ] The README/CONTRIBUTING docs are updated if behaviour changes.
- [ ] AI-assisted work is disclosed in the PR description.
