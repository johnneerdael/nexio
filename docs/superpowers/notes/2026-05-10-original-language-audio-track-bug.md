# Audio track auto-selection picks wrong language at startup — root cause + architecture review

**Date:** 2026-05-10
**Severity:** User-visible incorrect behavior (every playback session for users whose UI language ≠ show's production language)
**Bug class:** Conceptual conflation of two distinct fields, masked by a silent fallback
**Mode:** Root cause analysis only — no fixes attempted

---

## TL;DR

When a user has playback set to **Audio language = Original** and starts an English show, the player auto-selects the wrong audio track (e.g. Polish over English on Citadel). This happens whenever the user's app/system UI language differs from the show's production language.

The player is doing its job correctly. The bug is upstream: nexio plumbs the metadata router's `localization.selectedLanguage` (the user's UI locale, used to pick which translation to fetch) into the `originalLanguage` slot the player consumes for audio targeting. The two are unrelated concepts, but a fallback chain in `MetaDetailsViewModel` collapses one into the other when the show's actual production language is missing from the canonical metadata document.

The show's production language **is** available from TVDB's `/series/{id}/extended` response as `originalLanguage` (confirmed live: Citadel → `'eng'`). It is parsed into the Kotlin DTO at `TvdbApi.kt:242`, then **dropped on the floor**: no TVDB adapter or metadata-router resolver promotes it into `ResolvedField.LANGUAGE`, so `ResolvedMetadataDocument.language` is `null` for every TVDB-routed item, and the silent fallback to UI locale fires every time.

---

## Reproduction

- App language: Dutch (system locale `nl-NL`, app metadata locale `nld`).
- Playback setting: Audio language → **Original**.
- Show: Citadel S1 (Amazon Prime Video, originally produced in English).
- Stream: a multi-track release containing tracks `[0] pl` (Polish dub), `[1] en` (English original).
- Expected: track `[1] en` auto-selected at startup.
- Actual: track `[0] pl` plays.

---

## Evidence

### Smoking-gun on-device log

`PlayerRuntimeControllerTracks.logStartupAudioDiagnosis` (the always-on `AUDIO_STARTUP_EVAL` diagnostic) emitted:

```
05-10 03:25:21.811 1303 1303 I PlayerViewModel: AUDIO_STARTUP_EVAL: pref=original origLang=nld targets=[nl] wouldPick=[-1]<none> current=[0]pl|Polish (E-AC-3 5.1) autoSelected=false rememberedApplied=false rememberedLang=null tracks=[[0]pl|Polish (E-AC-3 5.1), [1]en|English (E-AC-3 5.1)]
```

Decoded:

| Field | Value | Comment |
|---|---|---|
| `pref` | `original` | User preference: correct |
| `origLang` | `nld` | **Wrong.** `nld` is Dutch ISO-3, the user's UI locale. Citadel's actual `originalLanguage` per TVDB is `eng`. |
| `targets` | `[nl]` | Computed from `origLang`. Correct given the wrong input. |
| `wouldPick` | `-1` | No Dutch track in the file. Correct given the wrong target. |
| `current` | `[0] pl` | Media3's pre-auto-selection default. Pure positional luck — the TS muxer happened to put Polish first. |
| `tracks` | `[pl, en]` | English exists at index 1; would be picked under `targets=[en]`. |

The picker chain runs as follows after the log fires:

1. `resolvePreferredAudioLanguages(...)` returns `["nl"]` (non-empty).
2. `findBestStartupAudioTrackIndex` returns `-1` because no track matches `nl`.
3. `resolveStartupAudioSelectionIndex` returns `currentSelectedIndex` (Media3 default → `0`).
4. The "preference-empty" original-language fallback at `PlayerRuntimeControllerTracks.kt:556` — which would call `findOriginalTrackFallbackIndex` and pick a non-dubbed track — **never fires**, because it gates on `preferredAudioLanguages.isEmpty()`. The list is non-empty: it's `["nl"]`.

### TVDB API has the correct data

Live calls against `/v4/series/{id}/extended`:

| Series | TVDB id | `originalLanguage` | `originalCountry` |
|---|---|---|---|
| Citadel (the show being watched) | 393268 | `eng` | `usa` |
| Supernatural | 78901 | `eng` | `usa` |
| Breaking Bad | 81189 | `eng` | `usa` |
| Game of Thrones | 121361 | `eng` | `usa` |
| 诛仙 (hydrated in same session) | 423000 | `zho` | `chn` |

Schema reference: `tvdb.yml:3948-3951`:

```yaml
SeriesExtendedRecord:
  ...
  originalCountry:
    type: string
  originalLanguage:
    type: string
```

These are ISO-3 codes, exactly the form `PlayerSubtitleUtils.normalizeLanguageCode` already collapses to ISO-2 (`eng → en`).

### nexio parses the field but throws it away

`app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt:241-242`:

```kotlin
@Json(name = "originalCountry")  val originalCountry: String? = null,
@Json(name = "originalLanguage") val originalLanguage: String? = null,
```

So the JSON value reaches Kotlin. After that it disappears: `grep -rn originalLanguage` across `data/integration/tvdb/`, `data/integration/metadata/`, and `core/metadata/` returns **zero references** (other than this DTO and unrelated TMDB code). The TVDB metadata adapter never produces a `ResolvedField.LANGUAGE` candidate from it.

### Field-resolution mechanics

`core/metadata/router/FieldResolver.kt:297` builds the resolved document:

```kotlin
language = fields[ResolvedField.LANGUAGE] as? String,
```

For TVDB-routed series, no candidate ever submits a `LANGUAGE` field, so `fields[LANGUAGE]` is missing → `ResolvedMetadataDocument.language = null`.

