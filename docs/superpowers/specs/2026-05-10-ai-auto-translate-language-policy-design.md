# AI Auto-Translate: Language Policy & Forced/SDH De-prioritization

**Date:** 2026-05-10
**Status:** Approved
**Owner:** john@neerdael.nl

## Problem

The AI auto-translate feature is perceived as dependent on a configured "secondary
language" because two pieces of supporting machinery key off it:

1. The OpenSubtitles addon-fetch pipeline only requests subtitles in the user's
   primary and secondary languages (`PlayerRuntimeControllerInitialization.kt:1574-1583`).
   Without a secondary, the addon list contains only primary-language subs, so
   the AI tier has no addon source to fall back to.
2. The startup picker's AI tier has a `findAddon(normalizedSecondary)` branch
   (`PlayerStartupSelectionPolicy.kt:319-325`) which is a duplicate of the
   tier-5 untranslated-secondary branch (with `enableAiTranslation = true`
   already set when AI is configured).

Separately, the picker currently returns `candidateIndexes.first()` within a
language match. On streams that ship `Forced (EN)` ahead of `English` in track
order, the user gets the forced track — which contains only signs/inserts, not
dialogue — and the subtitles appear non-functional.

## Goals

- Make AI auto-translate work when no secondary language is configured, using
  embedded subtitles only as the source.
- Translate from any source language to the user's primary language. Source
  language plumbing already works; only the prompt needs improvement for
  unknown source.
- When multiple subtitle tracks match a language, prefer normal dialogue over
  SDH and forced tracks. Forced is last resort.

## Non-Goals

- Expanding addon subtitle fetching to include English (or "any") when
  secondary is unset. Addon fetching stays primary+secondary keyed; AI source
  selection is embedded-only when secondary is absent. Trade-off accepted: a
  Dutch-primary user with no embedded text track and no Dutch addon match gets
  no subtitles.
- Removing or repurposing the secondary-language setting. It remains the
  no-AI untranslated fallback (tier 4/5) and acts as a source-language hint
  for AI when set.
- Changing the manual AI toggle path or the manual "translate this addon
  subtitle" path — both already work without secondary.

## Approved Design

### 1. Startup auto-pick (`PlayerStartupSelectionPolicy.kt`)

The AI tier in `decideStartupSubtitleAutoSelection` is consolidated. Tiers in
priority order:

1. Internal preferred-language match → `Internal(ai=false)`.
2. Addon preferred-language match → `Addon(ai=false)`.
3. **AI tier** (gated on `aiTranslationConfigured && normalizedPreferred != null && startupPhase`):
   `pickTranslatableInternalSubtitle` returns the best embedded text track using
   ladder `secondary (when set) → English → any text-based`. If a match is
   found, return `Internal(index, ai=true)`. **The addon branch
   (`findAddon(normalizedSecondary)`) is removed from this tier** — it was a
   duplicate of tier 5 with the same `enableAiTranslation` outcome.
4. Internal secondary-language match → `Internal(ai = AI_configured)`.
5. Addon secondary-language match → `Addon(ai = AI_configured)`.
6. Otherwise `None` (or `DeferAddonFallback` while scan/discovery pending).

`pickTranslatableInternalSubtitle` keeps its current ladder (`secondary →
English → any text-based`) but applies forced/SDH de-prioritization within
each tier (see §3).

The `aiTranslationAllowed` boolean stays `startupPhase && aiTranslationConfigured
&& normalizedPreferred != null` — it never required secondary, but the
addon-secondary branch made it appear to.

### 2. Translation prompt (`SubtitleTranslationService.kt`)

`buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName)` becomes
`buildTranslationSystemPrompt(targetLanguageCode, targetLanguageName,
sourceLanguageName)`. When `sourceLanguageName == "auto"` (the value
`displaySourceLanguage` returns for blank/`und`/`unknown`), append:

> The source language is unknown. Detect it automatically from the cue text
> and translate the items into &lt;targetLanguageName&gt;.

