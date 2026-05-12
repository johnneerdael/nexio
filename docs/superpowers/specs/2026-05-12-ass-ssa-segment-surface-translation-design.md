# ASS/SSA Segment-Surface Translation Design

## Goal

Replace NEXIO's current ASS/SSA subtitle translation paths with one deterministic segment-surface pipeline that keeps ASS/SSA syntax out of the LLM payload. The model receives structured JSON containing plain translation segments, plus only short intraword placeholders such as `<1/>` when an ASS/SSA syntax token sits inside a word.

This replaces both existing ASS/SSA modes:

- the default protected-unit path that exposes every protected token as placeholders such as `⟦ASS_000⟧` and `⟦LB_000⟧`
- the raw ASS/SSA system-prompt mode behind `assSsaSystemPromptEnabled`

SRT raw-system-prompt behavior is unchanged.

## Non-Goals

- Do not rebuild full ASS/SSA event lines from normalized fields.
- Do not ask the model to preserve raw ASS/SSA syntax.
- Do not expose line-break, style, karaoke, positioning, or drawing syntax as ordinary placeholders unless the syntax is intraword.
- Do not fail an entire subtitle file because one event response is invalid.

## Architecture

ASS/SSA translation uses one pipeline:

1. Parse the subtitle file using the active `[Events] Format:` line.
2. For each translatable event line whose format contains a `Text` field, parse only that `Text` field into an `AssSsaTranslationSurface`.
3. Support ASS/SSA event prefixes such as `Dialogue:` and `Comment:` through the same event-record parser, while preserving non-translatable or unsupported event lines unchanged.
4. Send provider JSON items shaped as `{ id, context, segments }`.
5. Validate each returned item independently.
6. Recompose valid events from original raw tokens and translated segments.
7. Preserve the original event text for any item that fails validation.
8. Rebuild each event line by replacing only the original `Text` field.

The old ASS/SSA raw prompt path is removed. The user-facing `assSsaSystemPromptEnabled` setting, UI row, strings, prompt builder, raw ASS request path, and raw ASS validation path are removed. ASS/SSA always uses the segment-surface translator.

## Surface Model

```kotlin
data class AssSsaTranslationSurface(
    val id: String,
    val prefixRaw: String,
    val segments: List<String>,
    val separators: List<String>,
    val suffixRaw: String,
    val inlineMarkers: Map<String, String>,
    val context: String
)
```

Invariant:

```text
separators.size == segments.size - 1
```

Events with no visible translatable text are excluded from the provider payload and preserved byte-for-byte.

## Parser Rules

The parser tokenizes the `Text` field into visible text spans and ASS/SSA syntax tokens: override blocks, `\N`, `\n`, `\h`, drawing spans, malformed blocks, and plain text. It tracks drawing mode via `\pN`, preserving drawing payloads as raw syntax.

Token classification is local:

- Leading structural tokens before the first visible text become `prefixRaw`.
- Trailing structural tokens after the final visible text become `suffixRaw`.
- Non-intraword syntax between visible text becomes a segment separator.
- Syntax between two word characters becomes a local inline marker: `<1/>`, `<2/>`, etc.
- Marker numbering resets per event.
- Separator whitespace is owned by the separator, not by the translated segment.

Word-character detection uses Unicode-aware letters, digits, and marks so accented and non-English scripts classify correctly.

Examples:

```ass
I{\c&H0F00A1&}nitiative
```

becomes one segment:

```text
I<1/>nitiative
```

```ass
with {\i1}me{\i0} today
```

becomes:

```kotlin
segments = listOf("with", "me", "today")
separators = listOf(" {\\i1}", "{\\i0} ")
```

```ass
{\k20}Good {\K30}morning
```

becomes:

```kotlin
prefixRaw = "{\\k20}"
segments = listOf("Good", "morning")
separators = listOf(" {\\K30}")
```

Malformed syntax or drawing-only events may produce a preserve-only result rather than expose unsafe text to the provider.

## LLM Contract

The provider receives structured JSON instead of raw subtitle text:

