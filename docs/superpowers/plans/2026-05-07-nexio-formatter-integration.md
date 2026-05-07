# Nexio Universal-Formatter Integration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Nexio Torii and Nexio Nagare integrate seamlessly with Nexio's universal stream formatter — each addon emits its native rich data shape, Nexio recognises them by manifest ID, applies dedicated parser branches, and renders custom drawable badges.

**Architecture:** Each addon gains a `lib/stream-formatter/` module with an enricher (collects every field its sources expose) + a formatter (projects to parser-friendly Stremio name/description strings). Nexio adds two `AddonParserPreset` entries with auto-detect on manifest ID, dedicated parser branches for each shape, and per-addon drawables.

**Tech Stack:** Node.js (addons, Express + axios + cheerio), Kotlin (Nexio Android, Compose + Hilt), Stremio addon protocol.

**Repos:** `nexio-torii`, `nexio-nagare`, `nexio` — three repos, plan moves through each in dependency order.

---

## File Structure

### nexio-torii
```
lib/stream-formatter/
  index.js                  ← entry: { enrichTorrent, formatToriiStream }
  enrich-torrent.js         ← Nyaa row + canonical + debrid + match → EnrichedTorrent
  format-torrent.js         ← EnrichedTorrent → { name, description } strings
  human-format.js           ← humanSize, humanAge, languageFlag, glyph helpers
lib/stream-builder.js       ← MODIFY: buildDebridStreams uses new formatter
addon.js                    ← MODIFY: pass match info + canonical to stream-builder
tests/stream-formatter-torii.test.js
tests/fixtures/torii/       ← frozen Nyaa + canonical + debrid samples
```

### nexio-nagare
```
lib/stream-formatter/
  index.js                  ← entry: { enrichDirectStream, formatNagareStream }
  enrich-direct.js          ← provider stream + canonical + match → EnrichedDirectStream
  format-direct.js          ← EnrichedDirectStream → { name, description } strings
  human-format.js           ← shared helpers (subset of torii's; could be extracted later)
lib/stream-builder.js       ← MODIFY: buildProviderStreams uses new formatter
addon.js                    ← MODIFY: pass match info to stream-builder
lib/providers/index.js      ← MODIFY: each provider entry adds { displayHost, language } static fields
tests/stream-formatter-nagare.test.js
tests/fixtures/nagare/      ← frozen provider responses
```

### nexio
```
app/src/main/res/drawable/
  ic_addon_nexiotorii.png       ← from /Users/jneerdael/Downloads/torii.png
  ic_addon_nexionagare.png      ← from /Users/jneerdael/Downloads/nagare.png
app/src/main/java/com/nexio/tv/
  domain/model/Addon.kt                                 ← MODIFY: extend AddonParserPreset enum
  core/stream/StreamPresentationModels.kt               ← MODIFY: ParsedStreamInfo + new MatchInfo
  core/stream/AioStrictStreamParser.kt                  ← MODIFY: 2 new branches + 4 line-parsers
  data/repository/AddonRepository.kt (or similar)       ← MODIFY: auto-detect on install
  ui/screens/addon/AddonManagerScreen.kt                ← MODIFY: label() + next() for new presets
  ui/components/StreamBadgeSupport.kt                   ← MODIFY: drawable mapping
app/src/test/java/com/nexio/tv/
  core/stream/AioStrictStreamParserNexioToriiTest.kt    ← CREATE
  core/stream/AioStrictStreamParserNexioNagareTest.kt   ← CREATE
  data/repository/AddonRepositoryAutoDetectTest.kt      ← CREATE
docs/superpowers/specs/2026-05-07-fixtures/             ← CREATE: shared cross-repo fixtures
  torii-cached-realdebrid.json
  torii-uncached-batch.json
  torii-p2p.json
  nagare-gojo-id-exact.json
  nagare-anizone-override.json
  nagare-animenosub-canon-gate.json
```

---

## Phase A — Nagare addon: enrichment + formatter

### Task 1: human-format helpers (Nagare)

**Files:**
- Create: `nexio-nagare/lib/stream-formatter/human-format.js`
- Test: `nexio-nagare/tests/stream-formatter-human.test.js`

- [ ] **Step 1: Write the failing tests**

```js
// nexio-nagare/tests/stream-formatter-human.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { humanQuality, languageFlag, languageCode3, dubGlyph, containerFromUrl, cdnHostFromUrl } = require("../lib/stream-formatter/human-format");

test("humanQuality normalises common forms", () => {
    assert.equal(humanQuality("1080p"), "1080p");
    assert.equal(humanQuality("auto"), "auto");
    assert.equal(humanQuality("multi-quality"), "multi-quality");
    assert.equal(humanQuality(null), "auto");
});

test("languageFlag maps ENG/JPN/etc to flag emoji", () => {
    assert.equal(languageFlag("ENG"), "🇬🇧");
    assert.equal(languageFlag("JPN"), "🇯🇵");
    assert.equal(languageFlag("ESP"), "🇪🇸");
    assert.equal(languageFlag("eng"), "🇬🇧");
    assert.equal(languageFlag(null), "🌐");
});

test("languageCode3 normalises to ISO-639-2", () => {
    assert.equal(languageCode3("English"), "ENG");
    assert.equal(languageCode3("ja"), "JPN");
    assert.equal(languageCode3(null), "UND");
});

test("dubGlyph returns sub vs dub markers", () => {
    assert.equal(dubGlyph(true), "🎙 DUB");
    assert.equal(dubGlyph(false), "📝 SUB");
    assert.equal(dubGlyph(null), "📝 SUB");
});

test("containerFromUrl detects HLS vs MP4", () => {
    assert.equal(containerFromUrl("https://cdn.example.com/master.m3u8"), "HLS");
    assert.equal(containerFromUrl("https://cdn.example.com/video.mp4?t=1"), "MP4");
    assert.equal(containerFromUrl("https://cdn.example.com/video.m3u8?signature=x"), "HLS");
    assert.equal(containerFromUrl(""), "HLS");
});

test("cdnHostFromUrl extracts the bare hostname", () => {
    assert.equal(cdnHostFromUrl("https://cdn.example.com/x.m3u8"), "cdn.example.com");
    assert.equal(cdnHostFromUrl("https://vault-04.uwucdn.top/stream/x"), "vault-04.uwucdn.top");
    assert.equal(cdnHostFromUrl("invalid"), "unknown");
});
```

- [ ] **Step 2: Run tests, verify they fail**

```bash
cd /Users/jneerdael/Scripts/stremio-addons/nexio-nagare
node --test tests/stream-formatter-human.test.js 2>&1 | tail -3
# Expected: Cannot find module '../lib/stream-formatter/human-format'
```

- [ ] **Step 3: Write the implementation**

```js
// nexio-nagare/lib/stream-formatter/human-format.js
const FLAG_BY_CODE = {
    ENG: "🇬🇧", JPN: "🇯🇵", ESP: "🇪🇸", FRE: "🇫🇷", GER: "🇩🇪",
    ITA: "🇮🇹", POR: "🇵🇹", RUS: "🇷🇺", CHI: "🇨🇳", KOR: "🇰🇷",
    HIN: "🇮🇳", ARA: "🇸🇦", DUT: "🇳🇱", POL: "🇵🇱", TUR: "🇹🇷",
    IND: "🇮🇩", VIE: "🇻🇳"
};

const CODE_BY_LABEL = {
    english: "ENG", "en-us": "ENG", "en-gb": "ENG", en: "ENG",
    japanese: "JPN", jp: "JPN", ja: "JPN",
    spanish: "ESP", es: "ESP",
    french: "FRE", fr: "FRE",
    german: "GER", de: "GER",
    italian: "ITA", it: "ITA",
    portuguese: "POR", pt: "POR", "pt-br": "POR",
    russian: "RUS", ru: "RUS",
    chinese: "CHI", "zh-cn": "CHI", "zh-tw": "CHI", zh: "CHI",
    korean: "KOR", ko: "KOR",
    hindi: "HIN", hi: "HIN",
    arabic: "ARA", ar: "ARA",
    dutch: "DUT", nl: "DUT",
    polish: "POL", pl: "POL",
    turkish: "TUR", tr: "TUR",
    indonesian: "IND", id: "IND",
    vietnamese: "VIE", vi: "VIE"
};

function humanQuality(q) {
    if (!q) return "auto";
    const t = String(q).trim().toLowerCase();
    if (t === "auto" || t === "multi-quality") return t;
    return String(q);
}

function languageCode3(label) {
    if (!label) return "UND";
    const k = String(label).trim().toLowerCase();
    return CODE_BY_LABEL[k] || k.slice(0, 3).toUpperCase();
}

function languageFlag(label) {
    if (!label) return "🌐";
    const code = /^[A-Z]{3}$/.test(label) ? label : languageCode3(label);
    return FLAG_BY_CODE[code] || "🌐";
}

function dubGlyph(isDub) {
    return isDub === true ? "🎙 DUB" : "📝 SUB";
}

function containerFromUrl(url) {
    const s = String(url || "").toLowerCase();
    if (s.includes(".m3u8")) return "HLS";
    if (s.includes(".mp4")) return "MP4";
    return "HLS";
}

function cdnHostFromUrl(url) {
    try { return new URL(url).hostname; } catch (e) { return "unknown"; }
}

module.exports = {
    humanQuality, languageFlag, languageCode3, dubGlyph,
    containerFromUrl, cdnHostFromUrl
};
```

- [ ] **Step 4: Run tests, verify they pass**

```bash
node --test tests/stream-formatter-human.test.js 2>&1 | tail -3
# Expected: pass 6 / fail 0
```

- [ ] **Step 5: Commit**

```bash
cd /Users/jneerdael/Scripts/stremio-addons/nexio-nagare
git add lib/stream-formatter/human-format.js tests/stream-formatter-human.test.js
git commit -m "feat(nagare): human-format helpers for stream-formatter"
```

---

### Task 2: enrich-direct module (Nagare)

**Files:**
- Create: `nexio-nagare/lib/stream-formatter/enrich-direct.js`
- Modify: `nexio-nagare/lib/providers/{onetwothreeanime,anizone,animenosub,animepahe,gojo}/index.js` (add `displayHost` field)
- Test: `nexio-nagare/tests/stream-formatter-enrich-direct.test.js`

- [ ] **Step 1: Add displayHost to provider entries**

```js
// nexio-nagare/lib/providers/onetwothreeanime/index.js
module.exports = {
    id: "onetwothreeanime",
    displayName: "123anime",
    displayHost: "123anime.la",
    language: "en",
    dub: "both",
    search, details: getDetails123anime, getStreams: getStreams123anime
};
```

Apply to all five providers (anizone → "anizone.to", animenosub → "animenosub.to", animepahe → "animepahe.pw", gojo → "animetsu.live"). Read each `index.js` first, add the field next to `displayName`.

- [ ] **Step 2: Write the failing test**

