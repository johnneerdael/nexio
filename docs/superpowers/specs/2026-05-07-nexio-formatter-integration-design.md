# Nexio Universal-Formatter Integration for Torii / Nagare — Design

**Date:** 2026-05-07
**Repos:** `nexio` (Android), `nexio-torii`, `nexio-nagare`
**Status:** Drafted, awaiting user review before plan generation.

## 1. Goal

Make Nexio Torii and Nexio Nagare integrate seamlessly with Nexio's universal stream formatter. Both addons today emit ad-hoc `name`/`description` strings that Nexio's `AioStrictStreamParser` can only partially extract because they don't fit the existing presets (`GENERIC`, `STREMTHRU`, `TORRENTIO`, `WEBSTREAMR`).

After this work:
- Each addon ships a richer, parser-friendly stream shape that bundles every field its data sources expose.
- Each shape is unique to its addon — Torii surfaces torrent / debrid / batch metadata; Nagare surfaces provider / server / track / canonical metadata.
- Nexio gains two new parser presets (`NEXIO_TORII`, `NEXIO_NAGARE`) with dedicated parser branches that pull every emitted field into `ParsedStreamInfo`.
- Auto-detect by manifest ID at install time so users never pick a preset manually for our addons.
- Custom drawable per addon, rendered by `StreamBadgeSupport` based on the resolved preset.

The end-user effect is that both addons feel like first-party Nexio sources with rich, beautifully-rendered stream rows.

## 2. Non-Goals

- Reformatting third-party addons. Nexio's existing presets cover them.
- A generic "Nexio family" auto-detect that matches any future namespace. Only the two known IDs are auto-promoted; future additions extend the enum + drawable + auto-detect map in one PR.
- URL-fetched icons, manifest-extension fields, or other dynamic-icon plumbing. Bundled drawables only (Approach 1 from brainstorming).
- Changes to `nexio-web`. The web app uses a separate formatter pipeline that is out of scope here.
- Backwards-incompatible changes to other parser presets — the new branches and `ParsedStreamInfo` extensions are additive.
- Stream-bytes proxying or any change to how the player fetches the actual media. Same `proxyHeaders` flow as today.

## 3. Locked Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | **Approach 1: bundled assets + auto-detect** | YAGNI — two addons today, both under our control, stable IDs. Future addons extend in one PR. |
| D2 | **Each addon emits its own native shape** (no forced uniformity) | Torii has data Nagare doesn't (seeders/size/age) and vice versa (provider/server). Forcing uniformity hides truthful signal. |
| D3 | **Reuse existing parser-known emoji conventions** where they map (`📄 📁 📦 💾 👥 📅 ⚡ ☁️ 🔍`), introduce new ones for unique fields (`🎯 🎬 📺 🆔 🌊 ⛩`) | Existing presets recognise the shared markers. Other parsers ignore the new ones cleanly. |
| D4 | **Nexio app holds the parser logic + drawables** | Addons should not need to know about Nexio internals. Addons emit standard Stremio JSON; Nexio recognises the addon by manifest ID and applies the matching preset. |
| D5 | **Each addon has its own internal parser/enrichment layer** before formatting | Source data (Nyaa torrent / provider response) feeds an `EnrichedStream` blob; the formatter projects that blob to Nexio's expected shape. Cleanly testable. |
| D6 | **Auto-detect is overridable** | A user can still manually set the preset to GENERIC if they want minimal parsing. The auto-detect only fires when the user picks GENERIC at install (the default). |

## 4. Architecture

```
                                   ┌───────────────────────────────┐
manifest.json {id: …}    ──────►   │ Nexio app: AddonRepository    │
                                   │   addAddon(url, parserPreset) │
                                   │   if GENERIC + id matches →   │
                                   │   set NEXIO_TORII | NAGARE    │
                                   └────┬──────────────────────────┘
                                        │
                                        ▼
                            stream → AioStrictStreamParser.parse()
                                        │
                                  branches on parserPreset:
                                  ├── NEXIO_TORII   → torii-specific extraction
                                  ├── NEXIO_NAGARE  → nagare-specific extraction
                                  ├── STREMTHRU     → existing
                                  ├── TORRENTIO     → existing
                                  ├── WEBSTREAMR    → existing
                                  └── GENERIC       → existing
                                        │
                                        ▼
                                ParsedStreamInfo  (now with optional
                                matchInfo, episodeTitle, crossIds[])
                                        │
                                        ▼
                                Stream-list rendering
                                StreamBadgeSupport.providerIconFor() →
                                ic_addon_nexiotorii / ic_addon_nexionagare
```

