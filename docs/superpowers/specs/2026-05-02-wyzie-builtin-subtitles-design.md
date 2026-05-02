# Wyzie Built-In Subtitles — Design

**Date:** 2026-05-02
**Worktree:** `integration-runtime-phase-a`
**Status:** Drafted, awaiting user review before plan generation.

## 1. Goal

Add Wyzie Subs as a first-party, built-in subtitle source in Nexio, sitting alongside the existing addon-based subtitle path. Wyzie aggregates subtitles from up to nine providers (OpenSubtitles, SubDL, Subf2m, Podnapisi, Gestdown, AnimeTosho, Jimaku, Kitsunekko, AjattTools — yify is intentionally dropped). The integration is curated per content type so that the right providers are queried for movies, TV, anime movies, and anime series.

The current "built-in OpenSubtitles" experience is in fact whichever subtitle Stremio addon the user has installed. Wyzie gives users a robust default that does not depend on third-party addon availability or quality, while leaving the addon ecosystem untouched.

## 2. Non-Goals

- Replacing the addon-based subtitle path. (Wyzie is additive.)
- Local subtitle caching beyond what Wyzie's CDN already provides.
- Surfacing the Wyzie `release` name in the picker UI (deferred — only language and source are shown).
- Per-source enable/disable UI; Hearing-Impaired filter UI; encoding override UI.
- Using `refresh=true` to bust Wyzie's cache from the player.
- Any embedded WebView / in-app key-redeem flow.
- A standalone reusable Kotlin port of `wyzie-lib` (no separate Gradle module).
- A first-run nudge / onboarding hint about getting a Wyzie key.

## 3. Locked Decisions

| # | Decision | Rationale |
|---|---|---|
| Q1 | **Coexist + merge** with addon subtitles | Preserves addon ecosystem; existing parallel-fetch+merge code already shaped for this. |
| Q2 | **BYO API key** in settings, configurable from both Android TV and `nexio-web` | Honors Wyzie's "one key per device" model; survives upstream enforcement; keys are free. |
| Q3 | **Hardcoded source lists** per content type, no UI override | Redundancy across the source list absorbs individual outages. |
| Q4a | Detect anime via **stable-id presence** (`kitsu` / `mal` / `anilist` / `anidb`) | Single source of truth in `ProviderIds`; no string-prefix coupling. |
| Q4b | **Plumb `WyzieIdHints` through `SubtitleRepository.getSubtitles`** | Wyzie only accepts IMDB/TMDB; we need richer ids than a single routing string. |
| Q5 | **Pass URLs through to Media3**, request `format=srt,ass,vtt`, drop yify | Avoids ZIP-unwrap complexity for one source; Media3 handles the rest. |
| Q6 | **Per-source identity** in picker (`addonName = "Wyzie · OpenSubtitles"`) | Surfaces provenance with zero `Subtitle` model changes. |
| Q7 | **Silent degrade** for missing key / failures | It's a free feature; addons keep working; no nags. |
| Impl | **Single `IntegrationProvider`** under `data/integration/subtitles/wyzie/`, no separate Gradle module | Reuses existing `IntegrationRuntime` pattern; ~3 endpoints; no extractability driver. |

## 4. Architecture & Module Layout

A new `wyzie` package under `data/integration/subtitles/`. Wyzie becomes a **second lane** inside `SubtitleRepositoryImpl`, fetched in parallel with the addon lane and merged into the same `List<Subtitle>` the player already consumes.

```
app/src/main/java/com/nexio/tv/
├── data/integration/subtitles/wyzie/
│   ├── WyzieSubtitleIntegrationProvider.kt   # IntegrationProvider — calls /search
│   ├── WyzieSourceRouter.kt                  # ContentType + WyzieIdHints → source list
│   ├── WyzieResultMapper.kt                  # WyzieSubtitleDto → domain Subtitle
│   └── transport/
│       ├── WyzieSubtitleApi.kt               # Retrofit interface (/search)
│       └── WyzieSubtitleTransport.kt         # OkHttp wiring, key-injection interceptor
├── data/remote/dto/
│   └── WyzieSubtitleDto.kt                   # @Serializable mirror of SubtitleData
├── data/local/
│   └── WyzieSettingsDataStore.kt             # api key + enabled flag
├── domain/model/
│   └── WyzieIdHints.kt                       # imdb / tmdb / kitsu / mal / anilist / anidb carrier
├── data/repository/
│   └── SubtitleRepositoryImpl.kt             # MODIFIED — second lane added
├── domain/repository/
│   └── SubtitleRepository.kt                 # MODIFIED — getSubtitles() takes WyzieIdHints
└── ui/screens/settings/
    └── WyzieSubtitleSettingsScreen.kt        # key field + toggle
```