```js
// nexio-nagare/tests/stream-formatter-enrich-direct.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { enrichDirectStream } = require("../lib/stream-formatter/enrich-direct");

const PROVIDER = { id: "gojo", displayName: "Gojo", displayHost: "animetsu.live", dub: "both" };
const CANONICAL = {
    anilist: "21", mal: "21", anidb: "69", kitsu: "12", imdb: "tt0388629",
    mainTitle: "ONE PIECE", englishTitle: "ONE PIECE", year: 1999, format: "TV",
    episodeCount: 1100, synonyms: []
};
const PROVIDER_STREAM = {
    url: "https://mega-cloud.top/proxy/oppai/pahe/abc123",
    server: "Gojo/pahe", quality: "1080p", dub: false,
    headers: { Referer: "https://animetsu.live/" },
    subtitles: [{ src: "https://x/eng.vtt", label: "English" }]
};
const MATCH = { score: 200, confidence: "HIGH", reasons: ["+200 anilist_id_exact (21)"], source: "match" };

test("enrichDirectStream gathers source + track + canonical + match", () => {
    const out = enrichDirectStream({
        provider: PROVIDER,
        providerStream: PROVIDER_STREAM,
        canonical: CANONICAL,
        episode: 1,
        season: 1,
        match: MATCH
    });
    assert.equal(out.source.providerId, "gojo");
    assert.equal(out.source.providerDisplay, "Gojo");
    assert.equal(out.source.providerHost, "animetsu.live");
    assert.equal(out.source.serverFamily, "Gojo");
    assert.equal(out.source.serverInstance, "pahe");
    assert.equal(out.source.cdnHost, "mega-cloud.top");
    assert.equal(out.source.container, "HLS");
    assert.equal(out.source.quality, "1080p");
    assert.equal(out.track.kind, "sub");
    assert.equal(out.track.audioLanguage, "JPN"); // sub default
    assert.equal(out.track.subtitleTracks.length, 1);
    assert.equal(out.track.subtitleTracks[0].lang, "ENG");
    assert.equal(out.canonical.anilistId, "21");
    assert.equal(out.canonical.season, 1);
    assert.equal(out.canonical.episode, 1);
    assert.equal(out.match.confidence, "HIGH");
    assert.equal(out.match.score, 200);
});

test("enrichDirectStream marks dub when providerStream.dub=true", () => {
    const out = enrichDirectStream({
        provider: PROVIDER,
        providerStream: { ...PROVIDER_STREAM, dub: true },
        canonical: CANONICAL,
        episode: 1, season: 1, match: MATCH
    });
    assert.equal(out.track.kind, "dub");
    assert.equal(out.track.audioLanguage, "ENG");
});

test("enrichDirectStream handles server with no slash (single-token)", () => {
    const out = enrichDirectStream({
        provider: PROVIDER,
        providerStream: { ...PROVIDER_STREAM, server: "raw" },
        canonical: CANONICAL,
        episode: 1, season: 1, match: MATCH
    });
    assert.equal(out.source.serverFamily, "Gojo");
    assert.equal(out.source.serverInstance, "raw");
});
```

- [ ] **Step 3: Run, verify failure**

```bash
node --test tests/stream-formatter-enrich-direct.test.js 2>&1 | tail -3
# Expected: Cannot find module
```

- [ ] **Step 4: Implement**

```js
// nexio-nagare/lib/stream-formatter/enrich-direct.js
const { containerFromUrl, cdnHostFromUrl, languageCode3 } = require("./human-format");

function splitServer(provider, server) {
    const raw = String(server || "").trim();
    if (raw.includes("/")) {
        const [family, instance] = raw.split("/", 2);
        return { family: family.trim() || provider.displayName, instance: instance.trim() };
    }
    return { family: provider.displayName, instance: raw || provider.displayName.toLowerCase() };
}

function enrichDirectStream({ provider, providerStream, canonical, episode, season, match }) {
    const { family, instance } = splitServer(provider, providerStream.server);
    const isDub = providerStream.dub === true;
    const subs = Array.isArray(providerStream.subtitles) ? providerStream.subtitles : [];

    return {
        source: {
            providerId: provider.id,
            providerDisplay: provider.displayName,
            providerHost: provider.displayHost || provider.id,
            serverFamily: family,
            serverInstance: instance,
            cdnHost: cdnHostFromUrl(providerStream.url),
            container: containerFromUrl(providerStream.url),
            quality: providerStream.quality || "auto",
            bitrate: Number.isFinite(providerStream.bitrate) ? providerStream.bitrate : null,
            duration: Number.isFinite(providerStream.duration) ? providerStream.duration : null,
            isHardSub: subs.length === 0
        },
        track: {
            kind: isDub ? "dub" : "sub",
            audioLanguage: isDub ? "ENG" : "JPN",
            subtitleTracks: subs.map(s => ({
                src: s.src,
                label: s.label || "Unknown",
                lang: languageCode3(s.label || ""),
                format: (s.src || "").match(/\.(vtt|srt|ass|ssa)/i)?.[1]?.toLowerCase() || "vtt"
            }))
        },
        canonical: {
            anilistId: canonical.anilist || null,
            malId: canonical.mal || null,
            anidbId: canonical.anidb || null,
            kitsuId: canonical.kitsu || null,
            imdbId: canonical.imdb || null,
            mainTitle: canonical.mainTitle || null,
            englishTitle: canonical.englishTitle || canonical.mainTitle || null,
            year: canonical.year || null,
            format: canonical.format || null,
            episodeCount: canonical.episodeCount || null,
            episodeTitle: canonical.episodeTitle || null,
            episodeAirDate: canonical.episodeAirDate || null,
            season: season,
            episode: episode,
            runtimeMinutes: canonical.runtimeMinutes || null
        },
        match: {
            score: match?.score ?? null,
            confidence: match?.confidence || "UNKNOWN",
            reasons: Array.isArray(match?.reasons) ? match.reasons : [],
            source: match?.source || "match"
        }
    };
}

module.exports = { enrichDirectStream };
```

- [ ] **Step 5: Run tests, verify pass**

```bash
node --test tests/stream-formatter-enrich-direct.test.js tests/stream-formatter-human.test.js 2>&1 | tail -3
# Expected: pass 9 / fail 0
```

- [ ] **Step 6: Commit**

```bash
git add lib/providers/*/index.js lib/stream-formatter/enrich-direct.js tests/stream-formatter-enrich-direct.test.js
git commit -m "feat(nagare): enrich-direct module + displayHost on provider entries"
```

---

### Task 3: format-direct module (Nagare)

**Files:**
- Create: `nexio-nagare/lib/stream-formatter/format-direct.js`
- Create: `nexio-nagare/lib/stream-formatter/index.js`
- Test: `nexio-nagare/tests/stream-formatter-format-direct.test.js`

- [ ] **Step 1: Failing test**

```js
// nexio-nagare/tests/stream-formatter-format-direct.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { formatNagareStream } = require("../lib/stream-formatter/format-direct");

const ENRICHED = {
    source: {
        providerId: "gojo", providerDisplay: "Gojo", providerHost: "animetsu.live",
        serverFamily: "Gojo", serverInstance: "pahe", cdnHost: "mega-cloud.top",
        container: "HLS", quality: "1080p", bitrate: null, duration: null, isHardSub: false
    },
    track: {
        kind: "sub", audioLanguage: "JPN",
        subtitleTracks: [
            { src: "https://x/eng.vtt", label: "English", lang: "ENG", format: "vtt" },
            { src: "https://x/jpn.vtt", label: "Japanese", lang: "JPN", format: "vtt" }
        ]
    },
    canonical: {
        anilistId: "21", malId: "21", anidbId: "69", kitsuId: "12", imdbId: "tt0388629",
        mainTitle: "ONE PIECE", englishTitle: "ONE PIECE", year: 1999, format: "TV",
        episodeCount: 1100, episodeTitle: "Romance Dawn", episodeAirDate: null,
        season: 1, episode: 1, runtimeMinutes: 24
    },
    match: { score: 200, confidence: "HIGH", reasons: ["+200 anilist_id_exact (21)"], source: "match" }
};

test("formatNagareStream emits a parser-friendly name + description", () => {
    const out = formatNagareStream(ENRICHED, { url: "https://cdn.example.com/x.m3u8", headers: { Referer: "x" } });
    assert.equal(typeof out.name, "string");
    assert.equal(typeof out.description, "string");

    // Name shape: 3 lines
    const nameLines = out.name.split("\n");
    assert.equal(nameLines.length, 3);
    assert.match(nameLines[0], /^1080p · HLS/);
    assert.match(nameLines[1], /🌊 Gojo · pahe · 📝 SUB/);
    assert.equal(nameLines[2], "Direct stream");

    // Description shape: synthetic filename, indexer, canonical, episode title, langs, transport, match, ids
    const desc = out.description;
    assert.match(desc, /📄 \[Gojo\] One Piece - S1E1 \[1080p HLS\]\.m3u8/);
    assert.match(desc, /📡 Gojo · animetsu\.live · server: pahe \(mega-cloud\.top\)/);
    assert.match(desc, /🎬 ONE PIECE · 1999 · TV · 1100ep/);
    assert.match(desc, /📺 S1E1 · "Romance Dawn"/);
    assert.match(desc, /📝 ENG, JPN/);
    assert.match(desc, /🌐 Direct stream · no debrid required/);
    assert.match(desc, /🎯 HIGH \(200\) · \+200 anilist_id_exact \(21\)/);
    assert.match(desc, /🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629/);
});

test("formatNagareStream omits 📺 line when episodeTitle is null", () => {
    const noTitle = { ...ENRICHED, canonical: { ...ENRICHED.canonical, episodeTitle: null } };
    const out = formatNagareStream(noTitle, { url: "https://x/y.m3u8", headers: {} });
    assert.doesNotMatch(out.description, /📺 /);
});

test("formatNagareStream uses 🎙 DUB on dub track", () => {
    const dub = { ...ENRICHED, track: { ...ENRICHED.track, kind: "dub", audioLanguage: "ENG" } };
    const out = formatNagareStream(dub, { url: "https://x/y.m3u8", headers: {} });
    assert.match(out.name, /🎙 DUB/);
    assert.match(out.description, /\[Gojo\] One Piece - S1E1 \[1080p HLS DUB\]/);
});
```

- [ ] **Step 2: Run, verify failure**

```bash
node --test tests/stream-formatter-format-direct.test.js 2>&1 | tail -3
```

- [ ] **Step 3: Implement format-direct**

```js
// nexio-nagare/lib/stream-formatter/format-direct.js
const { dubGlyph, humanQuality } = require("./human-format");

function syntheticFilename(enriched) {
    const c = enriched.canonical;
    const s = enriched.source;
    const dubTag = enriched.track.kind === "dub" ? " DUB" : "";
    const ext = s.container === "MP4" ? "mp4" : "m3u8";
    const title = c.englishTitle || c.mainTitle || "Anime";
    return `[${enriched.source.providerDisplay}] ${title} - S${c.season}E${c.episode} [${humanQuality(s.quality)} ${s.container}${dubTag}].${ext}`;
}

function formatCrossIds(c) {
    const parts = [];
    if (c.anilistId) parts.push(`anilist:${c.anilistId}`);
    if (c.malId)     parts.push(`mal:${c.malId}`);
    if (c.kitsuId)   parts.push(`kitsu:${c.kitsuId}`);
    if (c.anidbId)   parts.push(`anidb:${c.anidbId}`);
    if (c.imdbId)    parts.push(`imdb:${c.imdbId}`);
    return parts.join(" · ");
}

function formatNagareStream(enriched, base) {
    const s = enriched.source;
    const t = enriched.track;
    const c = enriched.canonical;
    const m = enriched.match;

    const trackTag = dubGlyph(t.kind === "dub");
    const subLangs = t.subtitleTracks.map(st => st.lang).filter(Boolean).join(", ");
    const filename = syntheticFilename(enriched);
    const crossIds = formatCrossIds(c);

    const nameLines = [
        `${humanQuality(s.quality)} · ${s.container}${s.bitrate ? ` · ${Math.round(s.bitrate / 1000)}kbps` : ""}`,
        `🌊 ${s.providerDisplay} · ${s.serverInstance} · ${trackTag}`,
        `Direct stream`
    ];

    const descLines = [];
    descLines.push(`📄 ${filename}`);
    descLines.push(`📡 ${s.providerDisplay} · ${s.providerHost} · server: ${s.serverInstance} (${s.cdnHost})`);
    const canonLine = [
        c.englishTitle || c.mainTitle,
        c.year,
        c.format,
        c.episodeCount ? `${c.episodeCount}ep` : null
    ].filter(Boolean).join(" · ");
    if (canonLine) descLines.push(`🎬 ${canonLine}`);
    if (c.episodeTitle) descLines.push(`📺 S${c.season}E${c.episode} · "${c.episodeTitle}"`);
    descLines.push(`${trackTag}${subLangs ? ` · 📝 ${subLangs}` : ""}`);
    descLines.push(`🌐 Direct stream · no debrid required`);
    if (Number.isFinite(m.score)) {
        descLines.push(`🎯 ${m.confidence} (${m.score}) · ${m.reasons.join(" · ") || m.source}`);
    }
    if (crossIds) descLines.push(`🆔 ${crossIds}`);

    return {
        name: nameLines.join("\n"),
        description: descLines.join("\n"),
        url: base.url,
        behaviorHints: {
            bingeGroup: `nexio_nagare_${s.providerId}_${s.serverInstance}`,
            notWebReady: s.container === "HLS",
            ...(base.headers && Object.keys(base.headers).length > 0 ? {
                proxyHeaders: { request: base.headers, response: base.headers }
            } : {})
        }
    };
}

module.exports = { formatNagareStream, syntheticFilename, formatCrossIds };
```

