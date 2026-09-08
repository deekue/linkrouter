# LinkRouter — Build Specification

> Self-contained design + build plan for a pure Android **dispatcher** app.
> It matches each outgoing web URL against user-defined rules and launches the
> target browser — optionally in private — then exits immediately. It never
> renders pages, makes no network calls, and requires **zero permissions**
> (the sole exception is D9 shortener resolution: opt-in per host, off by
> default → zero network).

This document is the source of truth for a build agent. Follow it in the
milestone order (M1 → M9). Where a decision is listed under "Locked decisions"
it is final — do not re-litigate it.

---

## 0. Locked decisions (do not change without explicit approval)

| # | Decision | Value |
|---|----------|-------|
| D1 | minSdk | **26** (Android 8.0) |
| D2 | Language / UI | **Kotlin**, Jetpack Compose, Material 3 |
| D3 | Persistence | **Room** (SQLite) |
| D4 | App scope | **Dispatcher + optional in-app WebView target** (REAL private; user opts in per rule) |
| D5 | Interception | **Default browser** (transparent `ACTION_VIEW` re-dispatch) |
| D6 | No-true-private fallback | **Warn (one-time toast) + open normally** |
| D7 | Network | **None.** Zero network calls, zero runtime permissions *(exception: D9 shortener resolution uses the INTERNET normal permission only when a host is enabled; off by default → zero network)* |
| D8 | Unmatched / uninstalled link | → **System chooser** (default) or OS default browser |
| D9 | **Shortener resolution** (t.co, bit.ly, …) | **Opt-in per host.** When a host is enabled, the dispatcher follows its redirects (pure-JVM fast path) to the final URL, re-runs the rule engine on it, and launches the final URL. Disabled by default → zero network. Requires the `INTERNET` *normal* permission (auto-granted, not a runtime permission) — see D7 note. Non-resolvable links degrade to the original URL (D6). |

---

## 1. Goal

An Android app the user sets as their **default browser**. Any web link tapped
anywhere is delivered to LinkRouter; it matches the URL against the user's
ordered rule list and launches the chosen browser for that rule, honoring the
per-rule **private/incognito** flag, then `finish()`es. The happy path shows
no UI and adds no perceptible latency.

Why default-browser and not App Links / VPN:
- **App Links** are static per build and require owning each domain → cannot
  express dynamic, user-added rules. Rejected as the primary mechanism.
- **VPN/DNS** interception is heavy (persistent VPN permission, battery) and
  overkill. Rejected.
- **Default browser** works for *any* URL (including links from other apps)
  with fully dynamic rules. **Chosen.**

---

## 2. Interception flow

```
Link tapped anywhere
  → Android delivers ACTION_VIEW (http/https) to LinkRouter (default browser)
  → DispatcherActivity:
        1. Read intent.data
        2. Loop-guard check (did we already handle this URL?)
        3. RuleEngine.resolve(uri)  → best rule | null
        4. If rule: resolve target browser
                    - PRIVATE  → strategy by capability:
                                  REAL    → private extra, no warn  (Firefox)
                                  ATTEMPT → best-effort extra + warn
                                  NONE    → open normally + warn   (Chrome)
                    - NORMAL   → direct launch
        5. If no rule / browser missing → FallbackHandler
        6. finish()
```

**Loop guard (critical):** If LinkRouter ever receives a URL it has already
handled (e.g. a rule that routes to itself, or a browser re-firing the URL back
to the default browser), it must NOT re-match. Instead it goes straight to the
system chooser. Implementation: an intent extra `EXTRA_HANDLED` (boolean); if
dropped by the OS, fall back to a transient query param `?__lr=1`. Detect either
on entry → `FallbackHandler.showChooser(uri)`.

---

## 3. Package layout