`data/repository/MetadataDisplayRepository.kt:264-275` faithfully copies that:

```kotlin
DetailAdvancedMetadata(
    ageRating = ageRating,
    countries = countries,
    language = language,                  // ← null
    ...
    originalCountry = originalCountry,    // also unpopulated for TVDB → null
    ...
)
```

### The silent fallback that hides the gap

`ui/screens/detail/MetaDetailsViewModel.kt:1550`:

```kotlin
language = document.advanced.language
    ?: document.localization.selectedLanguage
    ?: updated.language
```

When the canonical field is null, this falls through to `localization.selectedLanguage` — the user's UI locale (`nld`). Same fallback also at `:1617` and `:3398`. The earlier `Nexio.MetaRoute` log explicitly shows where `selectedLanguage` comes from:

```
metadata.localization_plan contentId=tvdb:393268 provider=TVDB
  requestedLanguage=nld fallbackLanguage=eng
  requestedIsFallback=false localeCollapsedToFallback=false
```

`requestedLanguage=nld` is the locale used to fetch translations. It has nothing to do with the production language of the show.

### The plumbing through to the player

`Meta.language = "nld"` then flows:

```
NexioNavHost.kt:172   originalLanguage = item.language        // "nld"
  → Stream nav route arg                                      // "nld"
    → StreamScreenViewModel.originalLanguage                  // "nld"
      → Player nav route arg                                  // "nld"
        → PlayerNavigationArgs.originalLanguage               // "nld"
          → PlayerRuntimeController.originalLanguage          // "nld"
            → resolvePreferredAudioLanguages(...)             // targets=["nl"]
              → findBestStartupAudioTrackIndex                // -1
                → Media3 default wins                         // track[0]=Polish
```

---

## Two independent bugs, not one

This investigation surfaces **two distinct defects** that compound. Either one alone would cause user-visible regressions; together they produce the observed misbehavior. They must be evaluated and fixed independently — fixing only one leaves a known-broken codepath in production.

### Bug A — UI-locale leakage into a content-property slot (primary defect, independent of any API)

`MetaDetailsViewModel.kt:1550, 1617, 3398` contains:

```kotlin
language = document.advanced.language
    ?: document.localization.selectedLanguage
    ?: updated.language
```

`document.advanced.language` is the show's production language (a content property). `document.localization.selectedLanguage` is the user's UI locale (a user property). They are **different concepts**. The `?:` chain silently substitutes the user property for the content property whenever the latter is null — and writes the result into a field named `Meta.language` that downstream code (the Stream/Player nav routes, `StreamScreenViewModel.buildOriginalLanguageMatchTokens`, `PlayerRuntimeController.originalLanguage`) consumes as if it were the content property.

**This bug exists regardless of any API or routing work.** It would still fire if:

- A show's TVDB entry had `originalLanguage = ""` or was malformed.
- A show came from an addon-only path that doesn't expose production language.
- The metadata router's TVDB candidate failed for any reason (rate limit, network blip, identity-resolution miss) and the document fell back to a partial source.
- A new metadata source were added that didn't supply the field.

In every one of those cases, with this fallback in place, `Meta.language` becomes the user's UI locale. The detail-screen language badge displays "NLD" for an English show. The player targets Dutch audio for an English show. The stream filter `buildOriginalLanguageMatchTokens` constructs Dutch-language tokens for an English show. The breakage is silent — there is no log, no warning, no telemetry event distinguishing "we resolved the production language to Dutch" from "we substituted the user's locale because we didn't know the production language".

This is the textbook shape of a *type-collision bug*: two semantically different values share a Kotlin type (`String?`) and a field name (`language`), so a `?:` chain that looks safe (every operand is `String?`) silently corrupts the meaning. The compiler can't catch it. The fallback's author almost certainly intended it as cosmetic UI sugar (keep the badge non-empty); they could not have anticipated the consumer that arrived later (`originalLanguage = item.language` in nav routing).

**Why this matters separately from Bug B:** even after the TVDB plumbing in Bug B is fixed, this fallback remains a loaded gun pointed at every future codepath that reads `Meta.language`. The next addon-sourced show, the next field-resolver edge case, the next `updated.copy(language = ...)` site — any of them will quietly leak UI locale into a content-property slot, with no diagnostic trail. The fix is structural (split the field) or at minimum the fallback must die.

### Bug B — `language` field dropped at the candidate-conversion layer (contributing defect)

This is more nuanced than initially characterized. Production language **is** read from the TVDB extended record and **is** populated on the upstream enrichment object — it is then dropped one layer up, at the conversion from `TvMetadataEnrichment` (or `TvdbSeriesExtendedRecord`) into a `MetadataCandidate`. The same dropping conversion handles the Kitsu path, so a single missing line in one helper function causes the gap for **two** providers simultaneously.

**Where the field is correctly populated upstream:**

- TVDB: `core/tvdb/TvdbMetadataService.kt:518` — `language = originalLanguage.trimmed()` (sourced from the TVDB extended record).
- Kitsu: `data/integration/metadata/KitsuMetadataProviderAdapter.kt:105` — hardcoded `language = "ja"` for the `ANIME_CORE` shape (anime is universally Japanese-original; reasonable inference for that media type).
- TMDB-as-TV-fallback: `core/tvdb/TvMetadataRouter.kt:516` — passes through the language from `TmdbEnrichment` when TMDB fills in for TVDB.

**Where it is dropped:**