- [ ] **Step 4: Create the module entry point**

```js
// nexio-nagare/lib/stream-formatter/index.js
const { enrichDirectStream } = require("./enrich-direct");
const { formatNagareStream } = require("./format-direct");
module.exports = { enrichDirectStream, formatNagareStream };
```

- [ ] **Step 5: Run, verify pass**

```bash
node --test tests/stream-formatter-format-direct.test.js 2>&1 | tail -3
# Expected: pass 3 / fail 0
```

- [ ] **Step 6: Commit**

```bash
git add lib/stream-formatter/format-direct.js lib/stream-formatter/index.js tests/stream-formatter-format-direct.test.js
git commit -m "feat(nagare): format-direct emits parser-friendly Nexio shape"
```

---

### Task 4: wire formatter into stream-builder + addon.js (Nagare)

**Files:**
- Modify: `nexio-nagare/lib/stream-builder.js` (replace buildProviderStreams body)
- Modify: `nexio-nagare/addon.js` (pass match info)
- Modify: `nexio-nagare/tests/stream-builder.test.js` (update assertions to match new shape)

- [ ] **Step 1: Read both files in full to understand current shape**

```bash
cat lib/stream-builder.js
grep -n "buildProviderStreams" addon.js | head -5
```

- [ ] **Step 2: Replace stream-builder.js**

```js
// nexio-nagare/lib/stream-builder.js
const { enrichDirectStream, formatNagareStream } = require("./stream-formatter");

function buildProviderStreams({ providerStreams, provider, canonical, episode, season, match }) {
    if (!Array.isArray(providerStreams) || providerStreams.length === 0) return [];
    const streams = [];
    for (const ps of providerStreams) {
        if (!ps || !ps.url) continue;
        const enriched = enrichDirectStream({
            provider, providerStream: ps, canonical, episode, season: season || 1, match
        });
        streams.push(formatNagareStream(enriched, { url: ps.url, headers: ps.headers || {} }));
    }
    return streams;
}

module.exports = { buildProviderStreams };
```

- [ ] **Step 3: Update addon.js stream handler invocation**

In `addon.js`, find the `streamJobs.map(async m => { … buildProviderStreams({...}) ... })` block and update to:

```js
const streamJobs = matches.map(async m => {
    if (!m || !m.slug) return [];
    const provider = REGISTRY_BY_ID.get(m.providerId);
    if (!provider) return [];
    try {
        const adjustedEpisode = episode + (Number.isFinite(m.episodeOffset) ? m.episodeOffset : 0);
        const providerStreams = await withTimeout(provider.getStreams(m.slug, adjustedEpisode), 8000, []);
        if (Array.isArray(providerStreams) && providerStreams.length === 0 && m.source !== "match") {
            invalidateCachedSlug({ canonical, providerId: m.providerId, opts: { preferDub: Boolean(userConfig.preferDub) } });
        }
        if (Array.isArray(providerStreams) && providerStreams.length > 0) {
            markSlugSucceeded({ canonical, providerId: m.providerId, opts: { preferDub: Boolean(userConfig.preferDub) } });
        }
        return buildProviderStreams({
            providerStreams,
            provider,
            canonical,
            episode: adjustedEpisode,
            season: 1,
            match: { score: m.confidence === "OVERRIDE" ? null : m.debug?.score,
                     confidence: m.confidence, reasons: m.debug?.reasons || [m.source], source: m.source }
        });
    } catch (e) {
        console.error(`[stream] provider=${m.providerId} getStreams failed: ${e.message}`);
        return [];
    }
});
```

- [ ] **Step 4: Update existing stream-builder test to new shape**

The existing test at `tests/stream-builder.test.js` uses the old `buildProviderStreams` signature. Replace its content:

```js
// tests/stream-builder.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { buildProviderStreams } = require("../lib/stream-builder");

const PROVIDER = { id: "gojo", displayName: "Gojo", displayHost: "animetsu.live", dub: "both" };
const CANONICAL = {
    anilist: "21", mal: "21", anidb: "69", kitsu: "12", imdb: "tt0388629",
    mainTitle: "ONE PIECE", englishTitle: "ONE PIECE", year: 1999, format: "TV",
    episodeCount: 1100
};
const MATCH = { score: 200, confidence: "HIGH", reasons: ["+200 anilist_id_exact"], source: "match" };

test("buildProviderStreams emits a Nexio-friendly stream object", () => {
    const out = buildProviderStreams({
        providerStreams: [{
            url: "https://cdn.example.com/master.m3u8",
            server: "Gojo/pahe", quality: "1080p", dub: false,
            headers: { Referer: "https://animetsu.live/" }, subtitles: []
        }],
        provider: PROVIDER, canonical: CANONICAL, episode: 1, season: 1, match: MATCH
    });
    assert.equal(out.length, 1);
    assert.match(out[0].name, /^1080p · HLS/);
    assert.match(out[0].name, /🌊 Gojo · pahe/);
    assert.match(out[0].description, /📄 \[Gojo\] One Piece - S1E1 \[1080p HLS\]\.m3u8/);
    assert.match(out[0].description, /🎯 HIGH \(200\)/);
    assert.deepEqual(out[0].behaviorHints.proxyHeaders.request, { Referer: "https://animetsu.live/" });
    assert.equal(out[0].behaviorHints.notWebReady, true);
});

test("buildProviderStreams returns [] for empty input", () => {
    assert.deepEqual(buildProviderStreams({ providerStreams: [], provider: PROVIDER, canonical: CANONICAL, episode: 1, season: 1, match: MATCH }), []);
});

test("buildProviderStreams skips entries without a url", () => {
    const out = buildProviderStreams({
        providerStreams: [{ url: "", server: "x" }, { url: "https://a/x.m3u8", server: "y" }],
        provider: PROVIDER, canonical: CANONICAL, episode: 1, season: 1, match: MATCH
    });
    assert.equal(out.length, 1);
});
```

- [ ] **Step 5: Run all tests**

```bash
npm test 2>&1 | tail -8
# Expected: every existing test still passing + new ones — count goes up
```

- [ ] **Step 6: Live smoke test against the live deploy**

```bash
SKIP_IDENTITY_REFRESH=1 node server.js > /tmp/n.log 2>&1 &
SERVER_PID=$!
sleep 2
curl -sS --max-time 30 "http://127.0.0.1:7002/stream/anime/anilist:21-1.json" \
  | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('streams:', len(d.get('streams', [])))
print('first name:', d['streams'][0]['name'][:80] if d['streams'] else '(none)')
print('first desc has 🎯:', '🎯' in (d['streams'][0]['description'] if d['streams'] else ''))
"
kill $SERVER_PID
```

Expected: streams returned, name starts with quality+container, description contains 🎯.

- [ ] **Step 7: Commit**

```bash
git add lib/stream-builder.js addon.js tests/stream-builder.test.js
git commit -m "feat(nagare): wire stream-formatter into stream handler"
```

---

## Phase B — Torii addon: enrichment + formatter

### Task 5: human-format helpers (Torii)

**Files:**
- Create: `nexio-torii/lib/stream-formatter/human-format.js`
- Test: `nexio-torii/tests/stream-formatter-human.test.js`

- [ ] **Step 1: Failing test**

```js
// nexio-torii/tests/stream-formatter-human.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { humanSize, humanAge, languageFlag, cacheGlyph, packEpisodes } = require("../lib/stream-formatter/human-format");

test("humanSize formats bytes", () => {
    assert.equal(humanSize(0), "0 B");
    assert.equal(humanSize(1024), "1 KiB");
    assert.equal(humanSize(1536), "1.5 KiB");
    assert.equal(humanSize(1024 * 1024 * 1024), "1 GiB");
    assert.equal(humanSize(1024 * 1024 * 1024 * 1.45), "1.45 GiB");
    assert.equal(humanSize(null), null);
});

test("humanAge formats hours", () => {
    assert.equal(humanAge(1), "1h");
    assert.equal(humanAge(6), "6h");
    assert.equal(humanAge(48), "2d");
    assert.equal(humanAge(24 * 7), "7d");
    assert.equal(humanAge(24 * 30), "30d");
    assert.equal(humanAge(24 * 365), "1y");
    assert.equal(humanAge(null), null);
});

test("languageFlag maps", () => {
    assert.equal(languageFlag("ENG"), "🇬🇧");
    assert.equal(languageFlag("JPN"), "🇯🇵");
    assert.equal(languageFlag("MULTI"), "🌍");
    assert.equal(languageFlag(null), "🌐");
});

test("cacheGlyph picks correct icon", () => {
    assert.equal(cacheGlyph(true), "⚡");
    assert.equal(cacheGlyph(false), "☁️");
    assert.equal(cacheGlyph(null), "📡");
});

test("packEpisodes formats range", () => {
    assert.equal(packEpisodes({ first: 1, last: 1100, total: 1100 }), "1-1100/1100");
    assert.equal(packEpisodes({ first: 1, last: 12 }), "1-12");
    assert.equal(packEpisodes(null), null);
});
```

- [ ] **Step 2: Run, verify failure**

```bash
cd /Users/jneerdael/Scripts/stremio-addons/nexio-torii
node --test tests/stream-formatter-human.test.js 2>&1 | tail -3
```

- [ ] **Step 3: Implement**

```js
// nexio-torii/lib/stream-formatter/human-format.js
const FLAG_BY_CODE = {
    ENG: "🇬🇧", JPN: "🇯🇵", ESP: "🇪🇸", FRE: "🇫🇷", GER: "🇩🇪",
    ITA: "🇮🇹", POR: "🇵🇹", RUS: "🇷🇺", CHI: "🇨🇳", KOR: "🇰🇷",
    HIN: "🇮🇳", ARA: "🇸🇦", DUT: "🇳🇱", POL: "🇵🇱", TUR: "🇹🇷",
    IND: "🇮🇩", VIE: "🇻🇳", MULTI: "🌍", LAT: "💃🏻", SPA: "🇪🇸",
    NLD: "🇳🇱"
};

function humanSize(bytes) {
    if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return null;
    if (bytes === 0) return "0 B";
    const units = ["B", "KiB", "MiB", "GiB", "TiB"];
    let i = 0;
    let v = bytes;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    const formatted = v >= 100 ? Math.round(v) : v >= 10 ? v.toFixed(1) : v.toFixed(2);
    return `${parseFloat(formatted)} ${units[i]}`;
}

function humanAge(hours) {
    if (hours == null || !Number.isFinite(hours)) return null;
    if (hours < 24) return `${Math.round(hours)}h`;
    const days = hours / 24;
    if (days < 30) return `${Math.round(days)}d`;
    if (days < 365) return `${Math.round(days)}d`;
    const years = days / 365;
    return `${Math.round(years)}y`;
}

function languageFlag(label) {
    if (!label) return "🌐";
    const code = String(label).toUpperCase();
    return FLAG_BY_CODE[code] || "🌐";
}

function cacheGlyph(isCached) {
    if (isCached === true) return "⚡";
    if (isCached === false) return "☁️";
    return "📡";
}

function packEpisodes(range) {
    if (!range || !Number.isFinite(range.first) || !Number.isFinite(range.last)) return null;
    const base = `${range.first}-${range.last}`;
    return Number.isFinite(range.total) ? `${base}/${range.total}` : base;
}

module.exports = { humanSize, humanAge, languageFlag, cacheGlyph, packEpisodes };
```