```
app/
├── DispatcherActivity.kt          # exported, transparent, ACTION_VIEW http/https
├── LauncherActivity.kt            # alias: separate app-icon launcher (not browser role)
├── WebViewActivity.kt             # in-app WebView target (REAL private; Compose + AndroidView)
├── ResolutionWebViewActivity.kt   # M7 ephemeral resolution WebView (no chrome; settles interstitials)
├── SettleDetector.kt              # M7 pure-JVM "has the page settled?" decision (no android.*)
├── rules/
│   ├── Rule.kt                    # data model
│   ├── RuleRepository.kt          # Room DAO + ordering
│   ├── RuleEngine.kt              # parse URL → best rule (specificity scoring)
│   ├── RuleValidator.kt           # pattern/type checks + live match preview
│   └── ShortenerWebResolver.kt    # M7 WebView fallback: interface + ActivityWebResolver
├── browsers/
│   ├── BrowserRegistry.kt         # discovery via PackageManager (no QUERY_ALL_PACKAGES)
│   ├── BrowserInfo.kt             # package, label, icon, isPrivateCapable
│   ├── PrivateLauncher.kt         # interface: launch() + isRealPrivate() + capability()
│   ├── FirefoxPrivateLauncher.kt  # private_browsing_mode extra (REAL, verified)
│   ├── ChromePrivateLauncher.kt   # NONE — incognito extra drops the URL
│   ├── WarnNormalLauncher.kt      # NONE — open normally + warn (D6)
│   ├── WebViewTarget.kt           # sentinel package + synthetic BrowserInfo (in-app)
│   ├── WebViewLauncher.kt         # REAL — opens WebViewActivity, no cross-app leak
│   └── StrategyTable.kt           # package → strategy (Firefox/WebView = REAL)
├── fallback/
│   └── FallbackHandler.kt         # chooser / default browser / block (setting-driven)
└── ui/
    ├── RulesScreen                # ordered list, drag reorder, toggles, swipe actions
    ├── RuleEditor                 # pattern + live preview, target picker, private toggle
    ├── FallbackConfig             # fallback mode
    └── ImportExport               # JSON backup/restore
```

`AndroidManifest` requirements:
- `<queries>` block advertising `ACTION_VIEW` http + https intent filters so
  browser discovery works **without** `QUERY_ALL_PACKAGES`.
- `DispatcherActivity`: `android:exported="true"` (required on Android 12+),
  transparent theme, singleTop, `taskAffinity=""` (own task — never pollutes the
  caller's back stack), intent-filter for `http` and `https` schemes.
- `LauncherActivity` as the normal app icon.

---

## 4. Data model

```kotlin
enum class MatchType { EXACT_HOST, SUBDOMAIN, PATH_PREFIX, REGEX }
enum class OpenMode  { NORMAL, PRIVATE }

data class Rule(
  val id: Long,
  val pattern: String,            // "example.com" | "docs.example.com/*"
                                  // | "*.app.com/api/" | "<regex>"
  val matchType: MatchType,
  val targetPackage: String,      // "org.mozilla.firefox"
  val targetActivity: String?,    // null = browser default entry (resolved at match time)
  val openMode: OpenMode,
  val enabled: Boolean,
  val priority: Int               // list order; higher wins on score ties
)
```

Room entity mirrors `Rule`. `RuleRepository` exposes ordered CRUD and an
`enabledRules()` stream. Ordering is the user-visible priority (top = highest).

---

## 5. Matching algorithm (RuleEngine)

1. **Normalize** the incoming `Uri` before matching:
   - Upgrade `http://` → `https://`.
   - Strip **query** and **fragment** (so `?utm_*` never breaks a rule).
   - Strip **credentials** (`user:pass@host`) and normalize the **host** to
     lowercase (handle IDN/punycode via `java.net.IDN`).
2. **Filter** to `enabled == true` rules.
3. **Score** each candidate:
   | Match | Score |
   |-------|-------|
   | exact host + path prefix | 40 |
   | exact host (any path) | 30 |
   | `*.host` wildcard subdomain | 20 |
   | regex (full match on normalized host+path) | 10 |
4. **Pick** the highest score. Ties broken by `priority` (user list order).
5. **No match** → return `null` → `FallbackHandler`.

`RuleValidator` (used by the editor) must:
- Reject empty patterns and malformed regex (compile-check).
- Auto-detect `matchType` from the pattern shape (host-only, `*.host`,
  `host/path/`, or explicit regex) and present it as an editable chip.
- Produce a **live match preview**: given the pattern, show whether sample URLs
  (and the current test URL) would match.

---

## 6. Dispatch flow (reference implementation)

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
  val uri = intent?.data?.let { normalize(it) }
  if (uri == null) { finish(); return }

  // 1. LOOP GUARD
  if (intent?.getBooleanExtra(EXTRA_HANDLED, false) == true ||
      uri.getQueryParameter("__lr") != null) {
    FallbackHandler.showChooser(uri)
    finish(); return
  }

  // 2. MATCH
  val rule = RuleEngine.resolve(uri)

  // 3. RESOLVE TARGET + LAUNCH
  when (rule) {
    null -> FallbackHandler.route(uri, settings.fallback)
    else -> {
      val target = BrowserRegistry.getInstalled(rule.targetPackage)
      when {
        target == null -> FallbackHandler.onBrowserMissing(rule, uri)
        rule.openMode == OpenMode.PRIVATE -> {
          val launcher = PrivateLauncher.for(target)
          if (launcher.isRealPrivate()) {
            launcher.launch(target, uri)
          } else {
            // D6: warn + open normally
            toastOnce("Private browsing isn't supported by ${target.label}")
            dispatchNormal(uri, target)
          }
        }
        else -> dispatchNormal(uri, target)
      }
    }
  }
  finish()
}

