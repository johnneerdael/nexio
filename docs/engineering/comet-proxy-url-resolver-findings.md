# Comet addon proxy-URL resolution — findings and implementation guide

## Problem

After commit `51de488034` re-enabled HTTPS/TLS/MBEDTLS in the in-process FFmpeg
metadata probe, NEXIO's Dolby Vision autoplay gate started paying a ~5.5s
startup penalty on every autoplay. The probe now opens a real TLS session and
walks HTTP headers for every candidate stream before playback can begin.

When the stream URL is a direct CDN link that's cheap — a few hundred
milliseconds. When the URL is an addon proxy such as Comet's
`https://comet.feels.legal/<b64config>/playback/<hash>/<index>/<size_idx>/n/n`,
the probe stalls because Comet itself calls the debrid provider's `unrestrict`
API on the first request, which is a 3.5–4s server-side operation.

We cannot skip the probe without losing the DV profile-5 signal that the gate
depends on. The mitigation is to resolve the Comet proxy URL to the underlying
CDN URL *once* (ideally ahead of autoplay), cache the mapping, and hand the real
URL to FFmpeg.

## How Comet's `/playback/` endpoint behaves

Comet's source (`comet/api/stream.py`, line 853 onward) has two response modes:

1. **302 redirect** (default). The handler resolves the debrid download link and
   returns `RedirectResponse(download_link, status_code=302)` at line 1017.
2. **Proxy-bytes**. Only active when
   `PROXY_DEBRID_STREAM_PASSWORD == config["debridStreamProxyPassword"]`. In that
   case the handler reverse-proxies the debrid CDN bytes as a `StreamingResponse`.

Comet caches the resolved download link in a SQLite `download_links` table keyed
on `(debrid_key, hash, file_index)` with a 3600-second TTL. Subsequent requests
within that window are sub-second.

## Measured behaviour

Four distinct hashes were tested against a real production Comet instance
configured with Real-Debrid + Premiumize, no proxy password set (so the 302
branch is active):

```
curl -s -o /dev/null -D - \
     -H "Range: bytes=0-0" \
     --max-redirs 0 \
     "<comet playback url including torrent_name & name query params>"
```

| Hash prefix | Torrent flavour          | Status | Time (fresh) | Redirect target                                                      |
|-------------|--------------------------|--------|--------------|----------------------------------------------------------------------|
| `09b382fa`  | 2160p DV HDR10 AMZN      | 302    | 3.87s        | `43-4.download.real-debrid.com/d/UO52ZQKDGA7G4/…`                    |
| `1532583d`  | 2160p AMZN DV P5         | 302    | 3.71s        | `42-4.download.real-debrid.com/d/6OYFAMUG5EUVC/…`                    |
| `022aef82`  | 1080p WEBSCREENER x265   | 302    | 3.63s        | `41-4.download.real-debrid.com/d/R3LZ7WP35XBFY/…`                    |
| `0d02a17d`  | 2160p DV HDR10+ WEBRip   | 302    | 3.78s        | `6-cdn2-ovh-bea.energycdn.com/cdn3sto/…` (Premiumize path)           |

The same URL repeated within the TTL responded in 131ms — that is Comet's SQLite
cache speaking, not a second `unrestrict` call.

## Findings

1. **Uniform 302 behaviour.** Every tested URL followed the redirect branch.
   Without the proxy password the bytes-proxy branch is unreachable, so NEXIO
   only ever needs to handle the 302 case.
2. **Redirect targets are heterogeneous.** RD may hand back a direct
   `*.download.real-debrid.com` host, an RD `/d/<token>/…` mirror, or — when the
   resolution went through Premiumize — a third-party CDN like EnergyCDN. The
   resolver must be URL-opaque: cache whatever Comet returns in `Location`
   verbatim, don't try to validate or rewrite it.
3. **Query params are mandatory.** `/playback/<hash>/<index>/<size_idx>/n/n`
   without `?torrent_name=…&name=…` returns HTTP 422 with a FastAPI validation
   payload:
   ```json
   {"detail":[{"type":"missing","loc":["query","torrent_name"],"msg":"Field required","input":null},
              {"type":"missing","loc":["query","name"],"msg":"Field required","input":null}]}
   ```
   The resolver must forward the URL unchanged — don't strip the query string.
