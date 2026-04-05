# Nexio Shadow Collector

Self-hosted FastAPI service for collecting shadow autoplay decision payloads.

## Features
- `POST /api/v1/shadow-autoplay-events` write-token ingestion
- `GET /api/v1/shadow-autoplay-events` read-token bulk export for LLM analysis
- `POST /api/v1/debrid-benchmark-results` write-token ingestion for completed provider benchmarks
- `GET /api/v1/debrid-benchmark-results` read-token export for support analysis
- Optional `client.androidId` support for POST ingestion and GET filtering
- Session-authenticated dashboard at `/`
- SQLite storage with indefinite retention by default
- Clear-all action from dashboard

## Run locally
1. Copy `.env.example` to `.env` and fill secrets.
2. `docker compose up --build`
3. Visit `http://localhost:8000`

Deploy behind HTTPS/reverse proxy for `https://datacollection.nexioapp.org`.