- [ ] **Step 4: Run, verify pass**

```bash
node --test tests/stream-formatter-human.test.js 2>&1 | tail -3
# Expected: pass 5 / fail 0
```

- [ ] **Step 5: Commit**

```bash
git add lib/stream-formatter/human-format.js tests/stream-formatter-human.test.js
git commit -m "feat(torii): human-format helpers for stream-formatter"
```

---

### Task 6: enrich-torrent module (Torii)

**Files:**
- Create: `nexio-torii/lib/stream-formatter/enrich-torrent.js`
- Test: `nexio-torii/tests/stream-formatter-enrich-torrent.test.js`

- [ ] **Step 1: Failing test**

```js
// nexio-torii/tests/stream-formatter-enrich-torrent.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { enrichTorrent } = require("../lib/stream-formatter/enrich-torrent");

const TORRENT = {
    title: "[SubsPlease] One Piece - 1100 [1080p][AAC].mkv",
    hash: "ABCDEF1234", size: "1.45 GiB", seeders: 152,
    source: "Nyaa.si", pubDate: "2024-08-04T12:00:00Z"
};
const CANONICAL = {
    name: "ONE PIECE", englishName: "ONE PIECE", altName: "One Piece",
    synonyms: [], format: "TV", year: 1999, episodes: 1100,
    epMeta: { 1100: { title: "Romance Dawn" } }, idMal: 21
};
const PARSED = {
    title: "One Piece", year: null, seasons: [], episodes: [1100],
    resolution: "1080p", quality: null, encode: null,
    visualTags: [], audioTags: ["AAC"], audioChannels: [],
    languages: [], subtitles: [], releaseGroup: "SubsPlease"
};
const DEBRID = {
    serviceCode: "RD", isCached: true,
    selectedFile: { name: "One Piece - 1100.mkv", sizeBytes: 1500000000, index: 0 }
};
const MATCH = { score: 150, confidence: "HIGH", reasons: ["title=100 exact_title year_match"] };

test("enrichTorrent merges torrent + parsed + canonical + debrid + match", () => {
    const out = enrichTorrent({
        torrent: TORRENT, parsed: PARSED, canonical: CANONICAL,
        debrid: DEBRID, match: MATCH, requestedEp: 1100, expectedSeason: 1, anilistId: "21"
    });
    assert.equal(out.source.rawTitle, TORRENT.title);
    assert.equal(out.source.infoHash, "ABCDEF1234");
    assert.equal(out.source.indexer, "Nyaa.si");
    assert.equal(out.source.seeders, 152);
    assert.ok(out.source.sizeBytes > 1.4e9);
    assert.ok(Number.isFinite(out.source.ageHours));
    assert.equal(out.parsed.releaseGroup, "SubsPlease");
    assert.equal(out.parsed.resolution, "1080p");
    assert.equal(out.canonical.englishTitle, "ONE PIECE");
    assert.equal(out.canonical.episodeTitle, "Romance Dawn");
    assert.equal(out.canonical.anilistId, "21");
    assert.equal(out.canonical.malId, "21");
    assert.equal(out.debrid.serviceCode, "RD");
    assert.equal(out.debrid.isCached, true);
    assert.equal(out.debrid.selectedFile.name, "One Piece - 1100.mkv");
    assert.equal(out.match.confidence, "HIGH");
});

test("enrichTorrent handles batch torrent with episodeRange", () => {
    const batchTorrent = { ...TORRENT, title: "[SubsPlease] One Piece (1090-1100) [1080p Batch].mkv" };
    const batchParsed = { ...PARSED, episodes: Array.from({ length: 11 }, (_, i) => 1090 + i), seasons: [], releaseGroup: "SubsPlease" };
    const out = enrichTorrent({
        torrent: batchTorrent, parsed: batchParsed, canonical: CANONICAL,
        debrid: { ...DEBRID, archiveLayout: { totalFiles: 11, mediaFiles: 11, isCompletePack: false } },
        match: MATCH, requestedEp: 1095, expectedSeason: 1, anilistId: "21"
    });
    assert.equal(out.parsed.isSeasonPack, true);
    assert.deepEqual(out.parsed.episodeRange, { first: 1090, last: 1100, total: 1100 });
});

test("enrichTorrent computes sizeBytes from human-readable sizes when present", () => {
    const out = enrichTorrent({
        torrent: { ...TORRENT, size: "750 MiB" }, parsed: PARSED, canonical: CANONICAL,
        debrid: DEBRID, match: MATCH, requestedEp: 1100, expectedSeason: 1, anilistId: "21"
    });
    assert.ok(out.source.sizeBytes > 700_000_000 && out.source.sizeBytes < 800_000_000);
});
```

- [ ] **Step 2: Run, verify failure**

```bash
node --test tests/stream-formatter-enrich-torrent.test.js 2>&1 | tail -3
```

- [ ] **Step 3: Implement**

```js
// nexio-torii/lib/stream-formatter/enrich-torrent.js
const SIZE_REGEX = /(\d+(?:\.\d+)?)\s*(KiB|MiB|GiB|TiB|KB|MB|GB|TB|B)/i;
const UNIT_BYTES = {
    B: 1, KB: 1000, MB: 1_000_000, GB: 1_000_000_000, TB: 1_000_000_000_000,
    KiB: 1024, MiB: 1048576, GiB: 1073741824, TiB: 1099511627776
};

function parseSizeToBytes(s) {
    if (typeof s === "number") return s;
    if (!s) return null;
    const m = SIZE_REGEX.exec(String(s));
    if (!m) return null;
    return Math.round(parseFloat(m[1]) * (UNIT_BYTES[m[2]] || UNIT_BYTES[m[2].toUpperCase()] || 0));
}

function ageHoursSince(pubDate) {
    if (!pubDate) return null;
    const t = Date.parse(pubDate);
    if (!Number.isFinite(t)) return null;
    return Math.max(0, Math.round((Date.now() - t) / 3_600_000));
}

function buildEpisodeRange(parsed, episodeCount) {
    if (!Array.isArray(parsed.episodes) || parsed.episodes.length === 0) return null;
    const sorted = [...parsed.episodes].sort((a, b) => a - b);
    return {
        first: sorted[0],
        last: sorted[sorted.length - 1],
        total: episodeCount || null
    };
}

function enrichTorrent({ torrent, parsed, canonical, debrid, match, requestedEp, expectedSeason, anilistId }) {
    const sizeBytes = parseSizeToBytes(torrent.size) || debrid?.selectedFile?.sizeBytes || null;
    const ageHours = ageHoursSince(torrent.pubDate);
    const isSeasonPack = Array.isArray(parsed.episodes) && parsed.episodes.length > 1;
    const episodeRange = buildEpisodeRange(parsed, canonical.episodes);
    const episodeTitle = canonical.epMeta?.[requestedEp]?.title || null;

    return {
        source: {
            rawTitle: torrent.title,
            infoHash: (torrent.hash || "").toLowerCase(),
            sizeBytes,
            seeders: Number.isFinite(torrent.seeders) ? torrent.seeders : (parseInt(torrent.seeders, 10) || 0),
            leechers: Number.isFinite(torrent.leechers) ? torrent.leechers : null,
            indexer: torrent.source || "Nyaa",
            pubDate: torrent.pubDate || null,
            ageHours,
            fileExtension: torrent.title?.match(/\.([a-z0-9]{2,4})$/i)?.[1]?.toLowerCase() || null,
            container: torrent.title?.match(/\.([a-z0-9]{2,4})$/i)?.[1]?.toLowerCase() || null
        },
        parsed: {
            title: parsed.title || null,
            year: parsed.year || null,
            seasons: parsed.seasons || [],
            episodes: parsed.episodes || [],
            isSeasonPack,
            episodeRange,
            resolution: parsed.resolution || null,
            quality: parsed.quality || null,
            encode: parsed.encode || null,
            visualTags: parsed.visualTags || [],
            audioTags: parsed.audioTags || [],
            audioChannels: parsed.audioChannels || [],
            languages: parsed.languages || [],
            subtitles: parsed.subtitles || [],
            releaseGroup: parsed.releaseGroup || null,
            network: parsed.network || null,
            edition: Array.isArray(parsed.editions) ? parsed.editions[0] || null : null,
            dubbed: parsed.dubbed === true,
            subbed: parsed.subbed === true,
            repack: parsed.repack === true,
            regraded: parsed.regraded === true,
            uncensored: parsed.uncensored === true,
            unrated: parsed.unrated === true,
            upscaled: parsed.upscaled === true
        },
        canonical: {
            anilistId: anilistId || null,
            malId: canonical.idMal ? String(canonical.idMal) : null,
            anidbId: canonical.anidb || null,
            kitsuId: canonical.kitsu || null,
            imdbId: canonical.imdb || null,
            mainTitle: canonical.name || null,
            englishTitle: canonical.englishName || canonical.name || null,
            year: canonical.year || (canonical.releaseInfo ? parseInt(canonical.releaseInfo, 10) : null),
            format: canonical.format || null,
            episodeCount: canonical.episodes || null,
            episodeTitle,
            episodeAirDate: canonical.epMeta?.[requestedEp]?.airDate || null,
            season: expectedSeason || 1,
            episode: requestedEp,
            runtimeMinutes: canonical.duration || null
        },
        debrid: {
            serviceCode: debrid?.serviceCode || null,
            isCached: debrid?.isCached === true,
            selectedFile: debrid?.selectedFile || null,
            archiveLayout: debrid?.archiveLayout || null
        },
        match: {
            score: match?.score ?? null,
            confidence: match?.confidence || "UNKNOWN",
            reasons: Array.isArray(match?.reasons) ? match.reasons : []
        }
    };
}

module.exports = { enrichTorrent, parseSizeToBytes };
```

- [ ] **Step 4: Run tests, verify pass**

```bash
node --test tests/stream-formatter-enrich-torrent.test.js 2>&1 | tail -3
# Expected: pass 3 / fail 0
```

- [ ] **Step 5: Commit**

```bash
git add lib/stream-formatter/enrich-torrent.js tests/stream-formatter-enrich-torrent.test.js
git commit -m "feat(torii): enrich-torrent merges all metadata sources"
```

---

### Task 7: format-torrent module + entry (Torii)

**Files:**
- Create: `nexio-torii/lib/stream-formatter/format-torrent.js`
- Create: `nexio-torii/lib/stream-formatter/index.js`
- Test: `nexio-torii/tests/stream-formatter-format-torrent.test.js`

- [ ] **Step 1: Failing test**