`data/integration/metadata/MetadataAdapterCandidates.kt:43-61`, the `TvMetadataEnrichment.toMetadataCandidate(provider)` helper. It builds the candidate's `fields` map by emitting put-calls for `CANONICAL_ID`, `TITLE`, `OVERVIEW`, `POSTER`, `BACKDROP`, `LOGO`, `RATING`, `RUNTIME`, `REMOTE_IDS` — and **omits `LANGUAGE`** (and `COUNTRIES`, and `ORIGINAL_COUNTRY`, etc.). The same omission is repeated in `TvdbSeriesExtendedRecord.toMetadataCandidate` at `:63-79`, which builds straight from the raw record without consulting `originalLanguage` or `originalCountry` either.

So `ResolvedMetadataDocument.language = null` for every TVDB-routed series and every Kitsu-routed anime, even though the right value already exists three layers upstream. `MetadataDisplayRepository.kt:267` faithfully copies the null into `DetailAdvancedMetadata.language`, and Bug A's silent fallback substitutes the user's UI locale.

This is a plumbing gap, fixable in isolation by adding one or two `put(...)` lines to the two converters. **But fixing it alone is not enough**: with Bug A still in place, any future content path that produces a null `language` — addon-only series, Kitsu drama (no hardcoded `"ko"`/`"zh"` analogue exists), `ProviderLocalizedMetadataResolver` canonical-route emissions which never set `language` at all — falls into the same UI-locale substitution. The fallback is the primary defect; this plumbing gap is what makes it fire on every TVDB and Kitsu playback today.

### Why the dossier discusses both

The recommended fix sequence below addresses these in priority order:

1. **Bug A** must be addressed because it is independent of any data source. The fallback's silent substitution turns "we don't know" into "the wrong answer" with no diagnostic.
2. **Bug B** should be addressed because TVDB has the correct data and it is currently being parsed and discarded. Routing it through fills the canonical slot for every TVDB-sourced show.

Treat them as separate review items. Either one can land first; both should land.

---

## Root cause (precise)

> Two distinct concepts share one field name. nexio's metadata router never produces a value for one of them (Bug B), so a defensive fallback silently substitutes the other (Bug A) — a value that is **guaranteed to be wrong** whenever the user's UI language differs from the show's production language.

**Concept A — production language (a property of the content):**
- In TVDB: `SeriesExtendedRecord.originalLanguage`.
- In TMDB: `original_language` on `MovieDetails` / `TvDetails`.
- Stable for the lifetime of the title. Same for every user.

**Concept B — localization request (a property of the user):**
- Tracked as `ResolvedDetailDisplayDocument.localization.selectedLanguage`.
- Equals the user's app/system locale (with fallback) and changes with their settings.

`MetaDetailsViewModel.kt:1550` collapses A and B into a single `Meta.language` field. The collapse is harmless for UI badges (showing "NLD" next to age rating in `HeroSection.kt:678`) but corrupts every downstream consumer that genuinely needs A — currently the audio-track picker, and presumably anything else that asks "what language is this content in?".

**Why the fallback exists at all:** my read is that this is defensive code from before TVDB's `originalLanguage` was being routed (or for cases where the metadata source is an addon that doesn't expose it). The fallback was intended to keep `Meta.language` non-null for the "language" badge in the detail-screen hero. Once it existed, the consumer at `originalLanguage = item.language` (added later) inherited a guaranteed-wrong value with no warning.

---

## Per-provider audit

Researched against the API blueprints (`tvdb.yml`, `apiblueprints/tmdb.json`, `apiblueprints/kitsu.apib`) and the integration code paths in `app/src/main/java/com/nexio/tv/{data/integration,core/tvdb,core/tmdb,core/metadata}/`.

### TMDB (movies — `MOVIE_CORE` API shape)

- **API exposes the field:** Yes. `tmdb.json` shows `original_language` on every movie response shape (search, details, recommendations, similar, etc.).
- **DTO parses it:** Yes. `TmdbApi.kt:367, 386, 434, 582` all carry `@Json(name = "original_language") val originalLanguage`.
- **Integration provider extracts it:** Yes. `TmdbIntegrationProvider.kt:901` — `val language = details.originalLanguage?.takeIf { it.isNotBlank() }` and writes it into `TmdbEnrichment.language` (`TmdbMetadataService.kt:882`).
- **Metadata router routes it:** Yes. `TmdbMetadataProviderAdapter.kt:82` calls `buildTmdbLocalizedCandidate`, and `MetadataAdapterCandidates.kt:291` emits `put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY))` from `source.language`.
- **Discovery rails populate `MetaPreview.language`:** Yes. `TmdbDiscoveryService.kt:251` — `language = result.originalLanguage?.trim()?.takeIf { it.isNotBlank() }`. Home/discovery items carry the right value end-to-end.
- **Verdict:** ✅ Correctly wired. Movies routed through TMDB `MOVIE_CORE` are not affected by Bug B at all. Bug A's fallback never fires for them in the canonical case (because `advanced.language` is non-null). However, Bug A still applies if some edge-case path produces a null `advanced.language` for a TMDB movie — the fallback would then leak UI locale exactly as it does for series.

### TMDB (TV — `TV_CORE` API shape)

- **API exposes the field:** Yes — same `original_language` attribute on TV detail responses.
- **DTO + integration extraction:** Same code path as movies. TV details are fetched by `TmdbIntegrationProvider.fetchTvCore(...)`; the shared extraction at line 901 reads `details.originalLanguage`.
- **Metadata router routes it:** Yes. `TmdbMetadataProviderAdapter.kt:90` (the `TV_CORE` branch) calls the same `buildTmdbLocalizedCandidate` that emits `ResolvedField.LANGUAGE`.
- **But — TV shows are not usually routed through TMDB.** The router's `metadata.route_decision` log on this device shows series go to TVDB (`provider=TVDB mediaKind=SERIES reason=ITEM_TYPE_SERIES`). TMDB is only consulted as a fallback when TVDB has no match. So in practice, the correctly-wired TMDB-TV path almost never runs for ordinary playback.
- **Verdict:** ✅ Correct in isolation, but mostly bypassed by the `mediaKind=SERIES → TVDB primary` routing rule. Useful as a fallback floor, but not the codepath that caused the user-visible bug.

