import json
import os
import base64
import hashlib
import secrets
import sqlite3
import logging
import time
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.security.utils import get_authorization_scheme_param
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field
from starlette.middleware.sessions import SessionMiddleware

BASE_DIR = Path(__file__).resolve().parent
TEMPLATES = Jinja2Templates(directory=str(BASE_DIR / "templates"))
SQLITE_PATH = os.environ.get("SHADOW_COLLECTOR_SQLITE_PATH", "/data/shadow_autoplay.db")
WRITE_TOKEN = os.environ.get("SHADOW_COLLECTOR_WRITE_TOKEN", "")
READ_TOKEN = os.environ.get("SHADOW_COLLECTOR_READ_TOKEN", "")
SESSION_SECRET = os.environ.get("SHADOW_COLLECTOR_SESSION_SECRET", "change-me")
ADMIN_USERNAME = os.environ.get("SHADOW_COLLECTOR_ADMIN_USERNAME", "admin")
ADMIN_PASSWORD = os.environ.get("SHADOW_COLLECTOR_ADMIN_PASSWORD", "")

app = FastAPI(title="Nexio Shadow Collector", version="1.1.0")
LOGGER = logging.getLogger(__name__)
app.add_middleware(SessionMiddleware, secret_key=SESSION_SECRET)
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")


class ClientInfo(BaseModel):
    appVersion: Optional[str] = None
    buildType: Optional[str] = None
    deviceModel: Optional[str] = None
    sdkInt: Optional[int] = None
    androidId: Optional[str] = None


class ShadowAutoplayEnvelope(BaseModel):
    sentAtMs: int = Field(default_factory=lambda: int(time.time() * 1000))
    client: ClientInfo = Field(default_factory=ClientInfo)
    payload: dict[str, Any]


class DebridBenchmarkEnvelope(BaseModel):
    sentAtMs: int = Field(default_factory=lambda: int(time.time() * 1000))
    client: ClientInfo = Field(default_factory=ClientInfo)
    result: dict[str, Any]