### Boundaries

- `WyzieSubtitleIntegrationProvider` knows only how to "given hints + source list, return DTOs." It does not know about routing rules or the player.
- `WyzieSourceRouter` is pure logic — `(ContentType, WyzieIdHints) → List<WyzieSource>`. No I/O, easy to unit-test.
- `WyzieResultMapper` converts DTOs to the existing `Subtitle` domain model with `addonName = "Wyzie · <Source>"`.
- `SubtitleRepositoryImpl` is the *only* place that knows both lanes exist. Player code is unchanged except for passing `WyzieIdHints`.
- `nexio-web` round-trips through the same `WyzieSettingsDataStore` via the existing settings bridge — no new sync mechanism.

## 5. Configuration

### 5.1 Storage

`WyzieSettingsDataStore` backed by Preferences DataStore (mirrors `SubtitleTranslationSettingsDataStore`):

```kotlin
data class WyzieSettings(
    val apiKey: String? = null,        // raw Wyzie key, e.g. "wyzie-abc123xyz"
    val enabled: Boolean = true,        // user kill-switch; doesn't gate on key presence
)
```

### 5.2 Behavior

- `enabled == false` → Wyzie lane skipped entirely. Addons still run.
- `apiKey` null/blank → Wyzie lane skipped (silent degrade). Addons still run. `enabled` stays `true` so the lane reactivates once a key is pasted.
- Key is opaque text. Trim whitespace on save. Non-blank is the only validation; format may evolve upstream.
- Stored in plaintext (consistent with how Trakt / debrid tokens are stored today). Secure storage upgrade is out of scope.

### 5.3 Android TV settings UI (`WyzieSubtitleSettingsScreen.kt`)

- One row: "Wyzie subtitles" with an enable/disable switch.
- Below it: a key field showing either "Not configured" or the masked key (`wyzie-abc•••xyz`).
- "Get a free key" action: displays a QR code linking to `https://sub.wyzie.io/redeem`. D-pad friendly.
- "Enter key" action: opens the standard text input dialog.
- Lives under the existing playback / subtitle settings cluster, sibling to `SubtitleTranslationSettingsScreen`.

### 5.4 nexio-web settings UI

- Mirror the same two fields (toggle + key input) on the appropriate web settings page.
- Reads/writes the same `WyzieSettingsDataStore` via the existing settings bridge. Changes round-trip both directions immediately.

### 5.5 Key injection

A small OkHttp `Interceptor` on the Wyzie HTTP client appends `key=<value>` to every outgoing query before the request hits the network. If the key is null/blank when the interceptor fires, it short-circuits with a synthetic 401 (mapped to empty list). In practice the repo skips the call entirely when the key is absent — the interceptor is a safety net.

## 6. Source Routing

```kotlin
enum class WyzieSource(val apiName: String) {
    OPENSUBTITLES("opensubtitles"),
    SUBDL("subdl"),
    SUBF2M("subf2m"),
    PODNAPISI("podnapisi"),
    GESTDOWN("gestdown"),
    ANIMETOSHO("animetosho"),
    JIMAKU("jimaku"),
    KITSUNEKKO("kitsunekko"),
    AJATTTOOLS("ajatttools"),
}

object WyzieSourceRouter {
    fun sourcesFor(type: ContentType, hints: WyzieIdHints): List<WyzieSource>
}
```

### 6.1 Decision tree (in order)