Three Kotlin touch-points:
- `AddonParserPreset` enum (extend by 2)
- `AioStrictStreamParser` (two new branches + helper line-parsers)
- `StreamBadgeSupport` (icon mapping)

Two web-app touch-points:
- `nexio-torii/lib/stream-formatter/` (new module — torii enrichment + formatter)
- `nexio-nagare/lib/stream-formatter/` (new module — nagare enrichment + formatter)

Two new drawable assets (plus density variants).

## 5. Torii — In-Addon Parser & Emission

### 5.1 EnrichedTorrent (intermediate representation)

Built per torrent candidate after canon-gate + debrid resolution succeeds. Sourced from: Nyaa RSS row, `lib/catalog/release-parser` (PTT wrapper), debrid availability response, `lib/normalizer/match` score, `getAnimeMeta` canonical.

```js
{
  source: {
    rawTitle, infoHash, sizeBytes, seeders, leechers,
    indexer,                                         // "Nyaa.si" | "AnimeTosho" | "TokyoTosho"
    pubDate, ageHours,
    fileExtension, container                          // ".mkv", "mkv"
  },
  parsed: {
    title, year, seasons[], episodes[],
    isSeasonPack, episodeRange,                       // {first: 1, last: 1100, total: 1100}
    resolution, quality, encode,                      // "1080p", "BluRay", "HEVC"
    visualTags[],                                     // ["DV", "HDR10", "10bit"]
    audioTags[],                                      // ["DTS", "FLAC"]
    audioChannels[],                                  // ["5.1"]
    languages[], subtitles[],                         // ["JPN", "ENG"], ["ENG", "JPN"]
    releaseGroup,                                     // "SubsPlease"
    network, edition,                                 // "Crunchyroll", "Director's Cut"
    dubbed, subbed, repack, regraded,
    uncensored, unrated, upscaled
  },
  canonical: {
    anilistId, malId, anidbId, kitsuId, imdbId,
    mainTitle, englishTitle, year, format,
    episodeCount, episodeTitle, episodeAirDate,       // from Jikan streamingEpisodes
    runtimeMinutes
  },
  debrid: {
    serviceCode, isCached,
    selectedFile: { name, sizeBytes, index },
    archiveLayout                                     // {totalFiles, mediaFiles, isCompletePack}
  },
  match: { score, confidence, reasons[] }             // canon-gate output
}
```

### 5.2 Stremio emission

```
name (3 lines):
  ${parsed.resolution} ${parsed.quality} ${parsed.encode} · ${visualTags.join(" ")} · ${audioChannels[0]}
  ${cacheGlyph} ${serviceCode} · 🎙 ${parsed.languages.join("+")} · 📝 ${parsed.subtitles.join(",")}
  ⛩ Torii

description (5-8 lines, fields conditional):
  📄 ${selectedFile.name || rawTitle}
  📁 ${parsed.folderName}                                        # only when isSeasonPack
  💾 ${humanSize(selectedFile.sizeBytes)} · 📦 ${humanSize(sizeBytes)} · 👥 ${seeders} · 📅 ${humanAge(ageHours)}
  📡 ${indexer} · ${releaseGroup}${network ? " · " + network : ""}
  🎬 ${canonical.englishTitle || mainTitle} · ${year} · ${format}${episodeCount ? " · " + episodeCount + "ep" : ""}
  📺 S${season}E${episode} · "${episodeTitle}"                    # only when episodeTitle present
  🎯 ${confidence} (${score}) · ${reasons.join("+")}
  🆔 anilist:${anilistId} · mal:${malId} · kitsu:${kitsuId} · imdb:${imdbId}
```

`cacheGlyph`: ⚡ when cached, ☁️ when uncached, 📡 for P2P. `serviceCode`: RD/PM/AD/DL/TB/ED/PK or `P2P`. P2P branch shares the same body but skips `📡` indexer source line and replaces service code.

## 6. Nagare — In-Addon Parser & Emission

### 6.1 EnrichedDirectStream (intermediate representation)

Built per provider stream. Provider getStreams() outputs are extended to carry richer fields per source, then merged with canonical + match info.

