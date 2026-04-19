#!/usr/bin/env python3
"""Live Trakt continue-watching probe for the mixed activity timeline model."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

BASE_URL = "https://api.trakt.tv"
DEFAULT_TOKEN_FILE = Path("/Users/jneerdael/Scripts/nexio/trakt_tokens.json")
DEFAULT_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
TIMEOUT = 30
NEAR_EQUAL_WINDOW_MS = 60_000


class TraktError(RuntimeError):
    pass


@dataclass
class TokenBundle:
    access_token: str
    refresh_token: str
    expires_in: int
    created_at: int
    token_type: str = "bearer"
    scope: str = "public"

    @property
    def expires_at(self) -> int:
        return int(self.created_at) + int(self.expires_in)

    @property
    def is_expired(self) -> bool:
        return time.time() >= (self.expires_at - 60)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "TokenBundle":
        return cls(
            access_token=data["access_token"],
            refresh_token=data["refresh_token"],
            expires_in=int(data["expires_in"]),
            created_at=int(data["created_at"]),
            token_type=data.get("token_type", "bearer"),
            scope=data.get("scope", "public"),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "access_token": self.access_token,
            "refresh_token": self.refresh_token,
            "expires_in": self.expires_in,
            "created_at": self.created_at,
            "expires_at": self.expires_at,
            "token_type": self.token_type,
            "scope": self.scope,
        }


class TraktClient:
    def __init__(self, client_id: str, client_secret: str, redirect_uri: str = DEFAULT_REDIRECT_URI) -> None:
        self.client_id = client_id
        self.client_secret = client_secret
        self.redirect_uri = redirect_uri
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "trakt-api-version": "2",
                "trakt-api-key": client_id,
                "User-Agent": "nexio-continue-watching-probe/1.0",
            }
        )
        self.metrics: dict[str, int] = {}

    def request(self, method: str, path: str, *, token: str | None = None, **kwargs: Any) -> requests.Response:
        url = f"{BASE_URL}{path}"
        headers = kwargs.pop("headers", {}).copy()
        if token:
            headers["Authorization"] = f"Bearer {token}"
        self.metrics[path.split("?")[0]] = self.metrics.get(path.split("?")[0], 0) + 1
        return self.session.request(method, url, headers=headers, timeout=TIMEOUT, **kwargs)

    def refresh_access_token(self, refresh_token: str) -> TokenBundle:
        response = self.request(
            "POST",
            "/oauth/token",
            json={
                "refresh_token": refresh_token,
                "client_id": self.client_id,
                "client_secret": self.client_secret,
                "redirect_uri": self.redirect_uri,
                "grant_type": "refresh_token",
            },
        )
        raise_for_status(response, "refreshing access token")
        return TokenBundle.from_dict(response.json())


def raise_for_status(response: requests.Response, context: str) -> None:
    if response.ok:
        return
    try:
        detail = json.dumps(response.json(), indent=2)
    except Exception:
        detail = response.text.strip() or "<empty response body>"
    raise TraktError(f"HTTP {response.status_code} while {context}:\n{detail}")


def load_tokens(path: Path) -> TokenBundle:
    return TokenBundle.from_dict(json.loads(path.read_text()))


def save_tokens(path: Path, token_bundle: TokenBundle) -> None:
    path.write_text(json.dumps(token_bundle.to_dict(), indent=2) + "\n")


def resolve_credentials(args: argparse.Namespace) -> tuple[str, str, str]:
    client_id = args.client_id or os.getenv("TRAKT_CLIENT_ID")
    client_secret = args.client_secret or os.getenv("TRAKT_CLIENT_SECRET")
    redirect_uri = args.redirect_uri or os.getenv("TRAKT_REDIRECT_URI") or DEFAULT_REDIRECT_URI
    if not client_id or not client_secret:
        raise TraktError("Missing TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET.")
    return client_id, client_secret, redirect_uri


def authenticate(client: TraktClient, token_path: Path) -> TokenBundle:
    tokens = load_tokens(token_path)
    if not tokens.is_expired:
        return tokens
    refreshed = client.refresh_access_token(tokens.refresh_token)
    save_tokens(token_path, refreshed)
    return refreshed


def parse_iso_ms(value: str | None) -> int:
    if not value:
        return 0
    try:
        return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)
    except Exception:
        return 0


def normalize_content_id(ids: dict[str, Any] | None) -> str:
    if not ids:
        return ""
    if ids.get("imdb"):
        return str(ids["imdb"]).strip()
    if ids.get("tmdb") is not None:
        return f"tmdb:{ids['tmdb']}"
    if ids.get("trakt") is not None:
        return f"trakt:{ids['trakt']}"
    if ids.get("slug"):
        return str(ids["slug"]).strip()
    return ""


def get_json(client: TraktClient, token: str, path: str, *, params: dict[str, Any] | None = None) -> Any:
    response = client.request("GET", path, token=token, params=params)
    raise_for_status(response, f"calling {path}")
    return response.json()


def get_hidden_items(client: TraktClient, token: str, section: str, media_type: str) -> list[dict[str, Any]]:
    page = 1
    results: list[dict[str, Any]] = []
    while True:
        response = client.request(
            "GET",
            f"/users/hidden/{section}",
            token=token,
            params={"type": media_type, "page": page, "limit": 100},
        )
        raise_for_status(response, f"calling /users/hidden/{section}")
        body = response.json()
        if not body:
            break
        results.extend(body)
        page_count = int(response.headers.get("X-Pagination-Page-Count", "1"))
        if page >= page_count:
            break
        page += 1
    return results


def group_paused_episode_resumes(playback_items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    newest_by_show: dict[str, dict[str, Any]] = {}
    for item in playback_items:
        show = item.get("show") or {}
        episode = item.get("episode") or {}
        content_id = normalize_content_id(show.get("ids"))
        season = episode.get("season")
        number = episode.get("number")
        if not content_id or not season or not number:
            continue
        candidate = {
            "kind": "resume_episode",
            "content_id": content_id,
            "title": show.get("title") or content_id,
            "season": season,
            "episode": number,
            "episode_title": episode.get("title"),
            "progress": float(item.get("progress") or 0.0),
            "activity_at_ms": parse_iso_ms(item.get("paused_at")),
        }
        current = newest_by_show.get(content_id)
        if current is None or candidate["activity_at_ms"] > current["activity_at_ms"]:
            newest_by_show[content_id] = candidate
    return sorted(newest_by_show.values(), key=lambda item: item["activity_at_ms"], reverse=True)


def build_main_feed(
    paused_movies: list[dict[str, Any]],
    paused_shows: list[dict[str, Any]],
    next_up_entries: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    all_entries = paused_movies + paused_shows + next_up_entries
    all_entries.sort(key=lambda item: item["activity_at_ms"], reverse=True)

    result: list[dict[str, Any]] = []
    cluster: list[dict[str, Any]] = []
    cluster_head_ms = 0
    for entry in all_entries:
        if not cluster:
            cluster = [entry]
            cluster_head_ms = entry["activity_at_ms"]
            continue
        if cluster_head_ms - entry["activity_at_ms"] <= NEAR_EQUAL_WINDOW_MS:
            cluster.append(entry)
            continue
        result.extend(sorted(cluster, key=lambda item: (0 if item["kind"].startswith("resume") else 1, -item["activity_at_ms"], item["content_id"])))
        cluster = [entry]
        cluster_head_ms = entry["activity_at_ms"]
    if cluster:
        result.extend(sorted(cluster, key=lambda item: (0 if item["kind"].startswith("resume") else 1, -item["activity_at_ms"], item["content_id"])))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Probe the Trakt continue-watching mixed timeline.")
    parser.add_argument("--client-id")
    parser.add_argument("--client-secret")
    parser.add_argument("--redirect-uri")
    parser.add_argument("--token-file", default=str(DEFAULT_TOKEN_FILE))
    parser.add_argument("--days-cap", default="all", help="Integer days or 'all'")
    parser.add_argument("--preview-limit", type=int, default=15)
    args = parser.parse_args()

    client_id, client_secret, redirect_uri = resolve_credentials(args)
    client = TraktClient(client_id, client_secret, redirect_uri=redirect_uri)
    token_bundle = authenticate(client, Path(args.token_file))
    token = token_bundle.access_token

    now_ms = int(time.time() * 1000)
    days_cap = None if str(args.days_cap).lower() == "all" else int(args.days_cap)
    cutoff_ms = None if days_cap is None else now_ms - (days_cap * 24 * 60 * 60 * 1000)

    last_activities = get_json(client, token, "/sync/last_activities")
    paused_movies_raw = get_json(client, token, "/sync/playback/movies")
    paused_episodes_raw = get_json(client, token, "/sync/playback/episodes")
    watched_shows_raw = get_json(client, token, "/sync/watched/shows", params={"extended": "noseasons"})
    hidden_shows_raw = get_hidden_items(client, token, "progress_watched", "show")
    hidden_seasons_raw = get_hidden_items(client, token, "progress_watched", "season")
    dropped_shows_raw = get_hidden_items(client, token, "dropped", "show")

    hidden_show_ids = {normalize_content_id((item.get("show") or {}).get("ids")) for item in hidden_shows_raw}
    hidden_show_ids.discard("")
    dropped_show_ids = {normalize_content_id((item.get("show") or {}).get("ids")) for item in dropped_shows_raw}
    dropped_show_ids.discard("")
    hidden_seasons = {
        (normalize_content_id((item.get("show") or {}).get("ids")), (item.get("season") or {}).get("number"))
        for item in hidden_seasons_raw
    }
    hidden_seasons = {(content_id, season) for content_id, season in hidden_seasons if content_id and season}

    paused_movie_resumes = []
    for item in paused_movies_raw:
        movie = item.get("movie") or {}
        content_id = normalize_content_id(movie.get("ids"))
        if not content_id:
            continue
        paused_movie_resumes.append(
            {
                "kind": "resume_movie",
                "content_id": content_id,
                "title": movie.get("title") or content_id,
                "progress": float(item.get("progress") or 0.0),
                "activity_at_ms": parse_iso_ms(item.get("paused_at")),
            }
        )
    paused_movie_resumes.sort(key=lambda item: item["activity_at_ms"], reverse=True)

    paused_show_resumes = group_paused_episode_resumes(paused_episodes_raw)
    paused_show_ids = {item["content_id"] for item in paused_show_resumes}

    watched_shows = []
    for item in watched_shows_raw:
        show = item.get("show") or {}
        content_id = normalize_content_id(show.get("ids"))
        last_watched_at_ms = parse_iso_ms(item.get("last_watched_at"))
        if not content_id or content_id in hidden_show_ids or content_id in dropped_show_ids:
            continue
        if cutoff_ms is not None and last_watched_at_ms and last_watched_at_ms < cutoff_ms:
            continue
        watched_shows.append(
            {
                "content_id": content_id,
                "title": show.get("title") or content_id,
                "last_watched_at_ms": last_watched_at_ms,
            }
        )
    watched_shows.sort(key=lambda item: item["last_watched_at_ms"], reverse=True)

    synthetic_next_up_entries = []
    aired_next_up_entries = []
    for watched_show in watched_shows:
        content_id = watched_show["content_id"]
        progress = get_json(
            client,
            token,
            f"/shows/{content_id}/progress/watched",
            params={"last_activity": "watched"},
        )
        next_episode = progress.get("next_episode")
        if not next_episode:
            continue
        season = next_episode.get("season")
        episode = next_episode.get("number")
        if not season or not episode or (content_id, season) in hidden_seasons:
            continue
        episode_summary = get_json(
            client,
            token,
            f"/shows/{content_id}/seasons/{season}/episodes/{episode}",
            params={"extended": "full"},
        )
        first_aired = episode_summary.get("first_aired")
        first_aired_ms = parse_iso_ms(first_aired)
        entry = {
            "kind": "next_episode",
            "content_id": content_id,
            "title": watched_show["title"],
            "season": season,
            "episode": episode,
            "episode_title": next_episode.get("title") or episode_summary.get("title"),
            "first_aired": first_aired,
            "first_aired_ms": first_aired_ms,
            "activity_at_ms": parse_iso_ms(progress.get("last_watched_at")) or watched_show["last_watched_at_ms"],
        }
        if content_id not in paused_show_ids:
            synthetic_next_up_entries.append(entry)
            if first_aired_ms <= 0 or first_aired_ms <= now_ms:
                aired_next_up_entries.append(entry)

    main_feed = build_main_feed(
        paused_movies=paused_movie_resumes,
        paused_shows=paused_show_resumes,
        next_up_entries=aired_next_up_entries,
    )

    print("Trakt Continue Watching Probe")
    print(json.dumps(
        {
            "timestamp_utc": datetime.now(timezone.utc).isoformat(),
            "days_cap": "all" if days_cap is None else days_cap,
            "counts": {
                "resume_movies": len(paused_movie_resumes),
                "resume_shows": len(paused_show_resumes),
                "synthetic_next_up": len(synthetic_next_up_entries),
                "aired_next_up": len(aired_next_up_entries),
                "main_feed": len(main_feed),
                "watched_show_candidates": len(watched_shows),
                "hidden_shows": len(hidden_show_ids),
                "hidden_seasons": len(hidden_seasons),
                "dropped_shows": len(dropped_show_ids),
            },
            "endpoint_calls": client.metrics,
            "last_activities": {
                "movies": last_activities.get("movies"),
                "episodes": last_activities.get("episodes"),
                "shows": last_activities.get("shows"),
                "seasons": last_activities.get("seasons"),
            },
        },
        indent=2,
    ))
    print("\nMain feed preview:")
    for entry in main_feed[: args.preview_limit]:
        when = datetime.fromtimestamp(entry["activity_at_ms"] / 1000, tz=timezone.utc).isoformat() if entry["activity_at_ms"] else "unknown"
        if entry["kind"] == "resume_movie":
            label = f"Resume movie at {entry['progress']:.1f}%"
        elif entry["kind"] == "resume_episode":
            label = f"Resume S{entry['season']}E{entry['episode']} {entry.get('episode_title') or ''}".strip()
            label += f" at {entry['progress']:.1f}%"
        else:
            aired = entry.get("first_aired") or "unknown"
            label = f"Play S{entry['season']}E{entry['episode']} {entry.get('episode_title') or ''}".strip()
            label += f" (aired {aired})"
        print(f"- {when} | {entry['kind']} | {entry['title']} | {label}")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        raise SystemExit(1)
