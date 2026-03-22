# Formatter Reference

Nexio’s formatter system is a template engine for stream cards. It powers:

- the **title** line
- the **description** lines
- the **web live preview**
- the **Android TV uniform formatting** experience

If you are new to formatter syntax, start with the [Formatter Getting Started](./formatter-getting-started.md) tutorial first. This page is the detailed reference.

## Quick mental model
A formatter is:

- plain text
- plus variables like `{stream.title}`
- plus modifiers like `::title`
- plus conditions like `::exists["yes"||"no"]`
- plus inline image tokens like `[[icon:4k]]`

## Core syntax

### Plain variable

```text
{stream.filename}
```

### Variable with modifiers

```text
{stream.title::title::truncate(30)}
```

### Conditional block

```text
{stream.year::exists[" ({stream.year})"||""]}
```

### Combined condition

```text
{stream.filename::~NF::and::stream.releaseGroup::exists[" • "||""]}
```

## Variables

### `stream`
These are the most important fields. They combine parsed filename data, provider hints, and stream-level metadata.

| Variable | Type | Description |
| :--- | :--- | :--- |
| `{stream.filename}` | String | Original filename used for parsing. |
| `{stream.folderName}` | String | Parent folder name if available. |
| `{stream.title}` | String | Parsed clean title. |
| `{stream.year}` | String / Number | Parsed year. |
| `{stream.seasons}` | Array | Raw season values. |
| `{stream.episodes}` | Array | Raw episode values. |
| `{stream.formattedSeasons}` | Array | Human-friendly season labels. |
| `{stream.formattedEpisodes}` | Array | Human-friendly episode labels. |
| `{stream.seasonEpisode}` | Array | Compact season/episode pair values. |
| `{stream.seasonPack}` | Boolean | Whether the stream is a season pack. |
| `{stream.size}` | Number | File size in bytes. |
| `{stream.folderSize}` | Number | Folder/package size in bytes. |
| `{stream.duration}` | Number | Duration in milliseconds. |
| `{stream.bitrate}` | Number | Bitrate in bits per second. |
| `{stream.age}` | Number / String | Age derived from provider metadata. |
| `{stream.indexer}` | String | Upstream indexer/source name. |
| `{stream.resolution}` | String | `2160p`, `1440p`, `1080p`, `720p`, etc. |
| `{stream.quality}` | String | `BluRay`, `BluRay Remux`, `WEB-DL`, `HDTV`, etc. |
| `{stream.encode}` | String | `HEVC`, `AVC`, `AV1`, and similar. |
| `{stream.visualTags}` | Array | `DV`, `HDR10`, `HDR10+`, `IMAX`, `10bit`, etc. |
| `{stream.audioTags}` | Array | `Atmos`, `TrueHD`, `DTS:X`, `DTS-HD MA`, `DD+`, etc. |
| `{stream.audioChannels}` | Array | `7.1`, `5.1`, `2.0`, etc. |
| `{stream.languages}` | Array | All audio languages. |
| `{stream.languageCodes}` | Array | ISO language codes. |
| `{stream.languageEmojis}` | Array | Language flag emojis. |
| `{stream.subtitles}` | Array | Subtitle languages. |
| `{stream.subtitleCodes}` | Array | Subtitle language codes. |
| `{stream.subtitleEmojis}` | Array | Subtitle flag emojis. |
| `{stream.releaseGroup}` | String | Scene/group tag parsed from the filename. |
| `{stream.message}` | String | Provider message or status line. |
| `{stream.type}` | String | `debrid`, `p2p`, `usenet`, `http`, `live`, `youtube`, `external`. |
| `{stream.private}` | Boolean | Private tracker / private source flag. |
| `{stream.proxied}` | Boolean | Whether the stream is proxied. |
| `{stream.library}` | Boolean | Whether the stream already exists in your library. |
| `{stream.infoHash}` | String | Torrent hash, when available. |
| `{stream.videoHash}` | String | Video hash when available. |
| `{stream.seeders}` | Number | Seeder count for P2P streams. |
| `{stream.repack}` | Boolean | Repack / proper flag. |
| `{stream.regraded}` | Boolean | Regraded visual tag. |
| `{stream.unrated}` | Boolean | Unrated release flag. |
| `{stream.uncensored}` | Boolean | Uncensored release flag. |
| `{stream.upscaled}` | Boolean | Upscaled release flag. |
| `{stream.seadex}` | Boolean | SeaDex curated match. |
| `{stream.seadexBest}` | Boolean | SeaDex “best” match. |