```js
// nexio-torii/tests/stream-formatter-format-torrent.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const { formatToriiStream } = require("../lib/stream-formatter/format-torrent");

const ENRICHED = {
    source: {
        rawTitle: "[SubsPlease] One Piece - 1100 [1080p][AAC].mkv",
        infoHash: "abcdef", sizeBytes: 1500000000, seeders: 152, leechers: null,
        indexer: "Nyaa.si", pubDate: "2024-08-04T12:00:00Z", ageHours: 6,
        fileExtension: "mkv", container: "mkv"
    },
    parsed: {
        title: "One Piece", year: null, seasons: [], episodes: [1100],
        isSeasonPack: false, episodeRange: null,
        resolution: "1080p", quality: "BluRay", encode: "HEVC",
        visualTags: ["DV", "HDR10"], audioTags: ["DTS"], audioChannels: ["5.1"],
        languages: ["JPN", "ENG"], subtitles: ["ENG"],
        releaseGroup: "SubsPlease", network: "Crunchyroll", edition: null
    },
    canonical: {
        anilistId: "21", malId: "21", anidbId: "69", kitsuId: "12", imdbId: "tt0388629",
        mainTitle: "ONE PIECE", englishTitle: "ONE PIECE", year: 1999, format: "TV",
        episodeCount: 1100, episodeTitle: "Romance Dawn", episodeAirDate: null,
        season: 1, episode: 1100, runtimeMinutes: 24
    },
    debrid: {
        serviceCode: "RD", isCached: true,
        selectedFile: { name: "One Piece - 1100.mkv", sizeBytes: 1500000000, index: 0 },
        archiveLayout: null
    },
    match: { score: 150, confidence: "HIGH", reasons: ["title=100", "year_match"] }
};

test("formatToriiStream emits Nexio-friendly name + description for cached debrid", () => {
    const out = formatToriiStream(ENRICHED, { url: "https://example/resolve", behaviorHints: {} });
    const nameLines = out.name.split("\n");
    assert.equal(nameLines.length, 3);
    assert.match(nameLines[0], /1080p · BluRay · HEVC/);
    assert.match(nameLines[1], /⚡ RD/);
    assert.match(nameLines[1], /🎙 JPN\+ENG/);
    assert.match(nameLines[1], /5\.1/);
    assert.match(nameLines[1], /📝 ENG/);
    assert.equal(nameLines[2], "⛩ Torii");

    const desc = out.description;
    assert.match(desc, /📄 .*One Piece.*1100/);
    assert.match(desc, /💾 1\.4 GiB · 👥 152 · 📅 6h/);
    assert.match(desc, /📡 Nyaa\.si · SubsPlease · Crunchyroll/);
    assert.match(desc, /🎬 ONE PIECE · 1999 · TV · 1100ep/);
    assert.match(desc, /📺 S1E1100 · "Romance Dawn"/);
    assert.match(desc, /🎯 HIGH \(150\) · title=100 · year_match/);
    assert.match(desc, /🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629/);
});

test("formatToriiStream emits 📦 line for season pack", () => {
    const pack = {
        ...ENRICHED,
        parsed: { ...ENRICHED.parsed, isSeasonPack: true, episodeRange: { first: 1090, last: 1100, total: 1100 } },
        debrid: { ...ENRICHED.debrid, selectedFile: { name: "One Piece - 1095.mkv", sizeBytes: 800_000_000, index: 5 } },
        source: { ...ENRICHED.source, sizeBytes: 14_200_000_000 }
    };
    const out = formatToriiStream(pack, { url: "x", behaviorHints: {} });
    assert.match(out.description, /📦 14\.2 GiB/);
    assert.match(out.description, /1090-1100\/1100/);
});

test("formatToriiStream uses ☁️ for uncached and P2P for missing service", () => {
    const uncached = { ...ENRICHED, debrid: { ...ENRICHED.debrid, isCached: false } };
    const o1 = formatToriiStream(uncached, { url: "x", behaviorHints: {} });
    assert.match(o1.name, /☁️ RD/);

    const p2p = { ...ENRICHED, debrid: { serviceCode: null, isCached: null, selectedFile: null } };
    const o2 = formatToriiStream(p2p, { url: "magnet:?xt=...", behaviorHints: {} });
    assert.match(o2.name, /📡 P2P/);
});
```

- [ ] **Step 2: Run, verify failure**

```bash
node --test tests/stream-formatter-format-torrent.test.js 2>&1 | tail -3
```

- [ ] **Step 3: Implement**

```js
// nexio-torii/lib/stream-formatter/format-torrent.js
const { humanSize, humanAge, languageFlag, cacheGlyph, packEpisodes } = require("./human-format");

function formatCrossIds(c) {
    const parts = [];
    if (c.anilistId) parts.push(`anilist:${c.anilistId}`);
    if (c.malId)     parts.push(`mal:${c.malId}`);
    if (c.kitsuId)   parts.push(`kitsu:${c.kitsuId}`);
    if (c.anidbId)   parts.push(`anidb:${c.anidbId}`);
    if (c.imdbId)    parts.push(`imdb:${c.imdbId}`);
    return parts.join(" · ");
}

function formatToriiStream(enriched, base) {
    const s = enriched.source;
    const p = enriched.parsed;
    const c = enriched.canonical;
    const d = enriched.debrid;
    const m = enriched.match;

    // Line 1: visual quality
    const visualBits = [p.resolution, p.quality, p.encode].filter(Boolean);
    const visualExtras = [...p.visualTags, p.audioChannels[0]].filter(Boolean);
    const line1 = [visualBits.join(" "), visualExtras.join(" ")].filter(Boolean).join(" · ");

    // Line 2: cache + service + audio + subs
    const cacheService = d.serviceCode ? `${cacheGlyph(d.isCached)} ${d.serviceCode}` : `📡 P2P`;
    const audioPart = p.languages.length > 0 ? `🎙 ${p.languages.join("+")}` : null;
    const channelPart = p.audioChannels[0] || null;
    const subPart = p.subtitles.length > 0 ? `📝 ${p.subtitles.join(",")}` : null;
    const line2 = [cacheService, audioPart, channelPart, subPart].filter(Boolean).join(" · ");

    const nameLines = [line1 || "Unknown", line2, "⛩ Torii"];

    // Description
    const descLines = [];
    const fname = d.selectedFile?.name || s.rawTitle;
    descLines.push(`📄 ${fname}`);

    const sizeParts = [];
    if (d.selectedFile?.sizeBytes) sizeParts.push(`💾 ${humanSize(d.selectedFile.sizeBytes)}`);
    else if (s.sizeBytes) sizeParts.push(`💾 ${humanSize(s.sizeBytes)}`);
    if (p.isSeasonPack && s.sizeBytes && d.selectedFile?.sizeBytes && s.sizeBytes !== d.selectedFile.sizeBytes) {
        sizeParts.push(`📦 ${humanSize(s.sizeBytes)}`);
    } else if (p.isSeasonPack && s.sizeBytes && !d.selectedFile?.sizeBytes) {
        sizeParts.push(`📦 ${humanSize(s.sizeBytes)}`);
    }
    if (Number.isFinite(s.seeders)) sizeParts.push(`👥 ${s.seeders}`);
    if (s.ageHours != null) sizeParts.push(`📅 ${humanAge(s.ageHours)}`);
    if (sizeParts.length > 0) descLines.push(sizeParts.join(" · "));

    const indexerParts = [s.indexer, p.releaseGroup, p.network].filter(Boolean);
    if (indexerParts.length > 0) descLines.push(`📡 ${indexerParts.join(" · ")}`);

    const canonLine = [
        c.englishTitle || c.mainTitle, c.year, c.format,
        c.episodeCount ? `${c.episodeCount}ep` : null
    ].filter(Boolean).join(" · ");
    if (canonLine) descLines.push(`🎬 ${canonLine}`);

    if (c.episodeTitle) descLines.push(`📺 S${c.season}E${c.episode} · "${c.episodeTitle}"`);
    if (p.isSeasonPack && p.episodeRange) descLines.push(`🌐 batch ${packEpisodes(p.episodeRange)}`);

    if (Number.isFinite(m.score)) {
        descLines.push(`🎯 ${m.confidence} (${m.score}) · ${m.reasons.join(" · ") || "match"}`);
    }
    const cross = formatCrossIds(c);
    if (cross) descLines.push(`🆔 ${cross}`);

    return {
        name: nameLines.join("\n"),
        description: descLines.join("\n"),
        url: base.url,
        behaviorHints: base.behaviorHints || {}
    };
}

module.exports = { formatToriiStream, formatCrossIds };
```

- [ ] **Step 4: Create entry point**

```js
// nexio-torii/lib/stream-formatter/index.js
const { enrichTorrent } = require("./enrich-torrent");
const { formatToriiStream } = require("./format-torrent");
module.exports = { enrichTorrent, formatToriiStream };
```

- [ ] **Step 5: Run all stream-formatter tests**

```bash
node --test tests/stream-formatter-*.test.js 2>&1 | tail -3
# Expected: pass all / fail 0
```

- [ ] **Step 6: Commit**

```bash
git add lib/stream-formatter/format-torrent.js lib/stream-formatter/index.js tests/stream-formatter-format-torrent.test.js
git commit -m "feat(torii): format-torrent emits parser-friendly Nexio shape"
```

---

### Task 8: wire formatter into Torii's stream-builder

**Files:**
- Modify: `nexio-torii/lib/stream-builder.js`
- Modify: `nexio-torii/addon.js` (pass match info; integrate canon-gate scores)
- Modify: `nexio-torii/tests/stream-builder.test.js`

- [ ] **Step 1: Read current stream-builder + addon.js sections**

```bash
sed -n '70,120p' lib/stream-builder.js
grep -n "buildDebridStreams\|filterByCanonical" addon.js | head -10
```

- [ ] **Step 2: Modify lib/stream-builder.js to use the new formatter**

The old `buildDebridStreams` builds Stremio stream objects directly. Refactor so it:
1. Accepts an extra `parsedTorrentByHash` map and a `canonicalForFormatter` blob,
2. Builds `EnrichedTorrent` per torrent using existing `parseReleaseTitle` output,
3. Calls `formatToriiStream` to produce the Stremio object.

Replace the body of `buildDebridStreams` so it still emits one stream per torrent×service pair, but each is now produced by `formatToriiStream(enrichTorrent(...), { url, behaviorHints })`. Keep the existing `_bytes/_lang/_isCached/_res/_prog/_seeders/_isBatch` shadow fields for the sort comparator in `addon.js`.

```js
// nexio-torii/lib/stream-builder.js
const { encodeConfigPayload } = require("./config");
const { resolvePathForCachedFile } = require("./debrid");
const { enrichTorrent, formatToriiStream } = require("./stream-formatter");
const { buildResolveUrl } = require("./playback");
const { parseReleaseTitle } = require("./catalog/release-parser");
const { SUPPORTED_DEBRID_SERVICES, getServiceCode } = require("./services");

function buildDebridStreams(input) {
    const {
        torrents, availabilityByEntry, userConfig, nexioPayload, baseUrl,
        requestedEp, expectedSeason, isMovie, isRawSearch,
        flags, extractTags, extractLanguage, parseSizeToBytes,
        selectBestVideoFile, isEpisodeMatch, isSeasonBatch,
        canonical
    } = input;

    const streams = [];
    userConfig.debridServices.forEach((entry, serviceIndex) => {
        const availability = availabilityByEntry[serviceIndex] || {};
        const serviceCode = getServiceCode(entry.service);

        torrents.forEach(t => {
            const hash = (t.hash || "").toLowerCase();
            const av = availability[hash];
            const isCached = av && av.is_cached === true;

            if (!isCached && userConfig.hideUncached) return;

            const parsed = t._parsed || (t._parsed = null); // populated upstream
            const enriched = enrichTorrent({
                torrent: t, parsed: parsed || {}, canonical: canonical || {},
                debrid: {
                    serviceCode, isCached,
                    selectedFile: av?.selected_file || null,
                    archiveLayout: av?.layout || null
                },
                match: { score: t._matchScore ?? null, confidence: t._matchConfidence || "MEDIUM", reasons: t._matchReasons || [] },
                requestedEp, expectedSeason, anilistId: canonical?.id?.split(":")?.[1] || null
            });

            const formatted = formatToriiStream(enriched, {
                url: buildResolveUrl(baseUrl, nexioPayload, serviceIndex, hash, requestedEp, t.title),
                behaviorHints: { bingeGroup: `nexio_torii_${enriched.canonical.anilistId || hash}_${serviceCode}` }
            });
            // Preserve sort fields used by addon.js's comparator
            const { res } = extractTags(t.title);
            formatted._bytes = parseSizeToBytes(t.size) || 0;
            formatted._lang = extractLanguage(t.title, []);
            formatted._isCached = isCached;
            formatted._res = res;
            formatted._prog = av?.progress || 0;
            formatted._seeders = parseInt(t.seeders, 10) || 0;
            formatted._isBatch = isSeasonBatch(t.title, expectedSeason);

            streams.push(formatted);
        });
    });
    return streams;
}

module.exports = { buildDebridStreams };
```