private fun dispatchNormal(uri: Uri, target: BrowserInfo) {
  val intent = Intent(Intent.ACTION_VIEW, uri).apply {
    setPackage(target.packageName)
    target.activity?.let { setClassName(it) }
    putExtra(EXTRA_HANDLED, true)          // loop-guard marker
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }
  try { startActivity(intent) }
  catch (e: Exception) { FallbackHandler.showChooser(uri) }  // D: crash-safe
}
```

### Shortener resolution (D9)

For an **enabled** shortener host (t.co, bit.ly, is.gd, …), the dispatcher
follows the shortener's server-side 3xx redirects to the **final URL**,
**re-runs the rule engine on that final URL**, and then **launches the final
URL**. Resolution is opt-in per host (`ShortenerHost`, a separate entity from
`RedirectFormat` — redirect formats are pure-JVM string extraction, shortener
resolution is network following); all built-in hosts ship **disabled**, so the
default build still makes zero network calls (D7).

- **Optional path prefix:** a `ShortenerHost` row may now carry an optional
  `pathPrefix` (nullable `TEXT`). `NULL` = **host-only** match, exactly the
  historical behaviour (`t.co`, `bit.ly`) and unchanged for any existing row. A
  non-null value additionally requires the **incoming** short link's path to
  start with that prefix — case-insensitive, with a stored prefix normalized to
  always start with `/` (so `t` and `/t` are equivalent). The host is matched on
  the **registrable domain** (one leading `www.` stripped from both the stored
  and incoming host) and the check runs on the **incoming** short link URL,
  **not** the resolved destination (the resolver is unchanged). Two further
  built-in rows ship **disabled** (so a default build still makes zero network
  calls, D7/D9): `www.tiktok.com` + `/t/` and `www.facebook.com` + `/share/r/`.
- **Fast path (implemented):** pure-JVM redirect following in
  `ShortenerResolver` (`RealFetcher` via `HttpURLConnection`,
  `instanceFollowRedirects=false`, browser UA, 6 s connect/read timeouts, max
  6 hops, loop guard on revisited URLs). Settled-page detection inspects the
  200 body (capped ~64 KB) for `<meta http-equiv=refresh>` /
  `location.replace` / `location.href` → flagged as an interstitial rather
  than resolved.
- **WebView fallback (M7, implemented):** when the fast path returns
  `Result.Interstitial`, the dispatcher escalates to an **ephemeral
  resolution WebView** (`ResolutionWebViewActivity`, a bare `Activity` with no
  chrome — a *resolution* mechanism, not a browsing experience; distinct from
  the §7.1 in-app browsing target). It loads the shortener URL, executes JS /
  follows `<meta>` + JS redirects, and — once the page has **settled** (see
  `SettleDetector`) — returns the final URL to the dispatcher, which re-runs
  the rule engine on it and launches it, exactly like the fast path. The
  settle decision is pure-JVM (`SettleDetector`, unit-testable without a real
  WebView); the activity only drives it. It is an **overall timeout** (default
  ~8 s) and, on failure/timeout, returns no URL.
- **Escalation policy (D6):** the dispatcher escalates **only** on
  `Result.Interstitial`. `Loop` / `MaxHops` / `Rejected` / `Error` are genuine
  failures a WebView won't safely fix and are **not** escalated.
- **Graceful degradation (D6):** if resolution fails (web-resolver null,
  timeout, loop, non-http(s) target, hop limit, network error), the dispatcher
  falls back to the **ORIGINAL URL** through the normal rule path — it never
  silently pretends.
- **Ephemeral privacy (D6):** on close (success or failure) the resolution
  WebView clears cookies, HTTP cache and web storage and is destroyed, so the
  interstitial's session cannot leak into a later real browsing session
  (mirrors `WebViewActivity.onDestroy` private-mode cleanup).
- **Permission note (D7/D9):** `INTERNET` is a *normal* (install-granted)
  permission, **not** a runtime permission. It is only exercised when at least
  one shortener host is enabled, and all built-in hosts are disabled by
   default — so a default install still makes zero network calls (honest opt-in).

### URL param cleanup (M9)

After the dispatcher has resolved the **final** URL (post shortener / redirect
resolution, §6) and is about to launch it, it strips a user-managed set of
tracking / attribution query params **from that final URL only**. Rule matching
(§5) is deliberately **untouched** — matching stays query-independent; the
stripping is a launch-time cleanup, not part of the match.

- **What:** a user-managed list of query-param filters. Each row =
  (`domain?`, `paramName`). `domain = NULL` ⇒ **global** (any domain); otherwise
  scoped to that domain **and its subdomains**, using the same domain-matching
  rules as shortener hosts (`ShortenerMatcher.domainMatches`, §6).
- **When it applies:** **only** to the URL the dispatcher **launches** — the
  final URL after shortener/redirect resolution. The loop-guard check, rule
  matching, and shortener/redirect resolution are all unaffected.
- **Behavior:** pure-JVM `QueryParamStripper.strip(url, enabledFilters)`
  rebuilds the URL **string-level**: it preserves scheme / authority / path /
  fragment, the **order** of the surviving params, and the **raw
  percent-encoding** of every kept param. Non-web (non-http/https) or
  query-less URLs, and the no-match case, return the input **unchanged** (D6:
  never silently pretend / degrade safely). Matching is **case-insensitive** on
  the param key; values are **never** inspected.
- **Built-ins (enabled by default, local, zero network risk):**
  - **Global:** `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`,
    `utm_content`, `gclid`, `gclsrc`, `msclkid`, `fbclid`, `fbid`, `sharer_id`,
    `mc_eid`, `mc_cid`.
  - **Scoped (domain → params):** `tiktok.com` → `_t`;
    `instagram.com` → `igsi`, `igshid`; `youtube.com` → `si`, `feature`;
    `facebook.com` → `original_uri`.
  - Internal IDs occupy **-31..-49** (negative IDs mark the built-in rows, like
    the other built-ins).
- **Built-in protection:** **delete ⇒ disable**; **update ⇒ only the `enabled`
  flag is mutable** (mirrors the shortener-host and redirect-format built-in
  rules). The domain / param of a built-in row cannot be edited.
- **Persistence:** a dedicated `query_param_filters` table (Room entity,
  **DB v7 — `MIGRATION_6_7`**), kept **separate from routing rules**: a filter
  is a launch-time cleanup flag, not a routing decision.
- **Internal guard:** the loop-guard param `__lr` (§2) is **never** stripped,
  regardless of any (user or built-in) filter.

> Param cleanup touches **only** the outbound launch URL. It makes no network
> calls, reads no remote data, and changes neither the rule match nor the
> shortener / redirect resolution.

---

## 7. Private/incognito (per-browser strategy)

Android has **no standard "open private" intent extra**; stock browsers expose
no public API. Private support is therefore a **best-effort, pluggable
strategy** keyed by package name, in three tiers:

- **REAL** — verified on-device to open a private window *and deliver the URL*.
  *(Firefox and the in-app WebView today.)* No warning is shown — we only claim
  private when it is actually true (D6).
- **ATTEMPT** — a best-effort vendor extra is attached that *may* open private;
  if the browser ignores it the URL simply opens normally (harmless). We warn
  the user we cannot guarantee privacy (D6). *(None today — see Chrome below.)*
- **NONE** — no private mechanism that also delivers the URL; open normally +
  warn (D6). *(Chrome today — its incognito extra drops the URL.)*

| Browser (package) | Strategy | Capability | `isRealPrivate()` |
|---|---|---|---|
| **Built-in WebView** (`net.chaosengine.linkrouter.webview`) | In-app [WebViewActivity](#71-in-app-webview-target). The page never leaves our process; cookies/cache cleared on close. | **REAL** | true |
| Firefox (`org.mozilla.firefox`) | Attach `private_browsing_mode=true` extra. **Verified on-device (2026-09-06):** URL opens in a genuine private window (purple shield). | **REAL** | true |
| Chrome (`com.android.chrome`) | `com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB=true` opens incognito **but drops the URL** (verified on-device) → must NOT be attached. Open the URL normally + warn (D6). | **NONE** | false |
| Brave / Kiwi / Bromite / DDG / others | No known private extra. Open normally + warn (D6). | **NONE** | false |

> **On-device findings (2026-09-06):**
> - **Firefox:** the `private_browsing_mode` intent extra (honored by Mozilla's
>   build — `HomeActivity`/`IntentReceiverActivity`, issue #14499) opens the URL
>   in a real private window → promoted to **REAL**.
> - **Chrome:** `EXTRA_OPEN_NEW_INCOGNITO_TAB` opens a *blank* incognito tab and
>   **drops the URL** — that breaks our core contract (delivering the URL), so
>   we do NOT attach it. Chrome is **NONE** (warn + open normally).
> - The earlier `about:privatebrowsing`-then-refire trick was **falsified**
>   (URL opened normally). The intent-extra approach is what works for Firefox.
>
> **Vendor extras are undocumented ("folk") contracts.** Even a *working* one
> (Firefox's) is not a public API and could change in a browser update; a REAL
> tier is a snapshot of a verified on-device result, not a guarantee. Re-verify
> after major browser upgrades before trusting REAL.

```kotlin
enum class PrivateCapability { REAL, ATTEMPT, NONE }