```js
{
  source: {
    providerId,                                  // "gojo", "animepahe", "anizone", "animenosub", "onetwothreeanime"
    providerDisplay,                             // "Gojo", "AnimePahe", "Anizone", "Animenosub", "123anime"
    providerHost,                                // "animetsu.live", "animepahe.pw", ...
    serverFamily, serverInstance,                // "pahe", "kite", "kir"
    cdnHost,                                     // "mega-cloud.top", "vault-04.uwucdn.top"
    container,                                   // "HLS" | "MP4"
    quality,                                     // "1080p", "720p", "auto", "multi-quality"
    bitrate, duration,                           // when available
    isHardSub                                    // true if subs are baked in
  },
  track: {
    kind,                                        // "sub" | "dub" | "raw"
    audioLanguage,                               // "JPN" | "ENG" | "ESP" (best-effort)
    subtitleTracks: [{src, label, lang, format}]
  },
  canonical: {
    anilistId, malId, anidbId, kitsuId, imdbId,
    mainTitle, englishTitle, year, format,
    episodeCount, episodeTitle, episodeAirDate,
    season, episode, runtimeMinutes
  },
  match: { score, confidence, reasons[], source }   // source: "override" | "cache" | "match"
}
```

### 6.2 Stremio emission

```
name (3 lines):
  ${quality} · ${container}${bitrate ? " · " + humanBitrate(bitrate) : ""}
  🌊 ${providerDisplay} · ${serverInstance} · ${trackKind === "dub" ? "🎙 DUB" : "📝 SUB"}
  Direct stream

description (5-8 lines):
  📄 [${providerDisplay}] ${canonical.englishTitle} - S${season}E${episode} [${quality} ${container}${dubTag}].${ext}
  📡 ${providerDisplay} · ${providerHost} · server: ${serverInstance} (${cdnHost})
  🎬 ${canonical.englishTitle} · ${year} · ${format} · ${episodeCount}ep
  📺 S${season}E${episode} · "${episodeTitle}"                    # only when episodeTitle present
  🎙 ${audioLanguage}${audioChannels ? " · " + audioChannels : ""} · 📝 ${subtitleLangs.join(", ")}
  🌐 Direct stream · no debrid required
  🎯 ${confidence} (${score}) · ${reasons.join("+")}
  🆔 anilist:${anilistId} · mal:${malId} · kitsu:${kitsuId} · imdb:${imdbId}
```

The `📄` line is a synthetic filename whose only purpose is to feed PTT a parseable string — it's not a real file on disk. PTT extracts title/season/episode/resolution/group; the dedicated parser branch supplements with the unique fields from the labeled lines below.

## 7. Nexio Parser Branches

### 7.1 Enum extension

```kotlin
// app/src/main/java/com/nexio/tv/domain/model/Addon.kt
enum class AddonParserPreset {
    GENERIC, STREMTHRU, TORRENTIO, WEBSTREAMR,
    NEXIO_TORII, NEXIO_NAGARE
}
```

`AddonManagerScreen.label()` and `AddonManagerScreen.next()` extended for the cycle UI.

### 7.2 Auto-detect on install

```kotlin
// AddonRepository.addAddon (or AddonManagerViewModel.installAddon)
fun resolveAutoPreset(manifest: StremioManifest, userPick: AddonParserPreset): AddonParserPreset {
    if (userPick != AddonParserPreset.GENERIC) return userPick   // honour explicit user override
    val id = manifest.id?.lowercase().orEmpty()
    return when (id) {
        "org.community.nexiotorii"  -> AddonParserPreset.NEXIO_TORII
        "org.community.nexionagare" -> AddonParserPreset.NEXIO_NAGARE
        else -> AddonParserPreset.GENERIC
    }
}
```

### 7.3 New branches in AioStrictStreamParser

```kotlin
when (stream.addonParserPreset) {
    AddonParserPreset.NEXIO_TORII   -> applyNexioToriiBranch(parsedFile, stream, name, description)
    AddonParserPreset.NEXIO_NAGARE  -> applyNexioNagareBranch(parsedFile, stream, name, description)
    /* existing branches unchanged */
}
```

Both branches share these new line-parsers:

```kotlin
// 🎯 HIGH (152) · year+title+ep+format
private fun parseMatchLine(description: String): MatchInfo? = ...

// 🎬 ONE PIECE · 1999 · TV · 1100ep
private fun parseCanonicalLine(description: String): CanonicalSummary? = ...

// 📺 S1E1100 · "The End of the Great Pirate Era"
private fun parseEpisodeTitleLine(description: String): String? = ...

// 🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629
private fun parseCrossIdsLine(description: String): Map<String, String> = ...
```

### 7.4 ParsedStreamInfo extension