def db() -> sqlite3.Connection:
    Path(SQLITE_PATH).parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(SQLITE_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def init_db() -> None:
    with closing(db()) as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS shadow_autoplay_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                received_at_ms INTEGER NOT NULL,
                sent_at_ms INTEGER NOT NULL,
                event_type TEXT,
                request_id TEXT,
                video_id TEXT,
                content_type TEXT,
                title TEXT,
                selected_provider TEXT,
                selected_transport TEXT,
                selected_score INTEGER,
                winner_count INTEGER NOT NULL DEFAULT 0,
                rejected_count INTEGER NOT NULL DEFAULT 0,
                client_app_version TEXT,
                client_build_type TEXT,
                client_device_model TEXT,
                client_sdk_int INTEGER,
                client_android_id TEXT,
                raw_json TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS debrid_benchmark_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                received_at_ms INTEGER NOT NULL,
                sent_at_ms INTEGER NOT NULL,
                provider TEXT NOT NULL,
                measured_at_ms INTEGER NOT NULL,
                termination_reason TEXT NOT NULL,
                client_app_version TEXT,
                client_build_type TEXT,
                client_device_model TEXT,
                client_sdk_int INTEGER,
                client_android_id TEXT,
                device_model TEXT,
                device_manufacturer TEXT,
                raw_json TEXT NOT NULL
            );
            """
        )
        ensure_column(conn, "shadow_autoplay_events", "client_android_id", "TEXT")
        ensure_column(conn, "debrid_benchmark_events", "client_android_id", "TEXT")
        conn.executescript(
            """
            CREATE INDEX IF NOT EXISTS idx_shadow_received_at ON shadow_autoplay_events(received_at_ms DESC);
            CREATE INDEX IF NOT EXISTS idx_shadow_request_id ON shadow_autoplay_events(request_id);
            CREATE INDEX IF NOT EXISTS idx_shadow_video_id ON shadow_autoplay_events(video_id);
            CREATE INDEX IF NOT EXISTS idx_shadow_selected_provider ON shadow_autoplay_events(selected_provider);
            CREATE INDEX IF NOT EXISTS idx_shadow_android_id ON shadow_autoplay_events(client_android_id);
            CREATE INDEX IF NOT EXISTS idx_benchmark_received_at ON debrid_benchmark_events(received_at_ms DESC);
            CREATE INDEX IF NOT EXISTS idx_benchmark_provider ON debrid_benchmark_events(provider);
            CREATE INDEX IF NOT EXISTS idx_benchmark_measured_at ON debrid_benchmark_events(measured_at_ms DESC);
            CREATE INDEX IF NOT EXISTS idx_benchmark_device_model ON debrid_benchmark_events(device_model);
            CREATE INDEX IF NOT EXISTS idx_benchmark_android_id ON debrid_benchmark_events(client_android_id);
            """
        )
        conn.commit()


@app.on_event("startup")
def startup() -> None:
    init_db()


def ensure_column(conn: sqlite3.Connection, table_name: str, column_name: str, column_type: str) -> None:
    columns = {
        row["name"]
        for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()
    }
    if column_name not in columns:
        conn.execute(f"ALTER TABLE {table_name} ADD COLUMN {column_name} {column_type}")


def require_bearer(request: Request, expected_token: str) -> None:
    auth = request.headers.get("Authorization", "")
    scheme, token = get_authorization_scheme_param(auth)
    if scheme.lower() != "bearer" or not expected_token or not secrets.compare_digest(token, expected_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unauthorized")


def parse_raw_json(raw_json: str | dict[str, Any] | None) -> dict[str, Any]:
    if isinstance(raw_json, dict):
        return raw_json
    if not raw_json:
        return {}
    try:
        return json.loads(raw_json)
    except json.JSONDecodeError:
        return {}


def first_non_blank(*values: Any) -> Optional[str]:
    for value in values:
        if isinstance(value, str):
            normalized = value.strip()
            if normalized:
                return normalized
        elif value is not None:
            return str(value)
    return None


PUBLIC_DASHBOARD_HASH_ALGORITHMS = ("sha3-512", "sha-512")
PUBLIC_DASHBOARD_HASH_FUNCTIONS = {
    "sha3-512": hashlib.sha3_512,
    "sha-512": hashlib.sha512,
}


def format_timestamp(epoch_ms: Optional[int]) -> str:
    if not epoch_ms:
        return "—"
    dt = datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc).astimezone()
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def format_size(size_bytes: Optional[int]) -> str:
    if not size_bytes or size_bytes <= 0:
        return "—"
    units = ["B", "KB", "MB", "GB", "TB"]
    value = float(size_bytes)
    unit_index = 0
    while value >= 1024.0 and unit_index < len(units) - 1:
        value /= 1024.0
        unit_index += 1
    formatted = f"{value:.1f}" if value < 10 and unit_index > 0 else f"{value:.0f}"
    return f"{formatted} {units[unit_index]}"


def format_duration(duration_ms: Optional[int]) -> str:
    if not duration_ms or duration_ms <= 0:
        return "—"
    total_minutes = int(round(duration_ms / 60000.0))
    hours, minutes = divmod(total_minutes, 60)
    if hours:
        return f"{hours}h {minutes}m"
    return f"{minutes}m"


def derive_public_dashboard_token(android_id: str) -> str:
    return base64.urlsafe_b64encode(
        hashlib.sha3_512(android_id.encode("utf-8")).digest()
    ).decode("ascii").rstrip("=")


def derive_public_dashboard_tokens(android_id: str) -> set[str]:
    """Return supported dashboard tokens for known hashing algorithms."""
    android_id_bytes = android_id.encode("utf-8")
    return {
        base64.urlsafe_b64encode(PUBLIC_DASHBOARD_HASH_FUNCTIONS[algorithm](android_id_bytes).digest())
        .decode("ascii")
        .rstrip("=")
        for algorithm in PUBLIC_DASHBOARD_HASH_ALGORITHMS
    }


def resolve_public_token_android_id(
    conn: sqlite3.Connection,
    token: str,
) -> Optional[str]:
    if not token or not token.strip():
        return None
    candidate_rows = conn.execute(
        """
        SELECT DISTINCT client_android_id AS android_id FROM shadow_autoplay_events
        WHERE client_android_id IS NOT NULL AND TRIM(client_android_id) != ''
        UNION
        SELECT DISTINCT client_android_id AS android_id FROM debrid_benchmark_events
        WHERE client_android_id IS NOT NULL AND TRIM(client_android_id) != ''
        """
    ).fetchall()
    for row in candidate_rows:
        android_id = row["android_id"]
        if not android_id:
            continue
        if token in derive_public_dashboard_tokens(str(android_id)):
            return str(android_id)
    return None


def compute_bitrate_mbps(size_bytes: Optional[int], duration_ms: Optional[int]) -> Optional[float]:
    if not size_bytes or not duration_ms or size_bytes <= 0 or duration_ms <= 0:
        return None
    return round((float(size_bytes) * 8.0) / float(duration_ms) / 1000.0, 2)


def format_bitrate_mbps(value: Optional[float]) -> str:
    if value is None:
        return "—"
    return f"{value:.2f} Mbps"


def format_ratio(value: Optional[float]) -> str:
    if value is None:
        return "—"
    return f"{value:.2f}"


def titleize_slug(value: str) -> str:
    return value.replace("_", " ").replace("-", " ").strip().title() if value else "—"


def primary_hdr_label(hdr_tags: list[str], visual_tags: list[str]) -> str:
    tags = [tag for tag in hdr_tags if tag] + [tag for tag in visual_tags if tag]
    return tags[0] if tags else "none"


def primary_audio_label(audio_tags: list[str]) -> str:
    return audio_tags[0] if audio_tags else "none"


def build_stream_summary(stream: dict[str, Any]) -> str:
    provider = stream.get("provider") or "none"
    transport = stream.get("transport") or "-"
    service = stream.get("service") or "unknown"
    file_name = stream.get("file") or stream.get("stream_key") or "unknown"
    size_label = stream.get("size_label") or "—"
    score = stream.get("score")
    ratio_label = stream.get("ratio_label") or "—"
    resolution = stream.get("resolution") or "—"
    hdr = stream.get("hdr") or "none"
    audio = stream.get("audio") or "none"
    score_label = score if score is not None else "—"
    return (
        f"winner={provider} {transport} service={service} file={file_name} size={size_label} "
        f"score={score_label} ratio={ratio_label} resolution={resolution} hdr={hdr} audio={audio}"
    )


def parse_stream_candidate(item: dict[str, Any] | None, status_label: str, reasons: Optional[list[str]] = None) -> Optional[dict[str, Any]]:
    if not item:
        return None
    parsed = item.get("parsed") or {}
    size_bytes = parsed.get("sizeBytes")
    duration_ms = parsed.get("durationMs")
    hdr_tags = list(item.get("hdrTags") or [])
    visual_tags = list(parsed.get("visualTags") or [])
    audio_tags = list(item.get("audioTags") or parsed.get("audioTags") or [])
    audio_channels = list(parsed.get("audioChannels") or [])
    ratio = item.get("suitabilityRatio")
    candidate = {
        "stream_key": item.get("streamKey") or "unknown",
        "status": status_label,
        "provider": item.get("provider") or "—",
        "transport": item.get("transport") or "—",
        "service": first_non_blank(parsed.get("serviceId"), item.get("provider")) or "—",
        "file": first_non_blank(parsed.get("filename"), item.get("streamKey")) or "—",
        "folder_name": parsed.get("folderName") or "—",
        "size_bytes": size_bytes,
        "size_label": format_size(size_bytes),
        "duration_ms": duration_ms,
        "duration_label": format_duration(duration_ms),
        "bitrate_mbps": compute_bitrate_mbps(size_bytes, duration_ms),
        "score": item.get("finalScore"),
        "ratio": ratio,
        "ratio_label": format_ratio(ratio),
        "resolution": first_non_blank(item.get("resolution"), parsed.get("resolution")) or "—",
        "quality": parsed.get("quality") or "—",
        "video_codec": parsed.get("videoCodec") or "—",
        "hdr": primary_hdr_label(hdr_tags, visual_tags),
        "audio": primary_audio_label(audio_tags),
        "audio_channels": ", ".join(audio_channels) if audio_channels else "—",
        "audio_tags": audio_tags,
        "hdr_tags": hdr_tags or visual_tags,
        "languages": ", ".join(parsed.get("languages") or []) or "—",
        "release_group": parsed.get("releaseGroup") or "—",
        "cached": parsed.get("cached"),
        "required_mbps": item.get("requiredMbps"),
        "safe_budget_mbps": item.get("safeBudgetMbps"),
        "content_quality_score": item.get("contentQualityScore"),
        "transport_fit_score": item.get("transportFitScore"),
        "reasons": [titleize_slug(reason) for reason in (reasons or item.get("reasons") or [])],
        "breakdown": item.get("breakdown") or {},
        "runtime_source": parsed.get("runtimeSource") or "—",
    }
    candidate["bitrate_label"] = format_bitrate_mbps(candidate["bitrate_mbps"])
    candidate["summary_line"] = build_stream_summary(candidate)
    candidate["search_blob"] = " ".join(
        str(part)
        for part in [
            candidate["provider"],
            candidate["transport"],
            candidate["service"],
            candidate["folder_name"],
            candidate["file"],
            candidate["resolution"],
            candidate["hdr"],
            candidate["audio"],
            candidate["quality"],
            candidate["languages"],
            candidate["release_group"],
            " ".join(candidate["reasons"]),
        ]
        if part not in (None, "—")
    ).lower()
    return candidate


def build_bitrate_chart(candidates: list[dict[str, Any]]) -> dict[str, Any]:
    items = [candidate for candidate in candidates if candidate.get("bitrate_mbps") is not None]
    if not items:
        return {
            "has_data": False,
            "data_points": [],
            "max_mbps": None,
            "message": "No bitrate data available for this event yet.",
        }
    max_mbps = max(candidate["bitrate_mbps"] for candidate in items)
    chart_items = []
    for candidate in sorted(items, key=lambda entry: entry["bitrate_mbps"], reverse=True):
        width_pct = 100.0 if max_mbps <= 0 else max(8.0, (candidate["bitrate_mbps"] / max_mbps) * 100.0)
        chart_items.append(
            {
                "label": candidate["file"],
                "provider": candidate["provider"],
                "status": candidate["status"],
                "value_label": candidate["bitrate_label"],
                "width_pct": round(width_pct, 2),
            }
        )
    return {
        "has_data": True,
        "data_points": chart_items,
        "max_mbps": format_bitrate_mbps(max_mbps),
        "message": None,
    }


def summarize(payload: dict[str, Any], envelope: ShadowAutoplayEnvelope) -> dict[str, Any]:
    request = payload.get("request") or {}
    selected = payload.get("selected") or {}
    winners = payload.get("winners") or []
    rejected = payload.get("rejected") or []
    return {
        "received_at_ms": int(time.time() * 1000),
        "sent_at_ms": envelope.sentAtMs,
        "event_type": payload.get("event_type"),
        "request_id": request.get("requestId"),
        "video_id": request.get("videoId"),
        "content_type": request.get("contentType"),
        "title": request.get("title"),
        "selected_provider": selected.get("provider"),
        "selected_transport": selected.get("transport"),
        "selected_score": selected.get("finalScore"),
        "winner_count": len(winners),
        "rejected_count": len(rejected),
        "client_app_version": envelope.client.appVersion,
        "client_build_type": envelope.client.buildType,
        "client_device_model": envelope.client.deviceModel,
        "client_sdk_int": envelope.client.sdkInt,
        "client_android_id": envelope.client.androidId,
        "raw_json": json.dumps(
            {
                "sentAtMs": envelope.sentAtMs,
                "client": envelope.client.model_dump(),
                "payload": payload,
            },
            separators=(",", ":"),
        ),
    }


def summarize_benchmark(result: dict[str, Any], envelope: DebridBenchmarkEnvelope) -> dict[str, Any]:
    provider = first_non_blank(result.get("provider"))
    measured_at_ms = result.get("measuredAtMs")
    termination_reason = first_non_blank(result.get("terminationReason"))
    device = result.get("device")
    if provider is None or not isinstance(measured_at_ms, int) or measured_at_ms <= 0:
        raise HTTPException(status_code=422, detail="Invalid benchmark payload")
    if termination_reason != "completed":
        raise HTTPException(status_code=422, detail="Only completed benchmark results are accepted")
    if not isinstance(device, dict) or not device:
        raise HTTPException(status_code=422, detail="Benchmark payload must include device capabilities")

    return {
        "received_at_ms": int(time.time() * 1000),
        "sent_at_ms": envelope.sentAtMs,
        "provider": provider,
        "measured_at_ms": measured_at_ms,
        "termination_reason": termination_reason,
        "client_app_version": envelope.client.appVersion,
        "client_build_type": envelope.client.buildType,
        "client_device_model": envelope.client.deviceModel,
        "client_sdk_int": envelope.client.sdkInt,
        "client_android_id": envelope.client.androidId,
        "device_model": first_non_blank(device.get("model")),
        "device_manufacturer": first_non_blank(device.get("manufacturer")),
        "raw_json": json.dumps(
            {
                "sentAtMs": envelope.sentAtMs,
                "client": envelope.client.model_dump(),
                "result": result,
            },
            separators=(",", ":"),
        ),
    }


def build_event_view(row: dict[str, Any]) -> dict[str, Any]:
    envelope = parse_raw_json(row.get("raw_json"))
    payload = envelope.get("payload") or {}
    request_data = payload.get("request") or {}
    client_data = envelope.get("client") or {}

    selected = parse_stream_candidate(payload.get("selected"), "Selected")
    winners = [
        candidate
        for candidate in [parse_stream_candidate(item, "Winner") for item in payload.get("winners") or []]
        if candidate is not None
    ]
    rejected = [
        candidate
        for candidate in [
            parse_stream_candidate(item, "Rejected", reasons=item.get("reasons") or [])
            for item in payload.get("rejected") or []
        ]
        if candidate is not None
    ]

    selected_key = selected.get("stream_key") if selected else None
    normalized_winners = []
    for winner in winners:
        if selected_key and winner["stream_key"] == selected_key:
            winner["status"] = "Selected"
        normalized_winners.append(winner)

    candidates = []
    seen_streams: set[str] = set()
    for candidate in ([selected] if selected else []) + normalized_winners + rejected:
        if not candidate:
            continue
        stream_key = candidate["stream_key"]
        if stream_key in seen_streams:
            continue
        seen_streams.add(stream_key)
        candidates.append(candidate)

    result_line = selected["summary_line"] if selected else f"winner=none eligible={len(normalized_winners)} rejected={len(rejected)}"
    title = first_non_blank(row.get("title"), request_data.get("title"), row.get("video_id"), f"Event {row.get('id')}") or "Untitled"

    event = {
        "id": row.get("id"),
        "title": title,
        "received_at_ms": row.get("received_at_ms"),
        "received_at_label": format_timestamp(row.get("received_at_ms")),
        "sent_at_ms": row.get("sent_at_ms"),
        "sent_at_label": format_timestamp(row.get("sent_at_ms")),
        "event_type": row.get("event_type") or payload.get("event_type") or "—",
        "request_id": row.get("request_id") or request_data.get("requestId") or "—",
        "video_id": row.get("video_id") or request_data.get("videoId") or "—",
        "content_type": row.get("content_type") or request_data.get("contentType") or "—",
        "winner_count": row.get("winner_count") or len(normalized_winners),
        "rejected_count": row.get("rejected_count") or len(rejected),
        "client_android_id": row.get("client_android_id") or client_data.get("androidId"),
        "client": {
            "appVersion": row.get("client_app_version") or client_data.get("appVersion"),
            "buildType": row.get("client_build_type") or client_data.get("buildType"),
            "deviceModel": row.get("client_device_model") or client_data.get("deviceModel"),
            "sdkInt": row.get("client_sdk_int") or client_data.get("sdkInt"),
            "androidId": row.get("client_android_id") or client_data.get("androidId"),
        },
        "selected": selected,
        "winners": normalized_winners,
        "rejected": rejected,
        "candidates": candidates,
        "bitrate_chart": build_bitrate_chart(candidates),
        "benchmarks_used": payload.get("benchmarksUsed") or [],
        "selected_non_dv_fallback": parse_stream_candidate(payload.get("selectedNonDolbyVisionFallback"), "Fallback"),
        "timings_ms": payload.get("timingsMs"),
        "result_line": result_line,
        "raw_json_pretty": json.dumps(envelope, indent=2),
        "request_cards": [
            {"label": "Request ID", "value": row.get("request_id") or request_data.get("requestId") or "—"},
            {"label": "Video ID", "value": row.get("video_id") or request_data.get("videoId") or "—"},
            {"label": "Content Type", "value": row.get("content_type") or request_data.get("contentType") or "—"},
            {"label": "Season", "value": request_data.get("season") or "—"},
            {"label": "Episode", "value": request_data.get("episode") or "—"},
            {"label": "Runtime", "value": f"{request_data.get('runtimeMinutes')}m" if request_data.get("runtimeMinutes") else "—"},
        ],
        "client_cards": [
            {"label": "App Version", "value": row.get("client_app_version") or client_data.get("appVersion") or "—"},
            {"label": "Build Type", "value": row.get("client_build_type") or client_data.get("buildType") or "—"},
            {"label": "Device Model", "value": row.get("client_device_model") or client_data.get("deviceModel") or "—"},
            {"label": "SDK", "value": row.get("client_sdk_int") or client_data.get("sdkInt") or "—"},
            {"label": "Android ID", "value": row.get("client_android_id") or client_data.get("androidId") or "—"},
            {"label": "Received", "value": format_timestamp(row.get("received_at_ms"))},
            {"label": "Sent", "value": format_timestamp(row.get("sent_at_ms"))},
        ],
    }
    event["search_blob"] = " ".join(
        part.lower()
        for part in [
            str(event["id"]),
            event["title"],
            event["request_id"],
            event["video_id"],
            event["content_type"],
            row.get("client_android_id") or client_data.get("androidId"),
            result_line,
            " ".join(candidate["search_blob"] for candidate in candidates),
        ]
        if part and part != "—"
    )
    return event


def build_public_event_view(row: dict[str, Any]) -> dict[str, Any]:
    event = build_event_view(row)
    event["client"]["androidId"] = None
    event["client_android_id"] = None
    event["client_cards"] = [
        card for card in event["client_cards"] if card["label"] != "Android ID"
    ]
    event.pop("raw_json_pretty", None)
    event.pop("search_blob", None)
    return event


def build_benchmark_view(row: dict[str, Any]) -> dict[str, Any]:
    envelope = parse_raw_json(row.get("raw_json"))
    client = envelope.get("client") or {}
    result = envelope.get("result") or {}
    device = result.get("device") or {}
    summary = result.get("summary") or {}
    return {
        "id": row.get("id"),
        "received_at_ms": row.get("received_at_ms"),
        "received_at_label": format_timestamp(row.get("received_at_ms")),
        "sent_at_ms": row.get("sent_at_ms"),
        "sent_at_label": format_timestamp(row.get("sent_at_ms")),
        "provider": row.get("provider") or result.get("provider") or "—",
        "measured_at_ms": row.get("measured_at_ms") or result.get("measuredAtMs"),
        "measured_at_label": format_timestamp(row.get("measured_at_ms") or result.get("measuredAtMs")),
        "termination_reason": row.get("termination_reason") or result.get("terminationReason") or "—",
        "client": {
            "appVersion": row.get("client_app_version") or client.get("appVersion"),
            "buildType": row.get("client_build_type") or client.get("buildType"),
            "deviceModel": row.get("client_device_model") or client.get("deviceModel"),
            "sdkInt": row.get("client_sdk_int") or client.get("sdkInt"),
            "androidId": row.get("client_android_id") or client.get("androidId"),
        },
        "client_android_id": row.get("client_android_id") or client.get("androidId"),
        "device_model": row.get("device_model") or device.get("model"),
        "device_manufacturer": row.get("device_manufacturer") or device.get("manufacturer"),
        "summary": summary,
        "result": result,
        "search_blob": " ".join(
            str(part).lower()
            for part in [
                row.get("provider"),
                row.get("client_android_id"),
                row.get("device_model"),
                row.get("device_manufacturer"),
                client.get("deviceModel"),
                client.get("androidId"),
                result.get("provider"),
                device.get("model"),
                device.get("manufacturer"),
                result.get("terminationReason"),
                result.get("candidate", {}).get("filename"),
            ]
            if part
        ),
    }


def build_public_benchmark_download_payload(row: dict[str, Any]) -> dict[str, Any]:
    envelope = parse_raw_json(row.get("raw_json"))
    result = envelope.get("result") or {}
    candidate = result.get("candidate") or {}
    device = result.get("device") or {}
    summary = result.get("summary") or {}
    comparison = result.get("comparison") or {}

    return {
        "provider": row.get("provider") or result.get("provider") or "—",
        "measuredAtMs": row.get("measured_at_ms") or result.get("measuredAtMs"),
        "terminationReason": row.get("termination_reason") or result.get("terminationReason") or "—",
        "candidate": {
            "filename": candidate.get("filename"),
            "sizeBytes": candidate.get("sizeBytes"),
            "host": candidate.get("host"),
        },
        "summary": {
            "startupTimeMs": summary.get("startupTimeMs"),
            "sustainedThroughputMbps": summary.get("sustainedThroughputMbps"),
            "transferredBytes": summary.get("transferredBytes"),
            "elapsedMs": summary.get("elapsedMs"),
        },
        "device": {
            "model": row.get("device_model") or device.get("model"),
            "manufacturer": row.get("device_manufacturer") or device.get("manufacturer"),
            "sdkInt": row.get("client_sdk_int") or device.get("sdkInt"),
        },
        "capabilitySummary": {
            "sustainedWinner": comparison.get("sustainedWinner"),
            "seekWinner": comparison.get("seekWinner"),
            "stabilityWinner": comparison.get("stabilityWinner"),
        },
    }


def filter_events(
    events: list[dict[str, Any]],
    query: str,
    provider: Optional[str],
    content_type: Optional[str],
    transport: Optional[str],
    android_id: Optional[str],
) -> list[dict[str, Any]]:
    filtered = events
    if query:
        normalized_query = query.strip().lower()
        filtered = [event for event in filtered if normalized_query in event["search_blob"]]
    if provider:
        filtered = [event for event in filtered if event.get("selected", {}).get("provider") == provider]
    if content_type:
        filtered = [event for event in filtered if event.get("content_type") == content_type]
    if transport:
        filtered = [event for event in filtered if event.get("selected", {}).get("transport") == transport]
    if android_id:
        filtered = [event for event in filtered if (event.get("client_android_id") or "") == android_id]
    return filtered


def sort_events(events: list[dict[str, Any]], sort_key: str, direction: str) -> list[dict[str, Any]]:
    reverse = direction != "asc"
    key_functions = {
        "received": lambda event: event.get("received_at_ms") or 0,
        "title": lambda event: event.get("title") or "",
        "score": lambda event: (event.get("selected") or {}).get("score") or -1,
        "ratio": lambda event: (event.get("selected") or {}).get("ratio") or -1.0,
        "size": lambda event: (event.get("selected") or {}).get("size_bytes") or -1,
        "bitrate": lambda event: (event.get("selected") or {}).get("bitrate_mbps") or -1.0,
        "provider": lambda event: (event.get("selected") or {}).get("provider") or "",
    }
    sorter = key_functions.get(sort_key, key_functions["received"])
    return sorted(events, key=sorter, reverse=reverse)


def filter_benchmarks(
    items: list[dict[str, Any]],
    query: str,
    provider: Optional[str],
    android_id: Optional[str],
) -> list[dict[str, Any]]:
    filtered = items
    if query:
        normalized_query = query.strip().lower()
        filtered = [item for item in filtered if normalized_query in item["search_blob"]]
    if provider:
        filtered = [item for item in filtered if item.get("provider") == provider]
    if android_id:
        filtered = [item for item in filtered if (item.get("client_android_id") or "") == android_id]
    return filtered


def sort_benchmarks(items: list[dict[str, Any]], sort_key: str, direction: str) -> list[dict[str, Any]]:
    reverse = direction != "asc"
    key_functions = {
        "received": lambda item: item.get("received_at_ms") or 0,
        "measured": lambda item: item.get("measured_at_ms") or 0,
        "provider": lambda item: item.get("provider") or "",
        "device": lambda item: item.get("device_model") or "",
    }
    sorter = key_functions.get(sort_key, key_functions["received"])
    return sorted(items, key=sorter, reverse=reverse)


def fetch_shadow_rows(
    conn: sqlite3.Connection,
    provider: Optional[str] = None,
    content_type: Optional[str] = None,
    transport: Optional[str] = None,
    android_id: Optional[str] = None,
) -> list[dict[str, Any]]:
    sql = "SELECT * FROM shadow_autoplay_events WHERE 1=1"
    params: list[Any] = []
    if provider:
        sql += " AND selected_provider = ?"
        params.append(provider)
    if content_type:
        sql += " AND content_type = ?"
        params.append(content_type)
    if transport:
        sql += " AND selected_transport = ?"
        params.append(transport)
    if android_id:
        sql += " AND client_android_id = ?"
        params.append(android_id)
    sql += " ORDER BY received_at_ms DESC"
    return [dict(row) for row in conn.execute(sql, tuple(params)).fetchall()]


def fetch_benchmark_rows(
    conn: sqlite3.Connection,
    provider: Optional[str] = None,
    android_id: Optional[str] = None,
) -> list[dict[str, Any]]:
    sql = "SELECT * FROM debrid_benchmark_events WHERE 1=1"
    params: list[Any] = []
    if provider:
        sql += " AND provider = ?"
        params.append(provider)
    if android_id:
        sql += " AND client_android_id = ?"
        params.append(android_id)
    sql += " ORDER BY received_at_ms DESC"
    return [dict(row) for row in conn.execute(sql, tuple(params)).fetchall()]


def latest_benchmark_for_android_id(
    conn: sqlite3.Connection,
    android_id: Optional[str],
) -> Optional[dict[str, Any]]:
    if not android_id:
        return None
    row = conn.execute(
        """
        SELECT * FROM debrid_benchmark_events
        WHERE client_android_id = ?
        ORDER BY measured_at_ms DESC, received_at_ms DESC
        LIMIT 1
        """,
        (android_id,),
    ).fetchone()
    return dict(row) if row is not None else None


def public_not_found() -> None:
    LOGGER.info("Public collector route lookup failed for unresolved token or mismatched scope")
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")


@app.post("/api/v1/shadow-autoplay-events")
def ingest_shadow_autoplay(request: Request, envelope: ShadowAutoplayEnvelope):
    require_bearer(request, WRITE_TOKEN)
    row = summarize(envelope.payload, envelope)
    with closing(db()) as conn:
        conn.execute(
            """
            INSERT INTO shadow_autoplay_events (
                received_at_ms, sent_at_ms, event_type, request_id, video_id, content_type, title,
                selected_provider, selected_transport, selected_score, winner_count, rejected_count,
                client_app_version, client_build_type, client_device_model, client_sdk_int, client_android_id, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            tuple(row.values()),
        )
        conn.commit()
        event_id = conn.execute("SELECT last_insert_rowid() AS id").fetchone()["id"]
    return {"ok": True, "id": event_id}


