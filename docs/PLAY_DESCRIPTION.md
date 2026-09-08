# Short description (80 chars max)

LinkRouter is a privacy-first, rule-based URL router for Android.

# Full Description (4000 chars max)

LinkRouter is a privacy-first, rule-based URL router for Android. Set it as your default browser and it intercepts every http/https link you tap — from any app — and routes it to the browser you choose, based on rules you define. It is a smart dispatcher, not a browser: it never renders a page itself and adds no perceptible latency.

WHAT IT DOES

• Rule-based routing — For each domain (exact host, *.subdomain, path prefix, or full regex), pick which browser opens it and whether it opens in a private window. List order is priority.
• Tracker cleanup — Strips tracking and attribution params (utm_*, gclid, fbclid, and more) from the final URL it launches, globally or per domain. Built-ins ship enabled by default; add your own.
• Short-link resolution (opt-in) — For hosts you enable (t.co, bit.ly, is.gd-style), it follows the redirect to the final URL, re-matches it against your rules, and launches the destination. Off by default: a default install makes zero network calls.
• Private browsing that never pretends — Opens a real private window in Firefox; for browsers that can't deliver a URL in a private window (e.g. Chrome) it warns you and opens normally. The built-in in-app WebView target is always genuinely private.
• Local backup/restore — JSON import/export of rules, settings, shortener-host toggles and param filters. No account, no cloud.

BUILT FOR PRIVACY

• Local-only storage: rules and settings live on-device. No browsing history is ever persisted; no URL log.
• No ads, no analytics, no telemetry, no crash reporting, no third-party SDKs.
• The single INTERNET permission is exercised only if you opt in to short-link resolution for specific hosts.

HOW IT WORKS

Tap a link → LinkRouter matches it against your ordered rule list → launches your chosen browser, normal or private → exits. No matching rule (or a missing target browser) falls back to the system app chooser, the OS default browser, or ask-and-remember — your choice.

Free and open source (GPLv3). Requires Android 8.0 or newer.
