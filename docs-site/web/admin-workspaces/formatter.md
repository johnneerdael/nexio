# Formatter Reference

If you want the outcome and the recommended template shape, start with [Universal Formatter](/customize/universal-formatter) first. This page is the syntax reference for the portal editor and the built-in templates.

Nexio’s formatter syntax follows the AIO-style formatting lineage popularized by Viren070’s AIOStreams work. The core formatter implementation in Nexio is adapted from earlier open-source formatter code and keeps that same mental model of variables, modifiers, and conditions.

## Quick mental model
A formatter is:
- plain text
- plus variables like `{stream.title}`
- plus modifiers like `::title`
- plus conditions like `::exists["yes"||"no"]`
- plus inline image tokens like `[[icon:4k]]`
- plus stream chip tokens like `[[chip:cached]]`

Custom formatters have three template fields:

- `nameTemplate` controls the stream card title.
- `descriptionTemplate` controls the multiline detail text.
- `badgeRowTemplate` controls an optional full-width row below the main card content. Leave it blank to skip the row.

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

## Built-in template family
Nexio ships with built-in presets that reflect common formatting styles:
- `Universal` for a clean cross-provider look
- `Torrentio` for a Torrentio-style card layout
- `TorBox` for cloud or debrid-oriented presentation
- `GDrive`, `Light GDrive`, and `Minimalistic GDrive` for Google-Drive-style libraries
- `Prism` for a compact, aesthetic layout
- `Tamtaro` for a dense, information-rich layout

These presets are a good reference even if you plan to write a custom template.

## Inline icon tokens
Inline icon tokens let you place small branded or technical badges directly inside formatter output. They are especially useful when you want a stream title or description to communicate provider, audio format, HDR format, or headline resolution without adding more words.

Use this syntax:

```text
[[icon:4k]]
[[icon:netflix]]
[[icon:atmos]]
```

You can place icon tokens anywhere plain text is allowed:

```text
[[icon:4k]] {stream.title}
[[icon:dovi]] {stream.visualTags::join(' • ')}
{service.cached::istrue["[[icon:prime]] Instant"||"[[icon:prime]] Cloud"]}
```

### How icon tokens behave
- Tokens are case-insensitive, so `[[icon:4k]]` and `[[icon:4K]]` both resolve.
- If a token is recognized, Nexio renders the matching icon in the web formatter preview and in the TV app formatter output.
- If a token is not recognized, Nexio leaves the original text in place instead of silently deleting it.
- When formatted text is converted to plain text, Nexio uses the icon's fallback label so the output still reads cleanly.

### Available icon tokens

#### Streaming services
| Token | Meaning |
| :--- | :--- |
| `[[icon:netflix]]` | Netflix |
| `[[icon:disneyplus]]` | Disney+ |
| `[[icon:hbo]]` | HBO Max |
| `[[icon:max]]` | Max |
| `[[icon:prime]]` | Amazon / Prime Video |
| `[[icon:appletv]]` | Apple TV+ |
| `[[icon:paramount]]` | Paramount+ |
| `[[icon:peacock]]` | Peacock |
| `[[icon:crunchyroll]]` | Crunchyroll |

#### Debrid and cloud providers
| Token | Meaning |
| :--- | :--- |
| `[[icon:realdebrid]]` | Real-Debrid |
| `[[icon:premiumize]]` | Premiumize |
| `[[icon:alldebrid]]` | AllDebrid |
| `[[icon:debridlink]]` | Debrid-Link |
| `[[icon:torbox]]` | TorBox |
| `[[icon:offcloud]]` | Offcloud |
| `[[icon:putio]]` | put.io |
| `[[icon:easydebrid]]` | EasyDebrid |
| `[[icon:debrider]]` | Debrider |
| `[[icon:pikpak]]` | PikPak |
| `[[icon:seedr]]` | Seedr |

#### Usenet providers
| Token | Meaning |
| :--- | :--- |
| `[[icon:easynews]]` | Easynews |
| `[[icon:nzbdav]]` | NzbDAV |
| `[[icon:altmount]]` | AltMount |
| `[[icon:stremionntp]]` | Stremio NNTP |
| `[[icon:stremthrunewz]]` | StremThru Newz |

#### Audio formats
| Token | Meaning |
| :--- | :--- |
| `[[icon:atmos]]` | Dolby Atmos |
| `[[icon:truehd]]` | Dolby TrueHD |
| `[[icon:ddp]]` | Dolby Digital+ |
| `[[icon:dd]]` | Dolby Digital |
| `[[icon:dts]]` | DTS |
| `[[icon:dtshd]]` | DTS-HD MA |
| `[[icon:dtsx]]` | DTS:X |
| `[[icon:stereo]]` | Stereo |

