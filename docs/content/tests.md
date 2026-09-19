---
date: 2026-09-10
draft: false
title: "Feature Test Links"
description: "Example links to verify each LinkRouter feature end-to-end on a device."
---

# Feature Test Links

Every row below is rendered directly from
[`app/linkrouter-rules-examples.json`](app/linkrouter-rules-examples.json) — the
same fixtures the unit-test suite consumes — so the page is a 1:1 map of what
the router should do: **Input** (what you open) → **Expected Output** (what the
target browser should show).

> **Setup** — enable the relevant feature before testing it:
>
> - *Shortener hosts* and *Host rewrites* ship **disabled** (opt-in) — flip the
>   specific host ON in Settings first.
> - *Rules* and *Redirect formats* must exist (add them, or keep) in Settings.
> - *Query-param filters* ship **enabled** by default.
>
> See [How to test](#how-to-test) for a reliable workflow.

{{< rule-examples >}}