### TVDB (series — primary route for all SERIES content)

- **API exposes the field:** Yes. `tvdb.yml:3948-3951` defines `SeriesExtendedRecord.originalLanguage: string` and `originalCountry: string`. Live verification (per the validation table earlier in this dossier): every English series returns `originalLanguage='eng'`; non-English series return their actual production-language code (`zho`, etc.).
- **DTO parses it:** Yes. `TvdbApi.kt:241-242`.
- **Integration provider extracts it:** Yes. `TvdbMetadataService.kt:518` — `language = originalLanguage.trimmed()` on the constructed `TvMetadataEnrichment`.
- **Metadata router routes it:** **No.** The conversion at `MetadataAdapterCandidates.kt:43-61` (`TvMetadataEnrichment.toMetadataCandidate`) does not emit a `ResolvedField.LANGUAGE` entry. The field is dropped at this single layer.
- **Discovery rails populate `MetaPreview.language`:** Mostly no for TVDB-only paths. The home pipeline uses TMDB for discovery (`BUILT_IN_TMDB` rail id observed in the device log), so MetaPreview language for those rails comes from TMDB discovery (correct). Anything routed exclusively through TVDB doesn't have an analogous MetaPreview-level discovery population.
- **Verdict:** ❌ Bug B applies. The fix is one line in `TvMetadataEnrichment.toMetadataCandidate` to forward `language` (and ideally `originalCountry`) into the candidate's `fields` map, with `FieldOwner.PRIMARY`. Same fix simultaneously closes the gap for Kitsu (next entry).

### Kitsu (anime / drama / manga)

- **API exposes the field:** **No, not directly.** `apiblueprints/kitsu.apib` defines `animeAttributes`, `dramaAttributes`, `mangaAttributes` with `titles` (a multi-locale map: `en`, `en_jp`, `ja_jp`) but no explicit `originalLanguage` or `original_language` field. Kitsu's API surfaces production language only implicitly through the `titles` map structure and the resource type (`anime` ⇒ Japanese, `drama` ⇒ varies, `manga` ⇒ varies).
- **Integration provider populates it via inference:** Partially. `KitsuMetadataProviderAdapter.kt:105` hardcodes `language = "ja"` for the `ANIME_CORE` shape. There is **no analogous hardcoding for drama** (which is typically Korean/Japanese/Chinese) — `KitsuDiscoveryIntegrationProvider.kt` has no `language=` references at all.
- **Metadata router routes it:** **No.** Kitsu uses the same `TvMetadataEnrichment.toMetadataCandidate` converter as TVDB. The `"ja"` set at line 105 is dropped at `MetadataAdapterCandidates.kt:43-61` for the same reason.
- **Discovery rails populate `MetaPreview.language`:** No. `KitsuDiscoveryIntegrationProvider` does not set a language on home-rail previews. So MetaPreview-level language for Kitsu items defaults to null — when they navigate to the player from a home rail, the player gets `originalLanguage=null` and falls into the naming-convention path (`findOriginalTrackFallbackIndex`), which often does the right thing for Japanese audio. But navigating from the **detail screen** runs the canonical metadata router, which drops the `"ja"` and falls back to UI locale.
- **Verdict:** ❌ Same plumbing gap as TVDB. Same one-line fix (forward `language` in `TvMetadataEnrichment.toMetadataCandidate`) closes it for Kitsu. **Additional gap specific to Kitsu:** drama and manga have no hardcoded inference at all. A drama playback would currently:
  - From home rail → null `originalLanguage` → naming-convention fallback in player → unpredictable.
  - From detail screen → null `advanced.language` → Bug A fallback to UI locale.
  Neither is correct. The structural fix (Option 3) is the only one that addresses this comprehensively; the narrow plumbing fix (Option 2) covers anime but leaves drama / manga in the original broken state.

### `ProviderLocalizedMetadataResolver` canonical-route emissions

