# CLAUDE.md

Claude Code: read [AGENTS.md](AGENTS.md) for the full project guide.

The essentials:

- Plain Java, no Kotlin, no AndroidX, no Compose. Only runtime dependency: ZXing.
- Build/test: `./gradlew test assembleDebug assembleRelease` (JDK 17+, Android SDK 36).
- minSdk 26 / targetSdk 36. Guard newer APIs with `Build.VERSION.SDK_INT`.
- Call `Ui.systemBars()` **after** `setContentView()`; calling it first crashes Android 11+.
- Pairing codes are single-use (5 minutes). Rewrite loopback QR codes with
  `PairPayload.withBase()` instead of redeeming twice.
- Keep the APK small and the change focused. Do not commit `local.properties`, `dist/`, build
  outputs or signing keys.
- Add a Robolectric launch test when touching activity startup.
- Disclose AI assistance in the PR description.