When `sourceLanguageName` is concrete (e.g. `"Polish"`), append:

> Translate the items from &lt;sourceLanguageName&gt; into &lt;targetLanguageName&gt;.

The same instructions are appended to `buildRawSubRipSystemPrompt` and
`buildRawAssSsaSystemPrompt`. `buildProtectedAssSsaSystemPrompt` already says
"Translate subtitle text from the source language to the target language" —
keep as is, but accept and forward the `sourceLanguageName` so call sites
stay symmetric.

`displaySourceLanguage`, `normalizeSourceLanguageCode`, and the existing
plumbing of `sourceLanguageCode` from `Subtitle.lang` /
`Format.language` through to provider requests are unchanged.

### 3. Forced/SDH de-prioritization (`PlayerStartupSelectionPolicy.kt`)

New helpers (private):

```kotlin
private val SDH_MARKERS = listOf("sdh", "[cc]", " cc ", "closed caption", "hearing impaired")

private fun TrackInfo.isSdhSubtitle(): Boolean {
    val haystack = listOfNotNull(name, trackId).joinToString(" ").lowercase(Locale.ROOT)
    return SDH_MARKERS.any { haystack.contains(it) }
}

// Lower rank = higher priority.
private fun TrackInfo.subtitleAccessibilityRank(): Int = when {
    isForced -> 2
    isSdhSubtitle() -> 1
    else -> 0
}
```

Applied at two call sites:

- `findBestInternalSubtitleTrackIndexForStartup`: replace
  `candidateIndexes.first()` with
  `candidateIndexes.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }
   ?: candidateIndexes.first()`. The pt-br tiebreak in
  `breakPortugueseSubtitleTieForStartup` is similarly updated: each existing
  fallback step (currently `firstOrNull { hasBrazilianTags(it) &&
  !hasEuropeanTags(it) }`, then `firstOrNull { hasBrazilianTags(it) }`, etc.)
  becomes
  `filter { ... }.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }`,
  preserving the existing pt-br variant precedence. Net effect: pt-br tag
  match is the dominant signal (a Brazilian Portuguese forced sub still wins
  over a European Portuguese normal sub when target is `pt-br`); within
  pt-br-tagged candidates, accessibility rank breaks ties.
- `pickTranslatableInternalSubtitle`: each of the three tiers (secondary,
  English, any text-based) replaces `firstOrNull` with
  `filter { matches }.minByOrNull { subtitleTracks[it].subtitleAccessibilityRank() }`.
  Bitmap filtering happens before ranking (unchanged).

The explicit `preferredLanguage == "forced"` pseudo-language branch
(`PlayerStartupSelectionPolicy.kt:230`) is **untouched**. When the user
explicitly opts into forced, we still pick forced.

### 4. Untouched

- `aiTranslationConfigured` calculation in
  `PlayerRuntimeControllerTracks.kt:1050-1051` — already only depends on
  settings.enabled + apiKey.
- `enableAiSubtitles()` (manual toggle), `selectAddonSubtitleRespectingAi`,
  `translateAndSelectAddonSubtitle`.
- `BuiltInSubtitleCueTranslator` — already passes `format.language` as source.
- `OpenSubtitlesSourceImpl` and addon fetch language list — stays
  primary+secondary.
- The secondary-language settings UI and DataStore key.

## Behavior Matrix

