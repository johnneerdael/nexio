# Formatter Chip Tokens Design

Date: 2026-04-13

## Context

Stream cards currently render automatic chips such as `Cached`, `Torrent`, `YouTube`, and `External` outside the Universal Formatter. The formatter controls the title and detail text through `nameTemplate` and `descriptionTemplate`, and it already supports inline icon tokens such as `[[icon:4k]]` and `[[icon:realdebrid]]`.

This creates a placement mismatch: formatter users can move icons and text around, but cannot move the existing stream chips. The `Cached` chip, in particular, stays in the bottom card area even if a custom formatter would be clearer with that status in the title, detail text, or a separate full-width row.

## Goals

- Allow stream chips to be placed by formatter templates.
- Support chip tokens inline with title and detail text.
- Add an optional full-width badge row configured through the formatter.
- Keep existing formatter templates working without migration.
- Preserve the current chip labels, colors, localization, and stream badge semantics.

## Non-Goals

- Do not redesign stream sorting or deduplication.
- Do not change how cached, torrent, YouTube, or external badges are detected.
- Do not make arbitrary user-defined chip colors or labels in this pass.
- Do not remove the existing automatic chip fallback for users without chip-aware templates.

## Formatter Model

Extend formatter definitions with an optional third template field:

```kotlin
data class AioTemplateDefinition(
    val id: String,
    val nameTemplate: String,
    val descriptionTemplate: String,
    val badgeRowTemplate: String = ""
)
```

Apply the same shape to custom template selection:

```kotlin
data class AioCustomTemplateSelection(
    val label: String? = null,
    val nameTemplate: String? = null,
    val descriptionTemplate: String? = null,
    val badgeRowTemplate: String? = null
)
```

`badgeRowTemplate` is optional. Empty, blank, or fully removed output means no formatter-controlled full-width badge row.

## Chip Tokens

Add formatter chip tokens:

```text
[[chip:cached]]
[[chip:torrent]]
[[chip:youtube]]
[[chip:external]]
```

These tokens render with the same semantics as today:

- `cached` uses the existing cached label and success color.
- `torrent` uses the existing torrent label and secondary color.
- `youtube` uses the existing YouTube label and red color.
- `external` uses the existing external label and primary color.

Unknown chip tokens should degrade to readable text in the same spirit as unknown icon tokens, rather than crashing the card.

## Template Examples

Inline placement:

```text
nameTemplate:
{service.cached::istrue["[[chip:cached]] "||""]}{stream.resolution} {stream.title}

descriptionTemplate:
{stream.filename}

badgeRowTemplate:

```

Separate row placement:

```text
nameTemplate:
{stream.resolution} {stream.title}

descriptionTemplate:
{stream.filename}

badgeRowTemplate:
{service.cached::istrue["[[chip:cached]]"||""]}{stream.type::=p2p[" [[chip:torrent]]"||""]}
```

Mixed placement:

```text
nameTemplate:
{service.cached::istrue["[[chip:cached]] "||""]}{stream.resolution} {stream.title}

descriptionTemplate:
{stream.filename}

badgeRowTemplate:
{stream.type::=youtube["[[chip:youtube]]"||""]}{stream.type::=external["[[chip:external]]"||""]}
```

## Rendering Behavior

`nameTemplate` and `descriptionTemplate` should support inline chip tokens. The chip renderer should live near the existing inline icon rendering path, but it should render chips as Compose UI rather than plain text whenever possible.

`badgeRowTemplate` should render below the main stream card content row, outside the existing title/details plus addon logo row. This makes the badge row span the whole card width, including the area beneath the addon logo column.

If a formatter output contains any `[[chip:*]]` token in the title, detail lines, or badge row, suppress the existing automatic chip row for that card. This gives the formatter full control over placement.

If no formatter output contains chip tokens, keep the current automatic chip row. Existing users and built-in templates remain visually stable.

## Data Flow

1. `AioUniformFormatter.render` renders `nameTemplate`, `descriptionTemplate`, and `badgeRowTemplate`.
2. `AioUniformPresentation` carries:
   - `title`
   - `detailLines`
   - `badgeRow`
   - `hasFormatterChipTokens`
3. `StreamPresentationEngine.organize` includes those fields in `StreamCardModel`.
4. `StreamCard` renders inline chips in title/detail text, renders `badgeRow` as a full-width row when present, and falls back to the automatic chip row only when `hasFormatterChipTokens` is false.

## Testing

Add focused unit coverage for:

- Rendering `badgeRowTemplate` through `AioUniformFormatter`.
- Detecting chip tokens in title, detail lines, and badge row output.
- Preserving automatic chip fallback when no formatter chip tokens are present.
- Suppressing automatic chip fallback when formatter chip tokens are present.
- Tokenizing known and unknown `[[chip:*]]` tokens.

Add Compose-level or UI-focused coverage where practical for:

- Inline chip rendering in title/detail text.
- Full-width badge row rendering below the card content row.

## Documentation

Update the formatter reference docs with:

- The `badgeRowTemplate` field.
- The supported `[[chip:*]]` tokens.
- Examples for inline and full-width badge row placement.

Update Universal Formatter docs only if the built-in Universal template starts using chip tokens by default. If the built-in template remains unchanged, the reference docs are enough.
