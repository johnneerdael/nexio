# Benchmark-Aware Scoring Variant Report

| Variant | Objective | Top-1 | Acceptable | Pairwise |
|---|---:|---:|---:|---:|
| default.json | 7.500 | 100.0% | 100.0% | 75.0% |
| immersive-heavy.json | 7.500 | 100.0% | 100.0% | 75.0% |
| transport-conservative.json | 7.500 | 100.0% | 100.0% | 75.0% |
| pcm-heavy.json | 6.833 | 91.7% | 91.7% | 70.0% |
| bitrate-strict.json | 6.833 | 91.7% | 91.7% | 70.0% |

## Variant: default.json

- No failed scenarios

## Variant: immersive-heavy.json

- No failed scenarios

## Variant: transport-conservative.json

- No failed scenarios

## Variant: pcm-heavy.json

### Failed Scenarios

| Scenario | Selected | Expected | Failure |
|---|---|---|---|
| audio-fallback-1 | truehd_pcm | ddp_atmos | wrong_winner |
| dv-profile5-autoplay-fallback | dv_primary | hdr10_fallback | wrong_winner |
| dv-probe-unknown-autoplay-fallback | dv_primary | hdr10_fallback | wrong_winner |

### Failure Slices

| Category | Count | Scenarios |
|---|---:|---|
| wrong_winner | 3 | audio-fallback-1, dv-profile5-autoplay-fallback, dv-probe-unknown-autoplay-fallback |

## Variant: bitrate-strict.json

### Failed Scenarios

| Scenario | Selected | Expected | Failure |
|---|---|---|---|
| remux-vs-webdl | webdl_stream | remux_stream | wrong_winner |
| dv-profile5-autoplay-fallback | dv_primary | hdr10_fallback | wrong_winner |
| dv-probe-unknown-autoplay-fallback | dv_primary | hdr10_fallback | wrong_winner |

### Failure Slices

| Category | Count | Scenarios |
|---|---:|---|
| wrong_winner | 3 | dv-profile5-autoplay-fallback, dv-probe-unknown-autoplay-fallback, remux-vs-webdl |
