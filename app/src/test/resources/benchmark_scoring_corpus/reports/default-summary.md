# Benchmark-Aware Scoring Evaluation Report

- Scenarios: 9
- Top-1 accuracy: 100.0%
- Acceptable accuracy: 100.0%
- Pairwise accuracy: 100.0%

## Scenario Results

| Scenario | Selected | Expected | Top match | Acceptable | Pairwise | Failure |
|---|---|---|---:|---:|---:|---|
| audio-fallback-1 | ddp_atmos | ddp_atmos | yes | yes | 1/1 | none |
| dv-vs-hdr10plus | dv_stream | dv_stream | yes | yes | 1/1 | none |
| remux-vs-webdl | remux_stream | remux_stream | yes | yes | 1/1 | none |
| fake-4k-penalty | healthy_1080p | healthy_1080p | yes | yes | 1/1 | none |
| av1-vs-hevc | av1_stream | av1_stream | yes | yes | 1/1 | none |
| dtshd-core-vs-pcm-vs-passthrough | dtshd_passthrough | dtshd_passthrough | yes | yes | 2/2 | none |
| lotr-return-of-the-king-movie | framestor | framestor | yes | yes | 2/2 | none |
| tv-hevc-ddp-vs-av1-webdl | hevc_ddp_atmos | hevc_ddp_atmos | yes | yes | 1/1 | none |
| movie-webdl-non-remux-quality-pack | healthy_webdl | healthy_webdl | yes | yes | 2/2 | none |
