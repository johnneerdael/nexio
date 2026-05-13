# Change: restrict anime stream addons and prefer Torii

## Why

Anime-specific stream addons currently receive priority in presentation, but the stream repository still queries every compatible addon for anime content. That keeps generic addons on the hot path even after the user has explicitly configured anime-specific sources.

NEXIO also supports two built-in Nexio provider presets. Torii should outrank Nagare when their other core ranking signals tie because Torii exposes selected-file size, which makes downstream ranking more accurate than Nagare's weaker metadata.

## What Changes

- When the requested content is classified as anime and at least one compatible installed addon is tagged `isAnime`, only those anime-tagged addons are queried for streams.
- For non-anime content, unknown content, or users without anime-tagged compatible addons, the existing all-compatible-addon query behavior remains unchanged.
- Grouped stream presentation ranks `AddonParserPreset.NEXIO_TORII` ahead of `AddonParserPreset.NEXIO_NAGARE` after cache and resolution, before size-based ordering.
- Existing service-wrap and progressive stream emission behavior remains intact for the selected addon set.

## Impact

- Affected app: `app`
- Affected areas: stream query fan-out, stream presentation ranking, autoplay candidate ordering through presented stream order
- Compatibility: existing generic-addon behavior remains unchanged unless content is confidently classified as anime and anime-specific addons are configured
