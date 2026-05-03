# Runtime Cache Network Proof

Use this procedure on a rooted/profileable device to prove whether `IntegrationRuntime` provider metadata calls used fresh cache or issued provider network requests.

## Scope

This proof covers calls that run through `IntegrationRuntime`, including provider metadata, identity, rail, and integration calls.

This proof does not cover Coil image fetches unless the poster request is routed through the integration poster fetcher. A provider metadata cache hit can still be followed by an independent Coil image network fetch. Image cache proof is separate and needs image-cache instrumentation.

## App Toggles

Enable these troubleshooting toggles before capturing evidence:

- Runtime & Metadata Trace mode: `INCLUDE_HTTP_SUMMARY`
- Logcat first paint: enabled
- Logcat metadata route: enabled
- Logcat integration runtime: enabled

## Quick Logcat Proof

Use the rooted/profileable device at `192.168.50.98:5555`.

```bash
ANDROID_SERIAL=192.168.50.98:5555 adb logcat -c
ANDROID_SERIAL=192.168.50.98:5555 adb logcat -v time -s Nexio.IntRuntime Nexio.MetaRoute Nexio.FirstPaint
```

Open the target detail screen once to populate the cache, back out, then open the same target again inside the cache TTL.

Fresh cached provider metadata must show a cache-hit decision with network suppression:

```text
runtime.cache_decision decision=HIT networkSuppressed=true
```

For that same `runtimeOperationId`, there must be no `http.request` entry. A matching `http.request` means the proof failed for that runtime operation.

## File Trace Proof

Start a runtime trace session in the app with `INCLUDE_HTTP_SUMMARY`, reproduce the first-open and second-open flow, stop the session, then pull and summarize the file trace:

```bash
ANDROID_SERIAL=192.168.50.98:5555 adb root
ANDROID_SERIAL=192.168.50.98:5555 adb shell ls -t /data/data/com.nexio.tv/files/traces
ANDROID_SERIAL=192.168.50.98:5555 adb pull /data/data/com.nexio.tv/files/traces/<session-id>/trace-events.jsonl ./trace-events.jsonl
scripts/trace-cache-proof.py ./trace-events.jsonl
```

The `ls -t` command lists newest sessions first. Use the session id that matches the just-stopped trace session.

## Kitsu One Piece Example

For a second open of the Kitsu One Piece detail screen inside the cache TTL, expected proof rows include:

| provider | cacheDecision | networkSuppressed | httpRequestCount |
| --- | --- | --- | --- |
| `KITSU` | `HIT` | `true` | `0` |

There should be no unexpired Kitsu metadata row with `cacheDecision` set to `MISS_THEN_NETWORK`.

## Interpreting Misses

The first run after a cache key bump can legitimately report `MISS_THEN_NETWORK` and `WRITE` because the old entry no longer satisfies the current key. The proof point is the second open inside the TTL: it should report `HIT`, `networkSuppressed=true`, and `httpRequestCount=0` for the same cached provider metadata operation.
