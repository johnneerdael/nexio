# Change: Collect completed debrid benchmark results in the self-hosted collector

## Why

Nexio can already collect shadow autoplay decisions in the self-hosted collector, but support work
also needs access to full provider benchmark sessions from real user devices. Those benchmark
results already include transport analysis and detected device capabilities, yet there is no
server-side ingestion/export path for them.

## What Changes

- Add a write-token `POST` endpoint to ingest completed provider benchmark results in
  `shadow-collector`.
- Add a read-token `GET` endpoint to export stored benchmark/device records for support analysis.
- Preserve full benchmark analysis payloads, including device capability snapshots and evidence.
- Add a separate debrid-settings opt-in toggle for benchmark collection in the Nexio app.
- Upload every completed provider benchmark result when that benchmark toggle is enabled.
- Exclude config benchmark results from this collector flow.

## Impact

- Affected app: `app`
- Affected self-hosted service: `shadow-collector`
- Affected settings surface: Debrid integration page
- Affected specs: `debrid-provider-benchmark`
- Affected persistence: new collector benchmark-events table/indexes plus app settings storage