```kotlin
@Immutable
data class MatchInfo(val score: Int?, val confidence: String, val reasons: List<String>)

data class ParsedStreamInfo(
    /* … existing fields … */
    val matchInfo: MatchInfo? = null,
    val episodeTitle: String? = null,
    val crossIds: Map<String, String> = emptyMap()
)
```

All three default to null/empty so other presets are unaffected.

### 7.5 StreamBadgeSupport drawable mapping

```kotlin
fun providerIconFor(stream: Stream): Int? = when (stream.addonParserPreset) {
    AddonParserPreset.NEXIO_TORII   -> R.drawable.ic_addon_nexiotorii
    AddonParserPreset.NEXIO_NAGARE  -> R.drawable.ic_addon_nexionagare
    else -> existingLogic(stream)
}
```

## 8. Asset Pipeline

```
Source artwork (user-supplied 512x512):
  /Users/jneerdael/Downloads/torii.png   (red torii gate, 121 KB)
  /Users/jneerdael/Downloads/nagare.png  (blue wave, 187 KB)

Build-time resize → density variants under app/src/main/res/:
  drawable-mdpi/ic_addon_nexiotorii.png    (24x24)
  drawable-hdpi/                            (36x36)
  drawable-xhdpi/                           (48x48)
  drawable-xxhdpi/                          (72x72)
  drawable-xxxhdpi/                         (96x96)
  …same for ic_addon_nexionagare.png
```

Existing `ic_wyzie_*` drawables follow the same pattern; we mirror it.

## 9. Testing

### 9.1 Per-addon (Node)

**`nexio-torii/tests/stream-formatter.test.js`** — feed a frozen Nyaa RSS row + AniList canonical + debrid-cached response → assert the formatted name/description match a known string. Covers cached, uncached, P2P, season-pack, single-episode-from-pack.

**`nexio-nagare/tests/stream-formatter.test.js`** — feed a frozen provider response per provider (123anime, Gojo, AnimePahe, etc.) + canonical → assert formatted strings match expected. Covers ID-exact-match path, override path, and gate-survivor path.

Both addons add a round-trip property test: every field in the `EnrichedStream` blob must be recoverable from the emitted `name`/`description` strings (no information loss).

### 9.2 Nexio parser (Kotlin)

**`AioStrictStreamParserNexioToriiTest`** — feed each of the per-addon fixture strings, assert every `ParsedStreamInfo` field has the expected value (filename, size, seeders, age, indexer, releaseGroup, languages, audioChannels, visualTags, serviceId, isCached, matchInfo, episodeTitle, crossIds, etc.).

**`AioStrictStreamParserNexioNagareTest`** — same, for nagare-shaped streams.

**`AddonRepositoryAutoDetectTest`** — manifest ID `org.community.nexiotorii` + GENERIC user pick → resolves to NEXIO_TORII. Same for nagare. User pick of any non-GENERIC preset is honoured.

**`StreamBadgeSupportTest`** — a stream tagged NEXIO_TORII → `providerIconFor` returns `R.drawable.ic_addon_nexiotorii`. Same for nagare. Other presets return existing-logic result.

### 9.3 Cross-repo round-trip

A small fixture suite kept in `nexio/docs/superpowers/specs/2026-05-07-fixtures/`:
- `torii-cached-realdebrid.json` — addon emission + expected ParsedStreamInfo
- `nagare-gojo-id-exact.json` — same shape
- 3-5 more covering the realistic variants

Both the addon test (asserts emission matches the JSON's `emitted` field) and the Nexio test (asserts parser of the JSON's `emitted` matches the JSON's `parsed` field) read these fixtures. If the contract drifts on either side, both test suites fail at the same fixture.

## 10. Open Items

None. Approach 1 + per-addon enrichment + bundled drawables + auto-detect by manifest ID are all locked. Implementation plan to follow.

## 11. Implementation Order

A subsequent plan (writing-plans skill output) will sequence the work. Rough phasing for context:

1. **Per-addon enrichment + emission** (independent in each repo) — can ship without Nexio changes; existing GENERIC parser will handle gracefully (no regression)
2. **Nexio enum + auto-detect + drawable assets** (no parser branches yet — preset is set but parser still uses GENERIC) — additive, no behaviour change
3. **Nexio parser branches + ParsedStreamInfo extensions** — wires the dedicated extraction
4. **Cross-repo round-trip fixtures** — prove the contract holds end-to-end
5. **Release notes** — both addons + Nexio

Each phase is independently shippable.
