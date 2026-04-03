from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Protocol

try:
    import requests
except ImportError:  # pragma: no cover - exercised only in runtime environments without requests
    requests = None  # type: ignore[assignment]


REAL_DEBRID_API_BASE_URL = "https://api.real-debrid.com"


class SupportsRequest(Protocol):
    def request(self, method: str, url: str, **kwargs: Any) -> Any: ...


class RealDebridApiError(RuntimeError):
    """Raised when the Real-Debrid API returns an unexpected response."""


@dataclass(frozen=True)
class RdDownload:
    id: str
    filename: str | None = None
    mime_type: str | None = None
    size_bytes: int | None = None
    link: str | None = None
    host: str | None = None
    direct_url: str | None = None
    listed_at: datetime | None = None
    file_size: int | None = None
    download: str | None = None
    generated: str | datetime | None = None

    def __post_init__(self) -> None:
        if self.size_bytes is None and self.file_size is not None:
            object.__setattr__(self, "size_bytes", self.file_size)
        if self.direct_url is None and self.download is not None:
            object.__setattr__(self, "direct_url", self.download)
        if self.listed_at is None:
            object.__setattr__(self, "listed_at", _coerce_datetime(self.generated))


@dataclass(frozen=True)
class RdTorrent:
    id: str
    filename: str | None = None
    status: str | None = None
    listed_at: datetime | None = None
    bytes: int | None = None
    links: tuple[str, ...] | list[str] = ()
    added: str | datetime | None = None
    ended: str | datetime | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "links", tuple(self.links))
        if self.listed_at is None:
            object.__setattr__(self, "listed_at", _coerce_datetime(self.ended) or _coerce_datetime(self.added))


@dataclass(frozen=True)
class RdTorrentFile:
    id: int | str
    path: str | None = None
    size_bytes: int | None = None
    selected: bool = False
    bytes: int | None = None

    def __post_init__(self) -> None:
        if self.size_bytes is None and self.bytes is not None:
            object.__setattr__(self, "size_bytes", self.bytes)
        object.__setattr__(self, "selected", bool(self.selected))


@dataclass(frozen=True)
class RdTorrentInfo:
    id: str
    filename: str | None = None
    original_filename: str | None = None
    status: str | None = None
    listed_at: datetime | None = None
    files: tuple[RdTorrentFile, ...] | list[RdTorrentFile] = ()
    links: tuple[str, ...] | list[str] = ()
    added: str | datetime | None = None
    ended: str | datetime | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "files", tuple(self.files))
        object.__setattr__(self, "links", tuple(self.links))
        if self.listed_at is None:
            object.__setattr__(self, "listed_at", _coerce_datetime(self.ended) or _coerce_datetime(self.added))


@dataclass(frozen=True)
class RdResolvedLink:
    link: str
    direct_url: str
    filename: str | None = None
    mime_type: str | None = None
    size_bytes: int | None = None
    host: str | None = None


@dataclass(frozen=True)
class RdUnrestricted:
    id: str | None = None
    filename: str | None = None
    file_size: int | None = None
    host: str | None = None
    download: str | None = None
    link: str | None = None
    mime_type: str | None = None

    @property
    def direct_url(self) -> str | None:
        return self.download

    @property
    def size_bytes(self) -> int | None:
        return self.file_size