- [ ] **Step 3: Modify addon.js to compute parsed-title per torrent + pass canonical**

Find the section right after `filterByCanonical` (we just added it in this session). After the `kept` torrents are determined, parse each one:

```js
// In addon.js — after filterByCanonical, before passing to buildDebridStreams
const parsedByHash = new Map();
await Promise.all(torrents.map(async t => {
    const parsed = await parseReleaseTitle(t.title);
    parsedByHash.set((t.hash || "").toLowerCase(), parsed);
    t._parsed = parsed;
    t._matchScore = t._matchScore ?? null;
    t._matchConfidence = t._matchConfidence || "MEDIUM";
}));

// then call buildDebridStreams as before, with extra fields
const streams = buildDebridStreams({
    /* existing inputs */, canonical: freshMeta || null
});
```

- [ ] **Step 4: Update existing stream-builder.test.js**

The existing tests assert exact name strings like `"TORII [⚡ RD]\n🎥 1080p"`. Update them to match the new shape (3-line name, 6+ description lines). Lift one test as a baseline:

```js
// nexio-torii/tests/stream-builder.test.js  — update expected strings
test("buildDebridStreams emits cached and uncached streams for multiple services", () => {
    const input = baseInput();
    const streams = buildDebridStreams(input);
    assert.equal(streams.length, 2);
    assert.match(streams[0].name, /^1080p\b/m);
    assert.match(streams[0].name, /⚡ RD/);
    assert.match(streams[0].name, /⛩ Torii/);
    assert.match(streams[1].name, /☁️ PM/);
});
```

