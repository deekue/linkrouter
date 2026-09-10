---
date: 2026-09-10
draft: false
title: "Feature Test Links"
description: "Example links to verify each LinkRouter feature end-to-end on a device."
---

# Feature Test Links

Use these links (open each while LinkRouter is set as your default browser) to
verify that every feature behaves correctly. Links are grouped by the feature
they exercise.

**Setup** — enable the relevant toggles first:
- *Shortener hosts* (Settings → Shortener hosts): flip the specific host ON
  before testing that section. All built-ins ship disabled (D8).
- *Param filters* ship enabled by default.
- *Rules* and *Redirect formats*: add a rule and/or format before testing
  those sections.

---

## 1. Redirect-format extraction (Google `url?q=` wrapper)

The built-in `google.com/url` format extracts the `q` query param.

| Test | Link | Expected |
|------|------|----------|
| Basic extraction | [https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage&source=chat&ust=123&usg=abc](https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage&source=chat&ust=123&usg=abc) | Opens [https://example.com/page](https://example.com/page) in your target browser; `utm_*`/`source`/`ust`/`usg` stripped |
| Nested wrapper (shortener inside `q`) | [https://www.google.com/url?q=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fr%2F1DBHjzmGnn%2F&source=chat&ust=1](https://www.google.com/url?q=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fr%2F1DBHjzmGnn%2F&source=chat&ust=1) | Extracts the Facebook share URL (does NOT resolve the shortener — single-pass) |
| Rule match on extracted URL | Add a rule with pattern `example.com` (EXACT_HOST) → same link as above | Rule fires on `example.com`, target browser opens it |

---

## 2. Shortener-host resolution (pure-JVM fast path)

Enable the relevant host in *Shortener hosts*, then open:

| Host to enable | Test link | Expected |
|---|---|---|
| `t.co` | [https://t.co/abc](https://t.co/abc) (any valid t.co short link) | Follows 3xx → final URL; if JS/`<meta refresh>` interstitial → WebView settles it; final URL opened |
| `bit.ly` | [https://bit.ly/3xYzAb](https://bit.ly/3xYzAb) (any valid bit.ly link) | Same |
| `is.gd` | [https://is.gd/xyz123](https://is.gd/xyz123) | Same |
| `tinyurl.com` | [https://tinyurl.com/abc123](https://tinyurl.com/abc123) | Same |
| `ow.ly` | [https://ow.ly/xyz](https://ow.ly/xyz) | Same |
| `buff.ly` | [https://buff.ly/xyz](https://buff.ly/xyz) | Same |
| `www.tiktok.com` + `/t/` | [https://www.tiktok.com/t/ZM8abc123/](https://www.tiktok.com/t/ZM8abc123/) | Path-prefix match; resolves to the video page |
| `www.facebook.com` + `/share/r/` | [https://www.facebook.com/share/r/1DBHjzmGnn/](https://www.facebook.com/share/r/1DBHjzmGnn/) | Path-prefix match; resolves to the shared post |

**Failure-visibility checks** (D6 degrade + toast):
- Open a short link that returns a **3xx without `Location`** → toast
  "Couldn't resolve that short link (HTTP 3xx without Location header)" +
  original URL opened.
- Open a short link that returns a **redirect loop** (A→B→A) → toast
  "Couldn't resolve that short link (redirect loop)" + original opened.
- Open a short link whose interstitial **times out** in the WebView → toast
  "Short link resolution timed out" + original opened.
- Observe `adb logcat -s LinkRouter` for the corresponding `Log.w` line.

---

## 3. Query-param stripping (M9)

All built-in filters ship **enabled**. Open these and confirm the tracking
params are absent from the launched URL:

| Test link | Params stripped | Params kept |
|---|---|---|
| [https://example.com/page?utm_source=twitter&utm_medium=social&utm_campaign=launch&id=5](https://example.com/page?utm_source=twitter&utm_medium=social&utm_campaign=launch&id=5) | `utm_source`, `utm_medium`, `utm_campaign` | `id` |
| [https://www.tiktok.com/@user/video/7123456789?_t=8&x=1](https://www.tiktok.com/@user/video/7123456789?_t=8&x=1) | `_t` (tiktok.com scope) | `x` |
| [https://www.instagram.com/reel/ABC123/?igshid=xyz&igsi=abc&user_id=1](https://www.instagram.com/reel/ABC123/?igshid=xyz&igsi=abc&user_id=1) | `igsi`, `igshid` (instagram.com scope) | `user_id` |
| [https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123&feature=share](https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc123&feature=share) | `si`, `feature` (youtube.com scope) | `v` |
| [https://www.facebook.com/photo/?fbid=123&sharer_id=456&fbclid=abc](https://www.facebook.com/photo/?fbid=123&sharer_id=456&fbclid=abc) | `fbclid`, `fbid`, `sharer_id` | (none) |
| [https://example.com/?gclid=abc123&gclsrc=3aw.0&ref=blog](https://example.com/?gclid=abc123&gclsrc=3aw.0&ref=blog) | `gclid`, `gclsrc` | `ref` |
| [https://example.com/?msclkid=abc&mc_eid=xyz&mc_cid=def&keep=1](https://example.com/?msclkid=abc&mc_eid=xyz&mc_cid=def&keep=1) | `msclkid`, `mc_eid`, `mc_cid` | `keep` |

**Scope guard** (param must NOT be stripped on a lookalike domain):
- [https://tiktok.com.evil.com/video?_t=8&x=1](https://tiktok.com.evil.com/video?_t=8&x=1) → `_t` **must remain** (the
  tiktok.com scope does not match `tiktok.com.evil.com`).

---

## 4. Rule matching (pattern types)

Add rules in *Rules* with the following patterns, then open the matching link:

| Match type | Rule pattern | Test link | Expected |
|---|---|---|---|
| `EXACT_HOST` | `example.com` | [https://example.com/x](https://example.com/x) | Rule fires; target browser opens it |
| `PATH_PREFIX` | `docs.example.com/guide` | [https://docs.example.com/guide/intro](https://docs.example.com/guide/intro) | Rule fires (path prefix matched) |
| `SUBDOMAIN` | `*.example.com` | [https://a.example.com/](https://a.example.com/) | Rule fires (subdomain matched) |
| `REGEX` | `example.com/old-page` | [https://example.com/old-page](https://example.com/old-page) | Rule fires (regex over host+path) |

**Precedence** — with multiple rules matching, the most specific wins:
- Add both `example.com` (EXACT_HOST, score 30) and `example.com/docs`
  (PATH_PREFIX, score 40).
- Open [https://example.com/docs/a](https://example.com/docs/a) → the PATH_PREFIX rule should fire
  (higher specificity).

**Loop guard** — LinkRouter must not intercept its own output:
- Open [https://example.com/?__lr=1](https://example.com/?__lr=1) → falls through to fallback (chooser or
  configured fallback browser), NOT re-dispatched.

---

## 5. Fallback behavior

With **no rule** matching and **no shortener/redirect** resolving:

| Fallback setting (Settings → Fallback) | Test link | Expected |
|---|---|---|
| *Chooser* (default) | [https://other.example/page](https://other.example/page) | In-app browser chooser appears (LinkRouter excluded from list) |
| *OS default* | [https://other.example/page](https://other.example/page) | OS-handled browser opens the link |
| *Block* | [https://other.example/page](https://other.example/page) | Toast "Blocked" (no browser launched) |
| *Ask & remember* | [https://other.example/page](https://other.example/page) | Chooser + "remember" option; subsequent opens go straight to chosen browser |
| *Fallback browser* (e.g. Firefox) | [https://other.example/page](https://other.example/page) | Firefox opens the link directly |

**Uninstalled target** — add a rule targeting a browser that is NOT installed:
- [https://example.com/x](https://example.com/x) → chooser appears (degraded), not a crash.

---

## 6. Private browsing

Add a rule with **Open mode = PRIVATE** targeting each browser:

| Target browser | Test link | Expected |
|---|---|---|
| Firefox | [https://example.com/private-test](https://example.com/private-test) | Opens in Firefox **private** window (`private_browsing_mode` extra) |
| In-app WebView | [https://example.com/private-test](https://example.com/private-test) | Opens in LinkRouter's built-in WebView; cookies/cache cleared on close |
| Chrome | [https://example.com/private-test](https://example.com/private-test) | Opens in Chrome **normal** tab + one-time toast "Private browsing not fully supported" |
| Other (e.g. Edge) | [https://example.com/private-test](https://example.com/private-test) | Opens normal + one-time warning toast |

**Open mode = NORMAL** (default):
- [https://example.com/normal](https://example.com/normal) → opens in the target browser's normal window.

---

## 7. Combined / integration scenarios

| Scenario | Link | Setup | Expected |
|---|---|---|---|
| Redirect format + rule | [https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage](https://www.google.com/url?q=https%3A%2F%2Fexample.com%2Fpage) | Rule: `example.com` → Firefox | Firefox opens [https://example.com/page](https://example.com/page) (extracted + matched) |
| Shortener + rule + param strip | [https://t.co/abc](https://t.co/abc) → resolves to [https://example.com/page?utm_source=tw&id=5](https://example.com/page?utm_source=tw&id=5) | Enable `t.co`; Rule: `example.com`; param filter `utm_*` ON | Firefox opens [https://example.com/page?id=5](https://example.com/page?id=5) (resolved + matched + stripped) |
| Shortener interstitial (WebView path) | A t.co/bit.ly link that serves a JS/`<meta refresh>` 200 page (most real t.co links) | Enable `t.co` | WebView settles the interstitial → final URL opened |
| Nested: redirector wrapping a shortener (NOT resolved — single-pass) | [https://www.google.com/url?q=https%3A%2F%2Fbit.ly%2Fadhdlist](https://www.google.com/url?q=https%3A%2F%2Fbit.ly%2Fadhdlist) | Format: google `url?q=`; enable `bit.ly` | Extracts [https://bit.ly/adhdlist](https://bit.ly/adhdlist); opens it as-is (shortener NOT re-resolved) |
| Loop guard on re-entry | [https://example.com/?__lr=1](https://example.com/?__lr=1) | Any | Falls back (chooser/fallback browser), no infinite loop |

---

## 8. Edge cases & regression links (from the test suite)

These mirror the automated test fixtures — useful for device-level regression:

| Test | Link | Expected |
|---|---|---|
| 3xx relative chain (2 hops) | Server: `/a`→302 `Location:/b`; `/b`→301 `Location:/final`; `/final`→200 | `Resolved` with 2 hops |
| JS `location.replace` interstitial | Server: 200 body `<script>location.replace('https://x')</script>` | `Interstitial` → WebView path |
| 3xx without Location (regression) | Server: 302, no `Location` header | `Error` → toast + degrade |
| Redirect loop | [https://a.example/1](https://a.example/1) ↔ [https://b.example/2](https://b.example/2) | `Loop` → toast + degrade |
| Max hops exceeded | 7+ redirect chain | `MaxHops` → toast + degrade |
| Non-http(s) target | [https://t.co/abc](https://t.co/abc) → 302 `Location: ftp://files.example/x` | `Rejected` → toast + degrade |
