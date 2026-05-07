# Codec-Fallback Audio Scoring Design

**Status:** Approved (pending user review of this written form)
**Author:** brainstorming session, 2026-04-28
**Scope:** `BenchmarkAwareStreamScorer` audio tier candidate construction

## Why

The autoplay scorer's audio side has two correctness gaps that hurt picks for legitimately good streams on partial-decode devices:

1. **Atmos without container co-tag is ambiguous.** When a release title carries an `atmos` tag but no `truehd` or `ddp` co-tag, `detectAudioTierCandidates` adds *both* `TRUEHD_ATMOS` and `DDP_ATMOS` candidates regardless of source format. WEB-DL Atmos always rides on a DDP (E-AC3) base; BluRay/REMUX Atmos always rides on TrueHD. The scorer should disambiguate using the parsed release type that is already plumbed through the call graph.
2. **DTS family has no fallback ladder.** A `DTS:X` track on a DTS-HD-MA-only receiver scores **−16** today (rejected/penalized), even though every DTS:X bitstream contains a DTS-HD MA layer the receiver can decode. Same for DTS-HD MA on DTS-only receivers. The decode-chain backward compatibility (DTS:X → DTS-HD MA → DTS core) is not modeled.

Result: tracks that the receiver *can* decode at a lower-but-still-good layer are scored as if the device can't play them at all, dropping them out of contention even though they would deliver a better experience than the alternatives.

## Goal

Make the audio score reflect the **highest layer the receiver can actually decode**, by emitting an ordered fallback ladder per detected primary tier and using the existing "first-supported-wins" mechanic to select the resolved tier.

## Decisions locked

These were settled during brainstorming and are not open for revisiting in this design:

1. **Source-format awareness.** `releaseType` is threaded into the candidate construction. Already classified upstream via `classifyReleaseType(parsed)` — no new parsing.
2. **Score equality semantics.** When an Atmos track resolves to its DDP base on an E-AC3-only receiver, it scores **+10** (DDP base points), exactly equal to a true DDP track. Tiebreakers (channel count, bitrate, etc.) decide between them. Achieved naturally by the ladder-pick mechanic — no separate "min" computation.
3. **DTS:X with DTS-HD-MA support but no DTS:X.** Score equal to DTS-HD MA (**+12**) — no firmware-future bonus.
4. **Unknown-source Atmos default.** Default to DDP base (most-permissive — E-AC3 is universal in modern Android TV/AVRs, WEB-DL is the streaming default).

## Non-goals

- **No new user-facing toggle.** This is a correctness fix to an existing scorer, not a feature behind a flag.
- **No AC3 fallback below TRUEHD or DDP.** Backwards compatibility with AC3-only receivers is not part of the spec. A TrueHD track on an AC3-only receiver continues to score −12, a DDP track on an AC3-only receiver continues to score −10.
- **No changes to the score table** (`audio` map in `BenchmarkAwareStreamScoringConfig`). Base points per tier are unchanged.
- **No changes to non-audio scoring** (resolution, HDR, bitrate, release-type-points, synergy bonus computation).
- **No tracker/upstream parser changes.** The `parsed.audioTags` and `parsed.quality` strings already contain everything the scorer needs.

## Architecture

Single-file change. The mechanic for resolving "first supported tier in candidate list" already exists at `BenchmarkAwareStreamScorer.kt:977-1003` (`resolveAudioScoringDecision`). Untouched.

The change has two components:

1. **`detectAudioTierCandidates(tags)` at `BenchmarkAwareStreamScorer.kt:1006-1034`** gains a `releaseType: ShadowReleaseType` parameter and replaces its current candidate-emission logic with the ladder rules below.

