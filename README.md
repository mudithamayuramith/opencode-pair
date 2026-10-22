# OpenCode Pair — Android app

A minimal, fullscreen WebView client that pairs an Android phone with the **OpenCode Pair**
server running on your PC. Plain Java, no AndroidX, no Kotlin, no third-party dependencies —
so the build stays tiny and the APK is ~20 KB.

## Build

### Windows (PowerShell)

```powershell
$env:JAVA_HOME="C:\Users\Muditha\AppData\Local\jdk21"     # JDK 17+
$env:ANDROID_HOME="C:\Users\Muditha\AppData\Local\Android\Sdk"
.\gradlew.bat assembleDebug assembleRelease
```

### Linux / macOS / WSL

```bash
./build.sh          # downloads the Android SDK if needed, then builds
```

### GitHub Actions

Push the folder to a repo — `.github/workflows/build.yml` builds both APKs and uploads them
as an artifact. No local setup needed.

### Outputs

| File | Notes |
| --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | Debug build, debug-signed |
| `app/build/outputs/apk/release/app-release.apk` | Release build, signed with the debug key (fine for sideloading) |

Ready-to-install copies are in `dist/`.

## Toolchain

| Component | Version |
| --- | --- |
| Gradle | 8.14.3 |
| Android Gradle Plugin | 8.13.0 |
| compileSdk / targetSdk | 36 (Android 16) |
| minSdk | 26 (Android 8.0) |
| Java | 17+ source/target, JDK 21 works |

`local.properties` points at your SDK (`sdk.dir=...`) and is not committed.

## Using the app

1. Start the OpenCode Pair server on your PC; it prints a URL such as `http://192.168.1.10:4096`.
2. Install the APK on the phone (`adb install -r dist/app-debug.apk`) and open **OpenCode Pair**.
3. On first launch, type the URL or `IP:port`. A missing `http://` is added automatically.
   The URL is saved and reopened automatically next time.
4. The phone and the PC must be on the same network (or you use the server's tunnel).

### Editing the URL

- Tap the **⚙** button in the top bar, or
- Tap **Change address** on the error page.

### Other behaviour

- **Top bar** — shows the connected host, a status dot (green = connected, amber = loading,
  red = failed), a reload button and an address button. It replaces the old floating button,
  which sat on top of the page's own composer.
- **Fullscreen** — no address bar, no tabs, no browser chrome.
- **Reload** — tap **↻** in the top bar.
- **Back** — goes back inside the WebView first, exits only at the first page.
  Back also cancels the address editor without reloading the page.
- **Keyboard** — the layout shrinks cleanly (edge-to-edge + IME insets on Android 11+,
  `adjustResize` on older releases).
- **Screen stays on** while the app is in the foreground.
- **Errors** — a failed load shows a themed page with **Try again** and **Change address**.
- **Extras** — top progress bar, file uploads, downloads, and external links open in the
  system browser.

## Notes on the implementation

- `targetSdk 36` means Android 16 no longer delivers `onBackPressed()`. The app registers a
  platform `OnBackInvokedCallback` on API 33+ and keeps the legacy `onBackPressed()` path
  for API 26–32.
- `targetSdk 35+` enforces edge-to-edge, so system-bar / cutout / IME insets are applied
  manually from API 30 up.
- The URL editor only accepts `http://` and `https://` targets; `file:`, `javascript:`,
  `content:` and `intent:` are rejected.
- Cleartext HTTP and user-installed CA certificates are trusted on purpose — the pairing
  server usually runs on a LAN address with a self-signed certificate.