interface PrivateLauncher {
  fun launch(context: Context, browser: BrowserInfo, uri: Uri)
  fun isRealPrivate(): Boolean          // true only for REAL
  fun capability(): PrivateCapability
}

object StrategyTable {
  fun launcherFor(browser: BrowserInfo): PrivateLauncher =
    when (browser.packageName) {
      WebViewTarget.PACKAGE -> WebViewLauncher          // REAL (in-app, no cross-app leak)
      "org.mozilla.firefox" -> FirefoxPrivateLauncher   // REAL (verified)
      "com.android.chrome"  -> ChromePrivateLauncher    // NONE (incognito extra drops URL)
      else                  -> WarnNormalLauncher       // NONE
    }
}
```

**Rule editor UX:** the private toggle shows a capability badge per tier —
**"Verified: opens a private window"** (REAL), **"Best-effort: tries a private
window (not guaranteed — verify on your browser)"** (ATTEMPT), or **"No private
support — will warn and open normally"** (NONE). Never silently pretend to be
private.

**Forward-looking:** if a browser exposes a public private API (or the OS ships
a system-level private-browsing toggle), register it here as a REAL strategy.

### 7.1 In-app WebView target

A built-in target (`WebViewTarget`, sentinel package
`net.chaosengine.linkrouter.webview`) that renders the URL inside
`WebViewActivity` — a Compose screen embedding a `WebView` via `AndroidView`.

- **REAL private, verified by construction:** the page never leaves our process.
  In private mode `WebViewActivity` clears cookies, HTTP cache and web storage
  on close, and the WebView never persists a profile.
- **No registry lookup:** `DispatcherActivity` intercepts WebView rules *before*
  `BrowserRegistry` is consulted, so it works even when no third-party browser
  is installed.
- **Navigation:** in-page `http/https` loads stay in-app
  (`shouldOverrideUrlLoading`); the system back button pops WebView history
  before finishing.
- **Rule editor:** the target picker shows the WebView first, with a "Built-in
  WebView" label and the REAL private badge.

> **Not the M7 resolution WebView.** The §7.1 in-app target is a *browsing*
> experience (top bar, back button, user-facing). The M7
> `ResolutionWebViewActivity` (§6) is a *resolution* mechanism — a bare,
> chrome-less, ephemeral WebView that exists only to settle a shortener
> interstitial and return the final URL. They are distinct classes and should
> not be conflated or reused.

---

## 8. Browser discovery (BrowserRegistry)

- Discover installed browsers via
  `queryIntentActivities(Intent(ACTION_VIEW).setAction/setData(http))`
  filtered to http/https, using the `<queries>` block (no `QUERY_ALL_PACKAGES`).
- Resolve each package's **launchable activity** (launcher activity query).
- Cache icons (adaptive-icon aware) in memory; refresh on
  `PACKAGE_ADDED` / `PACKAGE_REMOVED` / `PACKAGE_CHANGED` via a `BroadcastReceiver`.
- If a rule's target browser is uninstalled:
  - UI shows the rule **greyed** with a "reinstall or retarget" action.
  - At dispatch time, fall back to the `FallbackHandler`.
- Work profile: link routing only sees the **active profile's** browsers —
  document this in-app.

---

## 9. Fallback behavior (settings-driven)

`FallbackHandler` dispatches unmatched / uninstalled / loop-guarded URLs per the
user's chosen mode (default = **System chooser**):

| Mode | Behavior |
|------|----------|
| `CHOOSER` (default) | `Intent.createChooser(...)` over all http/https handlers |
| `OS_DEFAULT` | Launch the current OS default browser |
| `BLOCK` | Show a toast explaining nothing handled it; do nothing |
| `ASK_REMEMBER` | Show chooser once, remember the picked package for future misses |

---

## 10. UI / UX

**Home (RulesScreen)**
- Ordered rule list — list order **is** priority. Drag to reorder.
- Per-row: pattern summary, target browser (icon + name), private badge,
  enable/disable switch, swipe actions (edit, delete, duplicate).
- Greyed state for rules whose target browser is uninstalled.
- Empty state: CTA "Set LinkRouter as your default browser →" that opens the
  system default-browser prompt.

**RuleEditor**
- Pattern input with **auto-detected type** shown as an editable chip
  (`Exact host` / `Subdomain` / `Path prefix` / `Regex`).
- **Live match preview**: as the user types, show whether representative URLs
  would match; a **Test button** picks a sample URL and dry-runs the full chain
  (shows which browser + NORMAL/PRIVATE it would launch, without launching).
- Target picker: grid of installed browsers (icon + name).
- Private toggle with the capability badge from §7.

**Settings**
- Fallback mode (§9).
- Browser-discovery refresh (manual re-scan).
- JSON **import/export** of rules + settings (backup/restore).
- "Open default-browser prompt" shortcut.

**Entry points**
- `LauncherActivity` = normal app icon (distinct from the browser role).
- `DispatcherActivity` = transparent, only entered via `ACTION_VIEW`.

---

## 11. Privacy & security

- **Zero network access** — the app never fetches, logs, or transmits visited
  URLs. No analytics, no crash-remote.
- **No runtime permissions** required at all.
- **Stored data:** rules + settings only (Room). **No URL history** is ever
  persisted.
- Threat model note: a malicious rule could silently route links to an
  arbitrary browser. Mitigate with a **system dialog on first rule save**
  ("You are routing matching links to `<browser>`") and full auditability in
  the rule list.
- All link handling stays in the foreground (triggered by a user tap). Still
  guard `startActivity` from a background state with an `isActivityStarted`
  check (Android 10+ background-start restrictions).

---

## 12. Edge cases (must all be handled)

- `http://` → `https://` upgrade before matching.
- `mailto:` and other non-web schemes → **ignored** (not routed, not matched).
- URL with credentials (`user:pass@host`) → strip before matching.
- Host case + IDN/punycode normalization.
- Query/fragment present → stripped before matching, but **preserved** in the
  launch intent (target browser still gets the full original URL).
