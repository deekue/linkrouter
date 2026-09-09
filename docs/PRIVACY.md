# LinkRouter Privacy Policy

**App:** LinkRouter (`net.chaosengine.linkrouter`)
**Last updated:** 2026-09-08

LinkRouter is a local, rule-based URL router for Android. It contains **no
accounts, no analytics, no ads, no crash reporting, and no third-party
SDKs.** This page explains what data is processed and where it lives.

## What data LinkRouter processes

1. **URLs you route.** When a link is delivered to LinkRouter (e.g. because
   you've set it as your default browser), the URL is read, matched against
   your rules, and forwarded to your chosen browser. It is **processed
   locally, in memory, in the foreground**, only in response to your tap, and
   is **not persisted** as browsing history.
2. **Your rules and settings.** Your routing rules, fallback settings,
   shortener-host toggles, redirect formats, and tracking-param filters are
   stored **locally in app-private storage** (Room/SQLite). They never leave
   your device.
3. **Opt-in: shortener "web resolution."** Only **if you explicitly enable** a
   shortener host (e.g. t.co, bit.ly), LinkRouter makes a network fetch to
   follow that shortener's redirect and learn the final URL. This feature is
   **off by default** — a default install never makes any network calls.

## What LinkRouter does NOT collect

- No accounts or sign-in.
- No analytics, ads, telemetry, or crash reporting.
- No third-party SDKs of any kind.
- No collection or sale/sharing of data with any party.
- No persisted browsing history or URL log.

## Permissions

- **`INTERNET`** (a normal, install-granted permission — not a runtime
  permission). Used **only** for the opt-in shortener web-resolution feature
  described above. Disabled by default.
- **System "default apps / handle links"** — this is a setting **you** choose
  in the Android system settings (set LinkRouter as default browser or link
  handler). LinkRouter does not request or modify it by itself.

## Data retention & deletion

Everything LinkRouter stores lives **on your device, in app-private
storage**. You can delete all of it at any time by:

- Clearing the app's data (Android: Settings → Apps → LinkRouter → Clear
  data), or
- Uninstalling LinkRouter.

There is no remote copy to delete and no server to ask to erase data.

## Open source

LinkRouter is free and open source under the **GNU GPL v3** (see the `LICENSE`
file in the source repository). You are welcome to inspect exactly what it
does.

## Contact

For questions or concerns about this policy or your data, contact:
`deekue+privacy@chaosengine.net`.