- `ProviderLocalizedMetadataResolver.kt:39-48` constructs a `TvMetadataEnrichment` for the canonical-route case (when the canonical document already carries the routing's chosen value). It populates `localizedTitle`, `description`, `poster`, `backdrop`, `logo`, `rating`, `runtimeMinutes` — but **does not set `language`** at all.
- This means even when the upstream document had a correct `language`, this short-circuit path strips it out before it reaches the `toMetadataCandidate` converter.
- **Verdict:** ❌ Independent contribution to the same Bug B family. This is the path that fires in cache-hit and short-circuit scenarios (where the router decides it doesn't need to re-fetch the provider). Same one-line fix per provider — populate `language` on the constructed enrichment. Or, after Option 3 (split fields), this layer becomes obsolete because the canonical document carries `originalLanguage` as its own first-class field.

### Summary table

| Provider / shape | API has it? | DTO parses? | Integration extracts? | Routed to `ResolvedField.LANGUAGE`? | Discovery sets `MetaPreview.language`? | Net result |
|---|---|---|---|---|---|---|
| TMDB MOVIE_CORE | ✅ `original_language` | ✅ | ✅ `TmdbEnrichment.language` | ✅ via `buildTmdbLocalizedCandidate:291` | ✅ `TmdbDiscoveryService:251` | ✅ Correct end-to-end |
| TMDB TV_CORE | ✅ `original_language` | ✅ | ✅ same path as movies | ✅ same path as movies | ✅ same path as movies | ✅ Correct, but rarely the active route (TVDB wins for series) |
| TVDB SERIES_EXTENDED | ✅ `originalLanguage` | ✅ `TvdbApi:242` | ✅ `TvdbMetadataService:518` | ❌ dropped at `MetadataAdapterCandidates:43-61` | n/a (rails come from TMDB) | ❌ Bug B fires every TVDB-routed playback |
| Kitsu ANIME_CORE | ❌ (inferred from type) | n/a | ✅ hardcoded `"ja"` at `KitsuMetadataProviderAdapter:105` | ❌ dropped at `MetadataAdapterCandidates:43-61` | ❌ not set on previews | ❌ Bug B fires every Kitsu anime playback |
| Kitsu DRAMA / MANGA | ❌ | n/a | ❌ no hardcoding | ❌ | ❌ | ❌ Compounds Bug A even after Bug B fix |
| `ProviderLocalizedMetadataResolver` canonical-route | n/a (in-memory) | n/a | ❌ doesn't carry `language` to constructed `TvMetadataEnrichment` | ❌ | n/a | ❌ Strips correctly-fetched value on the short-circuit path |

---

## The stream resolver — same bug propagates beyond audio

The user's question explicitly raised: "is `originalLanguage` not passed to the stream resolver?" Yes it is, and that path inherits the same corruption.

`StreamScreenViewModel.kt:130` reads `originalLanguage` from the navigation `SavedStateHandle`:

```kotlin
private val originalLanguage: String? = savedStateHandle.getOptionalString("originalLanguage")
```

The nav-arg ingress is `NexioNavHost.kt:172` (`originalLanguage = item.language`), which suffers the full Bug A leakage chain. So when the user starts a playback session via "Play" from the detail screen or home rail, the stream resolver gets the same wrong `originalLanguage` value the player does.

It is then used in two stream-filtering paths in `StreamScreenViewModel`:

### 1. `applyDeterministicOriginalLanguageGuard` (`:2054-2065`)

```kotlin
internal fun applyDeterministicOriginalLanguageGuard(
    originalLanguage: String?,
    streams: List<StreamCardModel>
): List<StreamCardModel> {
    if (originalLanguage.isNullOrBlank()) return streams
    return streams.filterNot { stream ->
        shouldRejectDeterministicAutoplayForOriginalLanguage(
            originalLanguage = originalLanguage,
            parsedLanguages = stream.parsed.languages
        )
    }
}
```

In deterministic-autoplay mode, this **rejects every stream whose parsed language tags don't match the (wrong) `originalLanguage`**. With `originalLanguage="nld"` and a release set tagged `[en]` / `[pl]` / `[multi-en]`, all retail English releases get filtered out as "not matching the original language". The user either gets no autoplay candidate, or autoplay falls through to a stream the parser failed to language-tag.

The shape of this failure is observable but subtle: deterministic autoplay starts feeling unreliable for users whose UI language differs from the show's production language. Before this dossier nobody would have correlated "I changed my UI to Dutch" with "deterministic autoplay stopped working on English shows" — but they share a single root cause.

### 2. `shouldRejectDeterministicAutoplayForOriginalLanguage` + `buildOriginalLanguageMatchTokens` (`:2067-2096`)

Same analysis. The function name `buildOriginalLanguageMatchTokens` documents that the input is supposed to be the production language; the actual input is the user's UI locale.

### 3. Other stream-resolver consumers

Five sites in `StreamScreenViewModel` pass `originalLanguage` downstream as a context field on data classes (`StreamPlaybackInfo` at `:2122` is one such struct). Anywhere that struct is later consumed for "what language is this content in?" will inherit the wrong value. Recommend an audit during Option 3 of every reader of `StreamPlaybackInfo.originalLanguage` to ensure the consumer's intent matches.

### Stream-resolver impact summary

The stream resolver consumes the same poisoned `originalLanguage` nav arg the player does. The fix is identical: fix Bug A and Bug B upstream and the stream resolver becomes correct without any local change. Conversely, if Bug A and Bug B were not fixed and the stream resolver were patched in isolation (e.g., re-fetching the production language locally), it would only correct one of N consumers — the player would still pick wrong audio. The right place to fix this is at the source.

---

## Why the existing safeguards don't save this

The codebase already has several defenses for "unknown original language" cases. They all fail to fire here because the value isn't unknown — it's confidently *wrong*.

| Safeguard | Where | Why it doesn't fire |
|---|---|---|
| `findOriginalTrackFallbackIndex` (naming-convention based; picks `ORIGINAL`/`main`/`default` tracks) | `PlayerRuntimeControllerTracks.kt:557` | Gated on `preferredAudioLanguages.isEmpty()`. List is `["nl"]` — non-empty. |
| `audioTrackMatchesLanguage` "undetermined original" branch (matches `und`/blank tracks if they look like the original) | `PlayerStartupSelectionPolicy.kt:188-191` | Both available tracks have explicit non-`und` language tags (`pl`, `en`), so `nameInferredCodes` is non-empty and the function returns false at line 184-186 before reaching the original-language heuristic. |
| `targetingOriginalLanguage` boost in `startupAudioTrackScore` | `PlayerStartupSelectionPolicy.kt:109-110` | Compares the *target* (`nl`) against `originalLanguage` (`nl`). They match — but no track has `nl`, so this scorer never gets called. |

Architecturally significant: these safeguards all assume `originalLanguage` is either correct or absent. None of them are designed to detect or recover from a *confidently wrong* value. That's worth bearing in mind when designing the fix — defense-in-depth here means treating the input as untrusted, not just nullable.

---

## Fix candidates and architectural recommendations

Listed in order from "narrow patch" to "structural fix". The narrow patches mask the conceptual conflation; the structural fix removes it.

### Option 1 — minimal patch: drop the silent fallback

`MetaDetailsViewModel.kt:1550`, `:1617`, `:3398`:

```kotlin
language = document.advanced.language ?: updated.language    // remove `?: localization.selectedLanguage`
```

**Pros**
- One-line change at three sites.
- A null `language` is strictly better than a wrong one — `resolvePreferredAudioLanguages` would fall to an empty list, which triggers the `findOriginalTrackFallbackIndex` naming-convention path (the "original / main / default" classifier) — exactly what your spec ("if unknown, use English") is approximating.

**Cons**
- Doesn't deliver the *correct* original language, only avoids the wrong one.
- Detail-screen language badge (`HeroSection.kt:678`) will go blank when TVDB's `originalLanguage` isn't routed — currently it shows "NLD" (wrong) for everyone in NL locale; after the patch it shows nothing. Marginally better, but cosmetically a regression for users who happened to be watching Dutch content.
- Doesn't fix the next consumer of `Meta.language` that gets added by someone who assumes "this is the show's language" and finds it null.

**Verdict:** good as an emergency stop-gap. Not a fix.

### Option 2 — narrow correctness: forward `language` through the candidate converters

The TMDB `MOVIE_CORE` / `TV_CORE` paths already emit a `ResolvedField.LANGUAGE` candidate via `buildTmdbLocalizedCandidate:291`. The remaining gap is on the shared TVDB+Kitsu converter (`TvMetadataEnrichment.toMetadataCandidate` at `MetadataAdapterCandidates.kt:43-61`). Add one line to forward `language` (and ideally `originalCountry`) into the candidate's `fields` map:

```kotlin
internal fun TvMetadataEnrichment?.toMetadataCandidate(provider: MetadataPrimaryProvider): MetadataCandidate =
    MetadataCandidate(
        provider = provider,
        fields = buildMap {
            this@toMetadataCandidate ?: return@buildMap
            // ...existing put(...) calls...
            language?.takeIf { it.isNotBlank() }
                ?.let { put(ResolvedField.LANGUAGE, FieldValue(it, FieldOwner.PRIMARY)) }
            originalCountry?.takeIf { it.isNotBlank() }
                ?.let { put(ResolvedField.ORIGINAL_COUNTRY, FieldValue(it, FieldOwner.PRIMARY)) }
        }
    )
```

Apply the same pattern to:

- `TvdbSeriesExtendedRecord.toMetadataCandidate` at `MetadataAdapterCandidates.kt:63-79` — feed `originalLanguage` and `originalCountry` directly from the raw record.
- `ProviderLocalizedMetadataResolver.kt:39-48` — populate `language` on the `TvMetadataEnrichment` constructed for the canonical short-circuit path (otherwise the route bypasses the converter and starves `language`).
- `KitsuMetadataProviderAdapter` — extend the hardcoded inference beyond `ANIME_CORE`. At minimum add:
  - `DRAMA_CORE` → no single right answer; consider leaving null and letting the player's naming-convention fallback handle it, rather than guessing a wrong language. **Do not** hardcode Korean — Kitsu drama covers J-drama and C-drama too.
  - Any other Kitsu shapes that carry production language metadata implicitly via resource type.

**Pros**

- The fallback at `MetaDetailsViewModel.kt:1550` becomes correct in the canonical case for TVDB and Kitsu anime.
- Existing display-language badge starts being correct for non-Dutch content too.
- No architectural changes — fills two small plumbing gaps using the existing field-routing pattern.

**Cons**

- The conceptual conflation persists. `Meta.language` still means two things. Bug A's fallback is still loaded.
- Kitsu drama / manga remain unfixed unless a sound inference is added (and "sound" is doubtful — Korean / Japanese / Chinese drama all share the `DRAMA_CORE` shape).
- A future addon-only path that doesn't carry production language will silently revert to the wrong-fallback behavior.

**Verdict:** correct *enough* for the user-visible bug today. Pair with Option 1's removal of the `selectedLanguage` fallback so the failure mode of "no production language available" is null/empty (which the player already handles via naming-convention fallback) instead of "user's UI locale" (which masks the failure).

**Pros**
- The fallback at `:1550` becomes correct: `advanced.language` is now populated, so the `?:` chain never reaches `selectedLanguage` for the common case.
- Existing display-language badge keeps working and starts being correct for non-Dutch content too.
- No architectural changes — purely fills a gap in the existing field-routing pattern.

**Cons**
- The conceptual conflation persists. `Meta.language` still means two things: "the show's production language" *and* "the language the metadata strings are in". They happen to be equal in the common-case path because TVDB's `originalLanguage` and the user's UI locale will *usually* differ — but the fallback to `selectedLanguage` remains a footgun for the edge cases (TVDB-untagged anime, addon-only sources, etc.).
- A future addon-only path that doesn't carry production language will silently revert to the wrong-fallback behavior.

**Verdict:** correct *enough* and probably the pragmatic landing point. Pair with Option 1's removal of the `selectedLanguage` fallback so the failure mode of "no production language available" is null/empty (which the player already handles via naming-convention fallback) instead of "user's UI locale" (which masks the failure).

### Option 3 — structural: split the two fields end-to-end

Stop overloading `Meta.language`. Introduce two distinct fields and migrate consumers:

| Field | Means | Source |
|---|---|---|
| `Meta.originalLanguage: String?` | Production language of the title | TVDB `originalLanguage` / TMDB `original_language`; `null` when unknown |
| `Meta.metadataLanguage: String?` | Language that title/overview/etc. were fetched in | `localization.selectedLanguage` |

Concretely:

1. Add `originalLanguage`/`originalCountry` to `ResolvedMetadataDocument` and `DetailAdvancedMetadata` as first-class fields (parallel to the existing `originalCountry` slot which is already plumbed but never fed).
2. Add `originalLanguage` to `Meta` and `MetaPreview` as a real field (today they only have `language`, which is being abused).
3. Plumb TVDB's `extended.originalLanguage` and TMDB's `original_language` into those slots via the field router, owned by `PRIMARY`.
4. Change every consumer that currently reads `meta.language` to declare its intent:
   - **Player audio targeting** (`NexioNavHost.kt:172`, `Stream` route, `Player` route, `PlayerRuntimeController.originalLanguage`) → reads `meta.originalLanguage`.
   - **Detail screen language badge** (`HeroSection.kt:678`) → reads `meta.originalLanguage` if set, otherwise falls back to `metadataLanguage` for the cosmetic "show something" case (this is the only place where the fallback is conceptually OK, because it's UI sugar, not an audio-track decision).
   - **Stream filter** (`StreamScreenViewModel.kt:2055-2086 buildOriginalLanguageMatchTokens`) → reads `meta.originalLanguage`. The function is *named* `buildOriginalLanguageMatchTokens` — that's a strong signal it always wanted the production language and was getting fed UI locale by accident.
5. Delete the `?: localization.selectedLanguage` fallback at `MetaDetailsViewModel.kt:1550/1617/3398`. It has no legitimate consumer once the split lands.

**Pros**
- Removes the conceptual collision permanently. Future code that wants "the show's language" and writes `meta.originalLanguage` cannot accidentally get the user's locale.
- Makes the spec for "Audio language = Original" a precise type-check: `audioTargetForOriginalPreference(meta.originalLanguage ?: ENGLISH_FALLBACK)`. The "if unknown, use English" rule lives in one place.
- `StreamScreenViewModel.buildOriginalLanguageMatchTokens` likely has the same latent bug and gets fixed for free — worth a follow-up audit.
- Aligns with the project's "Display authority" rule (`docs/superpowers/notes/2026-05-09-modern-home-leak-root-cause.md`): one canonical source per concept, no silent downgrades.

**Cons**
- Larger change surface — touches domain models (`Meta`, `MetaPreview`, `DetailAdvancedMetadata`, `ResolvedMetadataDocument`), the metadata router, two adapters, three navigation routes (Stream + Player nav args), and a handful of UI consumers.
- Requires a migration plan for any persisted forms of `Meta` that include `language` (continue-watching cache, hydrated overlay store).
- Not all pre-existing consumers may be obvious — needs a thorough grep before landing.

**Verdict:** the right long-term fix. Worth doing because:
- It surfaces the real shape of the data (two concepts, two fields).
- It makes the player's "Original" preference behavior provable by inspection rather than by tracing six layers of plumbing.
- It defuses the next time someone adds a feature ("subtitles in the show's original language", "filter rail by language", "tts default voice") and reaches for `meta.language` — they'll instead pick the field whose name matches the concept.

### Option 4 — defensive layer in the player (not recommended on its own)

Inside `PlayerRuntimeController`, treat `originalLanguage` matching the user's resolved UI locale with no available track as suspect, and fall through to the naming-convention picker.

**Pros**
- Localized fix; doesn't touch the metadata layer.

**Cons**
- Heuristic, not principled. Breaks for users where UI locale legitimately equals production language (a Dutch user watching a Dutch show — currently rare in your data but real).
- Pushes the correctness burden into the worst place (the playback hot path), where regressions are hardest to debug. The player is currently a clean function of its inputs; this would make it dependent on user context.
- Hides the upstream bug from observability: `AUDIO_STARTUP_EVAL` would still log `origLang=nld` and a heuristic recovery, instead of a single-source-of-truth `origLang=eng`.

**Verdict:** only as a belt-and-braces complement to Option 3, not as a primary fix.

---

## Recommended landing sequence

Note that Step 1 fixes **Bug A** (the UI-locale leakage, primary defect). Step 2 fixes **Bug B** (the TVDB routing gap). They are independent and could land in either order, but Step 1 is both smaller and addresses the more dangerous of the two — a silent type-collision that affects all metadata sources, not just TVDB.

1. **Today — fix Bug A (unblocks users immediately):** Option 1 — drop the `?: localization.selectedLanguage` fallback at `MetaDetailsViewModel.kt:1550/1617/3398`. This converts the bug from "wrong audio every time" to "naming-convention fallback every time", which matches the user's documented expectation ("if unknown, use English"). One-line diff × 3, low blast radius. The detail-screen language badge will show blank for shows whose production language isn't routed yet (until Step 2 lands). Add a one-line `Log.w` at the call site for "production language unavailable" so the gap is observable instead of silent.
2. **This week — fix Bug B:** Option 2 — wire TVDB `originalLanguage` and TMDB `original_language` through the metadata router as `ResolvedField.LANGUAGE` candidates owned by `PRIMARY`. The detail-screen badge becomes correct; the player picker gets correct input; the new `Log.w` from Step 1 stops firing for canonical sources.
3. **Next iteration — eliminate the collision permanently:** Option 3 — split `originalLanguage` from `metadataLanguage` end-to-end. Audit `StreamScreenViewModel.buildOriginalLanguageMatchTokens` while there (it almost certainly inherits the same bug — its name says it wants the production language, but it reads `Meta.language`). Add a unit test that asserts `Meta.originalLanguage` for a TVDB-sourced English show is `"eng"` regardless of the user's locale, so the regression cannot recur.

**Do not skip Step 1 in favor of Step 2.** Even with TVDB fully routed, an addon-only show, an unmapped legacy entry, a new metadata source, or a router cache miss can leave `advanced.language` null — and the silent fallback will leak UI locale into the content-property slot again. The fallback is the primary bug; Step 2 is "fill in the data so the fallback is rarely needed", not "make the fallback safe".

---

## Files referenced

### Player (audio-track picker — consumer of the wrong value)

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt` — picker
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerTracks.kt:471-586` — startup application + diagnostics + naming-convention fallback
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerInitialization.kt:1500-1547` — `resolvePreferredAudioLanguages` (the `ORIGINAL` branch)
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt:125` — `originalLanguage` ingress
- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerNavigationArgs.kt:18,74` — nav-arg parse

### Stream resolver (also consumer of the wrong value)

- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt:130` — nav-arg ingress
- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt:2054-2096` — `applyDeterministicOriginalLanguageGuard` + `shouldRejectDeterministicAutoplayForOriginalLanguage` + `buildOriginalLanguageMatchTokens`
- `app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt:2122` — `StreamPlaybackInfo.originalLanguage` (downstream propagation)

### Bug A — silent fallback (primary defect)

- `app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt:1550, 1617, 3398` — silent fallback to `selectedLanguage`
- `app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt:678` — sole legitimate consumer of "any language string for cosmetic purposes"
- `app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt:172` — site that maps `item.language` → nav arg `originalLanguage`

### Bug B — provider plumbing gaps

#### TMDB (correctly wired — reference for what the others should look like)

- `apiblueprints/tmdb.json` — schema (`original_language` on movie / TV detail responses)
- `app/src/main/java/com/nexio/tv/data/remote/api/TmdbApi.kt:367, 386, 434, 582` — DTOs parse `original_language`
- `app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt:901` — `language = details.originalLanguage`
- `app/src/main/java/com/nexio/tv/core/tmdb/TmdbMetadataService.kt:862-882` — `TmdbEnrichment.language`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt:65-100` — calls `buildTmdbLocalizedCandidate`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:225-322` — `buildTmdbLocalizedCandidate`; line 291 emits `ResolvedField.LANGUAGE`
- `app/src/main/java/com/nexio/tv/data/repository/TmdbDiscoveryService.kt:251` — discovery rails populate `MetaPreview.language` from `originalLanguage`

#### TVDB (broken — language read but dropped at converter)

- `tvdb.yml:3948-3951` — `SeriesExtendedRecord.originalLanguage`/`originalCountry`
- `app/src/main/java/com/nexio/tv/data/remote/api/TvdbApi.kt:241-242` — DTO parses `originalLanguage` and `originalCountry`
- `app/src/main/java/com/nexio/tv/core/tvdb/TvdbMetadataService.kt:518` — sets `TvMetadataEnrichment.language = originalLanguage.trimmed()`
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:43-61` — **the gap.** `TvMetadataEnrichment.toMetadataCandidate` does not put `LANGUAGE` into the field map.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:63-79` — same gap on `TvdbSeriesExtendedRecord.toMetadataCandidate`.

#### Kitsu (broken — language inferred at the adapter but dropped at the same converter)

- `apiblueprints/kitsu.apib` — `animeAttributes`, `dramaAttributes`, `mangaAttributes`. No explicit `originalLanguage` field; production language must be inferred from resource type.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt:105` — hardcodes `language = "ja"` for the `ANIME_CORE` shape only. No analogue for drama or manga.
- `app/src/main/java/com/nexio/tv/data/integration/metadata/MetadataAdapterCandidates.kt:43-61` — same `TvMetadataEnrichment.toMetadataCandidate` gap drops the inferred `"ja"`.
- `app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuDiscoveryIntegrationProvider.kt` — discovery rails do not populate `MetaPreview.language`.

#### Canonical short-circuit path

- `app/src/main/java/com/nexio/tv/core/tvdb/ProviderLocalizedMetadataResolver.kt:39-48` — constructs a `TvMetadataEnrichment` for the canonical-route case without setting `language` at all.

### Field-resolution backbone

- `app/src/main/java/com/nexio/tv/data/repository/MetadataDisplayRepository.kt:264-275` — `DetailAdvancedMetadata` factory; copies `language` from `ResolvedMetadataDocument`.
- `app/src/main/java/com/nexio/tv/core/metadata/router/MetadataModels.kt:155-178` — `ResolvedMetadataDocument` shape.
- `app/src/main/java/com/nexio/tv/core/metadata/router/FieldResolver.kt:284-300` — builds `ResolvedMetadataDocument.language` from `fields[ResolvedField.LANGUAGE]`.

### Domain models touched by Option 3 (split fields)

- `app/src/main/java/com/nexio/tv/domain/model/Meta.kt:34` — `language: String?` (overloaded)
- `app/src/main/java/com/nexio/tv/domain/model/MetaPreview.kt:30` — `language: String?` (overloaded)
- `app/src/main/java/com/nexio/tv/domain/model/ResolvedDetailDisplayDocument.kt:56-67` — `DetailAdvancedMetadata` (has `originalCountry`; should add `originalLanguage`).

## Diagnostic logs

- `Nexio.MetaRoute` (logcat tag) — `metadata.localization_plan` events show `requestedLanguage=nld fallbackLanguage=eng`.
- `PlayerViewModel` (logcat tag) — `AUDIO_STARTUP_EVAL` events. Pattern `origLang=<UI locale> targets=[<UI locale>] wouldPick=-1` is the bug fingerprint; this log already exists and is exactly the right signal.
