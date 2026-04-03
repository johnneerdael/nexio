# Benchmark-Aware Scoring Variant Report

| Variant | Objective | Top-1 | Acceptable | Pairwise |
|---|---:|---:|---:|---:|
| default.json | 8.000 | 100.0% | 100.0% | 100.0% |
| pcm-heavy.json | 7.167 | 88.9% | 88.9% | 91.7% |

## Variant: default.json

- No failed scenarios

## Variant: pcm-heavy.json

### Failed Scenarios

| Scenario | Selected | Expected | Failure |
|---|---|---|---|
| audio-fallback-1 | truehd_pcm | ddp_atmos | wrong_winner |

### Failure Slices

| Category | Count | Scenarios |
|---|---:|---|
| wrong_winner | 1 | audio-fallback-1 |