4. **HEAD is not supported on the GET handler.** A `HEAD /<b64config>/playback/…`
   returns `405 Method Not Allowed` with `allow: GET`. Use `GET` with
   `Range: bytes=0-0` to avoid downloading a body.
5. **Fresh-resolution cost is consistent at 3.6–3.9s.** That is well inside a
   pre-warm budget if we trigger resolution the moment the stream list arrives,
   not when the user (or autoplay) opens the player.
6. **Comet's cache (60 min) is longer than any reasonable in-process TTL.** A
   50-minute in-app LRU keeps us safely under Comet's TTL while surviving
   multiple autoplay attempts on the same content.

## Resolution procedure (reference)

To turn a Comet proxy URL into the underlying CDN URL:

1. Take the full proxy URL **including query string**. Do not rewrite, sort, or
   drop parameters; `torrent_name` and `name` are required by Comet's route.
2. Issue an HTTPS `GET` with:
   - `Range: bytes=0-0` (so any accidental body fetch is a single byte)
   - automatic redirects **disabled** (`followRedirects(false)` on OkHttp, or
     `--max-redirs 0` on curl)
   - any auth/custom headers the Stremio addon specified for the stream
   - a short timeout (8s is comfortable for a 4s worst case)
3. Inspect the response:
   - `302` + `Location: <url>` → cache `{proxyUrl → location}` and return
     `location`. This is the happy path for all observed traffic.
   - `200` (proxy-bytes mode) → the URL is *already* acting as a CDN and should
     be probed as-is. Cache `{proxyUrl → proxyUrl}` so we don't re-probe.
   - `4xx`/`5xx` → don't cache, fall back to the original URL and let the normal
     probe path handle it.
4. Store the mapping in a bounded LRU (≥64 entries) with a TTL under 60 minutes.
   Entries older than the TTL must be re-resolved; RD/Premiumize download links
   themselves typically expire on a similar window.

## Example (curl, reproducible)

```sh
URL='https://comet.feels.legal/<b64config>/playback/1532583de4348f3911e641fc0d10f0e3b33da68e/0/0/n/n?torrent_name=Avatar.Fire.And.Ash.2025.2160p.AMZN.WEB-DL.DV.P5%5BBen%20The%20Men%5D.mp4&name=Avatar%3A%20Fire%20and%20Ash&media_id=tt1757678'

curl -s -o /dev/null -D - \
     -H "Range: bytes=0-0" \
     --max-redirs 0 \
     -w "\n--\ntime_total: %{time_total}s\nhttp_code: %{http_code}\n" \
     "$URL"
```

Look for the `location:` header in the dumped response — that's the URL FFmpeg
should probe.

## Which URLs the resolver accepts

The resolver is gated to addons known to 302-redirect their `/playback/`-style
endpoints to the debrid CDN. Each addon's behaviour was verified against a real
production instance:

| Addon               | Example URL shape                                                                                   | Proxy path marker | Fresh latency | Cache TTL | `HEAD`?   |
|---------------------|------------------------------------------------------------------------------------------------------|-------------------|---------------|-----------|-----------|
| Comet               | `https://<host>/<b64>/playback/<hash>/<idx>/<size_idx>/n/n?torrent_name=…&name=…`                    | `/playback/`      | 3.6–3.9s      | 1h        | No        |
| Meteor              | `https://<host>/<b64>/play/<hash>/<a>/<b>/<c>/<file>?pv=2`                                           | `/play/`          | 2.1–3.4s      | —         | No        |
| StremThru Torz      | `https://<host>/stremio/torz/<b64>/_/strem/<imdbid>/rd/<hash>/<idx>/<file>`                          | `/_/strem/`       | similar       | 3h        | Yes       |
| Torrentio           | `https://torrentio.strem.fun/resolve/realdebrid/<apikey>/<hash>/null/<idx>/<file>`                   | `/resolve/`       | n/a           | n/a       | n/a       |