Repeat the pattern for the other expectations in the file (don't assert exact strings; assert markers).

- [ ] **Step 5: Run all torii tests**

```bash
npm test 2>&1 | tail -8
# Expected: every test still passing — pre-existing failures (catalog-live etc.) unchanged
```

- [ ] **Step 6: Commit**

```bash
git add lib/stream-builder.js addon.js tests/stream-builder.test.js
git commit -m "feat(torii): wire stream-formatter into debrid stream emission"
```

---

## Phase C — Nexio: assets + enum + auto-detect

### Task 9: Drop drawables + extend AddonParserPreset enum

**Files:**
- Create: `nexio/app/src/main/res/drawable/ic_addon_nexiotorii.png`
- Create: `nexio/app/src/main/res/drawable/ic_addon_nexionagare.png`
- Modify: `nexio/app/src/main/java/com/nexio/tv/domain/model/Addon.kt`

- [ ] **Step 1: Copy + resize source PNGs**

```bash
cd /Users/jneerdael/Scripts/nexio
cp /Users/jneerdael/Downloads/torii.png app/src/main/res/drawable/ic_addon_nexiotorii.png
cp /Users/jneerdael/Downloads/nagare.png app/src/main/res/drawable/ic_addon_nexionagare.png
file app/src/main/res/drawable/ic_addon_nexio*.png
# Expected: PNG image data, 512x512 each
```

- [ ] **Step 2: Add NEXIO_TORII / NEXIO_NAGARE to the enum**

```kotlin
// app/src/main/java/com/nexio/tv/domain/model/Addon.kt
@Immutable
enum class AddonParserPreset {
    GENERIC,
    STREMTHRU,
    TORRENTIO,
    WEBSTREAMR,
    NEXIO_TORII,
    NEXIO_NAGARE
}
```

- [ ] **Step 3: Update AddonManagerScreen label() and next()**

Find both helpers near the bottom of `app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerScreen.kt` (around lines 1103 and 1108). Extend `label()`:

```kotlin
private fun AddonParserPreset.label(): String {
    return when (this) {
        AddonParserPreset.GENERIC -> "Generic"
        AddonParserPreset.STREMTHRU -> "StremThru"
        AddonParserPreset.TORRENTIO -> "Torrentio"
        AddonParserPreset.WEBSTREAMR -> "WebStreamr"
        AddonParserPreset.NEXIO_TORII -> "Nexio Torii"
        AddonParserPreset.NEXIO_NAGARE -> "Nexio Nagare"
    }
}
```

`next()` continues to walk all enum entries (already correct).

- [ ] **Step 4: Build to confirm enum compiles**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -5
# Expected: BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/ic_addon_nexio*.png \
        app/src/main/java/com/nexio/tv/domain/model/Addon.kt \
        app/src/main/java/com/nexio/tv/ui/screens/addon/AddonManagerScreen.kt
git commit -m "feat(stream-presets): add NEXIO_TORII / NEXIO_NAGARE enum + drawable assets"
```

---

### Task 10: Auto-detect parser preset on addon install

**Files:**
- Modify: `nexio/app/src/main/java/com/nexio/tv/data/repository/AddonRepository*.kt` (find the actual file with `addAddon`)
- Test: `nexio/app/src/test/java/com/nexio/tv/data/repository/AddonAutoDetectTest.kt`

- [ ] **Step 1: Find the addAddon implementation**

```bash
grep -rn "fun addAddon\|fun installAddon" /Users/jneerdael/Scripts/nexio/app/src/main/java | head -5
```

- [ ] **Step 2: Failing test for auto-detect**

```kotlin
// app/src/test/java/com/nexio/tv/data/repository/AddonAutoDetectTest.kt
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.AddonParserPreset
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonAutoDetectTest {

    @Test fun `auto-detect resolves NEXIO_TORII from manifest id when user pick is GENERIC`() {
        val resolved = resolveAutoPreset(manifestId = "org.community.nexiotorii", userPick = AddonParserPreset.GENERIC)
        assertEquals(AddonParserPreset.NEXIO_TORII, resolved)
    }

    @Test fun `auto-detect resolves NEXIO_NAGARE from manifest id when user pick is GENERIC`() {
        val resolved = resolveAutoPreset(manifestId = "org.community.nexionagare", userPick = AddonParserPreset.GENERIC)
        assertEquals(AddonParserPreset.NEXIO_NAGARE, resolved)
    }

    @Test fun `auto-detect honours explicit user override`() {
        val resolved = resolveAutoPreset(manifestId = "org.community.nexiotorii", userPick = AddonParserPreset.GENERIC.let { AddonParserPreset.STREMTHRU })
        assertEquals(AddonParserPreset.STREMTHRU, resolved)
    }

    @Test fun `auto-detect falls through to GENERIC for unknown ids`() {
        assertEquals(AddonParserPreset.GENERIC, resolveAutoPreset(manifestId = "com.someone.other", userPick = AddonParserPreset.GENERIC))
        assertEquals(AddonParserPreset.GENERIC, resolveAutoPreset(manifestId = null, userPick = AddonParserPreset.GENERIC))
    }
}
```

- [ ] **Step 3: Run, verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AddonAutoDetectTest 2>&1 | tail -5
# Expected: compilation error (resolveAutoPreset not found)
```

- [ ] **Step 4: Implement resolveAutoPreset**

Add to `AddonRepository.kt` (or wherever `addAddon` is defined; if no obvious home, create `AddonAutoDetect.kt` in the same package as the repository):

```kotlin
// app/src/main/java/com/nexio/tv/data/repository/AddonAutoDetect.kt
package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.AddonParserPreset

private val NEXIO_TORII_IDS = setOf("org.community.nexiotorii")
private val NEXIO_NAGARE_IDS = setOf("org.community.nexionagare")

fun resolveAutoPreset(manifestId: String?, userPick: AddonParserPreset): AddonParserPreset {
    if (userPick != AddonParserPreset.GENERIC) return userPick
    val id = manifestId?.lowercase() ?: return AddonParserPreset.GENERIC
    return when {
        id in NEXIO_TORII_IDS -> AddonParserPreset.NEXIO_TORII
        id in NEXIO_NAGARE_IDS -> AddonParserPreset.NEXIO_NAGARE
        else -> AddonParserPreset.GENERIC
    }
}
```

- [ ] **Step 5: Wire into addAddon flow**

In `AddonRepository.addAddon` (or wherever the addon is persisted after fetch), call `resolveAutoPreset(manifest.id, userPick)` to override the user pick. Example pattern:

```kotlin
// inside addAddon
val effectivePreset = resolveAutoPreset(manifest.id, parserPreset)
saveAddon(manifest, effectivePreset)
```

(The exact integration depends on the existing call site — find the line where `parserPreset` is passed to a save/persist operation and replace it with `effectivePreset`.)

- [ ] **Step 6: Run, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.data.repository.AddonAutoDetectTest 2>&1 | tail -5
# Expected: 4 tests pass
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/nexio/tv/data/repository/AddonAutoDetect.kt \
        app/src/main/java/com/nexio/tv/data/repository/AddonRepository*.kt \
        app/src/test/java/com/nexio/tv/data/repository/AddonAutoDetectTest.kt
git commit -m "feat(addon-install): auto-detect NEXIO_TORII / NEXIO_NAGARE from manifest id"
```

---

### Task 11: StreamBadgeSupport drawable mapping

**Files:**
- Modify: `nexio/app/src/main/java/com/nexio/tv/ui/components/StreamBadgeSupport.kt`
- Test: `nexio/app/src/test/java/com/nexio/tv/ui/components/StreamBadgeSupportNexioPresetTest.kt`

- [ ] **Step 1: Read current StreamBadgeSupport**

```bash
head -120 /Users/jneerdael/Scripts/nexio/app/src/main/java/com/nexio/tv/ui/components/StreamBadgeSupport.kt
```

- [ ] **Step 2: Failing test**

```kotlin
// app/src/test/java/com/nexio/tv/ui/components/StreamBadgeSupportNexioPresetTest.kt
package com.nexio.tv.ui.components

import com.nexio.tv.R
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamBadgeSupportNexioPresetTest {

    private fun streamWithPreset(p: AddonParserPreset) = Stream(
        addonParserPreset = p, name = "x", description = null, title = null,
        url = "https://x", behaviorHints = null
    )

    @Test fun `nexio torii preset maps to torii drawable`() {
        assertEquals(R.drawable.ic_addon_nexiotorii, providerIconFor(streamWithPreset(AddonParserPreset.NEXIO_TORII)))
    }

    @Test fun `nexio nagare preset maps to nagare drawable`() {
        assertEquals(R.drawable.ic_addon_nexionagare, providerIconFor(streamWithPreset(AddonParserPreset.NEXIO_NAGARE)))
    }
}
```

- [ ] **Step 3: Run, verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.components.StreamBadgeSupportNexioPresetTest 2>&1 | tail -5
```

- [ ] **Step 4: Implement / extend providerIconFor**

Open StreamBadgeSupport.kt; if `providerIconFor` doesn't exist, add it; if it does, extend its `when` block:

```kotlin
fun providerIconFor(stream: Stream): Int? = when (stream.addonParserPreset) {
    AddonParserPreset.NEXIO_TORII -> R.drawable.ic_addon_nexiotorii
    AddonParserPreset.NEXIO_NAGARE -> R.drawable.ic_addon_nexionagare
    else -> existingResolution(stream)   // keep prior logic, if any
}
```

If StreamBadgeSupport has no `providerIconFor` today, add it as a new top-level function. Then find where stream-row badges are rendered and call `providerIconFor(stream)` to pick the leading icon.

- [ ] **Step 5: Run, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.ui.components.StreamBadgeSupportNexioPresetTest 2>&1 | tail -5
# Expected: 2 tests pass
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nexio/tv/ui/components/StreamBadgeSupport.kt \
        app/src/test/java/com/nexio/tv/ui/components/StreamBadgeSupportNexioPresetTest.kt
git commit -m "feat(stream-badge): render Nexio Torii / Nagare drawables for matching presets"
```

---

## Phase D — Nexio: parser branches

### Task 12: ParsedStreamInfo extensions

**Files:**
- Modify: `nexio/app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt`

- [ ] **Step 1: Add MatchInfo data class + ParsedStreamInfo fields**

In StreamPresentationModels.kt, add new `MatchInfo` near the top of the file (after `StreamTransportKind` enum) and extend `ParsedStreamInfo`:

```kotlin
@Immutable
data class MatchInfo(
    val score: Int? = null,
    val confidence: String = "UNKNOWN",
    val reasons: List<String> = emptyList()
)
```

Append to `ParsedStreamInfo` (default values keep the rest of the codebase backwards-compatible):

```kotlin
data class ParsedStreamInfo(
    /* ...existing fields... */
    val matchInfo: MatchInfo? = null,
    val episodeTitle: String? = null,
    val crossIds: Map<String, String> = emptyMap()
)
```

- [ ] **Step 2: Build to confirm compiles + no callers broken**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -5
# Expected: BUILD SUCCESSFUL
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/StreamPresentationModels.kt
git commit -m "feat(parser): add MatchInfo + episodeTitle + crossIds fields to ParsedStreamInfo"
```

---

### Task 13: AioStrictStreamParser NEXIO_TORII branch

**Files:**
- Modify: `nexio/app/src/main/java/com/nexio/tv/core/stream/AioStrictStreamParser.kt`
- Create: `nexio/app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserNexioToriiTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
// app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserNexioToriiTest.kt
package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AioStrictStreamParserNexioToriiTest {

    private val name = """
        1080p BluRay HEVC · DV HDR10 · 5.1
        ⚡ RD · 🎙 JPN+ENG · 5.1 · 📝 ENG
        ⛩ Torii
    """.trimIndent()

    private val description = """
        📄 [SubsPlease] One Piece - 1100 [1080p][AAC].mkv
        💾 1.45 GiB · 👥 152 · 📅 6h
        📡 Nyaa.si · SubsPlease · Crunchyroll
        🎬 ONE PIECE · 1999 · TV · 1100ep
        📺 S1E1100 · "Romance Dawn"
        🎯 HIGH (152) · year+title+ep
        🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629
    """.trimIndent()

    private val stream = Stream(
        addonParserPreset = AddonParserPreset.NEXIO_TORII,
        name = name, description = description, title = null,
        url = "https://x", behaviorHints = null
    )

    @Test fun `extracts filename, size, seeders, age`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("[SubsPlease] One Piece - 1100 [1080p][AAC].mkv", info.filename)
        assertNotNull(info.sizeBytes)
        assertTrue(info.sizeBytes!! > 1_400_000_000)
        assertEquals(152, info.seeders)
        assertEquals("6h", info.age)
    }

    @Test fun `extracts service code and cached flag`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("RD", info.serviceId)
        assertEquals(true, info.isCached)
    }

    @Test fun `extracts indexer + release group`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("Nyaa.si", info.indexer)
        assertEquals("SubsPlease", info.releaseGroup)
    }

    @Test fun `populates matchInfo from match line`() {
        val info = AioStrictStreamParser.parse(stream)
        assertNotNull(info.matchInfo)
        assertEquals("HIGH", info.matchInfo?.confidence)
        assertEquals(152, info.matchInfo?.score)
    }

    @Test fun `populates episodeTitle from episode line`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("Romance Dawn", info.episodeTitle)
    }

    @Test fun `populates crossIds`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("21", info.crossIds["anilist"])
        assertEquals("21", info.crossIds["mal"])
        assertEquals("12", info.crossIds["kitsu"])
        assertEquals("tt0388629", info.crossIds["imdb"])
    }
}
```

- [ ] **Step 2: Run, verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioStrictStreamParserNexioToriiTest 2>&1 | tail -5
# Expected: assertions fail (matchInfo null, episodeTitle null, crossIds empty)
```

- [ ] **Step 3: Implement parser line-helpers + branch**

Open AioStrictStreamParser.kt. Add these private helpers above the `parse()` function:

```kotlin
private val matchLineRegex = Regex("""🎯\s+([A-Z]+)(?:\s*\((\d+)\))?(?:\s*·\s*(.+))?""")
private val canonicalLineRegex = Regex("""🎬\s+(.+)""")
private val episodeTitleRegex = Regex("""📺\s+S\d+E\d+\s+·\s+"([^"]+)"""")
private val crossIdsRegex = Regex("""🆔\s+(.+)""")

private fun parseMatchLine(description: String): MatchInfo? {
    val line = description.lineSequence().firstOrNull { it.contains("🎯") } ?: return null
    val m = matchLineRegex.find(line) ?: return null
    val confidence = m.groupValues[1].ifBlank { "UNKNOWN" }
    val score = m.groupValues.getOrNull(2)?.toIntOrNull()
    val reasons = m.groupValues.getOrNull(3)?.split("·", "+")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    return MatchInfo(score = score, confidence = confidence, reasons = reasons)
}

private fun parseEpisodeTitleLine(description: String): String? {
    val line = description.lineSequence().firstOrNull { it.contains("📺") } ?: return null
    return episodeTitleRegex.find(line)?.groupValues?.get(1)
}

private fun parseCrossIdsLine(description: String): Map<String, String> {
    val line = description.lineSequence().firstOrNull { it.contains("🆔") } ?: return emptyMap()
    val m = crossIdsRegex.find(line) ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    m.groupValues[1].split("·").forEach { token ->
        val parts = token.trim().split(":", limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            out[parts[0]] = parts[1]
        }
    }
    return out
}
```

Then in `parse()`, when preset is NEXIO_TORII, populate the new fields:

```kotlin
return ParsedStreamInfo(
    /* …existing… */,
    matchInfo = if (stream.addonParserPreset == AddonParserPreset.NEXIO_TORII || stream.addonParserPreset == AddonParserPreset.NEXIO_NAGARE)
        parseMatchLine(description) else null,
    episodeTitle = if (stream.addonParserPreset == AddonParserPreset.NEXIO_TORII || stream.addonParserPreset == AddonParserPreset.NEXIO_NAGARE)
        parseEpisodeTitleLine(description) else null,
    crossIds = if (stream.addonParserPreset == AddonParserPreset.NEXIO_TORII || stream.addonParserPreset == AddonParserPreset.NEXIO_NAGARE)
        parseCrossIdsLine(description) else emptyMap()
)
```

Also add `AddonParserPreset.NEXIO_TORII` to the `deriveFilename` `when` and `indexerEmojisFor` so it follows the StremThru convention (`📄`/`📁` markers, `📡` indexer):

```kotlin
// in deriveFilename
AddonParserPreset.NEXIO_TORII, AddonParserPreset.STREMTHRU -> deriveStremThruFilename(description)?.let { return it }

// in indexerEmojisFor
AddonParserPreset.NEXIO_TORII -> listOf("📡")
AddonParserPreset.NEXIO_NAGARE -> listOf("📡")
```

- [ ] **Step 4: Run, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioStrictStreamParserNexioToriiTest 2>&1 | tail -5
# Expected: 6 tests pass
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nexio/tv/core/stream/AioStrictStreamParser.kt \
        app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserNexioToriiTest.kt
git commit -m "feat(parser): NEXIO_TORII branch + match/episodeTitle/crossIds line parsers"
```

---

### Task 14: AioStrictStreamParser NEXIO_NAGARE branch verification

**Files:**
- Create: `nexio/app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserNexioNagareTest.kt`

The NEXIO_NAGARE preset already shares the line-parsers (we wired both presets in Task 13). This task just verifies it works end-to-end against a nagare-shaped stream.

- [ ] **Step 1: Failing test**

```kotlin
// app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserNexioNagareTest.kt
package com.nexio.tv.core.stream

import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AioStrictStreamParserNexioNagareTest {

    private val name = """
        1080p · HLS
        🌊 Gojo · pahe · 📝 SUB
        Direct stream
    """.trimIndent()

    private val description = """
        📄 [Gojo] One Piece - S1E1 [1080p HLS].m3u8
        📡 Gojo · animetsu.live · server: pahe (mega-cloud.top)
        🎬 ONE PIECE · 1999 · TV · 1100ep
        📺 S1E1 · "Romance Dawn"
        📝 ENG, JPN
        🌐 Direct stream · no debrid required
        🎯 HIGH (200) · +200 anilist_id_exact (21)
        🆔 anilist:21 · mal:21 · kitsu:12 · imdb:tt0388629
    """.trimIndent()

    private val stream = Stream(
        addonParserPreset = AddonParserPreset.NEXIO_NAGARE,
        name = name, description = description, title = null,
        url = "https://cdn.example.com/x.m3u8", behaviorHints = null
    )

    @Test fun `parses synthetic filename via PTT`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("[Gojo] One Piece - S1E1 [1080p HLS].m3u8", info.filename)
        assertEquals("1080p", info.resolution)
    }

    @Test fun `populates indexer from 📡 line`() {
        val info = AioStrictStreamParser.parse(stream)
        assertNotNull(info.indexer)
    }

    @Test fun `populates matchInfo + episodeTitle + crossIds`() {
        val info = AioStrictStreamParser.parse(stream)
        assertEquals("HIGH", info.matchInfo?.confidence)
        assertEquals(200, info.matchInfo?.score)
        assertEquals("Romance Dawn", info.episodeTitle)
        assertEquals("21", info.crossIds["anilist"])
    }
}
```

- [ ] **Step 2: Run, verify pass (or fix gaps)**

```bash
./gradlew :app:testDebugUnitTest --tests com.nexio.tv.core.stream.AioStrictStreamParserNexioNagareTest 2>&1 | tail -5
# Expected: 3 tests pass; if any fail, tighten the parsers.
```

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserNexioNagareTest.kt
git commit -m "test(parser): verify NEXIO_NAGARE shape parses correctly"
```

---

## Phase E — Cross-repo round-trip + release notes

### Task 15: Shared cross-repo fixtures

**Files:**
- Create: `nexio/docs/superpowers/specs/2026-05-07-fixtures/torii-cached-realdebrid.json`
- Create: `nexio/docs/superpowers/specs/2026-05-07-fixtures/nagare-gojo-id-exact.json`
- Modify: `nexio-torii/tests/stream-formatter-roundtrip.test.js` (CREATE)
- Modify: `nexio-nagare/tests/stream-formatter-roundtrip.test.js` (CREATE)
- Modify: `nexio/app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserCrossRepoFixtureTest.kt` (CREATE)

- [ ] **Step 1: Create fixture file for torii cached RD**

```json
{
  "addon": "torii",
  "input": {
    "torrent": {
      "title": "[SubsPlease] One Piece - 1100 [1080p][AAC].mkv",
      "hash": "ABCDEF1234567890ABCDEF1234567890ABCDEF12",
      "size": "1.45 GiB",
      "seeders": 152,
      "source": "Nyaa.si",
      "pubDate": "2024-08-04T12:00:00Z"
    },
    "parsed": {
      "title": "One Piece", "resolution": "1080p", "quality": "BluRay", "encode": "HEVC",
      "audioTags": ["AAC"], "audioChannels": ["5.1"], "languages": ["JPN", "ENG"], "subtitles": ["ENG"],
      "releaseGroup": "SubsPlease", "network": "Crunchyroll", "visualTags": ["DV", "HDR10"],
      "episodes": [1100], "seasons": []
    },
    "canonical": {
      "name": "ONE PIECE", "englishName": "ONE PIECE", "year": 1999, "format": "TV",
      "episodes": 1100, "epMeta": { "1100": { "title": "Romance Dawn" } }, "idMal": 21,
      "anidb": "69", "kitsu": "12", "imdb": "tt0388629"
    },
    "debrid": {
      "serviceCode": "RD", "isCached": true,
      "selectedFile": { "name": "One Piece - 1100.mkv", "sizeBytes": 1500000000, "index": 0 }
    },
    "match": { "score": 152, "confidence": "HIGH", "reasons": ["title=100", "year_match"] },
    "anilistId": "21",
    "requestedEp": 1100,
    "expectedSeason": 1
  },
  "emitted": {
    "name": "1080p BluRay HEVC · DV HDR10 · 5.1\n⚡ RD · 🎙 JPN+ENG · 5.1 · 📝 ENG\n⛩ Torii",
    "description": "📄 One Piece - 1100.mkv\n💾 1.4 GiB · 👥 152 · 📅 _placeholder_h\n📡 Nyaa.si · SubsPlease · Crunchyroll\n🎬 ONE PIECE · 1999 · TV · 1100ep\n📺 S1E1100 · \"Romance Dawn\"\n🎯 HIGH (152) · title=100 · year_match\n🆔 anilist:21 · mal:21 · kitsu:12 · anidb:69 · imdb:tt0388629"
  },
  "parsed": {
    "filename": "One Piece - 1100.mkv",
    "resolution": "1080p", "quality": "BluRay",
    "serviceId": "RD", "isCached": true,
    "seeders": 152, "indexer": "Nyaa.si", "releaseGroup": "SubsPlease",
    "matchInfo": { "score": 152, "confidence": "HIGH" },
    "episodeTitle": "Romance Dawn",
    "crossIds": { "anilist": "21", "mal": "21", "kitsu": "12", "anidb": "69", "imdb": "tt0388629" }
  }
}
```

(`_placeholder_h` because `ageHours` is computed against `Date.now()`; the test should compare structurally, not literally.)

- [ ] **Step 2: Same for nagare-gojo-id-exact**

```json
{
  "addon": "nagare",
  "input": {
    "provider": { "id": "gojo", "displayName": "Gojo", "displayHost": "animetsu.live" },
    "providerStream": {
      "url": "https://mega-cloud.top/proxy/oppai/pahe/abc123",
      "server": "Gojo/pahe", "quality": "1080p", "dub": false,
      "headers": { "Referer": "https://animetsu.live/" },
      "subtitles": [{ "src": "https://x/eng.vtt", "label": "English" }, { "src": "https://x/jpn.vtt", "label": "Japanese" }]
    },
    "canonical": {
      "anilist": "21", "mal": "21", "anidb": "69", "kitsu": "12", "imdb": "tt0388629",
      "mainTitle": "ONE PIECE", "englishTitle": "ONE PIECE",
      "year": 1999, "format": "TV", "episodeCount": 1100, "episodeTitle": "Romance Dawn"
    },
    "match": { "score": 200, "confidence": "HIGH", "reasons": ["+200 anilist_id_exact (21)"], "source": "match" },
    "episode": 1, "season": 1
  },
  "emitted": {
    "name": "1080p · HLS\n🌊 Gojo · pahe · 📝 SUB\nDirect stream",
    "description": "📄 [Gojo] One Piece - S1E1 [1080p HLS].m3u8\n📡 Gojo · animetsu.live · server: pahe (mega-cloud.top)\n🎬 ONE PIECE · 1999 · TV · 1100ep\n📺 S1E1 · \"Romance Dawn\"\n📝 SUB · 📝 ENG, JPN\n🌐 Direct stream · no debrid required\n🎯 HIGH (200) · +200 anilist_id_exact (21)\n🆔 anilist:21 · mal:21 · kitsu:12 · anidb:69 · imdb:tt0388629"
  },
  "parsed": {
    "filename": "[Gojo] One Piece - S1E1 [1080p HLS].m3u8",
    "resolution": "1080p",
    "matchInfo": { "score": 200, "confidence": "HIGH" },
    "episodeTitle": "Romance Dawn",
    "crossIds": { "anilist": "21", "mal": "21", "kitsu": "12", "anidb": "69", "imdb": "tt0388629" }
  }
}
```

- [ ] **Step 3: Round-trip test in nagare**

```js
// nexio-nagare/tests/stream-formatter-roundtrip.test.js
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { enrichDirectStream, formatNagareStream } = require("../lib/stream-formatter");

const FIXTURE_DIR = "/Users/jneerdael/Scripts/nexio/docs/superpowers/specs/2026-05-07-fixtures";

test("nagare emission matches fixture", () => {
    const fixturePath = path.join(FIXTURE_DIR, "nagare-gojo-id-exact.json");
    if (!fs.existsSync(fixturePath)) {
        console.warn("fixture not found, skipping cross-repo round-trip");
        return;
    }
    const f = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
    const enriched = enrichDirectStream({
        provider: f.input.provider, providerStream: f.input.providerStream,
        canonical: f.input.canonical, episode: f.input.episode, season: f.input.season,
        match: f.input.match
    });
    const out = formatNagareStream(enriched, { url: f.input.providerStream.url, headers: f.input.providerStream.headers });
    assert.equal(out.name, f.emitted.name);
    assert.equal(out.description, f.emitted.description);
});
```

Same pattern in nexio-torii (with the placeholder substitution for `📅 ageHours`).

- [ ] **Step 4: Round-trip test in Nexio (Kotlin)**

```kotlin
// app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserCrossRepoFixtureTest.kt
package com.nexio.tv.core.stream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AioStrictStreamParserCrossRepoFixtureTest {

    private val mapper = ObjectMapper()

    private fun load(name: String): JsonNode? {
        val f = File("docs/superpowers/specs/2026-05-07-fixtures/$name")
        return if (f.exists()) mapper.readTree(f) else null
    }

    @Test fun `parses torii fixture into expected ParsedStreamInfo`() {
        val node = load("torii-cached-realdebrid.json") ?: return
        val emitted = node["emitted"]
        val expected = node["parsed"]
        val stream = Stream(
            addonParserPreset = AddonParserPreset.NEXIO_TORII,
            name = emitted["name"].asText(),
            description = emitted["description"].asText(),
            title = null, url = "https://x", behaviorHints = null
        )
        val info = AioStrictStreamParser.parse(stream)
        assertEquals(expected["filename"].asText(), info.filename)
        assertEquals(expected["resolution"].asText(), info.resolution)
        assertEquals(expected["serviceId"].asText(), info.serviceId)
        assertEquals(expected["isCached"].asBoolean(), info.isCached)
        assertEquals(expected["seeders"].asInt(), info.seeders)
    }

    @Test fun `parses nagare fixture into expected ParsedStreamInfo`() {
        val node = load("nagare-gojo-id-exact.json") ?: return
        val emitted = node["emitted"]
        val expected = node["parsed"]
        val stream = Stream(
            addonParserPreset = AddonParserPreset.NEXIO_NAGARE,
            name = emitted["name"].asText(),
            description = emitted["description"].asText(),
            title = null, url = "https://x", behaviorHints = null
        )
        val info = AioStrictStreamParser.parse(stream)
        assertEquals(expected["filename"].asText(), info.filename)
        assertEquals(expected["resolution"].asText(), info.resolution)
    }
}
```

- [ ] **Step 5: Run all three test suites**

```bash
# In each repo
cd /Users/jneerdael/Scripts/stremio-addons/nexio-nagare && npm test 2>&1 | tail -5
cd /Users/jneerdael/Scripts/stremio-addons/nexio-torii  && npm test 2>&1 | tail -5
cd /Users/jneerdael/Scripts/nexio                       && ./gradlew :app:testDebugUnitTest 2>&1 | tail -5
```

- [ ] **Step 6: Commit per-repo**

```bash
# nexio
git -C /Users/jneerdael/Scripts/nexio add docs/superpowers/specs/2026-05-07-fixtures/ \
    app/src/test/java/com/nexio/tv/core/stream/AioStrictStreamParserCrossRepoFixtureTest.kt
git -C /Users/jneerdael/Scripts/nexio commit -m "test(parser): cross-repo fixture-driven round-trip for Torii/Nagare"

# nagare
git -C /Users/jneerdael/Scripts/stremio-addons/nexio-nagare add tests/stream-formatter-roundtrip.test.js
git -C /Users/jneerdael/Scripts/stremio-addons/nexio-nagare commit -m "test: cross-repo round-trip against shared fixture"

# torii
git -C /Users/jneerdael/Scripts/stremio-addons/nexio-torii add tests/stream-formatter-roundtrip.test.js
git -C /Users/jneerdael/Scripts/stremio-addons/nexio-torii commit -m "test: cross-repo round-trip against shared fixture"
```

---

### Task 16: Release notes per repo

**Files:**
- Modify: `nexio-torii/readme.md`
- Modify: `nexio-nagare/readme.md`
- Modify: `nexio/CHANGELOG.md` (or whatever Nexio's release notes file is — check first)

- [ ] **Step 1: Check Nexio's release-note convention**

```bash
ls /Users/jneerdael/Scripts/nexio/CHANGELOG* /Users/jneerdael/Scripts/nexio/RELEASE_NOTES* 2>/dev/null
ls /Users/jneerdael/Scripts/nexio/docs/release-notes/ 2>/dev/null | tail -3
```

- [ ] **Step 2: Add a one-paragraph note to each readme + Nexio's release-notes**

In `nexio-torii/readme.md` and `nexio-nagare/readme.md`, add a "Universal-formatter integration" section noting that streams emit a richer parser-friendly shape recognised by the Nexio app's `NEXIO_TORII` / `NEXIO_NAGARE` presets, and that other Stremio clients fall back to the description-only render which is still readable.

In Nexio's release-notes equivalent, note the new presets, auto-detect by manifest ID, and the bundled drawables.

- [ ] **Step 3: Commit per repo**

```bash
git -C /Users/jneerdael/Scripts/stremio-addons/nexio-torii add readme.md && \
  git -C /Users/jneerdael/Scripts/stremio-addons/nexio-torii commit -m "docs: note Nexio universal-formatter integration"
git -C /Users/jneerdael/Scripts/stremio-addons/nexio-nagare add readme.md && \
  git -C /Users/jneerdael/Scripts/stremio-addons/nexio-nagare commit -m "docs: note Nexio universal-formatter integration"
git -C /Users/jneerdael/Scripts/nexio add docs/release-notes/* CHANGELOG.md 2>/dev/null && \
  git -C /Users/jneerdael/Scripts/nexio commit -m "docs: NEXIO_TORII / NEXIO_NAGARE parser presets" || true
```

---

## Self-Review

**Spec coverage:** Sections 4 (architecture), 5 (Torii emission), 6 (Nagare emission), 7 (Nexio parser), 8 (asset pipeline), 9 (testing) all map to specific tasks above. Section 11 implementation order = Phase A→B→C→D→E. ✓

**Placeholder scan:** Tasks 1-16 each contain real failing tests, real implementation code, real commands. No "TBD" / "TODO" / "implement later". The two known undefined helper integrations (StreamBadgeSupport `existingResolution`, AddonRepository `addAddon` integration point) are flagged for the engineer to find via `grep` in the steps that need them — that's appropriate "where to put it" guidance for navigating an existing codebase, not a placeholder.

**Type consistency:**
- `EnrichedTorrent` / `EnrichedDirectStream` field names match between enrich- modules and format- modules (Tasks 2/3 vs 6/7).
- `MatchInfo` data class fields (score, confidence, reasons) match the line-parser regex captures (Task 13).
- `ParsedStreamInfo` extension fields (matchInfo, episodeTitle, crossIds) match across Tasks 12, 13, 14, 15.
- `resolveAutoPreset(manifestId, userPick)` signature is consistent in Task 10.

**Scope check:** 16 tasks across 3 repos, sequenced into 5 phases. Each phase is independently shippable per the spec. Single plan is appropriate; subagent-driven execution can checkpoint between phases.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-07-nexio-formatter-integration.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Best for cross-repo work where each task is self-contained and the inter-task contracts (file paths, type signatures) are nailed down.

**2. Inline Execution** — I execute tasks in this session via the executing-plans skill, batched with checkpoints. Faster total wall-clock if the work goes smoothly, but each cross-repo cd/git overhead lives in this conversation's context.

**Which approach?**