- Target browser throws on launch → `try/catch` → fall back to chooser.
- Two regex rules overlapping → priority order decides; editor warns on overlap.
- Android 12+ `android:exported` explicit on both activities.
- Loop guard (self-routing rule) → chooser, never infinite re-dispatch.
- Work-profile-only browsers → only visible in that profile (documented).

---

## 13. Testing plan

**Unit (JVM, table-driven)**
- `RuleEngine`: full matrix of `matchType` × URL shape — subdomains, ports,
  paths, query, fragment, credentials, non-matches, http→https, IDN hosts.
  Assertions on chosen rule + score + tie-break by priority.
- `RuleValidator`: bad/empty patterns, invalid regex, uppercase, IDN/punycode,
  type auto-detection correctness.

**Robolectric**
- `BrowserRegistry` against a stubbed `PackageManager` (fake packages,
  installed/uninstalled transitions, icon cache).
- `DispatcherActivity` integration: stub `PackageManager`; assert the exact
  `startActivity` args (package, data, extras) for each (rule, private)
  combination. Cover: normal, true-private (Firefox), warn+normal,
  uninstalled-browser fallback, loop-guard path.

**Manual matrix (emulator + real device)**
- Firefox → true private (verify private window).
- Chrome → warn + open normally.
- Missing-browser fallback (uninstall target, tap a matching link).
- Loop-guard (create a rule routing to LinkRouter itself).
- Chooser fallback (no matching rule).
- Work-profile browser scenario.