#### HDR and video badges
| Token | Meaning |
| :--- | :--- |
| `[[icon:dovi]]` | Dolby Vision |
| `[[icon:hdr10]]` | HDR10 |

#### Headline resolution badges
| Token | Meaning |
| :--- | :--- |
| `[[icon:4k]]` | 4K |
| `[[icon:2k]]` | 2K |
| `[[icon:fullhd]]` | Full HD |
| `[[icon:hd]]` | HD |
| `[[icon:sd]]` | SD |

### Practical guidance
- Use icons for information users can scan instantly, such as service branding, premium audio, HDR, and headline resolution.
- Keep them near the start of a title or the start of a short metadata line so they read like badges rather than decoration.
- Prefer one or two strong icons over a long chain of small badges.
- If you need broad compatibility outside icon-aware surfaces, include a text fallback nearby such as `{stream.audioTags::join(' • ')}`.

## Stream chip tokens
Stream chip tokens render the same chip styling used by Nexio's automatic stream badges. Place them inline inside `nameTemplate` or `descriptionTemplate`, or put them in `badgeRowTemplate` for a separate full-width badge row.

Use this syntax:

```text
[[chip:cached]]
[[chip:torrent]]
[[chip:youtube]]
[[chip:external]]
```

Available chip tokens:

| Token | Meaning |
| :--- | :--- |
| `[[chip:cached]]` | Cached / instant stream |
| `[[chip:torrent]]` | Torrent / P2P stream |
| `[[chip:youtube]]` | YouTube stream |
| `[[chip:external]]` | External player stream |

Inline example:

```text
{service.cached::istrue["[[chip:cached]] "||""]}{stream.resolution} {stream.title}
```

Separate badge row example:

```text
badgeRowTemplate:
{service.cached::istrue["[[chip:cached]]"||""]}{stream.type::=p2p[" [[chip:torrent]]"||""]}
```

If `badgeRowTemplate` is configured, Nexio treats that row as the replacement for the automatic bottom chip row. The automatic row does not appear unless the formatter recreates it with `[[chip:*]]` tokens. Inline `[[chip:*]]` tokens in `nameTemplate` or `descriptionTemplate` also suppress the automatic row.

### Universal formatter examples
The built-in `Universal` formatter currently uses icon tokens in three main places:

- Title line: headline resolution badges such as `[[icon:4k]]`, `[[icon:2k]]`, and `[[icon:fullhd]]`
- Badge line: visual and audio badges such as `[[icon:dovi]]`, `[[icon:hdr10]]`, `[[icon:atmos]]`, and `[[icon:dtshd]]`
- Provider line: filename-based platform icons such as `[[icon:netflix]]` plus service icons such as `[[icon:realdebrid]]`, `[[icon:premiumize]]`, `[[icon:torbox]]`, `[[icon:alldebrid]]`, `[[icon:easynews]]`, or `[[icon:stremthrunewz]]`

That means custom templates can mix platform and debrid badges in the same line, for example:

```text
{stream.filename::~NF["[[icon:netflix]] Netflix"||""]}{stream.filename::~NF::and::service.name::exists[" • "||""]}{service.name::~Real-Debrid["[[icon:realdebrid]] Real-Debrid"||"{service.name}"]}
```

## Variables

### `stream`
These fields are the most useful for titles, badges, and descriptions.