### `service`

| Variable | Type | Description |
| :--- | :--- | :--- |
| `{service.id}` | String | Internal service ID. |
| `{service.name}` | String | Full service name like `Real-Debrid`. |
| `{service.shortName}` | String | Compact service name like `RD`. |
| `{service.cached}` | Boolean | Whether the stream is cached / instant. |

### `addon`

| Variable | Type | Description |
| :--- | :--- | :--- |
| `{addon.name}` | String | Addon/provider display name. |

### `metadata`

| Variable | Type | Description |
| :--- | :--- | :--- |
| `{metadata.title}` | String | Queried content title. |
| `{metadata.year}` | Number | Queried content year. |
| `{metadata.runtime}` | Number | Runtime in minutes. |
| `{metadata.episodeRuntime}` | Number | Episode runtime in minutes. |
| `{metadata.queryType}` | String | `movie`, `series`, or similar. |

### User-filtered helpers
These are especially useful for clean UI because they already respect the user’s language preferences.

| Variable | Description |
| :--- | :--- |
| `{stream.uLanguages}` | Preferred audio languages present in the stream. |
| `{stream.uLanguageCodes}` | Preferred language codes. |
| `{stream.uLanguageEmojis}` | Preferred language flags. |
| `{stream.uSubtitles}` | Preferred subtitle languages present in the stream. |
| `{stream.uSubtitleCodes}` | Preferred subtitle language codes. |
| `{stream.uSubtitleEmojis}` | Preferred subtitle flags. |

### Tools

| Variable | Description |
| :--- | :--- |
| `{tools.newLine}` | Forces a new line. |
| `{tools.removeLine}` | Removes the whole rendered line when emitted inside a failed branch. |

## Text modifiers

| Modifier | Description |
| :--- | :--- |
| `::upper` | Convert to uppercase. |
| `::lower` | Convert to lowercase. |
| `::title` | Convert to title case. |
| `::trim` | Remove leading and trailing spaces. |
| `::truncate(N)` | Truncate to length `N`. |
| `::replace('old','new')` | Replace text. |
| `::remove('text')` | Remove text. |
| `::reverse` | Reverse string or array. |
| `::base64` | Base64-encode a string. |

## Numeric and unit modifiers

| Modifier | Description |
| :--- | :--- |
| `::bytes` | Human-readable bytes. |
| `::bytes10` | Human-readable decimal bytes. |
| `::sbytes` | Short byte form. |
| `::rbytes` | Rounded byte form. |
| `::bitrate` | Human-readable bitrate. |
| `::sbitrate` | Short bitrate form. |
| `::rbitrate` | Rounded bitrate form. |
| `::time` | Convert milliseconds to readable time. |
| `::age` | Convert age value to compact age text. |
| `::comma` | Add thousands separators. |
| `::hex` | Convert to hexadecimal. |
| `::octal` | Convert to octal. |
| `::binary` | Convert to binary. |
| `::star` | Convert a numeric score to stars. |
| `::pstar` | Same as `::star` but padded. |

## Array modifiers

| Modifier | Description |
| :--- | :--- |
| `::join(' sep ')` | Join array values with a separator. |
| `::first` | First array element. |
| `::last` | Last array element. |
| `::get(N)` | Get element at index `N`. |
| `::slice(S,E)` | Slice array from `S` to `E`. |
| `::random` | Random element. |
| `::length` | Array length. |
| `::sort` | Smart sort. |
| `::rsort` | Reverse sort. |
| `::lsort` | Alphabetical sort. |

## Localization modifiers

| Modifier | Description |
| :--- | :--- |
| `::flag` | Language name to emoji flag. |
| `::langcode` | Language name to ISO code. |
| `::lang` | ISO code to language name. |

## Conditions and comparisons
Conditionals use this form:

```text
{value::condition["true output"||"false output"]}
```

### Boolean conditions

- `::istrue`
- `::isfalse`

### Presence conditions

- `::exists`

### Numeric / string comparisons

- `::>value`
- `::<value`
- `::>=value`
- `::<=value`
- `::=value`
- `::!=value`

### String matching

- `::~value` contains
- `::$value` starts with
- `::^value` ends with

## Comparator chains
You can chain conditions together without nesting.

### Available comparators

- `::and::`
- `::or::`
- `::xor::`
- `::neq::`
- `::equal::`
- `::left::`
- `::right::`

### Example: only show a separator when both values exist

```text
{stream.uLanguages::exists::and::stream.releaseGroup::exists[" • "||""]}
```

