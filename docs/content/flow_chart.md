+++
date = '2026-09-21T13:10:31Z'
draft = false
title = 'URL Flow Chart'
description = 'flow chart of URLs through LinkRouter'
+++
# URL Flow

An incoming link arrives through one of two intents and always travels the same pipeline before any browser is launched. Non-web schemes (e.g. `mailto:`) are dropped, and once a URL reaches a browser it is marked so it is never re-processed (loop guard). The shortener-resolution step is opt-in and off by default (zero network):

{{<mermaid>}}
graph TD
    A["Incoming link<br/>ACTION_VIEW — default browser / link tap<br/>or ACTION_SEND — Share sheet"] --> B["Extract and normalize URL<br/>(RuleEngine.normalize)"]
    B --> C{"Web URL?<br/>(http / https)"}
    C -->|"no (e.g. mailto:)"| O["Ignore — finish"]
    C -->|"yes"| D{"Already handled?<br/>(loop guard)"}
    D -->|"yes"| O
    D -->|"no"| E["Load enabled rules,<br/>formats, filters, rewrites"]
    E --> E2["Unwrap redirect wrappers<br/>(user formats, e.g. google.com/url)"]
    E2 --> F{"Enabled shortener host?<br/>(opt-in, off by default)"}
    F -->|"no"| H
    F -->|"yes"| G["Resolve shortener to final URL<br/>(pure-JVM; ephemeral WebView fallback)"]
    G --> H["Match rules<br/>(specificity first, list-order tie-break)"]
    H --> I{"Rule matched?"}
    I -->|"no"| J["Fallback<br/>(chooser / OS default / block / ask-and-remember / specific browser)"]
    J --> N["finish"]
    I -->|"yes"| K["Host rewrite<br/>(first enabled rewrite rule)"]
    K --> L["Strip tracker query params<br/>(final launched URL only)"]
    L --> M["Launch in target browser<br/>(private / normal / in-app WebView / Stop)"]
    M --> N
{{</mermaid>}}

