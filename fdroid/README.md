# LinkRouter — F-Droid submission guide (draft)

This folder holds a **draft F-Droid recipe** for the app in this repo
(`net.chaosengine.linkrouter`). It is a drafting artifact: nothing here changes
the app's own build files, and nothing is meant to be committed to F-Droid yet.

Files here:
- `net.chaosengine.linkrouter.yml` — the recipe (f-droiddata `metadata` schema).
- `README.md` — this guide.

## Where the recipe actually goes

F-Droid reads **per-version metadata files**. The draft
`net.chaosengine.linkrouter.yml` in this folder corresponds to:

```
<fdroiddata or third-party-repo>/
    metadata/net.chaosengine.linkrouter/0.1.0.yml
```

- **Main `f-droiddata` repo:** the `0.1.0` version file lives at
  `metadata/net.chaosengine.linkrouter/0.1.0.yml`, and a *second* file at
  `metadata/net.chaosengine.linkrouter.yml` (the package manifest listing the
  versions). The draft contains the fields F-Droid needs for one version.
- **Third-party repo:** the layout is the same `metadata/…` convention; the
  repo's maintainer handles the manifest file.

## Prereqs that MUST be true before the recipe can be accepted

| Requirement | Status | Notes |
|---|---|---|
| Public source repo at `SourceCode.repo`, tagged `v0.1.0` (+ commit) | **TODO** | Must be public, reachable over `https`, and contain the `v0.1.0` tag. No local tag exists yet. |
| App licensed GPLv3 | **Done** | `LICENSE` is the full GPLv3 text; recipe uses `GPL-3.0-only`. |
| All deps FLOSS | **Done** | AGP/Kotlin/Compose/Room/Moshi/Coil — all free (Apache-2.0 or compatible). No proprietary deps, so `doDeps` is empty. |
| Real `Website` + `IssueTracker` | **TODO** | F-Droid requires both; both are `TODO_*` placeholders. |
| `./gradlew clean assembleRelease` works from a clean checkout, offline (allowed hosts only) | **Verify** | Deps come only from `google()` + `mavenCentral()` (F-Droid-allowed). JDK 17 required (see build notes). |
| `AuthorName` / `AuthorEmail` | **TODO** | Placeholders, not invented. |

## How F-Droid builds it

- F-Droid checks out `SourceCode` (the `v0.1.0` tag / pinned commit) and runs
  the `Build:` steps inside a **clean, sandboxed build environment with a JDK 17
  toolchain** (AGP 8.10.1 / Kotlin 2.0.21 / KSP / Compose need it — the F-Droid
  image provides this; no action needed).
- It runs `doBuild` → `./gradlew clean assembleRelease --stacktrace`.
  Because **none** of the `RELEASE_*` signing secrets are present in the clean
  checkout, the app's build type is left **unsigned**, so the output is exactly
  `app/build/outputs/apk/release/app-release-unsigned.apk`. This is the expected
  unsigned input.
- It copies that file to `LinkRouter.apk` (`doPackage`), then **signs the APK
  with F-Droid's own key** and re-packages it for the store. **You do not provide
  a key; you must not sign it yourself.**
- `doCheck` (`./gradlew test`) is optional and is run per the repo's test
  policy; the unit tests are pure-JVM / Robolectric and pass offline.

### Why `./gradlew` instead of the bare `gradle` binary

The project commits a `gradle-wrapper.jar` pinned to Gradle **8.11.1**. The
task scaffold suggested the generic `gradle clean assembleRelease …`. Building
via the committed `./gradlew` is the more faithful and safer choice: it uses the
exact Gradle AGP 8.10.1 was validated against, independent of which Gradle the
F-Droid image ships. If you prefer the bare binary, replace `./gradlew` with
`gradle` (requires the image's Gradle to satisfy AGP 8.10.1's minimum).

### Build notes (documented, not required to change)

- `doDeps` is **empty by design**: every dependency resolves from `google()` and
  `mavenCentral()`, both in F-Droid's default allow-list. No non-free deps added.
- Release uses **R8 minification** (`isMinifyEnabled = true`); the APK F-Droid
  signs is `app-release-unsigned.apk`.
- Only the `INTERNET` (normal, install-granted) permission is declared, and only
  exercised if the opt-in shortener feature is enabled. No ad/analytics SDKs.

## Two submission paths

1. **Third-party F-Droid repo (easiest for a niche tool).**
   Fork or contribute to a third-party F-Droid data/metadata repo (e.g.
   `muntashirakon`-style or any maintained one). Add
   `metadata/net.chaosengine.linkrouter/0.1.0.yml`, and the package manifest
   `metadata/net.chaosengine.linkrouter.yml`. Their CI (usually the
   `f-droid/fdroidserver` build service) builds + signs.
2. **Main `f-droiddata` repo.**
   Open a PR against `fdroid/fdroiddata` adding
   `metadata/net.chaosengine.linkrouter/0.1.0.yml` (and the manifest file). This
   is the canonical store but has stricter review requirements (public repo,
   working build, FLOSS deps, no `TODO` fields remaining).

## Checklist (fields the maintainer must fill)

| Field | Current value | Status |
|---|---|---|
| `Name` | `LinkRouter` | Done |
| `Summary` | one-liner | Done |
| `Description` | multi-line | Done |
| `AuthorName` | `Chaos Engine` | TODO (real name) |
| `AuthorEmail` | `REPLACE_WITH_CONTACT_EMAIL` | TODO |
| `License` | `GPL-3.0-only` | Done (verified GPLv3 `LICENSE`) |
| `Website` | `TODO_ADD_WEBSITE` | TODO |
| `IssueTracker` | `TODO_ADD_ISSUE_TRACKER` | TODO |
| `SourceCode.repo` | `TODO_ADD_PUBLIC_REPO_URL` | TODO (public repo) |
| `SourceCode.tag` | `v0.1.0` | TODO (must exist on the public repo) |
| Deps (`doDeps`) | empty | Done (all FLOSS, allowed hosts) |
| `doBuild` | `./gradlew clean assembleRelease` | Done |
| `doCheck` | `./gradlew test` (optional) | Done |
| `doPackage` | copy unsigned release APK | Done |

## Schema notes / assumptions

- The draft uses the **current** f-droiddata `metadata` schema
  (`Name / Summary / Description / AuthorName / AuthorEmail / License /
  Website / IssueTracker / SourceCode{repo|tag|commit} / Build{doBuild|doCheck|doPackage}`).
  (Live f-droiddata could not be fetched from this sandbox — all external
  fetches returned 404 — so the schema was taken from F-Droid's stable, current
  metadata format. Key names here match that schema.)
- **`ifNoAuthForce`** present in the task scaffold is **not** a valid
  f-droiddata `SourceCode` key and was **omitted**.
- `commit` is left as a *commented* alternative to `tag` so a broken `TODO`
  value can't be interpreted as a real commit by F-Droid. Pick one ref.
- Build invoked with `./gradlew` (committed wrapper) rather than bare `gradle`.
- `doCheck` is optional per the task; it is included but easily removable.
- License is asserted **GPLv3** from the repo `LICENSE` header — confirm it is
  the intended license before submission.
