# Benchmark-Aware Scoring Corpus Format

This sample corpus demonstrates the on-disk format used by the benchmark-aware scorer tuning harness.
It now includes parser-backed movie/TV scenarios, report fixtures for the expanded corpus, and generated-variant sweep output.

## Layout

- `manifest.json` — declares the corpus name, dataset files, and built-in variant config files.
- `datasets/*.json` — one or more scenario datasets, including parser-backed filename scenarios that still override size/runtime for bitrate math.
- `variants/*.json` — tunable scoring configs to compare.
- `reports/*.md` — sample human-readable reports produced by the harness.
- `templates/template-scenario.json` — starter scenario template for adding more labeled cases.

## Manifest

```json
{
  "corpusVersion": 1,
  "name": "sample-benchmark-scoring-corpus",
  "datasets": [
    "datasets/audio-fallback.json",
    "datasets/lotr-return-of-the-king-movie.json",
    "datasets/tv-hevc-ddp-vs-av1-webdl.json"
  ],
  "variants": ["variants/default.json", "variants/pcm-heavy.json"]
}
```

## CLI examples

Evaluate the corpus with one explicit config:

```bash
./gradlew --no-daemon :app:testDebugUnitTest --tests com.nexio.tv.data.repository.benchmark.BenchmarkAwareScoringCorpusTest
```

Run the harness directly in application code with:

- `--corpus-dir <dir>`
- optional `--config <config.json>` for single-config evaluation
- optional `--variant <config.json>` repeated for explicit variant sweeps
- optional `--generate-variants <dir>` to emit the built-in grid (`default`, `pcm-heavy`, `immersive-heavy`, `bitrate-strict`, `transport-conservative`)
- optional `--output <file>` for JSON output
- optional `--report-md <file>` for Markdown output

## Semantics

Each scenario contains:
- request metadata
- provider benchmark snapshots
- candidate streams, optionally parsed via the real filename parser
- expected winner
- acceptable winners
- optional pairwise preferences

This supports:
- exact winner scoring
- acceptable winner scoring
- pairwise ranking accuracy
- variant objective ranking