| Configuration | Stream Tracks | Old Outcome | New Outcome |
|---|---|---|---|
| preferred=nl, no secondary, AI on | embedded `[en, fr]` text | `Internal(en, ai=true)` | Same |
| preferred=nl, no secondary, AI on | embedded `[pl]` text only | `Internal(pl, ai=true)` | Same |
| preferred=nl, no secondary, AI on | embedded `[pgs-en]` (bitmap) only | `None` | Same |
| preferred=nl, no secondary, AI on | no embedded text + addon `[nl]` | `Addon(nl, ai=false)` (tier 2) | Same |
| preferred=nl, no secondary, AI on | no embedded text + no nl addon | `None` | Same (addon fetch is nl-only) |
| preferred=nl, secondary=fr, AI on | embedded `[en, fr, de]` text | `Internal(fr, ai=true)` | Same — secondary-as-source-hint preserved |
| preferred=nl, secondary=en, AI on | no embedded text + addon `[en]` | `Addon(en, ai=true)` (via tier 3 addon branch) | `Addon(en, ai=true)` (via tier 5; identical user-visible outcome) |
| preferred=en, embedded `[Forced (EN), English]` | — | `Forced (EN)` (track 0 wins) | `English` |
| preferred=en, embedded `[Forced (EN), English SDH]` | — | `Forced (EN)` | `English SDH` |
| preferred=en, embedded `[Forced (EN)]` | — | `Forced (EN)` | `Forced (EN)` (last resort) |
| preferred="forced", embedded `[Forced (EN), English]` | — | `Forced (EN)` | `Forced (EN)` (explicit branch unchanged) |
| preferred=nl, AI on, embedded `[Forced (EN), English]` | — | AI tier picks `Forced (EN)` (track 0) | AI tier picks `English` |
| Source `lang=und`, AI translate | — | `source_language=auto` field, prompt silent on detection | Prompt explicitly instructs auto-detection |
| Source `lang=pl`, AI translate | — | `source_language=pl`, prompt silent on source | Prompt explicitly says "from Polish to Dutch" |

## Error Handling

No new error states. The change reduces the set of valid AI-tier paths and
makes the picker more discriminating; every removed path lands in `None` or
falls through to tier 4/5, both already handled.

Existing failure modes unchanged:
- Translation provider error → `aiSubtitleError` UI state + 60s suppression
  cooldown via `BUILT_IN_SUBTITLE_PROVIDER_FAILURE_COOLDOWN_MS`.
- Bitmap-only embedded tracks → `pickTranslatableInternalSubtitle` returns -1,
  AI tier falls through.
- Format unsupported → existing `supportsAiTranslation` check rejects.

## Testing

### Unit tests (JVM)

Added to `PlayerStartupSelectionPolicyTest.kt`:

1. `decideStartupSubtitleAutoSelection_aiTier_pickEnglishEmbeddedWhenNoSecondary` —
   preferred=nl, secondary=null, AI configured, embedded `[en, fr]` text →
   `Internal(en-index, ai=true)`.
2. `decideStartupSubtitleAutoSelection_aiTier_pickAnyEmbeddedWhenNoEnglishNoSecondary` —
   preferred=nl, secondary=null, AI configured, embedded `[pl]` text only →
   `Internal(pl-index, ai=true)`.
3. `decideStartupSubtitleAutoSelection_aiTier_returnsNoneWhenOnlyBitmapEmbedded` —
   preferred=nl, secondary=null, AI configured, embedded `[application/pgs]` →
   `None`.
4. `decideStartupSubtitleAutoSelection_aiTier_secondaryHintWinsOverEnglish` —
   preferred=nl, secondary=fr, AI configured, embedded `[en, fr, de]` →
   `Internal(fr-index, ai=true)`.
5. `decideStartupSubtitleAutoSelection_aiTierAddonBranchRemoved_fallsThroughToTier5` —
   preferred=nl, secondary=en, AI configured, no embedded text, addon `[en]` →
   `Addon(en-sub, ai=true)`. Documents the consolidation: same outcome as
   before, served by tier 5 instead of tier-3 addon branch.
6. `findBestInternal_picksNormalOverForcedWhenBothEnglishExist` —
   `[en-forced, en-normal]` → en-normal.
7. `findBestInternal_picksNormalOverSdhAndForced` —
   `[en-forced, en-sdh, en-normal]` → en-normal.
8. `findBestInternal_picksSdhWhenOnlyForcedAndSdhExist` —
   `[en-forced, en-sdh]` → en-sdh.
9. `findBestInternal_picksForcedAsLastResort` —
   `[en-forced]` → en-forced.
10. `findBestInternal_forcedPreferenceStillHonored` —
    preferredLanguage="forced", `[en-forced, en-normal]` → en-forced.