1. **Anime?** True iff any of `hints.kitsu / mal / anilist / anidb` is non-null.
2. Anime + `MOVIE` → `[JIMAKU, AJATTTOOLS]`
3. Anime + `SERIES` (or `TV`) → `[ANIMETOSHO, JIMAKU, KITSUNEKKO, AJATTTOOLS]`
4. Else `MOVIE` → `[OPENSUBTITLES, SUBDL, SUBF2M, PODNAPISI]` *(yify dropped)*
5. Else `SERIES` / `TV` → `[OPENSUBTITLES, SUBDL, SUBF2M, PODNAPISI, GESTDOWN]`
6. Else → empty list (skip Wyzie lane).

### 6.2 Single API call, not parallel

Wyzie's `/search` accepts a comma-separated `source=` parameter and queries them simultaneously server-side. We make **one HTTP request per fetch** with `source=opensubtitles,subdl,subf2m,podnapisi,…` — no client-side fan-out, no per-source timeout management.

### 6.3 Hint extraction

`PlayerRuntimeControllerObservers.fetchAddonSubtitlesNow` already has access to the current `Meta`, which carries the full `ProviderIds` via the rail/hydration pipeline. Build hints once at the call site:

```kotlin
WyzieIdHints(
    imdb = providerIds.imdb,                  // "tt1234567" form preserved
    tmdb = providerIds.tmdb?.toIntOrNull(),
    kitsu = providerIds.kitsu,
    mal = providerIds.mal,
    anilist = providerIds.anilist,
    anidb = providerIds.anidb,
)
```

### 6.4 Wyzie `id` parameter selection

Wyzie itself only accepts IMDB or TMDB ids, even for its anime sources (jimaku/kitsunekko/ajatttools resolve via TMDB internally). Selection rules:

- Prefer `imdb` (with `tt` prefix preserved) if present, else `tmdb`.
- If neither is set → **skip Wyzie lane entirely**, even for anime. No call is made. Addons still run.

### 6.5 TV episode params

When `type` is series/TV and the player has `season` + `episode`, both are sent on the same `/search` call. Both must be present together (Wyzie rejects one without the other; we pre-validate client-side and skip the call if mismatched, logging the reason).

### 6.6 Constant request params

- `format=srt,ass,vtt` (omits `.sub` and `.ssa` quirks)
- No `language=` filter — existing client-side filter/sort handles language preferences and the picker shows everything.
- No `hi=` filter — HI status surfaces via the result's `isHearingImpaired`.
- No `release=` / `filename=` filters in v1.

## 7. Data Flow

```
PlayerRuntimeControllerObservers.fetchAddonSubtitlesNow()
    │
    ├─ Build WyzieIdHints from current Meta.providerIds
    │
    └─ subtitleRepository.getSubtitles(type, id, videoId, hash, size, filename, hints)
            │
            ├─ Lane A (existing, unchanged): query each subtitle-supporting addon in parallel
            │       → List<Subtitle>  (addonName = real addon name)
            │
            └─ Lane B (new): WyzieSubtitleIntegrationProvider.search(type, hints, season, episode)
                    │
                    ├─ Skip if: !settings.enabled || settings.apiKey.isNullOrBlank()
                    │           || sourcesFor(type, hints).isEmpty()
                    │           || (hints.imdb == null && hints.tmdb == null)
                    │
                    ├─ One HTTP GET to /search with id, source list, format, [season, episode]
                    │       (key injected by interceptor; per-call timeout = 8000ms,
                    │        same as PER_ADDON_TIMEOUT_MS for consistency)
                    │
                    └─ Map List<WyzieSubtitleDto> → List<Subtitle>
                            via WyzieResultMapper:
                                addonName = "Wyzie · ${dto.source.displayName()}"
                                addonLogo = wyzieSourceIcon(dto.source)   // per-source vector asset
                                id        = "wyzie:${dto.id}"             // namespaced
                                url       = dto.url                        // playable as-is
                                lang      = dto.language                   // ISO 639-1
    │
    ├─ Lane A and Lane B run in the same coroutineScope { ... awaitAll() } block.
    │   Either lane failing/timing out yields an empty list for that lane only.
    │
    └─ Concatenate: laneA + laneB → existing filterAndSortAddonSubtitlesForPreferences
                                     (downstream code is unchanged)
```

### 7.1 Dedup