2. **`audioTierSupported(tier, device)` at `BenchmarkAwareStreamScorer.kt:1036-1052`** receives a one-line tightening for the two Atmos tiers. Today both `TRUEHD_ATMOS` and `DDP_ATMOS` return `true` whenever the *base layer* is decodable (TrueHD passthrough or E-AC3 passthrough respectively), which causes the scorer to award full Atmos points to tracks the receiver cannot actually decode at the Atmos layer. After the fix, both Atmos tiers require genuine `output.atmos.passthroughLikely`. The candidate ladder constructed in step 1 carries the base layer (`TRUEHD` or `DDP`) as the next entry, so the ladder-pick mechanic falls through cleanly to the base-layer score when Atmos passthrough is missing.

   ```kotlin
   // before
   ShadowAudioTier.TRUEHD_ATMOS -> output.truehd.passthroughLikely
   ShadowAudioTier.DDP_ATMOS    -> output.atmos.passthroughLikely || output.eac3.passthroughLikely

   // after
   ShadowAudioTier.TRUEHD_ATMOS -> output.atmos.passthroughLikely
   ShadowAudioTier.DDP_ATMOS    -> output.atmos.passthroughLikely
   ```

   This is a behavior fix that `detectAudioTierCandidates` alone cannot achieve — without it, the higher Atmos tier always claims support and the ladder never falls through.

**Call-graph delta:**

```
StreamScreenViewModel.scoreWithManualCap(...)
  └─ evaluateStreamWithManualCap(stream)
      ├─ classifyReleaseType(parsed)             ← already runs
      └─ buildContentScoreBreakdown(..., releaseType)        [+1 param]
          └─ resolveAudioScoringDecision(audioTags, device, releaseType)  [+1 param]
              └─ detectAudioTierCandidates(tags, device, releaseType)     [+1 param]
                  └─ <NEW: ladder logic per tier>
```

Three function signatures gain a `releaseType` parameter; threading is mechanical.

## Ladder rules

For each primary signal detected from the title tags, `detectAudioTierCandidates` emits an ordered list of `ShadowAudioTier`. The scorer picks the first entry whose `passthroughLikely` flag is true and uses that tier's base points.

| Detected primary signal | Disambiguator | Candidate ladder (in order) |
|---|---|---|
| `atmos` + `truehd` co-tag | — | `[TRUEHD_ATMOS, TRUEHD]` |
| `atmos` + `ddp` co-tag | — | `[DDP_ATMOS, DDP]` |
| `atmos` (no container co-tag) | release ∈ {`REMUX`, `BLURAY_ENCODE`} | `[TRUEHD_ATMOS, TRUEHD]` |
| `atmos` (no container co-tag) | release ∉ {`REMUX`, `BLURAY_ENCODE`} (incl. `WEBDL`, `WEBRIP`, encodes, `HDTV`, `HDRIP`, `UNKNOWN`, …) | `[DDP_ATMOS, DDP]` |
| `dts:x` | — | `[DTSX, DTSHD, DTS]` |
| `dts-hd` | — | `[DTSHD, DTS]` |
| `truehd` (no `atmos`) | — | `[TRUEHD]` |
| `ddp` / `e-ac3` (no `atmos`) | — | `[DDP]` |
| `ac3` | — | `[AC3]` |
| `dts` (core only) | — | `[DTS]` |
| Nothing recognized | — | `[OTHER]` |

**Co-tag override:** When `atmos` appears with an explicit `truehd` or `ddp` co-tag in the title, the co-tag wins regardless of `releaseType`. The title metadata is more reliable than the release-classification heuristic for the rare cross-source cases (`BluRay.Atmos.DDP+5.1` etc.). Documented inline.

**Both `truehd` and `ddp` co-tags present** (very rare: `Atmos.DDP+.TrueHD`): TrueHD wins. Justification: TrueHD-base implies the higher-quality source. Documented inline.

## Edge cases