class RealDebridClient:
    """Small token-authenticated Real-Debrid client for local probe usage."""

    def __init__(
        self,
        api_token: str,
        *,
        base_url: str = REAL_DEBRID_API_BASE_URL,
        session: SupportsRequest | None = None,
        timeout_seconds: float = 30.0,
    ) -> None:
        token = api_token.strip()
        if not token:
            raise ValueError("Real-Debrid API token is required.")
        self._token = token
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        if session is None:
            if requests is None:
                raise RuntimeError("requests is required to use RealDebridClient without an injected session.")
            session = requests.Session()
        self._session = session

    def list_downloads(self, *, page: int = 1, limit: int = 200) -> list[RdDownload]:
        payload = self._request_json("GET", "/rest/1.0/downloads", params={"page": page, "limit": limit})
        return [self._to_download(item) for item in self._expect_list(payload, "downloads")]

    def list_torrents(self, *, page: int = 1, limit: int = 200) -> list[RdTorrent]:
        payload = self._request_json("GET", "/rest/1.0/torrents", params={"page": page, "limit": limit})
        return [self._to_torrent(item) for item in self._expect_list(payload, "torrents")]

    def get_torrent_info(self, torrent_id: str) -> RdTorrentInfo:
        payload = self._request_json("GET", f"/rest/1.0/torrents/info/{torrent_id}")
        return self._to_torrent_info(self._expect_dict(payload, "torrent info"))

    def unrestrict_link(self, link: str, *, remote: int = 0) -> RdResolvedLink:
        payload = self._request_json("POST", "/rest/1.0/unrestrict/link", data={"link": link, "remote": remote})
        return self._to_resolved_link(self._expect_dict(payload, "unrestrict link"))

    def _request_json(self, method: str, path: str, **kwargs: Any) -> Any:
        url = f"{self._base_url}{path}"
        headers = {"Authorization": f"Bearer {self._token}"}
        response = self._session.request(
            method,
            url,
            headers=headers,
            timeout=self._timeout_seconds,
            **kwargs,
        )
        status_code = getattr(response, "status_code", None)
        if status_code is None:
            raise RealDebridApiError(f"Missing HTTP status for {method} {path}.")
        if status_code >= 400:
            body = self._safe_response_text(response)
            raise RealDebridApiError(f"Real-Debrid API {method} {path} failed with {status_code}: {body}")
        try:
            return response.json()
        except Exception as exc:  # pragma: no cover - depends on runtime response object
            raise RealDebridApiError(f"Real-Debrid API {method} {path} returned invalid JSON.") from exc

    @staticmethod
    def _safe_response_text(response: Any) -> str:
        text = getattr(response, "text", "")
        return text if isinstance(text, str) and text else "<no body>"

    @staticmethod
    def _expect_list(payload: Any, label: str) -> list[dict[str, Any]]:
        if not isinstance(payload, list):
            raise RealDebridApiError(f"Expected {label} payload to be a list.")
        return [RealDebridClient._expect_dict(item, label) for item in payload]

    @staticmethod
    def _expect_dict(payload: Any, label: str) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise RealDebridApiError(f"Expected {label} payload to be an object.")
        return payload

    @staticmethod
    def _to_download(payload: dict[str, Any]) -> RdDownload:
        return RdDownload(
            id=str(payload["id"]),
            filename=_normalize_optional_string(payload.get("filename")),
            mime_type=_normalize_optional_string(payload.get("mimeType")),
            size_bytes=_normalize_optional_int(payload.get("filesize")),
            link=_normalize_optional_string(payload.get("link")),
            host=_normalize_optional_string(payload.get("host")),
            direct_url=_normalize_optional_string(payload.get("download")),
            listed_at=_parse_iso_datetime(payload.get("generated")),
        )

    @staticmethod
    def _to_torrent(payload: dict[str, Any]) -> RdTorrent:
        return RdTorrent(
            id=str(payload["id"]),
            filename=_normalize_optional_string(payload.get("filename")),
            status=_normalize_optional_string(payload.get("status")),
            listed_at=_parse_iso_datetime(payload.get("ended") or payload.get("added")),
        )

    @staticmethod
    def _to_torrent_info(payload: dict[str, Any]) -> RdTorrentInfo:
        files_payload = payload.get("files") or []
        links_payload = payload.get("links") or []
        files = tuple(
            RdTorrentFile(
                id=file_payload.get("id", ""),
                path=_normalize_optional_string(file_payload.get("path")),
                size_bytes=_normalize_optional_int(file_payload.get("bytes")),
                selected=file_payload.get("selected") == 1,
            )
            for file_payload in files_payload
            if isinstance(file_payload, dict)
        )
        links = tuple(str(link) for link in links_payload if link is not None)
        return RdTorrentInfo(
            id=str(payload["id"]),
            filename=_normalize_optional_string(payload.get("filename")),
            original_filename=_normalize_optional_string(payload.get("original_filename")),
            status=_normalize_optional_string(payload.get("status")),
            listed_at=_parse_iso_datetime(payload.get("ended") or payload.get("added")),
            files=files,
            links=links,
        )

    @staticmethod
    def _to_resolved_link(payload: dict[str, Any]) -> RdResolvedLink:
        link = _normalize_optional_string(payload.get("link"))
        direct_url = _normalize_optional_string(payload.get("download"))
        if not link or not direct_url:
            raise RealDebridApiError("Unrestrict response is missing link or download URL.")
        return RdResolvedLink(
            link=link,
            direct_url=direct_url,
            filename=_normalize_optional_string(payload.get("filename")),
            mime_type=_normalize_optional_string(payload.get("mimeType")),
            size_bytes=_normalize_optional_int(payload.get("filesize")),
            host=_normalize_optional_string(payload.get("host")),
        )


def _normalize_optional_string(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None



def _normalize_optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None



def _parse_iso_datetime(value: Any) -> datetime | None:
    text = _normalize_optional_string(value)
    if not text:
        return None
    normalized = text.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed


def _coerce_datetime(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value
    return _parse_iso_datetime(value)