None in v1. Per-source labeling makes duplicates visually distinct, and the addon and Wyzie lanes will rarely overlap (most users won't run a subtitle addon if Wyzie is on). If duplicates become a UX problem, a `(language, source, release)` dedup pass can be added later — the data model already carries enough info.

### 7.2 Per-source icons

Static `WyzieSourceIcon` map under `app/src/main/res/drawable/` with one vector asset per source (9 total — `ic_wyzie_opensubtitles.xml`, `ic_wyzie_subdl.xml`, …). All sources fall back to a generic `ic_wyzie.xml` if a specific icon is missing. Display strings live alongside in `WyzieSource.displayName()` (`OPENSUBTITLES → "OpenSubtitles"`, `SUBF2M → "Subf2m"`, etc.).

### 7.3 Source identity from response

Wyzie's `SubtitleData.source` is typed `string | string[]` in the NPM types. The DTO accepts both shapes (custom kotlinx.serialization adapter): if it's a list, use the first entry. If absent, fall back to `"Wyzie"` plain.

### 7.4 Caching

None client-side in v1. Wyzie caches server-side. The player only fetches once per stream load. The `refresh=true` Wyzie param is not exposed in v1 (could be wired to a "Reload subtitles" button later).

### 7.5 Telemetry

`Log.d/e` lines mirroring the addon lane, prefixed `WYZIE_SUBS`. Captures `sourcesQueried`, `count`, `latencyMs`, error class on failure. No analytics events.

## 8. Error Handling

Silent degrade across the board. The Wyzie lane is exactly as quiet as a flaky addon.

| Condition | Behavior |
|---|---|
| `enabled == false` | Lane skipped before any work. No log. |
| `apiKey` null/blank | Lane skipped. `Log.d("WYZIE_SUBS skipped: no key")`. |
| Hints have neither imdb nor tmdb | Lane skipped. `Log.d("WYZIE_SUBS skipped: no usable id for type=$type")`. |
| `WyzieSourceRouter.sourcesFor(...)` returns empty | Lane skipped. `Log.d("WYZIE_SUBS skipped: no sources for type=$type anime=$isAnime")`. |
| Season/episode mismatch (one without other) | Lane skipped. `Log.d("WYZIE_SUBS skipped: season/episode partial")`. |
| HTTP 401 / 403 | Lane returns `emptyList()`. `Log.w("WYZIE_SUBS auth failed status=$code")`. **Key is NOT auto-cleared** — user might be offline-typing-wrong-key, and we don't want to silently nuke a key that just needs re-entry. |
| HTTP 4xx / 5xx | Lane returns `emptyList()`. `Log.w("WYZIE_SUBS http error status=$code reason=$reason")`. |
| Network timeout (8s, matches addon lane) | Lane returns `emptyList()`. `Log.w("WYZIE_SUBS timed out")`. |
| Malformed JSON | Lane returns `emptyList()`. `Log.e("WYZIE_SUBS parse error", e)`. |
| `CancellationException` | Re-thrown (consistent with addon lane). |
| Player cancellation mid-fetch | Coroutine scope tears down naturally; nothing leaks. |

**Crash safety:** All Wyzie code paths wrapped exactly like `fetchSubtitlesFromAddon` — try/catch that re-throws `CancellationException` and swallows everything else into an empty-list log. A bug in Wyzie code can never break the addon lane.

**Lane independence:** Lane A (addons) and Lane B (Wyzie) live in sibling `async { }` blocks under the same `coroutineScope`. A throw from one is caught locally inside that lane's try/catch, so `awaitAll` never sees an exception — both lanes always contribute (possibly empty) lists.

## 9. Testing Strategy

Mirrors how `SubtitleRepositoryImpl` and existing integration providers are tested in this codebase (Robolectric + JUnit, fake transports, no live HTTP).

### 9.1 Unit tests — pure logic

- **`WyzieSourceRouterTest`**
  - Anime detection: each of `kitsu`, `mal`, `anilist`, `anidb` populated alone → anime sources returned.
  - Anime + MOVIE → `[JIMAKU, AJATTTOOLS]` exactly, in order.
  - Anime + SERIES → `[ANIMETOSHO, JIMAKU, KITSUNEKKO, AJATTTOOLS]` exactly, in order.
  - Non-anime + MOVIE → `[OPENSUBTITLES, SUBDL, SUBF2M, PODNAPISI]` (yify absent).
  - Non-anime + SERIES → `[OPENSUBTITLES, SUBDL, SUBF2M, PODNAPISI, GESTDOWN]`.
  - Non-anime + TV (alias) → same as SERIES.
  - Unknown ContentType → empty list.
  - Hints with neither imdb nor tmdb → router still returns the source list (the *id-skip* check lives in the integration provider, not the router; this test pins that boundary).

- **`WyzieResultMapperTest`**
  - DTO with `source = "subdl"` → `addonName == "Wyzie · SubDL"`, `addonLogo == ic_wyzie_subdl resource id`.
  - DTO with `source = ["opensubtitles", "subdl"]` (array form) → uses first.
  - DTO with `source = null` → `addonName == "Wyzie"`, `addonLogo == ic_wyzie generic`.
  - DTO with empty `language` → mapped to `Subtitle.lang = ""`, no crash.
  - `id` namespacing: `Subtitle.id` starts with `wyzie:`.
  - DTO with unknown `source` value → `addonName == "Wyzie · <raw value>"`, generic logo.

- **`WyzieSubtitleDtoTest`** (serialization)
  - Real Wyzie response fixture (one per source) deserializes round-trip.
  - `source` field accepts both string and string-array shapes.
  - Optional fields (`release`, `releases`, `fileName`, `downloadCount`, `origin`, `matchedRelease`, `matchedFilter`) tolerate missing/null.

### 9.2 Integration-provider tests

- **`WyzieSubtitleIntegrationProviderTest`** — uses a fake `WyzieSubtitleApi` (in-memory Retrofit interface impl) wired through the real `IntegrationRuntime`.
  - Skips network call when settings disabled.
  - Skips network call when api key blank.
  - Skips network call when both imdb and tmdb absent.
  - Skips network call when source list empty.
  - Sends `id=tt0121955` when imdb present (with `tt` prefix preserved).
  - Sends `id=12345` when only tmdb present.
  - Prefers imdb when both present.
  - Sends `season=1&episode=2` when both provided; rejects season without episode (no call, log).
  - Sends `source=opensubtitles,subdl,subf2m,podnapisi` joined correctly.
  - Sends `format=srt,ass,vtt`.
  - 401 → empty list, log line emitted.
  - 5xx → empty list.
  - Timeout (8s) → empty list.
  - `CancellationException` re-thrown.

### 9.3 Repository tests — `SubtitleRepositoryImplTest` additions

- Both lanes succeed → result contains addons + Wyzie items, ordering preserved.
- Wyzie lane throws → addon results unaffected.
- Addon lane throws → Wyzie results unaffected.
- Both lanes empty → empty list.
- `WyzieIdHints` flows through unchanged from caller to provider (mock provider verifies received hints).

### 9.4 Settings tests

- **`WyzieSettingsDataStoreTest`** — round-trip key + enabled flag, default values, blank-trim on save.

### 9.5 Manual smoke (recorded, not automated)

- One movie with TMDB id only.
- One movie with IMDB id only.
- One TV episode (S/E both set).
- One anime entry (Kitsu id present, TMDB id present) — both anime sources queried, picker shows `Wyzie · Jimaku` etc.
- One anime entry (Kitsu id present, TMDB id absent) — Wyzie skipped silently.
- Disable Wyzie in settings mid-session → next fetch shows addon results only.
- Clear key → next fetch silently skips Wyzie.

### 9.6 Out of scope for v1 tests

- Live HTTP against `sub.wyzie.io`.
- Key redemption flow.
- Dedup logic (none implemented).

## 10. Open Questions

None blocking implementation. Items deferred for v2:

- Whether to add a `(language, source, release)` dedup pass once usage data shows overlap with addons.
- Whether to surface the `release` name in the picker as a secondary line.
- Whether to wire `refresh=true` to a "Reload subtitles" button in the picker.
- Whether to add per-source enable/disable toggles in settings if particular providers prove problematic.