### Example: only show release-group separator when any service marker matched

```text
{stream.filename::~NF::or::stream.filename::~DSNP::or::stream.filename::~AMZN::and::stream.releaseGroup::exists[" • "||""]}
```

### Example: highlight premium anime logic

```text
{stream.seadexBest::istrue[" 🏆 BEST"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" 🥈 ALT"||""]}
```

## Inline icon tokens
Nexio supports inline image tokens in formatter output:

```text
[[icon:token]]
```

These render as actual inline icons on Android and in the web preview, while preserving plain-text fallback when rendering is unavailable.

## Icon token reference

### Resolution badges

- `[[icon:4k]]`
- `[[icon:2k]]`
- `[[icon:fullhd]]`
- `[[icon:hd]]`
- `[[icon:sd]]`

### Streaming services

- `[[icon:netflix]]`
- `[[icon:disneyplus]]`
- `[[icon:hbo]]`
- `[[icon:max]]`
- `[[icon:prime]]`
- `[[icon:appletv]]`
- `[[icon:paramount]]`
- `[[icon:peacock]]`
- `[[icon:crunchyroll]]`

### Audio

- `[[icon:atmos]]`
- `[[icon:truehd]]`
- `[[icon:ddp]]`
- `[[icon:dd]]`
- `[[icon:dts]]`
- `[[icon:dtshd]]`
- `[[icon:dtsx]]`

### Visual

- `[[icon:dovi]]`
- `[[icon:hdr10]]`

## Advanced examples

### Resolution badge title

```text
{stream.resolution::exists["{stream.resolution::replace('2160p','[[icon:4k]]')::replace('1440p','[[icon:2k]]')::replace('1080p','[[icon:fullhd]]')::replace('720p','[[icon:hd]]')::replace('576p','[[icon:sd]]')::replace('480p','[[icon:sd]]')}"||""]}{stream.resolution::exists::and::stream.title::exists["   "||""]}{stream.title::exists["{stream.title::title::truncate(30)}"||"?"]}
```

### Audio icon row

```text
{stream.audioTags::exists["{stream.audioTags::join('  ')::replace('Atmos','[[icon:atmos]]')::replace('TrueHD','[[icon:truehd]]')::replace('DTS-HD MA','[[icon:dtshd]]')::replace('DTS:X','[[icon:dtsx]]')::replace('DD+','[[icon:ddp]]')::replace('DD','[[icon:dd]]')::replace('EAC3','[[icon:ddp]]')::replace('AC3','[[icon:dd]]')::replace('DTS','[[icon:dts]]')}"||""]}
```

### Conditional provider + release group line

```text
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}{stream.filename::~DSNP["[[icon:disneyplus]] Disney+"||""]}{stream.filename::~NF::or::stream.filename::~DSNP::and::stream.releaseGroup::exists[" • "||""]}{stream.releaseGroup::exists["👤 {stream.releaseGroup}"||""]}
```

### Runtime and size line

```text
💾 {stream.size::>0["{stream.size::bytes}"||"Unknown"]} • ⏱️ {stream.duration::>0["{stream.duration::time}"||"Unknown"]}
```

### Filename fallback line

```text
📄 {stream.filename::exists["{stream.filename}"||"—"]}
```

## Practical guidance

### Good formatter habits

- Guard optional data with `::exists`
- Keep separators inside the condition
- Build incrementally
- Preview with both movies and episodes
- Test missing-field cases

### When to use icons
Icons work best for:

- resolution badges
- streaming platforms
- premium audio/visual tags

Use plain text when readability matters more than visual density, or when the field can vary too much to justify a token mapping.

### When to use arrays
Use arrays with `::join(...)` when:

- multiple languages can be present
- multiple audio tags can exist
- multiple visual tags can exist

## Current built-in Universal formatter style
The built-in `Universal` formatter combines:

- resolution icon title badges
- compact title and year
- compact `Sxx Exx` season/episode output
- icon-based premium audio tags
- size, provider, addon, language, service, release-group, and filename metadata

You can use it as a starting point in the web formatter workspace and customize only the pieces you care about.

## Preview workflow

1. Start with a built-in formatter.
2. Edit the title first.
3. Preview it with a movie sample and an episode sample.
4. Then edit the description.
5. Use the JSON/debug view when a field is missing or behaving unexpectedly.
6. Save only after your formatter handles sparse and noisy payloads gracefully.

## Related docs

- [Formatter Getting Started](./formatter-getting-started.md)