**Release checklist**
- Play Store "default browser" consent UX verified.
- "No permissions" / "No network" privacy labels accurate.
- `exported` flags, background-start guard, and loop guard confirmed on-device.

---

## 14. Milestones

| # | Scope | Exit criteria |
|---|-------|---------------|
| **M1** | Core dispatch | Transparent default-browser activity; 1 hard-coded rule; chooser fallback; **loop guard** works. *Proves end-to-end value.* |
| **M2** | Rule engine + UI | Room persistence; full rule list/editor; browser picker; drag ordering; validation + live preview. |
| **M3** | Private strategies | `PrivateLauncher` + `StrategyTable`; Firefox true-private; in-app WebView target (REAL); warn+normal degradation UX; capability badge. |
| **M4** | Polish | JSON import/export; browser-discovery refresh; all fallback modes; Test button; settings. |
| **M5** | QA & release | Manual matrix green; unit + Robolectric green; Play listing + privacy labels. |
| **M6** | Shortener resolution — fast path (D9) | `ShortenerResolver` pure-JVM redirect following; `ShortenerHost` opt-in per host (built-ins disabled); dispatcher re-runs the rule engine on the final URL and launches it; graceful degradation to the original URL (D6). **Completed.** |
| **M7** | Shortener resolution — WebView fallback (D9) | Ephemeral resolution WebView (`ResolutionWebViewActivity`) that settles JS/Cloudflare/`<meta refresh>` interstitials and returns the final URL; the pure-JVM settle decision lives in `SettleDetector` (unit-testable, no WebView). The dispatcher escalates **only** on `Result.Interstitial` (not on Loop/MaxHops/Rejected/Error) and degrades to the original URL on failure/timeout (D6). Ephemeral privacy: cookies + cache + web storage cleared and the WebView destroyed on close. **Completed.** |
| **M8** | Path-prefix shortener hosts (D9) | Optional `pathPrefix` on `ShortenerHost` (`NULL` = host-only, back-compat; non-null = the incoming short link's path must start with the prefix, case-insensitive, registrable-domain host match); pure-JVM `ShortenerMatcher`; two new **disabled** built-ins (`www.tiktok.com` + `/t/`, `www.facebook.com` + `/share/r/`). **Completed.** |
| **M9** | URL param cleanup | Pure-JVM `QueryParamStripper.strip(url, enabledFilters)` strips user-managed + built-in tracking params from the **final launched URL** only (scheme/authority/path/fragment, param order, and raw percent-encoding of kept params preserved; non-web / query-less / no-match unchanged, D6); case-insensitive key match, values never inspected; `__lr` never stripped. Dedicated `query_param_filters` table (**DB v7 — `MIGRATION_6_7`**), separate from routing rules; built-ins use negative IDs **-31..-49** with delete ⇒ disable / update ⇒ enabled-only. Matching and shortener/redirect resolution are untouched. **Completed.** |

Build strictly in M1 → M9 order; each milestone must be independently shippable
and tested before the next begins. (M6, M7, M8 and M9 are complete.)

---

## 15. Out of scope (explicit)

- No general in-app browsing experience — the only in-app *browsing* is the
  opt-in per-rule WebView target (§7.1), not a full browser. (The M7
  resolution WebView is a **resolution** mechanism, not a browsing
  experience: it has no chrome and exists only to settle a URL and hand it
  back.)
- No network, analytics, or telemetry (D7).
- No VPN/DNS interception.
- No multi-profile simultaneous routing (only the active profile's browsers).
- No account sync (import/export is local JSON only).
- Param filters are **launch-time cleanup only**: they never affect rule
  matching (matching stays query-independent), never affect shortener/redirect
  resolution, and never strip params from a non-launched URL (they touch only
  the final URL the dispatcher launches, §6 / M9).
