# Benchmark Autoplay Scoring Matrix

This document reflects the **current simplified benchmark autoplay scorer** implemented in:

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScoringConfig.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`

It is meant as a policy/audit reference so scoring changes can be reviewed against the actual code.

## 1. Core selection flow

For each debrid-wrapped candidate:

1. Resolve provider benchmark session.
2. Reject streams that fail hard constraints.
3. Compute transport viability and choose the best transport mode for that provider.
4. Compute a **simplified score** from only:
   - video codec
   - HDR
   - audio
   - transport ratio
5. Build a **viable bitrate bucket** from the highest viable bitrate.
6. Collapse lower resolutions only after that bitrate bucket is formed.
7. Final ordering inside the bucket is:
   - total score
   - stream bitrate
   - safe budget
   - startup TTFB
   - seek p95

## 2. Final score formula

```text
finalScore = contentQualityScore + transportFitScore
```

```text
contentQualityScore = codecPoints + hdrPoints + audioPoints
```

```text
transportFitScore = ratioScore
```

Important:
- **startup and seek do not add score**
- **stability does not add score**
- **resolution does not add score**
- **release/source type does not add score**
- **bitrate realism does not add score**
- **synergy bonuses do not add score**
- **penalties do not add score**

## 3. Hard rejects

| Reject reason | Meaning |
|---|---|
| `NOT_DEBRID_WRAPPED` | Not mapped to a benchmark provider |
| `MISSING_BENCHMARK` | No benchmark session for the provider |
| `MISSING_SIZE` | Missing usable stream size |
| `MISSING_RUNTIME` | Runtime unavailable when needed |
| `UNSUPPORTED_CODEC` | Device snapshot says codec unsupported |
| `INSUFFICIENT_TRANSPORT_BUDGET` | Candidate is not transport-viable |
| `NO_ELIGIBLE_TRANSPORT` | No direct/optimized transport profile was eligible |

## 4. Candidate bucket logic

### 4.1 Transport viability first

A candidate must first survive transport viability.

```text
requiredMbps = averageBitrateMbps * burstMargin(releaseType)
suitabilityRatio = safeBudgetMbps / requiredMbps
```

Minimum ratio required:

- `1.15`

Anything below that is not eligible.

### 4.2 Viable bitrate bucket

After viable candidates are known:

1. Find the **highest viable bitrate** candidate.
2. Keep all viable candidates within **30% below** that bitrate.

```text
bitrateFloor = highestViableBitrate * 0.70
keep if candidateBitrate >= bitrateFloor
```

### 4.3 Resolution collapse inside the bucket

After the viable bitrate bucket is formed:

- if the bucket contains **3+ qualifying 2160p candidates**, drop lower resolutions
  - qualifying 2160p means release type is one of:
    - `WEBDL`
    - `BLURAY_ENCODE`
    - `REMUX`
- else if the bucket contains **3+ 1080p candidates**, drop lower resolutions
- else if the bucket contains **3+ 720p candidates**, drop lower resolutions
- else keep the bucket as-is

Resolution is therefore a **bucket filter**, not a scoring reward.

## 5. Release type classification

Release type is still classified because it affects burst margin and the 2160p collapse rule.

| Rule | Release type |
|---|---|
| quality contains `remux` | `REMUX` |
| quality contains `blu` | `BLURAY_ENCODE` |
| quality contains `web-dl` / `webdl` | `WEBDL` |
| quality contains `webrip` / `web-rip` | `WEBRIP` |
| bitrate `>= 30 Mbps` | `HIGH_BITRATE_ENCODE` |
| bitrate `>= 10 Mbps` | `NORMAL_ENCODE` |
| bitrate `> 0 Mbps` | `SMALL_ENCODE` |
| otherwise | `UNKNOWN` |

## 6. Burst margins

| Release type | Burst margin |
|---|---:|
| `SMALL_ENCODE` | 1.20 |
| `NORMAL_ENCODE` | 1.35 |
| `HIGH_BITRATE_ENCODE` | 1.45 |
| `WEBDL` | 1.35 |
| `WEBRIP` | 1.35 |
| `BLURAY_ENCODE` | 1.35 |
| `REMUX` | 1.60 |
| `UNKNOWN` | 1.35 |

## 7. Transport ratio score

Movie and show paths now use the **same ratio score table**.

| Suitability ratio | Ratio score |
|---|---:|
| `< 1.15` | 0 / ineligible |
| `1.15 - <1.20` | 5 |
| `1.20 - <1.25` | 10 |
| `>= 1.25` | 15 |

Startup and seek values are kept in breakdown/ordering, but they are **not score inputs**.

## 8. Codec score matrix

| Codec tier | Points |
|---|---:|
| `AV1_HW` | 14 |
| `HEVC_HW` | 10 |
| `H264_HW` | 4 |
| `OTHER` | 0 |
| `HEVC_SW` | -10 |
| `VC1` | -16 |
| `MPEG2` | -16 |
| `AV1_SW` | -18 |
| `UNSUPPORTED` | -24 |

Notes:
- Unsupported codec hard-rejects the stream.
- For 4K remuxes with unknown codec tags, the scorer may still infer HEVC from device decode capability.

## 9. HDR score matrix

### 9.1 Base HDR points

| HDR tier | Points |
|---|---:|
| `DOLBY_VISION` | 16 |
| `HDR10_PLUS` | 12 |
| `HDR10` | 7 |
| `HLG` | 4 |
| `SDR` | 0 |

### 9.2 Parsed HDR tag mapping

| Parsed tag(s) | Parsed HDR tier |
|---|---|
| `DV`, `Dolby Vision`, `DoVi` | `DOLBY_VISION` |
| `HDR10+` | `HDR10_PLUS` |
| `HDR10` or `HDR` | `HDR10` |
| `HLG` | `HLG` |
| no HDR tag | `SDR` |

### 9.3 Effective HDR scoring logic

The scorer does **not** multiply base HDR points by a generic support multiplier anymore.

Instead it computes an **effective HDR tier** and scores that directly.

#### Dolby Vision

| Condition | Effective tier | Score |
|---|---|---:|
| Display supports DV | `DOLBY_VISION` | 16 |
| DV unsupported, but HDR10 supported | `HDR10` fallback | 7 |
| DV unsupported, HDR10 unsupported | unsupported | 0 |

#### HDR10+

| Condition | Effective tier | Score |
|---|---|---:|
| Display supports HDR10+ | `HDR10_PLUS` | 12 |
| HDR10+ unsupported, but HDR10 supported | `HDR10` fallback | 7 |
| HDR10+ unsupported, HDR10 unsupported | unsupported | 0 |

#### HDR10

| Condition | Effective tier | Score |
|---|---|---:|
| Display supports HDR10 or HDR10+ | `HDR10` | 7 |
| Otherwise | unsupported | 0 |

#### HLG

| Condition | Effective tier | Score |
|---|---|---:|
| Display supports HLG | `HLG` | 4 |
| Otherwise | unsupported | 0 |

#### SDR

| Condition | Effective tier | Score |
|---|---|---:|
| always | `SDR` | 0 |

### 9.4 HDR ranking summary

```text
Dolby Vision > HDR10+ > HDR10 > HLG > SDR
```

with fallback rules:

```text
DV unsupported + HDR10 supported     -> HDR10 score
HDR10+ unsupported + HDR10 supported -> HDR10 score
otherwise unsupported HDR            -> 0
```

## 10. Audio score matrix

Audio support tiers/multipliers/downgrade penalties are no longer used for scoring.

Each stream gets **one** audio score.

### 10.1 Base audio points

| Audio tier | Supported score | Unsupported score |
|---|---:|---:|
| `TRUEHD_ATMOS` | 16 | -16 |
| `DTSX` | 16 | -16 |
| `DDP_ATMOS` | 16 | -16 |
| `TRUEHD` | 12 | -12 |
| `DTSHD` | 12 | -12 |
| `DDP` | 10 | -10 |
| `AC3` | 7 | -7 |
| `DTS` | 7 | -7 |
| `OTHER` | 0 | 0 |

### 10.2 Audio detection behavior

The scorer builds an ordered list of possible audio tiers from parsed tags.

Examples:

| Parsed audio tags | Candidate tiers considered |
|---|---|
| `Atmos`, `TrueHD` | `TRUEHD_ATMOS`, `DDP_ATMOS`, `TRUEHD` |
| `Atmos`, `DD+` | `DDP_ATMOS`, `TRUEHD_ATMOS`, `DDP` |
| `Atmos` only | `TRUEHD_ATMOS`, `DDP_ATMOS` |
| `DTS:X` | `DTSX` |
| `DTS-HD MA` | `DTSHD` |
| `TrueHD` | `TRUEHD` |
| `DD+` | `DDP` |
| `AC3` | `AC3` |
| `DTS` | `DTS` |

### 10.3 Best-audio selection rule

The scorer walks the candidate audio tiers from best to worst and chooses:

1. the **highest supported** tier, if any
2. otherwise the **highest detected** tier as a negative score

This means:

- if a stream has both a high unsupported format and a lower supported format, the lower supported format wins
- if none of the detected formats are supported, the best detected format becomes a negative score

### 10.4 Example from the current rule

If a stream has:

- `DTSX`
- `Atmos`
- `AC3`

and only `AC3` is supported, then the stream gets:

```text
+7
```

If `AC3` is also unsupported, then the stream gets the **highest numeric unsupported score** (closest to zero), not the harshest one:

```text
-7
```

### 10.5 Audio support checks used by the scorer

Current support checks are passthrough-oriented:

| Audio tier | Supported when |
|---|---|
| `TRUEHD_ATMOS` | `device.audioOutput.truehd.passthroughLikely` |
| `DTSX` | `device.audioOutput.dtsx.passthroughLikely` |
| `TRUEHD` | `device.audioOutput.truehd.passthroughLikely` |
| `DTSHD` | `device.audioOutput.dtshd.passthroughLikely` |
| `DDP_ATMOS` | `device.audioOutput.atmos.passthroughLikely` OR `eac3.passthroughLikely` |
| `DDP` | `device.audioOutput.eac3.passthroughLikely` |
| `AC3` | `device.audioOutput.ac3.passthroughLikely` |
| `DTS` | `device.audioOutput.dts.passthroughLikely` |

## 11. Final ordering inside the bucket

After viable bucket filtering and simplified scoring:

1. higher `finalScore`
2. higher `contentQualityScore`
3. higher **stream bitrate** (`averageBitrateMbps`)
4. higher **safe budget** (`safeBudgetMbps`)
5. lower `startupTtfbMs`
6. lower `seekTtfbP95Ms`

This means startup/seek are real tie-breakers, not score contributors.

## 12. Worked examples

### Example A: Dolby Vision unsupported but HDR10 supported

| Stream | Parsed HDR | Display support | Effective HDR | HDR score |
|---|---|---|---|---:|
| DV remux | DV | no DV, yes HDR10 | HDR10 fallback | 7 |

### Example B: HLG supported vs SDR

| Stream | HDR score |
|---|---:|
| HLG stream on HLG-capable display | 4 |
| SDR stream | 0 |

### Example C: unsupported top audio, supported lower audio

Stream tags:

- `Atmos`
- `AC3`

Device support:

- Atmos unsupported
- AC3 supported

Result:

```text
audioScore = +7
```

### Example D: viable bitrate bucket

If the highest viable bitrate candidate is `100 Mbps`, then the bucket floor is:

```text
100 * 0.70 = 70 Mbps
```

Only candidates at `>= 70 Mbps` remain in the viable bitrate bucket before resolution collapse.

## 13. What no longer matters to score

These concepts may still appear in legacy breakdown fields/logging structures, but they are no longer active score drivers:

- resolution points
- release/source points
- bitrate realism points
- synergy points
- penalty points
- startup score
- seek score
- stability score
- audio support multipliers
- generic HDR multipliers

If they appear in a legacy serialized breakdown, they should effectively be read as **inactive / zero-impact** under the simplified scorer.

## 14. Files to inspect when changing policy

- `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScoringConfig.kt`
- `app/src/main/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorer.kt`
- `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareStreamScorerTest.kt`
- `app/src/test/java/com/nexio/tv/data/repository/benchmark/BenchmarkAwareScoringHarnessTest.kt`
- `app/src/test/java/com/nexio/tv/core/stream/AioStrictFileParserParityTest.kt`