```json
{
  "sourceLanguage": "auto",
  "targetLanguage": "Dutch",
  "items": [
    {
      "id": "ass_0",
      "context": "On the contrary, I was convinced from the start that he couldn't be X.",
      "segments": [
        "On the contrary, I was convinced",
        "from the start that he",
        "couldn't",
        "be X."
      ]
    }
  ]
}
```

Expected response:

```json
{
  "items": [
    {
      "id": "ass_0",
      "segments": [
        "Integendeel, ik was ervan overtuigd",
        "vanaf het begin dat hij",
        "niet",
        "X kon zijn."
      ]
    }
  ]
}
```

The system prompt stays short:

```text
Translate subtitle segments to the target language.
Return valid JSON only.
Keep the same item ids.
Keep exactly the same number of segments for each item.
Do not merge, split, reorder, or omit segments.
Preserve placeholders like <1/>, <2/>, <3/> exactly.
Place placeholders inside the equivalent translated word when possible.
Do not output ASS/SSA syntax such as {...}, \N, \n, or \h.
Keep subtitle phrasing concise and natural.
```

Batching can reuse the existing ramped batch planner concept, but it should size batches by segment-surface payload size instead of protected-text length. Overlap can remain useful for context, with only core items merged back.

## Validation

Each returned item is validated independently:

- `id` exists in the request.
- Segment count matches exactly.
- No segment contains real line breaks.
- No segment contains raw ASS/SSA override blocks or escapes: `{...}`, `\N`, `\n`, `\h`.
- Every expected inline marker appears exactly once across that item's returned segments.
- No unknown inline marker appears.
- Intraword markers do not have spaces immediately around them after repair.
- Empty translated segments are rejected unless the source segment was empty.

Before final rejection, run narrow auto-repair:

- Strip code fences and outer prose from JSON using the existing sanitizer.
- Remove spaces immediately around known intraword markers.
- Ignore unknown returned items.
- Accept item order changes because ids are authoritative.

If an item still fails, preserve only that original event's `Text` field. Other valid events in the same provider response are still recomposed.

## Recomposition

Recomposition is deterministic:

```text
prefixRaw
+ translatedSegments[0]
+ separators[0]
+ translatedSegments[1]
+ ...
+ translatedSegments.last()
+ suffixRaw
```

Inline markers are replaced with original raw ASS/SSA syntax inside each segment. The final event line is rebuilt by replacing only the original `Text` field, preserving event type, commas, timings, style, margins, effect, and unknown fields byte-for-byte.

## Code Scope

Implementation should stay within the existing subtitle translation boundary:

- Replace `AssSsaProtectedTranslationUnit` / `AssSsaTextAstTranslationUnit` usage for ASS/SSA translation with the new segment-surface model.
- Keep or reuse the low-level ASS/SSA tokenizer and event-record parsing where they already behave correctly.
- Update `TimedTextDocument.assSsaProtectedUnits()` and `renderAssSsaProtected()` into segment-surface equivalents.
- Update `SubtitleTranslationService` to call the segment-surface request/response parser for all ASS/SSA files.
- Remove `assSsaSystemPromptEnabled` from `SubtitleTranslationSettings`, DataStore, ViewModel/UI state, settings screen, sync payload if present, strings, tests, cache key, and raw ASS prompt/validation code.
- Keep SRT raw-system-prompt behavior unchanged.
- Bump the subtitle translation disk cache key version so old translated ASS/SSA cache files are not reused.

## Test Plan

Cover these cases with focused unit tests:

- Leading layout/style prefix preservation.
- `\N`, `\n`, and formatting tags as segment separators.
- Intraword tags as `<1/>`.
- Formatting around a word without placeholders.
- Karaoke between words as separators.
- Karaoke inside a word as `<1/>`.
- Drawing-only and tag-only events preserved.
- Event types beyond `Dialogue:` when they have a valid `Text` field.
- Per-event fallback on bad response.
- Removal of the ASS/SSA system-prompt setting and UI row.
- Cache-key version bump.

## Success Criteria

- ASS/SSA provider payloads contain only JSON segments and local intraword markers.
- The model never needs to copy raw ASS/SSA syntax.
- Valid items recompose byte-for-byte around translated text.
- Invalid items preserve their original event text without failing the whole file.
- The raw ASS/SSA system-prompt setting and code path are gone.
