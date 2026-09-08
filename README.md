# LinkRouter (`net.chaosengine.linkrouter`)

A privacy-focused, rule-based URL router for Android: it intercepts `http`/`https` links (as the system default browser) and, per your user-defined rules, strips tracking/query params, rewrites URLs, and forwards them to a user-chosen browser — including private-window launchers for Chrome/Firefox. It is a **router/dispatcher, not a browser**: it never renders pages itself.

## Features

- **Rule engine**: ordered rules matching exact host, `*.subdomain`, path prefix, or full regex; list order is priority, with specificity scoring as tie-breaking.
- **Redirect formats**: pattern-based extraction from redirect-style URLs via pure-JVM string processing (no network).
- **Shortener-host handling**: opt-in per-host resolution of t.co / bit.ly / is.gd-style redirects (pure-JVM redirect following with a WebView interstitial fallback), re-running the rule engine on the final URL. **Off by default → zero network.**
- **URL query-param / tracker cleanup**: global and domain-scoped stripping of tracking params (`utm_*`, `gclid`, `fbclid`, `fbid`, etc.) from the **final launched URL only**; never affects rule matching.
- **Browser chooser with private-window support**: dispatches to installed browsers with per-browser private capability (Firefox real-private verified; Chrome opens normally + honest warning when private is requested but unsupported); built-in in-app WebView target (real private, no cross-app leak); system/OS-default/chooser/ask-and-remember fallback modes.
- **JSON import/export** of rules, settings, shortener hosts, redirect formats, and param filters (local backup/restore, no sync).

## Privacy

- **Local-only** rule and settings storage (Room, app-private storage). No URL history is ever persisted.
- **No ads, no analytics, no telemetry, no third-party SDKs.**
- **Only the `INTERNET` permission** is declared (a normal, install-granted permission), and it is exercised **only** if you enable the opt-in "shortener web resolution" feature for one or more hosts — all built-in shortener hosts ship disabled, so a default install makes zero network calls.
- See [PRIVACY.md](PRIVACY.md) for the full privacy policy.

## Requirements

- Android **minSdk 26** (Android 8.0)
- Gradle: AGP **8.10.1**, Kotlin **2.0.21**

## Build

```bash
./gradlew :app:assembleDebug      # debug APK (signed with the automatic debug key)
./gradlew :app:testDebugUnitTest  # JVM + Robolectric unit tests
./gradlew :app:bundleRelease      # Play AAB (unsigned unless you configure release signing, below)
```

### Release signing

Release signing is entirely **external-secret-driven** (no keystore path or password ever lives in this repo). Provide all four via **environment variables** *or* `gradle.properties` keys (see `gradle.properties.example`):

| Variable | Meaning |
|----------|---------|
| `RELEASE_KEYSTORE` | Path to the release keystore (`.jks`/`.keystore`) |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | Key password |
| `RELEASE_STORE_PASSWORD` | Keystore password |

If **any** of the four is missing, the release build type is simply left **unsigned** and `./gradlew :app:bundleRelease` still succeeds (producing an unsigned AAB) — useful for CI/dev. When all four are present, the AAB is signed with your keystore. **Never commit a keystore or real passwords** (both are git-ignored; `gradle.properties.example` keeps the keys blank).

Release builds also run R8 minification (`isMinifyEnabled = true`); debug builds stay unminified.

## Project layout

`app/src/main/java/net/chaosengine/linkrouter/`:

| Package / file | Purpose |
|---|---|
| `DispatcherActivity.kt`, `LauncherActivity.kt` | Transparent `ACTION_VIEW` interceptor + app-icon launcher |
| `WebViewActivity.kt`, `ResolutionWebViewActivity.kt`, `SettleDetector.kt`, `ShortenerResolver.kt` | In-app WebView target, ephemeral shortener-resolution WebView, pure-JVM redirect resolution |
| `rules/` | Rule model, Room repository, rule engine, validator, shortener hosts |
| `browsers/` | Browser discovery, per-browser private-launch strategies |
| `fallback/` | Chooser / OS-default / block / ask-and-remember fallbacks |
| `settings/` | App settings, param filters, redirect formats |
| `ui/` | Compose screens (rules list/editor, fallback config) |
| `importexport/` | JSON backup/restore |

## License

GNU GPL v3 — see [LICENSE](LICENSE). This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License.

## Status

Current version: `versionName = 0.1.0` (`versionCode = 1`), as defined in `app/build.gradle.kts`.