| Variable | Type | Description |
| :--- | :--- | :--- |
| `{stream.filename}` | String | Original filename used for parsing. |
| `{stream.folderName}` | String | Parent folder name if available. |
| `{stream.title}` | String | Parsed title. |
| `{stream.year}` | String / Number | Parsed year. |
| `{stream.seasons}` | Array | Raw season values. |
| `{stream.episodes}` | Array | Raw episode values. |
| `{stream.formattedSeasons}` | Array | Human-friendly season labels. |
| `{stream.formattedEpisodes}` | Array | Human-friendly episode labels. |
| `{stream.seasonEpisode}` | Array | Compact season and episode pair values. |
| `{stream.seasonPack}` | Boolean | Whether the stream is a season pack. |
| `{stream.size}` | Number | File size in bytes. |
| `{stream.folderSize}` | Number | Folder or package size in bytes. |
| `{stream.duration}` | Number | Duration in milliseconds. |
| `{stream.bitrate}` | Number | Bitrate in bits per second. |
| `{stream.age}` | Number / String | Age derived from provider metadata. |
| `{stream.indexer}` | String | Upstream indexer or source name. |
| `{stream.resolution}` | String | `2160p`, `1440p`, `1080p`, `720p`, etc. |
| `{stream.quality}` | String | `BluRay`, `BluRay Remux`, `WEB-DL`, `HDTV`, and similar. |
| `{stream.encode}` | String | `HEVC`, `AVC`, `AV1`, and similar. |
| `{stream.visualTags}` | Array | `DV`, `HDR10`, `HDR10+`, `IMAX`, `10bit`, and similar. |
| `{stream.audioTags}` | Array | `Atmos`, `TrueHD`, `DTS:X`, `DTS-HD MA`, `DD+`, and similar. |
| `{stream.audioChannels}` | Array | `7.1`, `5.1`, `2.0`, and similar. |
| `{stream.languages}` | Array | All audio languages. |
| `{stream.languageCodes}` | Array | ISO language codes. |
| `{stream.languageEmojis}` | Array | Language flag emojis. |
| `{stream.subtitles}` | Array | Subtitle languages. |
| `{stream.subtitleCodes}` | Array | Subtitle language codes. |
| `{stream.subtitleEmojis}` | Array | Subtitle flag emojis. |
| `{stream.releaseGroup}` | String | Scene or group tag parsed from the filename. |
| `{stream.message}` | String | Provider message or status line. |
| `{stream.type}` | String | `debrid`, `p2p`, `usenet`, `http`, `live`, `youtube`, or `external`. |
| `{stream.private}` | Boolean | Private tracker or private source flag. |
| `{stream.proxied}` | Boolean | Whether the stream is proxied. |
| `{stream.library}` | Boolean | Whether the stream already exists in your library. |
| `{stream.infoHash}` | String | Torrent hash, when available. |
| `{stream.videoHash}` | String | Video hash when available. |
| `{stream.seeders}` | Number | Seeder count for P2P streams. |
| `{stream.repack}` | Boolean | Repack or proper flag. |
| `{stream.regraded}` | Boolean | Regraded visual tag. |
| `{stream.unrated}` | Boolean | Unrated release flag. |
| `{stream.uncensored}` | Boolean | Uncensored release flag. |
| `{stream.upscaled}` | Boolean | Upscaled release flag. |
| `{stream.seadex}` | Boolean | SeaDex curated match. |
| `{stream.seadexBest}` | Boolean | SeaDex best-match flag. |
| `{stream.regexMatched}` | String | Best matching regex label. |
| `{stream.rankedRegexMatched}` | Array | Ordered regex match labels. |
| `{stream.seMatched}` | String | Best matching stream-expression label. |
| `{stream.rseMatched}` | Array | Ordered stream-expression match labels. |
| `{stream.regexScore}` | Number | Raw regex score. |
| `{stream.nRegexScore}` | Number | Normalized regex score. |
| `{stream.seScore}` | Number | Raw stream-expression score. |
| `{stream.nSeScore}` | Number | Normalized stream-expression score. |

### `service`
| Variable | Type | Description |
| :--- | :--- | :--- |
| `{service.id}` | String | Internal service ID. |
| `{service.name}` | String | Full service name like `Real-Debrid`. |
| `{service.shortName}` | String | Compact service name like `RD`. |
| `{service.cached}` | Boolean | Whether the stream is cached or instant. |

### `addon`
| Variable | Type | Description |
| :--- | :--- | :--- |
| `{addon.name}` | String | Addon or provider display name. |

### `metadata`
| Variable | Type | Description |
| :--- | :--- | :--- |
| `{metadata.title}` | String | Queried content title. |
| `{metadata.year}` | Number | Queried content year. |
| `{metadata.runtime}` | Number | Runtime in minutes. |
| `{metadata.episodeRuntime}` | Number | Episode runtime in minutes. |
| `{metadata.queryType}` | String | `movie`, `series`, or similar. |

### User-filtered helpers
These helpers already respect the user’s language preferences.

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
| `{tools.removeLine}` | Removes the whole rendered line when used in a branch that should collapse. |

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

### Numeric and string comparisons
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

### Examples
```text
{stream.uLanguages::exists::and::stream.releaseGroup::exists[" • "||""]}
```

```text
{stream.filename::~NF::or::stream.filename::~DSNP::or::stream.filename::~AMZN::and::stream.releaseGroup::exists[" • "||""]}
```

```text
{stream.seadexBest::istrue[" 🏆 BEST"||""]}{stream.seadex::istrue::and::stream.seadexBest::isfalse[" 🥈 ALT"||""]}
```

## Practical notes
- Prefer `::exists[...]` around optional values so empty fields do not leave awkward spacing behind.
- Use `tools.removeLine` when you want a whole line to disappear if a branch does not match.
- Keep formatter logic readable by building it one condition at a time and validating with the preview.
