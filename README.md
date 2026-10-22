# OpenCode Pair — Android app

A minimal, fullscreen WebView client that pairs an Android phone with the **OpenCode** server
running on your PC. Plain Java, no AndroidX, no Kotlin. The only library is Google's pure-Java
ZXing decoder, used to read the pairing QR code — the APK is still only ~250 KB.

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

## Pairing

On the PC, run this inside the project you want to open:

```bash
opencode pair
```

It prints three one-time links (valid for 5 minutes, each usable once) and a QR code:

| Link | Reachable from |
| --- | --- |
| `http://127.0.0.1:45278/auth/connect/<code>` | the PC itself only |
| `http://192.168.x.x:45278/auth/connect/<code>` | the same Wi-Fi |
| `https://<id>.opentunnel.xyz/auth/connect/<code>` | anywhere, via OpenTunnel |

The QR code is **not** a URL. It contains JSON:

```json
{"code":"<code>","urls":["http://192.168.x.x:45278","https://<id>.opentunnel.xyz"]}
```

`urls` are the server's base addresses with loopback filtered out; the real connect link for
each is `<base>/auth/connect/<code>`.

### In the app

1. Install `dist/app-debug.apk` (`adb install -r dist/app-debug.apk`) and open **OpenCode Pair**.
2. Tap **Scan QR code** and point the camera at the terminal, **or** paste any of the three links
   (or the QR JSON itself) into the address field and tap **Connect**.
3. When a scan yields several addresses, the app tries them in order — LAN first — and falls back
   to the next one automatically if a host cannot be reached. Every address carries the same
   one-time code, so whichever answers first wins.
4. After the code is redeemed, the app keeps the page it landed on instead of the used pairing
   link, so the next launch reopens the paired session (the session cookie is kept).
5. `127.0.0.1` is rejected with a hint, because on the phone it points at the phone itself.
   Use the `192.168.x.x` or `https://…opentunnel.xyz` link, or the QR code.

### Editing the URL

- Tap the **⚙** button in the top bar, or
- Tap **Change address** on the error page.

### Other behaviour

- **Top bar** — shows the connected host, a status dot (green = connected, amber = loading,
  red = failed), a reload button and an address button. It replaces the old floating button,
  which sat on top of the page's own composer.
- **QR scanner** — full-screen camera preview with a viewfinder. Normal and inverted codes are
  both decoded, so light-themed terminals work too. Camera permission is requested on first use.
- **Automatic fallback** — a scanned pairing code contains more than one address; if the first
  cannot be reached the next is tried before the error page is shown.
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
- QR scanning uses the framework `android.hardware.Camera` (deprecated, but it needs no
  AndroidX) together with `com.google.zxing:core`. Frames are decoded on a background thread
  using one recycled preview buffer.
- `PairPayload` parses the QR JSON, the three printed links and pasted text, and orders
  loopback addresses last. `app/src/test/.../PairPayloadTest.java` covers all of those shapes
  (`.\gradlew.bat test`).