Torrentio is handled separately — its `/resolve/` endpoint encodes the target
URL inline in the path, so `FfmpegStreamMetadataProbe.extractEmbeddedResolveUrl`
decodes it locally and never issues a 302 probe.

### Gate logic

A URL is treated as a resolvable proxy if **either** of the following holds:

- **Gate A (host match):** The stream URL's host equals the host portion of the
  addon's own manifest URL. This covers self-hosted instances — we trust the
  addon to redirect us off its own host. Plumbed through from
  `Stream.addonBaseUrl` (set at fetch time in `StreamRepositoryImpl`).
- **Gate B (static allowlist + path marker):** The stream URL's host is in
  `knownProxyHosts` *and* the path contains one of the markers `/playback/`,
  `/play/`, or `/_/strem/`. The path constraint prevents false positives on
  non-proxy routes served by the same host (logos, health checks, etc.).

Either gate alone is tight; together they cover the "known production instance"
case (Gate B) and the "user self-hosts on a custom domain" case (Gate A).

### Current static allowlist

Comet instances:
- `comet.feels.legal`
- `cometfortheweebs.midnightignite.me`
- `comet.elfhosted.com`
- `comet.stremio.ru`

Meteor instance:
- `meteorfortheweebs.midnightignite.me`

StremThru instances:
- `stremthru.atbphosting.com`
- `stremthrufortheweebs.midnightignite.me`
- `stremthru.elfhosted.com`
- `stremthru.fortheweak.cloud`
- `stremthru.13377001.xyz`
- `stremthru.stremio.ru`

If a user points NEXIO at a new self-hosted instance, Gate A catches it
automatically — no allowlist update needed. The static list is only required
for paths where the addon context is not available (e.g., cache-hit fast path
in the stream screen, probe-side blocking resolve).

## Applying this in NEXIO

- Introduce a `ProxyUrlResolver` (`@Singleton`, Hilt-injected) exposing
  `resolve(url, headers): String?` (blocking) and
  `prewarm(url, headers)` (fire-and-forget on an IO dispatcher). Internally it
  uses an OkHttp client with `followRedirects(false)`, a bounded LRU
  (`proxyUrl → realUrl`, ≥64 entries, ~50-minute TTL), and in-flight
  deduplication so concurrent callers coalesce onto a single network round-trip.
- Teach `FfmpegStreamMetadataProbe.probeBlocking` to consult the resolver cache
  before the native probe. Extend the existing `/resolve/` filter at
  `app/src/main/java/com/nexio/tv/core/player/FfmpegStreamMetadataProbe.kt:108`
  and the `resolveDirectProbeUrl` / `isResolveProxyUrl` helpers at line 183 so
  Comet's `/playback/<hash>/…` pattern is also recognised.
- Wire `resolver.prewarm(...)` into the autoplay scorer at
  `app/src/main/java/com/nexio/tv/core/player/StreamAutoPlaySelector.kt:38` so
  every candidate begins resolving the moment the stream list is ranked. If the
  autoplay gate still races the resolver, add a second prewarm call at the
  point the stream list first arrives in `StreamScreenViewModel`.
- Leave the DV autoplay gate untouched. It still calls
  `FfmpegStreamMetadataProbe.probe(...)`; the probe now just receives the real
  CDN URL instead of the Comet proxy URL, so DV profile-5 detection and the
  fallback loop behave exactly as before — only faster.

## Anti-patterns to avoid

- Don't key the cache on `(hash, index)` — Stremio addons sometimes re-sign the
  same underlying file with different configs or wrappers. Key on the full URL.
- Don't skip the probe for proxy URLs. The whole point of this exercise is to
  keep the DV signal.
- Don't assume the `Location` points at `*.real-debrid.com`. Premiumize/other
  debrid paths resolve to arbitrary CDN hostnames.
- Don't use `HEAD`. Comet's `/playback/` route only accepts `GET`.
- Don't drop or reorder query parameters — Comet's FastAPI validator rejects
  the request before it ever looks up the cache.
