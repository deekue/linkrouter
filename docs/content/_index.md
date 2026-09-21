+++
date = '2026-09-09T13:10:31Z'
draft = false
title = 'LinkRouter'
description = 'LinkRouter'
+++
# LinkRouter

A privacy-focused, rule-based URL router for Android: it intercepts `http`/`https` links (as the system default browser) and, per your user-defined rules, strips tracking/query params, rewrites URLs, and forwards them to a user-chosen browser — including private-window launchers for Chrome/Firefox. It is a **router/dispatcher, not a browser**: it never renders pages itself.

## URL Flow

An incoming link arrives through one of two intents and always travels the same pipeline before any browser is launched. Non-web schemes (e.g. `mailto:`) are dropped, and once a URL reaches a browser it is marked so it is never re-processed (loop guard). The shortener-resolution step is opt-in and off by default (zero network):

{{<mermaid>}}
graph TD
    A["Incoming link<br/>ACTION_VIEW — default browser / link tap<br/>or ACTION_SEND — Share sheet"] --> B["Extract and normalize URL<br/>(RuleEngine.normalize)"]
    B --> C{"Web URL?<br/>(http / https)"}
    C -->|"no (e.g. mailto:)"| O["Ignore — finish"]
    C -->|"yes"| D{"Already handled?<br/>(loop guard)"}
    D -->|"yes"| O
    D -->|"no"| E["Load enabled rules,<br/>formats, filters, rewrites"]
    E --> F{"Enabled shortener host?<br/>(opt-in, off by default)"}
    F -->|"no"| H
    F -->|"yes"| G["Resolve shortener to final URL<br/>(pure-JVM; ephemeral WebView fallback)"]
    G --> H["Match rules<br/>(list-order priority + specificity tie-break)"]
    H --> I{"Rule matched?"}
    I -->|"no"| J["Fallback<br/>(chooser / OS default / block / ask-and-remember)"]
    J --> N["finish"]
    I -->|"yes"| K["Host rewrite<br/>(first enabled rewrite rule)"]
    K --> L["Strip tracker query params<br/>(final launched URL only)"]
    L --> M["Launch in target browser<br/>(private / normal / in-app WebView)"]
    M --> N
{{</mermaid>}}

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
- See [PRIVACY](repo/privacy) for the full privacy policy.

## License

GNU GPL v3 — see [LICENSE](repo/LICENSE). This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License.

## Status

[Feature Test Links]({{< relref "tests" >}} "Manual test links") — every rule, redirect format, param filter, shortener host and host-rewrite fixture from `app/linkrouter-rules-examples.json`, rendered with clickable inputs + expected outputs so you can verify the router end-to-end on a phone or tablet.

[Screenshots]({{< relref "screenshots" >}} "App screenshots") — the rule, redirect format, param filter, shortener host and settings screens as they appear in the app.