@app.get("/api/v1/shadow-autoplay-events")
def list_shadow_autoplay_events(
    request: Request,
    limit: int = 200,
    offset: int = 0,
    provider: Optional[str] = None,
    content_type: Optional[str] = None,
    transport: Optional[str] = None,
    android_id: Optional[str] = None,
    q: str = "",
    sort: str = "received",
    direction: str = "desc",
):
    require_bearer(request, READ_TOKEN)
    limit = max(1, min(limit, 2000))
    with closing(db()) as conn:
        raw_rows = fetch_shadow_rows(
            conn,
            provider=provider,
            content_type=content_type,
            transport=transport,
            android_id=android_id,
        )
    events = [build_event_view(row) for row in raw_rows]
    events = filter_events(events, q, provider, content_type, transport, android_id)
    events = sort_events(events, sort, direction)
    sliced = events[max(0, offset):max(0, offset) + limit]
    return {"items": sliced, "count": len(sliced), "total": len(events), "limit": limit, "offset": offset}


@app.post("/api/v1/debrid-benchmark-results")
def ingest_debrid_benchmark_result(request: Request, envelope: DebridBenchmarkEnvelope):
    require_bearer(request, WRITE_TOKEN)
    row = summarize_benchmark(envelope.result, envelope)
    with closing(db()) as conn:
        conn.execute(
            """
            INSERT INTO debrid_benchmark_events (
                received_at_ms, sent_at_ms, provider, measured_at_ms, termination_reason,
                client_app_version, client_build_type, client_device_model, client_sdk_int, client_android_id,
                device_model, device_manufacturer, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            tuple(row.values()),
        )
        conn.commit()
        event_id = conn.execute("SELECT last_insert_rowid() AS id").fetchone()["id"]
    return {"ok": True, "id": event_id}


@app.get("/api/v1/debrid-benchmark-results")
def list_debrid_benchmark_results(
    request: Request,
    limit: int = 200,
    offset: int = 0,
    provider: Optional[str] = None,
    android_id: Optional[str] = None,
    q: str = "",
    sort: str = "received",
    direction: str = "desc",
):
    require_bearer(request, READ_TOKEN)
    limit = max(1, min(limit, 2000))
    with closing(db()) as conn:
        raw_rows = fetch_benchmark_rows(
            conn,
            provider=provider,
            android_id=android_id,
        )
    items = [build_benchmark_view(row) for row in raw_rows]
    items = filter_benchmarks(items, q, provider, android_id)
    items = sort_benchmarks(items, sort, direction)
    sliced = items[max(0, offset):max(0, offset) + limit]
    return {"items": sliced, "count": len(sliced), "total": len(items), "limit": limit, "offset": offset}


def require_session(request: Request) -> None:
    if request.session.get("user") != ADMIN_USERNAME:
        raise HTTPException(status_code=status.HTTP_303_SEE_OTHER, headers={"Location": "/login"})


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return TEMPLATES.TemplateResponse(request, "login.html", {"error": None})


@app.post("/login", response_class=HTMLResponse)
async def login_submit(request: Request):
    form = await request.form()
    username = str(form.get("username", ""))
    password = str(form.get("password", ""))
    if secrets.compare_digest(username, ADMIN_USERNAME) and secrets.compare_digest(password, ADMIN_PASSWORD):
        request.session["user"] = ADMIN_USERNAME
        return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
    return TEMPLATES.TemplateResponse(request, "login.html", {"error": "Invalid credentials"}, status_code=401)


@app.post("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/login", status_code=status.HTTP_303_SEE_OTHER)


@app.get("/", response_class=HTMLResponse)
def dashboard(
    request: Request,
    limit: int = 100,
    q: str = "",
    provider: Optional[str] = None,
    content_type: Optional[str] = None,
    transport: Optional[str] = None,
    android_id: Optional[str] = None,
    sort: str = "received",
    direction: str = "desc",
):
    require_session(request)
    limit = max(1, min(limit, 500))
    with closing(db()) as conn:
        raw_rows = fetch_shadow_rows(
            conn,
            provider=provider,
            content_type=content_type,
            transport=transport,
            android_id=android_id,
        )
        total = conn.execute("SELECT COUNT(*) AS c FROM shadow_autoplay_events").fetchone()["c"]

    events = [build_event_view(row) for row in raw_rows]
    filtered_events = filter_events(events, q, provider, content_type, transport, android_id)
    sorted_events = sort_events(filtered_events, sort, direction)
    rows = sorted_events[:limit]

    selected_scores = [event["selected"]["score"] for event in rows if event.get("selected") and event["selected"].get("score") is not None]
    provider_counts = Counter(event["selected"]["provider"] for event in events if event.get("selected"))
    top_provider = provider_counts.most_common(1)[0][0] if provider_counts else "—"

    context = {
        "rows": rows,
        "total": total,
        "visible_total": len(filtered_events),
        "stats": {
            "shown": len(rows),
            "average_score": round(sum(selected_scores) / len(selected_scores), 1) if selected_scores else "—",
            "top_provider": top_provider,
            "unique_titles": len({event['title'] for event in filtered_events}),
        },
        "filters": {
            "q": q,
            "provider": provider or "",
            "content_type": content_type or "",
            "transport": transport or "",
            "android_id": android_id or "",
            "sort": sort,
            "direction": direction,
            "limit": limit,
        },
        "options": {
            "providers": sorted({event["selected"]["provider"] for event in events if event.get("selected")}),
            "content_types": sorted({event["content_type"] for event in events if event.get("content_type") and event["content_type"] != "—"}),
            "transports": sorted({event["selected"]["transport"] for event in events if event.get("selected")}),
        },
    }
    return TEMPLATES.TemplateResponse(request, "dashboard.html", context)


@app.get("/public/{token}", response_class=HTMLResponse)
def public_dashboard(
    request: Request,
    token: str,
):
    with closing(db()) as conn:
        android_id = resolve_public_token_android_id(conn, token)
        if android_id is None:
            public_not_found()
        rows = [build_public_event_view(row) for row in fetch_shadow_rows(conn, android_id=android_id)]

    context = {
        "rows": rows,
        "token": token,
        "public_rows": len(rows),
    }
    return TEMPLATES.TemplateResponse(request, "public_dashboard.html", context)


@app.get("/public/{token}/events/{event_id}", response_class=HTMLResponse)
def public_event_detail(request: Request, token: str, event_id: int):
    with closing(db()) as conn:
        android_id = resolve_public_token_android_id(conn, token)
        if android_id is None:
            public_not_found()

        row = conn.execute(
            "SELECT * FROM shadow_autoplay_events WHERE id = ?",
            (event_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="Not found")
        event_android_id = row["client_android_id"] if row is not None else None
        if not event_android_id or event_android_id != android_id:
            public_not_found()

        latest_benchmark_row = latest_benchmark_for_android_id(conn, android_id)

    event = build_public_event_view(dict(row))
    latest_benchmark = (
        build_benchmark_view(latest_benchmark_row) if latest_benchmark_row else None
    )
    return TEMPLATES.TemplateResponse(
        request,
        "public_event_detail.html",
        {
            "event": event,
            "token": token,
            "latest_benchmark": latest_benchmark,
        },
    )


@app.get("/events/{event_id}", response_class=HTMLResponse)
def event_detail(request: Request, event_id: int):
    require_session(request)
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM shadow_autoplay_events WHERE id = ?", (event_id,)).fetchone()
        latest_benchmark_row = latest_benchmark_for_android_id(
            conn,
            dict(row).get("client_android_id") if row is not None else None,
        )
    if row is None:
        raise HTTPException(status_code=404, detail="Not found")
    event = build_event_view(dict(row))
    latest_benchmark = build_benchmark_view(latest_benchmark_row) if latest_benchmark_row else None
    return TEMPLATES.TemplateResponse(
        request,
        "event_detail.html",
        {
            "event": event,
            "latest_benchmark": latest_benchmark,
        },
    )


@app.get("/public/{token}/benchmarks/latest/download")
def public_benchmark_download(request: Request, token: str):
    with closing(db()) as conn:
        android_id = resolve_public_token_android_id(conn, token)
        if android_id is None:
            public_not_found()

        row = latest_benchmark_for_android_id(conn, android_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Not found")

    payload = build_public_benchmark_download_payload(row)
    return JSONResponse(
        payload,
        headers={"Content-Disposition": 'attachment; filename="benchmark-latest.json"'},
    )


@app.get("/benchmarks/{benchmark_id}/download")
def benchmark_download(request: Request, benchmark_id: int):
    require_session(request)
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM debrid_benchmark_events WHERE id = ?", (benchmark_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Not found")
    payload = build_benchmark_view(dict(row))
    return JSONResponse(
        payload,
        headers={
            "Content-Disposition": f'attachment; filename="benchmark-{benchmark_id}.json"'
        },
    )


@app.post("/admin/clear")
def clear_events(request: Request):
    require_session(request)
    with closing(db()) as conn:
        conn.execute("DELETE FROM shadow_autoplay_events")
        conn.execute("DELETE FROM debrid_benchmark_events")
        conn.commit()
    return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
