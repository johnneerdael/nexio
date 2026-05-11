# Trailer AI Translation + Fullscreen UX — Design Spec

**Status:** Approved 2026-05-11.
**Builds on:** the trailer captions pipeline shipped in `e21ae10bd..46efccd05` (SRV3 fetch → typed parse → SRT serialize → file:// SubtitleConfiguration → DefaultDataSource scheme dispatch).

## Goal

Two related improvements to the trailer caption / playback subsystem:

1. **AI auto-translation.** When the user has subtitle translation configured (the same AI providers stream playback uses — Anthropic, Gemini, OpenAI, DashScope) and the trailer ships a caption track in a language other than the user's preferred subtitle language, translate the source SRT to the target language via a single provider call before handing the URI to ExoPlayer. Fall back to the source-language SRT when AI is unavailable or translation fails.
2. **Fullscreen UX cleanups.** Trailer playback currently renders captions over a black background (Media3 default), inconsistent with the stream player which uses transparent background + outline. And the modern-home hero gradient overlay (designed for the corner-window trailer view) persists when the trailer transitions to fullscreen, darkening corners and reducing subtitle readability.

The two changes share the trailer-captions subsystem and ship as one spec.

## Non-goals

- Translating audio (we only ever read source captions and translate cue text).
- Per-cue translation, retries, or live-reload of stale translations.
- AI translation cache shared with the stream subtitle translation cache. They share the `SubtitleTranslationService` API surface but their on-disk SRT caches are separate (trailer SRTs live under `cacheDir/trailer-subtitles/`; stream translations live wherever the stream translator persists).
- New subtitle styling options for trailers beyond what already exists in the stream player's `SubtitleStyle` settings.

---

## Architecture

### AI translation hook

The translation step lives inside `TrailerSubtitleCache.ensure(selected)`. The cache already serializes per call via a `Mutex`; we extend its body:

1. **Fetch source SRV3** (existing).
2. **Parse → write source SRT** to `cacheDir/trailer-subtitles/<sha1_16(baseUrl)>-<srcLang>.srt` (existing).
3. **New:** if `selected.translateTo` is non-null AND distinct from `selected.languageCode` AND `SubtitleTranslationSettings` indicates an active provider:
   1. Look up the translated-target file (`<sha1_16(baseUrl)>-<srcLang>-<tgtLang>.srt`). If present and non-empty, return its URI.
   2. Read the source SRT body, call `SubtitleTranslationService.translateSrtAtomically(srt, srcLang, tgtLang, settings)`.
   3. On non-null return that validates as well-formed SRT (cue count matches, every cue has a `-->` line), write the translated SRT to disk and return its URI.
   4. On null return or validation failure, log under `TrailerSubtitleCache` and fall through to step 4.
4. **Return source SRT URI** (existing behavior, now also reached as the AI-translation fallback path).

### Caption track selection

`TrailerSubtitlePicker.pickTrailerCaptionTrack` reverts the `46efccd05`-era "drop translation" change in spirit: when no native track matches the user's preferred language, it returns

```kotlin
SelectedTrailerCaptionTrack(
    baseUrl = sourceTrack.baseUrl,
    languageCode = sourceTrack.languageCode,  // unchanged from current fix
    translateTo = normalized                  // re-introduced
)
```

The `translateTo` field's contract changes: it signals **"if AI translation is configured, translate this from source to target"** rather than the original "append `&tlang=` to the YouTube URL." Downstream — `TrailerSubtitleCache` — interprets it. The picker stays agnostic of translation availability; that decision lives in the cache.

If the user has no AI translation configured (or provider is null), the cache treats `translateTo` as a no-op and returns the source-language SRT, matching today's behavior post-`46efccd05`.

### Subtitle styling on trailers

Today's two divergent setups:

- **Stream player** (`PlayerScreen.kt:155-180`): applies a custom `CaptionStyleCompat` with transparent background, outline edge, and user-configured foreground / typeface, plus burn-in-aware alpha and translation offsets on the `PlayerView.subtitleView`.
- **Trailer player** (`TrailerPlayer.kt:bindTrailerPlayerView`): applies no custom styling; Media3 defaults paint a black background behind cues.

We extract a shared helper in `ui/components/`:

```kotlin
internal fun applyTrailerCompatibleSubtitleStyle(
    subtitleView: SubtitleView,
    subtitleStyle: SubtitleStyle,
    burnInProtection: <existing burn-in protection type, with a disabled/default value>
)
```

It performs the same `setStyle / setApplyEmbeddedStyles / setFixedTextSize / setBottomPaddingFraction` calls the stream player does today. The stream's existing helper (whether named directly or inline in `PlayerScreen`) is refactored to call this helper; the trailer's `bindTrailerPlayerView` calls it with a disabled/default burn-in state. The exact type name comes from whatever `PlayerScreen` currently uses — implementation will adopt it verbatim rather than invent a new one.

Trade-off accepted: trailers ignore burn-in protection. Trailer playback windows are short (~30s–2min), burn-in is irrelevant.

### Gradient gating in fullscreen

`ModernHomeHero.ModernHeroGradientLayer` gains a `fullscreenTrailerActive: Boolean` parameter. When `true`, the composable returns an empty `Box` carrying only the `modifier` — no `drawWithCache`, no `onDrawBehind`. The three gradients (`horizontalGradient`, `radialGradient`, `verticalGradient`) are simply not painted.

The flag is propagated from `ModernHomeHero`'s caller. Today the hero composable already references `fullscreenTrailerActive = false` literal at the hint-padding callsite (line 166). The literal `false` is a latent bug — the hint padding rule depends on whether the trailer is fullscreen, so the call should receive the real state. We fix that callsite in the same change.

Source of truth for the flag: the screensaver/idle subsystem already tracks `inAppTrailerActive` (visible in logcat as `IdleScreensaverDebug: event=state_changed ... inAppTrailerActive=true`). Plumbing details (which composable layer reads it from which ViewModel) are an implementation concern; the spec requires that `ModernHeroGradientLayer` receives a `Boolean` that is `true` whenever the trailer is rendered fullscreen and `false` otherwise.

---

## Data flow

```
                 (CLIENTS extraction)
                          │
                          ▼
    YouTubeCaptionTrack [{en, ASR, baseUrl, isTranslatable}]
                          │
                          ▼
              pickTrailerCaptionTrack(preferredLanguage="nl")
                          │
                          ▼   SelectedTrailerCaptionTrack(
                              │   baseUrl = en-asr-url,
                              │   languageCode = "en",
                              │   translateTo = "nl"
                              │ )
                              ▼
                       TrailerSubtitleCache.ensure
                          │
              ┌───────────┼────────────────────┐
              ▼                                ▼
        cache hit on                  cache miss → fetch SRV3,
        <hash>-en-nl.srt              parse, write <hash>-en.srt
              │                                │
              │                                ▼
              │                  translateTo set + AI configured?
              │                                │
              │           ┌────────────────────┼───────────────┐
              │       no  │                yes │               │ provider
              │           ▼                    ▼               │  error
              │   return <hash>-en.srt    translateSrtAtomically
              │      file:// URI               │
              │                                ▼
              │                       validate cue count
              │                                │
              │                ┌───────────────┼─────────────┐
              │                ▼               ▼             ▼
              │             write          unparseable    null
              │           <hash>-en-nl.srt  output       return
              │                │               │             │
              ▼                ▼               ▼             ▼
       return file:// URI to translated  fall back to source SRT URI
                                        (cache the source, NOT the failure)
```

---

## Single-call translation contract

```kotlin
// in SubtitleTranslationService

suspend fun translateSrtAtomically(
    srt: String,
    sourceLanguageCode: String,
    targetLanguageCode: String,
    settings: SubtitleTranslationSettings
): String?
```

**Behavior:**

- Sends one provider request. No batching, no per-cue parallelism.
- System prompt instructs the model to preserve cue numbers and `HH:MM:SS,mmm --> HH:MM:SS,mmm` timestamp lines verbatim, translating only the text-content lines.
- Receives the model's response, strips conversational preamble (lines before the first `1\n`), trims trailing whitespace.
- Validates the result: must contain the same number of cues as the source (counted by detecting timestamp lines), every cue has a `-->` timestamp, and the file does not contain banned syntax artifacts like raw `--` arrows in translated text (we keep our existing `––>` workaround from the source serializer).
- Returns the validated SRT body on success, `null` on any failure (HTTP error, provider error, validation failure).

**Reuses existing infrastructure:**

- Per-provider request builders (`anthropicRequest`, `geminiRequest`, `openAiRequest`, `dashScopeRequest`).
- `SubtitleTranslationSettings` — provider, model, baseUrl, API key fields.
- Retry policy on transient HTTP errors (existing `MAX_TRANSLATION_PROVIDER_ATTEMPTS = 4`).

**Does NOT reuse:**

- The cue-level cache (`subtitleTranslationCueCacheKey`) — trailer translation is a single call, no per-cue caching.
- The file-level disk cache (`subtitleTranslationDiskCacheKey`) — trailer translations live in `cacheDir/trailer-subtitles/` keyed by baseUrl hash, not by source URL. Different caches by design (different lifecycle, different invalidation).
- The 6-parallel batched orchestrator — trailers use atomic call.

---

## Failure semantics table

| Trigger | Behavior |
|---|---|
| AI translation disabled in `SubtitleTranslationSettings` | Skip translation step; source SRT URI returned. |
| `selected.translateTo == selected.languageCode` (native match exists) | No translation needed; source SRT URI returned. |
| Provider configured but provider field is null or invalid | Treat as disabled; source SRT URI returned. |
| Provider call throws (network error, timeout, 5xx) | Log under `TrailerSubtitleCache`; return source SRT URI. |
| Provider returns malformed SRT (cue count mismatch, missing `-->` lines) | Log; return source SRT URI. Do NOT cache the corrupt result. |
| Provider returns empty response | Same as malformed. |
| User has no captions available for this trailer | `pickTrailerCaptionTrack` returns null; cache never called. |

---

## Cache file naming

| File | Path |
|---|---|
| Source SRT | `cacheDir/trailer-subtitles/<sha1_16(baseUrl)>-<srcLang>.srt` |
| Translated SRT | `cacheDir/trailer-subtitles/<sha1_16(baseUrl)>-<srcLang>-<tgtLang>.srt` |

`<sha1_16>` is the first 16 hex chars of SHA-1 over the caption baseUrl, matching the existing `cacheFileFor` implementation. The `cacheFileFor(selected: SelectedTrailerCaptionTrack)` helper already handles the tlang suffix when `selected.translateTo` is non-null — we reuse it unchanged.

**Volatile cache.** Files under `cacheDir` may be reclaimed by the OS without notice. That's acceptable: a missing translated file simply re-runs the translation on next play; a missing source file re-fetches SRV3 + parses.

**Provider switching.** The cache key does NOT include provider/model. If the user changes provider, existing translated SRTs remain on disk until age-out. Trade-off accepted: trailer SRTs are tiny (1–3 KB), and switching provider is rare; expiring on disk via cacheDir eviction is good enough.

---

## File structure

**New / modified files:**

| File | Change |
|---|---|
| `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt` | Add `translateSrtAtomically(...)` method. ~80 LoC including prompt construction, validation, and per-provider dispatch reuse. |
| `app/src/main/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCache.kt` | Inject `SubtitleTranslationService` + `SubtitleTranslationSettingsDataStore` (or equivalent). Extend `ensure()` with the post-parse translation branch. Reuse existing `cacheFileFor` for the translated path. ~50 LoC. |
| `app/src/main/java/com/nexio/tv/data/trailer/TrailerSubtitlePicker.kt` | Restore `translateTo = normalized` in the translation-fallback branch (revert the literal change in `6c73f702d`'s body, keep its English-source-preference rationale). ~5 LoC. |
| `app/src/main/java/com/nexio/tv/ui/components/SubtitleStylePainter.kt` (new) | Extract the `CaptionStyleCompat` configuration from `PlayerScreen` into a shared `applyTrailerCompatibleSubtitleStyle(subtitleView, subtitleStyle, burnInProtection)` helper. ~60 LoC. |
| `app/src/main/java/com/nexio/tv/ui/components/TrailerPlayer.kt` | Call the new helper from `bindTrailerPlayerView` with `BurnInProtectionState.Disabled`. ~10 LoC. |
| `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerScreen.kt` | Refactor the inline subtitle styling block to call the new helper. ~5 LoC delta (extraction, not duplication). |
| `app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeHero.kt` | Add `fullscreenTrailerActive: Boolean` parameter to `ModernHeroGradientLayer`. Skip drawing when `true`. Fix the literal `false` at line 166 to receive the real state. Thread the parameter from the hero caller. ~15 LoC across this file + the caller. |

**New tests:**

| File | Coverage |
|---|---|
| `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServiceAtomicTest.kt` | Mock the provider HTTP transport; verify `translateSrtAtomically` sends a single request, validates output cue count, returns null on parse failure, propagates settings correctly. |
| `app/src/test/java/com/nexio/tv/data/trailer/captions/TrailerSubtitleCacheTranslationTest.kt` | With Robolectric: mock `SubtitleTranslationService`, exercise `ensure()` paths — translation success writes translated file and returns its URI; failure falls back to source; native match skips translation entirely. |

---

## Testing approach

**Unit (Robolectric where Android APIs are touched):**

- `SubtitleTranslationServiceAtomicTest`: synthetic SRT in, validated SRT out; verify the prompt mentions source/target language codes; verify validation rejects mismatched cue counts.
- `TrailerSubtitleCacheTranslationTest`: cover the four-way decision matrix — (translateTo set | not set) × (AI configured | not). Verify cache hit on second call returns the translated file without re-invoking the service.
- `TrailerSubtitlePickerTest`: assert `translateTo` is populated in the no-native-match branch (regression guard for the contract change).

**On-device smoke (combined with implementation completion):**

- Trailer with English-only captions, user preferred lang = Dutch, AI translation configured → verify `<hash>-en-nl.srt` exists in `cacheDir/trailer-subtitles/` after first play, contains valid SRT, Dutch captions visible on screen.
- Same setup but AI translation disabled → verify only `<hash>-en.srt` exists, English captions visible.
- Toggle fullscreen on a trailer (via screensaver or whatever fullscreen path exists) → verify no left/bottom gradients darken the corners. Captions remain readable.
- Subtitle background visually transparent (matches stream player).

---

## Open questions resolved during brainstorm

| Question | Resolution |
|---|---|
| Translation integration point | New method on `SubtitleTranslationService`, single provider call. Not a separate translator; not reuse of the batched path. |
| Translation failure fallback | Source-language captions (current behavior). No retries in background, no "no captions" state. |
| Cache key for translated SRT | Reuse `TrailerSubtitleCache.cacheFileFor` with `translateTo` set. Cache key does NOT include provider; switching providers ages out on cacheDir eviction. |
| Burn-in protection on trailers | Out of scope; pass `BurnInProtectionState.Disabled` from `TrailerPlayer`. |
| Fullscreen state propagation | `ModernHomeHero` gets a `fullscreenTrailerActive: Boolean` parameter; caller supplies it from the existing screensaver/idle subsystem. The latent `false` literal at line 166 gets fixed in the same change. |

---

## Risks / known follow-ups

- **Provider response variance.** Some providers (especially smaller models) occasionally inject conversational preamble or change the cue numbering. The validation step is the safety net; expect a non-zero fallback rate to source captions during initial rollout. If the rate is high enough to be visible, tighten the system prompt or escalate to a stricter parse-and-reassemble strategy in a follow-up.
- **Cost.** Each trailer translation = one provider API call. A user with 40 home-screen items playing trailer previews could trigger 40 calls in a session if every one is in a non-preferred language. Mitigation: aggressive caching (translations are cached forever within `cacheDir` lifetime).
- **No fullscreen-state holder check yet.** The spec assumes the existing `inAppTrailerActive` signal is reachable from `ModernHomeHero`. Implementation will confirm; if not, plumbing it through adds 1–2 small composables of context-passing. Worth flagging during planning.
- **SubtitleStyle helper extraction.** Pulling the styling block out of `PlayerScreen` is a small refactor that risks subtle behavioral drift in the stream player. The extraction must be a pure-rename refactor with no logic change, validated by running the stream-player tests (or at minimum a manual smoke on a stream after the change).