1. **Release type `UNKNOWN`** (parser couldn't classify the title): treated identically to `WEBDL` for ladder purposes — DDP base. No new logging; the existing scorer log line at the relevant level surfaces release type already.
2. **Empty audio tags / no recognized format:** falls through to `[OTHER]`, score 0. Unchanged.
3. **Device snapshot is `null`** (capability detection failed or pre-snapshot path): `audioTierSupported` returns `true` for every tier (existing behavior at line 1040), so the first ladder entry wins by default. Without device info, we trust the title.
4. **Synergy bonus interaction.** Synergy (UHD + HDR + premium audio = +3 to +6) checks the **resolved** tier against `PREMIUM_AUDIO_TIERS`. An Atmos-on-DDP track that resolves to `DDP` on an E-AC3-only receiver does **not** receive the synergy bonus (DDP is not in the premium set). This is intentional — full equivalence with a real DDP track, including absence of synergy. Confirmed in brainstorming.
5. **Existing harness test** at `BenchmarkAwareScoringHarnessTest.kt` passes audio tags through scoring; it'll need a `releaseType` in any direct calls to changed functions. Default to `ShadowReleaseType.WEBDL` in test setup.

## Tests

New file: `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareAudioFallbackScoringTest.kt`.

| # | Audio tags | Release type | Device support (passthroughLikely) | Expected resolved tier | Expected score |
|---|---|---|---|---|---|
| 1 | `[atmos, ddp]` | WEBDL | eac3 only | `DDP` | +10 |
| 2 | `[atmos, ddp]` | WEBDL | atmos + eac3 | `DDP_ATMOS` | +16 |
| 3 | `[atmos, truehd]` | REMUX | truehd only | `TRUEHD` | +12 |
| 4 | `[atmos, truehd]` | REMUX | atmos + truehd | `TRUEHD_ATMOS` | +16 |
| 5 | `[atmos]` | WEBDL | eac3 only | `DDP` (default → DDP base) | +10 |
| 6 | `[atmos]` | REMUX | truehd only | `TRUEHD` (default → TrueHD base) | +12 |
| 7 | `[atmos]` | UNKNOWN | eac3 only | `DDP` (UNKNOWN treated as WEBDL) | +10 |
| 8 | `[atmos]` | BLURAY_ENCODE | atmos | `TRUEHD_ATMOS` | +16 |
| 9 | `[dts:x]` | REMUX | dtshd only | `DTSHD` | +12 |
| 10 | `[dts:x]` | REMUX | dts only | `DTS` | +7 |
| 11 | `[dts:x]` | REMUX | dtsx + dtshd + dts | `DTSX` | +16 |
| 12 | `[dts-hd]` | BLURAY_ENCODE | dts only | `DTS` | +7 |
| 13 | `[dts-hd]` | BLURAY_ENCODE | dtshd | `DTSHD` | +12 |
| 14 | `[truehd]` (no atmos) | REMUX | ac3 only | `TRUEHD` (unsupported, negated) | -12 |
| 15 | `[ddp]` (no atmos) | WEBDL | ac3 only | `DDP` (unsupported, negated) | -10 |
| 16 | `[atmos, truehd]` | WEBDL (override) | atmos | `TRUEHD_ATMOS` | +16 |
| 17 | `[atmos, ddp]` | REMUX (override) | atmos | `DDP_ATMOS` | +16 |
| 18 | `[]` (none recognized) | WEBDL | full support | `OTHER` | 0 |

**Coverage rationale:**
- Cases 1–4 lock the explicit-co-tag path.
- Cases 5–8 lock the release-type-derived default for tag-only Atmos.
- Cases 9–13 lock the DTS family ladder.
- Cases 14–15 confirm we did **not** introduce speculative AC3 fallbacks.
- Cases 16–17 confirm explicit co-tags override release type.
- Case 18 confirms unrecognized tracks still resolve to `OTHER`.

**Test seam:** call `detectAudioTierCandidates(tags, device, releaseType)` directly to assert the candidate list ordering, then call `resolveAudioScoringDecision(...)` for the score-resolution cases. This isolates ladder construction from device-support checks.

## Out of scope (potential follow-ups, not part of this work)

- AC3 fallback for TrueHD or DDP tracks on AC3-only receivers.
- Tracking unknown-release-type rate in production telemetry to validate the WEBDL default assumption.
- Surfacing the resolved tier in the autoplay decision UI ("DDP via Atmos fallback") — would need a dedicated label in `event_detail.html` on the collector side.
- Synergy bonus for resolved-DDP-from-Atmos tracks (would require expanding `PREMIUM_AUDIO_TIERS` or computing synergy off the *detected* tier, which is a behavior change beyond this spec).