11. `pickTranslatableInternal_aiSource_picksNormalOverForced` —
    secondary=null, `[en-forced, en-normal]` → en-normal.
12. `pickTranslatableInternal_aiSource_secondaryHint_picksNormalOverSdh` —
    secondary="fr", `[fr-sdh, fr-normal, en-normal]` → fr-normal.
13. `findBestInternal_ptBrTiebreakStillWinsOverAccessibility` — target=pt-br,
    `[pt-eu-normal, pt-br-forced]` → pt-br-forced (pt-br preference outranks
    accessibility rank).

New file `SubtitleTranslationServicePromptTest.kt`:

14. `buildTranslationSystemPrompt_includesAutoDetectInstructionWhenSourceIsAuto` —
    pass source="auto" → prompt contains "detect" and "automatically".
15. `buildTranslationSystemPrompt_includesExplicitSourceWhenKnown` —
    pass source="Polish", target="Dutch" → prompt contains "Polish" and "Dutch".
16. `buildRawSubRipSystemPrompt_includesAutoDetectInstructionWhenSourceIsAuto`.
17. `buildRawSubRipSystemPrompt_includesExplicitSourceWhenKnown`.
18. `buildRawAssSsaSystemPrompt_includesAutoDetectInstructionWhenSourceIsAuto`.
19. `buildRawAssSsaSystemPrompt_includesExplicitSourceWhenKnown`.

### Manual / on-device verification

- Dutch primary, no secondary, AI configured: play an MKV with embedded
  English subs → English picked, translated to Dutch in-player.
- Dutch primary, no secondary, AI configured: play an MKV with `lang=und`
  Polish embedded subs → Polish detected by LLM (verify provider request log
  shows the explicit detect instruction), translated to Dutch.
- Dutch primary, no secondary, AI configured: play a container with no
  embedded text subs → no subs, no error toast (consistent with current
  behavior).
- English primary, AI off: play an MKV with `[Forced (EN), English]` → English
  (normal) picked. Confirm dialogue is visible from the first cue.
- English primary, AI off: play an MKV with `[Forced (EN), English SDH]` →
  English SDH picked.
- Preferred=forced setting: confirm forced track still picked when set
  explicitly.

## Files Changed

- `app/src/main/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicy.kt`
  — remove tier-3 addon branch; add `SDH_MARKERS`, `isSdhSubtitle`,
  `subtitleAccessibilityRank`; apply ranking in
  `findBestInternalSubtitleTrackIndexForStartup` and
  `pickTranslatableInternalSubtitle`.
- `app/src/main/java/com/nexio/tv/data/repository/SubtitleTranslationService.kt`
  — extend `buildTranslationSystemPrompt`, `buildRawSubRipSystemPrompt`,
  `buildRawAssSsaSystemPrompt` with the source-language argument and the
  detect/explicit instruction. Update call sites.
- `app/src/test/java/com/nexio/tv/ui/screens/player/PlayerStartupSelectionPolicyTest.kt`
  — tests 1-13.
- `app/src/test/java/com/nexio/tv/data/repository/SubtitleTranslationServicePromptTest.kt`
  (new) — tests 14-19.

## Risks

- **Forced-detection false positives.** `isForced` already includes tracks
  whose name contains "forced" or matches the songs/signs heuristic
  (`PlayerRuntimeControllerTracks.kt:172-183`). De-prioritizing them is
  desirable for the songs/signs case too. No new risk.
- **SDH-detection false positives.** A track named "Special Edition CC" could
  be mis-classified as SDH. Conservative marker list (`sdh`, `[cc]`, ` cc `,
  `closed caption`, `hearing impaired`) keeps this unlikely. Even when
  mis-classified, SDH still wins over forced — the worst case is preferring
  one normal-equivalent track over another.
- **Prompt-tax.** The added "detect/explicit source" sentence costs ~15-30
  tokens per system prompt. Prompt-cache amortizes; the impact on per-cue
  cost is negligible.

## Open Questions

None remaining.
