# Real-Debrid Parallel Transport Probe

Local troubleshooting CLI for investigating Real-Debrid parallel range transport behavior.

## What it does

- authenticates with Real-Debrid from `.env`
- resolves a large candidate automatically or by override
- runs controlled parallel range workers
- models in-order consumer delivery
- records worker-side and consumer-side telemetry
- can optionally start/stop `tcpdump`

## Quick start

```bash
cd tools/rd_probe
cp .env.example .env
# fill REALDEBRID_API_TOKEN
python -m rd_probe --help
python -m rd_probe run --parallel 4 --chunk-mb 16 --duration 120
```

Optional packet capture:

```bash
python -m rd_probe run --parallel 4 --chunk-mb 16 --duration 120 --enable-pcap
```

## Candidate selection

Automatic selection is deterministic:

1. fetch Real-Debrid downloads/torrents
2. filter to playable video files with known size
3. choose the largest candidate by size
4. break size ties by newest listing time

Overrides:

- `--download-id`
- `--torrent-id`
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

- The probe is Real-Debrid only in phase 1.
- Packet capture is optional and may require local privileges.
- The in-order consumer model is chunk-based for troubleshooting clarity.
