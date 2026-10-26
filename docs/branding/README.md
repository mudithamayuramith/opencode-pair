# Branding

`opencode-logo-light.svg` is the source mark used for the app icon and the pairing screen.

- Source viewBox: `240 x 300`.
- Android adaptive icon: `app/src/main/res/drawable/ic_launcher_foreground.xml`
  (`scaleX/Y = 0.36`, `translateX = 10.8`, so the 240 x 300 artwork fits a 108 x 108 viewport).
- Background: `ic_launcher_background.xml` (`#211E1E`).
- Themed icon (Android 13+): `ic_launcher_monochrome.xml`.
- In-app mark: `ic_logo.xml`, tinted to the current call-to-action colour.

If you replace the artwork, keep the same 240 x 300 viewBox and update the group transform in the
three vector drawables. Rebuild and check the launcher icon in both light and dark themes.
