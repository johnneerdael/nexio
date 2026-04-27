# Runtime & Metadata Trace — Manual QA Playbook

This playbook validates on-device that the runtime/metadata trace harness captures the same events our unit audit proves in CI. Run these flows on real Android TV / Fire TV hardware with the trace toggle enabled.

## Setup

1. Open **Settings → Playback → Troubleshooting → Runtime & Metadata Trace** (the row labelled *Open*).
2. On the detail screen, select mode `INCLUDE_HTTP_SUMMARY`.
3. Tap **Start**. Confirm the status row reads *Session active* and shows a session id.
4. After running the flow, tap **Stop**, then export the bundle (when the Export action is wired) — until then, pull the JSONL from `/data/data/com.nexio.tv/files/traces/{sessionId}/trace-events.jsonl` via `adb pull`.

For each flow below, run the trace validator on the captured JSONL and confirm the expected verdict.

---

## Flow A — Preview row, no item focus

**Steps:**
1. Open Home → Discover row.
2. Do **not** focus or open any item.
3. Stop the trace after 5 seconds idle.

**Expected events:**
- `metadata.first_paint` with `source = "ADDON_META_PREVIEW"`, `routerExecuted = false`, `networkExecuted = false`.

**Expected validator verdict:** `PASS`. The rule `PreviewMustNotRouteOrNetwork` would fire if the preview path invoked the router.

> **Status:** Currently the addon-preview render path does not emit `metadata.first_paint` (no production code path bypasses the router yet). The `emitFirstPaint` helper exists for future wiring. Skip this flow until the addon-preview path is built; the validator rule is in place.

---

## Flow B — Crunchyroll anime routing

**Steps:**
1. Open the Crunchyroll catalog row.
2. Focus item with id `tt12343534` (or any IMDB id mapped to Kitsu).
3. Open the detail screen.

**Expected events:**
- `metadata.route_decision` with `provider = "KITSU"`, `mediaKind = "ANIME"`.
- `usedInputs` contains `"item.id"`, `"item.type"`, `"AnimeIdentityIndex"`, `"IdMappingStore"`.
- `ignoredInputs` contains `"catalog.type"`, `"addon.name"`, `"genre"`, `"animeType"`, `"links"`, `"trend"`.

**Expected validator verdict:** `PASS`. The rule `RouteDecisionUsedInputs` would fire if `usedInputs` referenced any catalog/genre/animeType/link/trend token.

---

## Flow C — Cache hit suppression

**Steps:**
1. Open any movie's detail screen (e.g. `tt0111161`). Wait for full hydration.
2. Back out, then re-open the same movie detail screen.
3. Stop the trace.

**Expected events on the second open:**
- `runtime.cache_decision` with `decision = "HIT"` and `runtimeOperationId = "op_<n>"`.
- **No** `http.request` for that same `runtimeOperationId`.

**Expected validator verdict:** `PASS`. The rule `FreshCacheHitSuppressesNetwork` would fire if a network request was issued for the same op after a fresh HIT.

---

## Flow D — Premium poster source

**Steps:**
1. Ensure TMDB metadata for a movie is cached (open it once, exit).
2. Enable Top-Posters or RPDB in Settings.
3. Re-open the movie's detail screen.

**Expected events:**
- `runtime.cache_decision` HIT for `tmdb.movie.core` (TMDB metadata).
- `metadata.field_selected` with `field = "POSTER"`, `selectedProvider = "TOP_POSTERS"` (or `"RPDB"`), and the TMDB poster appearing in `rejectedCandidates`.
- `metadata.field_selected` with `field = "TITLE"`, `selectedProvider = "TMDB"` — **title is NOT overwritten** by the artwork provider.

**Expected validator verdict:** `PASS`. `SecondaryDoesNotOverwritePrimary` would fire if the artwork provider overwrote the title.

---

## Flow E — Profile boundary (multi-profile)

**Steps:**
1. As Profile 1, open and play a movie for ~30 seconds. Stop. The Continue Watching tile appears.
2. Switch to Profile 2 (use the Profile selector).
3. Open the Continue Watching row on Profile 2.
4. Stop the trace.

**Expected events:**
- `continue_watching.snapshot_write` with `profileHash` matching Profile 1.
- After profile switch: `continue_watching.snapshot_read` with `profileHash` matching Profile 2 — `recordCount = 0` if Profile 2 has no CW.
- **No** `profile.boundary_check` with `verdict = "FAIL"`.
- **No** `runtime.operation_start` for Trakt/Simkl with the wrong profile's `credentialTraceHash`.

**Expected validator verdict:** `PASS`. The rules `TraktSimklUsesCorrectProfile` and `NoStaleProfileWritesAfterSwitch` would fire on cross-profile leakage.

---

## Flow F — Localized TVDB with English fallback

**Steps:**
1. Set the active profile's language to **Dutch (nl)**.
2. Open a TV series detail screen (preferably one with partial Dutch translations on TVDB, e.g. a recent show).
3. Open the season list.

**Expected events:**
- `metadata.localization_plan` with `provider = "TVDB"`, `requestedLanguage = "nl"`, `fallbackLanguage = "en"`.
- `payloads[]` shows both `lang:nl` and `lang:en` cache decisions.
- Episode titles missing Dutch translation are filled from the English payload — **no TMDB fallback** for missing localized TVDB fields.

**Expected validator verdict:** `PASS`.

> **Status:** Currently the localization plan emission is unwired pending TVDB/Kitsu provider-adapter orchestration site identification. The `emitLocalizationPlan` helper exists. Skip this flow until the orchestration site is wired.

---

## Validator command

Once the trace JSONL is captured, run:

```bash
./gradlew :app:generateTraceValidatorAudit --rerun-tasks
```

The validator currently runs the unit test fixtures. Once the export bundle is wired into the live UI (Task 35 has TODO for Export), it will validate the captured session directly.

For ad-hoc validation, the validator + bundle exporter classes are at:

- `com.nexio.tv.core.trace.RuntimeTraceValidator`
- `com.nexio.tv.core.trace.TraceBundleExporter`

---

## Known gaps (deferred to follow-on work)

| Gap | Location |
|---|---|
| `metadata.first_paint` emission point | No addon-preview UI path bypasses the router yet. |
| `metadata.localization_plan` emission point | TVDB/Kitsu provider adapters need orchestration-site wiring. |
| Clear / Export buttons in the detail screen | Marked `TODO` in `RuntimeTraceSettingsScreen.kt`. Manual `adb pull` works in the meantime. |
| Navigation route from Troubleshooting row to the detail screen | Marked `TODO` in `PlaybackSettingsScreen.kt`. The row is visible but tapping it is a no-op pending nav graph wiring. |
