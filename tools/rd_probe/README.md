# Debrid Parallel Transport Probe

Local troubleshooting CLI for investigating debrid parallel range transport behavior (Real-Debrid and Premiumize).

## What it does

- authenticates with provider API credentials from `.env`
- resolves a large candidate automatically or by override
- runs controlled parallel range workers
- models in-order consumer delivery
- records worker-side and consumer-side telemetry
- can optionally start/stop `tcpdump`

## Quick start

```bash
cd tools/rd_probe
cp .env.example .env
# fill REALDEBRID_API_TOKEN (or PREMIUMIZE_API_KEY)
python -m rd_probe --help
python -m rd_probe run --provider realdebrid --parallel 4 --chunk-mb 16 --duration 120
python -m rd_probe run --provider premiumize --parallel 4 --chunk-mb 16 --duration 120
```

Optional packet capture:

```bash
python -m rd_probe run --parallel 4 --chunk-mb 16 --duration 120 --enable-pcap
```

## Candidate selection

Automatic selection is deterministic for both providers:

1. fetch provider media candidates
2. filter to playable video files with known size
3. choose the largest candidate by size
4. break size ties by newest listing time

Overrides:

- Real-Debrid: `--download-id`, `--torrent-id`
- Premiumize: `--item-id`
- `--direct-url` (debug-only)

## Output artifacts

Each run writes a timestamped directory under `runs/` by default:

- `session.json`
- `workers.jsonl`
- `consumer.jsonl`
- `ranges.csv`
- `summary.json`
- optional `capture.pcap`

## Notes

- Provider is selected by `--provider` (or `RD_PROBE_PROVIDER` in `.env`).
- Packet capture is optional and may require local privileges.
- The in-order consumer model is chunk-based for troubleshooting clarity.
