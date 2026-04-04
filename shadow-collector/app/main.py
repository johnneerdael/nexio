import json
import os
import secrets
import sqlite3
import time
from contextlib import closing
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.security.utils import get_authorization_scheme_param
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

app = FastAPI(title="Nexio Shadow Collector", version="1.0.0")
app.add_middleware(SessionMiddleware, secret_key=SESSION_SECRET)


class ClientInfo(BaseModel):
    appVersion: Optional[str] = None
    buildType: Optional[str] = None
    deviceModel: Optional[str] = None
    sdkInt: Optional[int] = None


class ShadowAutoplayEnvelope(BaseModel):
    sentAtMs: int = Field(default_factory=lambda: int(time.time() * 1000))
    client: ClientInfo = Field(default_factory=ClientInfo)
    payload: dict[str, Any]


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
                raw_json TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_shadow_received_at ON shadow_autoplay_events(received_at_ms DESC);
            CREATE INDEX IF NOT EXISTS idx_shadow_request_id ON shadow_autoplay_events(request_id);
            CREATE INDEX IF NOT EXISTS idx_shadow_video_id ON shadow_autoplay_events(video_id);
            CREATE INDEX IF NOT EXISTS idx_shadow_selected_provider ON shadow_autoplay_events(selected_provider);
            """
        )
        conn.commit()


@app.on_event("startup")
def startup() -> None:
    init_db()


def require_bearer(request: Request, expected_token: str) -> None:
    auth = request.headers.get("Authorization", "")
    scheme, token = get_authorization_scheme_param(auth)
    if scheme.lower() != "bearer" or not expected_token or not secrets.compare_digest(token, expected_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Unauthorized")


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
        "raw_json": json.dumps({
            "sentAtMs": envelope.sentAtMs,
            "client": envelope.client.model_dump(),
            "payload": payload,
        }, separators=(",", ":")),
    }


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
                client_app_version, client_build_type, client_device_model, client_sdk_int, raw_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            tuple(row.values()),
        )
        conn.commit()
        event_id = conn.execute("SELECT last_insert_rowid() AS id").fetchone()["id"]
    return {"ok": True, "id": event_id}


@app.get("/api/v1/shadow-autoplay-events")
def list_shadow_autoplay_events(request: Request, limit: int = 200, offset: int = 0, provider: Optional[str] = None, content_type: Optional[str] = None):
    require_bearer(request, READ_TOKEN)
    limit = max(1, min(limit, 2000))
    clauses = []
    params: list[Any] = []
    if provider:
        clauses.append("selected_provider = ?")
        params.append(provider)
    if content_type:
        clauses.append("content_type = ?")
        params.append(content_type)
    where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
    query = f"SELECT * FROM shadow_autoplay_events {where} ORDER BY received_at_ms DESC LIMIT ? OFFSET ?"
    params.extend([limit, offset])
    with closing(db()) as conn:
        rows = [dict(row) for row in conn.execute(query, params).fetchall()]
    for row in rows:
        row["raw_json"] = json.loads(row["raw_json"])
    return {"items": rows, "count": len(rows), "limit": limit, "offset": offset}


def require_session(request: Request) -> None:
    if request.session.get("user") != ADMIN_USERNAME:
        raise HTTPException(status_code=status.HTTP_303_SEE_OTHER, headers={"Location": "/login"})


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return TEMPLATES.TemplateResponse("login.html", {"request": request, "error": None})


@app.post("/login", response_class=HTMLResponse)
async def login_submit(request: Request):
    form = await request.form()
    username = str(form.get("username", ""))
    password = str(form.get("password", ""))
    if secrets.compare_digest(username, ADMIN_USERNAME) and secrets.compare_digest(password, ADMIN_PASSWORD):
        request.session["user"] = ADMIN_USERNAME
        return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
    return TEMPLATES.TemplateResponse("login.html", {"request": request, "error": "Invalid credentials"}, status_code=401)


@app.post("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/login", status_code=status.HTTP_303_SEE_OTHER)


@app.get("/", response_class=HTMLResponse)
def dashboard(request: Request, limit: int = 100):
    require_session(request)
    with closing(db()) as conn:
        rows = [dict(row) for row in conn.execute(
            "SELECT id, received_at_ms, content_type, title, video_id, selected_provider, selected_transport, selected_score, winner_count, rejected_count FROM shadow_autoplay_events ORDER BY received_at_ms DESC LIMIT ?",
            (max(1, min(limit, 500)),),
        ).fetchall()]
        total = conn.execute("SELECT COUNT(*) AS c FROM shadow_autoplay_events").fetchone()["c"]
    return TEMPLATES.TemplateResponse("dashboard.html", {"request": request, "rows": rows, "total": total})


@app.get("/events/{event_id}", response_class=HTMLResponse)
def event_detail(request: Request, event_id: int):
    require_session(request)
    with closing(db()) as conn:
        row = conn.execute("SELECT * FROM shadow_autoplay_events WHERE id = ?", (event_id,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="Not found")
    event = dict(row)
    event["raw_json"] = json.dumps(json.loads(event["raw_json"]), indent=2)
    return TEMPLATES.TemplateResponse("event_detail.html", {"request": request, "event": event})


@app.post("/admin/clear")
def clear_events(request: Request):
    require_session(request)
    with closing(db()) as conn:
        conn.execute("DELETE FROM shadow_autoplay_events")
        conn.commit()
    return RedirectResponse("/", status_code=status.HTTP_303_SEE_OTHER)
