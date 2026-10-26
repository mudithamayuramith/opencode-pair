# Contributing to OpenCode Pair

Thanks for helping! This is a small project, so every bug report, test run and pull request
matters.

## Ways to help

- **Report a bug** with the issue template. Include your Android version, phone model and the
  exact screen/error text.
- **Test on a real device.** Pairing, camera scanning, edge-to-edge and launcher icons behave
  differently across OEMs.
- **Improve the native UI** — tablet/landscape layouts, images/diffs in chat, accessibility.
- **Add tests** around `ApiClient`, `PairPayload` and the activities.
- **Improve the docs** or add screenshots/GIFs.

## Build and test

```bash
./gradlew test assembleDebug assembleRelease
```

On Windows use `.\gradlew.bat`. You need JDK 17+ and Android SDK 36. The GitHub Actions workflow
does the same on every push.

## Ground rules

- Keep the app **plain Java** and **AndroidX-free**. The only runtime dependency is ZXing.
- Do not add telemetry, analytics, ads or a networking library.
- Keep the APK small; prefer platform widgets and `HttpURLConnection`.
- Guard APIs newer than minSdk 26 with `Build.VERSION.SDK_INT`.
- Call `Ui.systemBars()` **after** `setContentView()`. On Android 11+ it dereferences the decor
  view.
- Pairing codes are one-time and expire after five minutes. Never redeem a code twice; rewrite a
  loopback code onto a reachable address instead.
- Match the existing naming and formatting style. No reformatting unrelated files.

## Pull requests

1. Fork the repo and create a branch: `git checkout -b fix/short-description`.
2. Make the smallest useful change.
3. Run `./gradlew test assembleDebug assembleRelease`.
4. Open a PR with **what changed**, **why**, and **how you tested it**. Screenshots help.
5. Keep the PR focused; split unrelated changes.

## AI-assisted contributions

AI-assisted PRs are welcome. Please read [AGENTS.md](AGENTS.md) (or [CLAUDE.md](CLAUDE.md) for
Claude Code) and mention the tool in the PR description. Review generated code carefully — you are
responsible for it.

## Good first issues

- Add a screenshot/GIF section to the README.
- Test on Android 8, 10, 12, 14 and 16.
- Add a dark/light icon preview.
- Improve the empty/error states.
- Add a `Threading` test for `Bg`.
