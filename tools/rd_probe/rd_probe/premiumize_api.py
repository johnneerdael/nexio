from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Protocol

try:
    import requests
except ImportError:  # pragma: no cover
    requests = None  # type: ignore[assignment]


PREMIUMIZE_API_BASE_URL = "https://www.premiumize.me/api"


class SupportsRequest(Protocol):
    def request(self, method: str, url: str, **kwargs: Any) -> Any: ...


class PremiumizeApiError(RuntimeError):
    """Raised when the Premiumize API returns an unexpected response."""


@dataclass(frozen=True)
class PremiumizeItem:
    id: str
    name: str | None = None
    created_at: datetime | None = None
    size_bytes: int | None = None
    mime_type: str | None = None
    path: str | None = None


@dataclass(frozen=True)
class PremiumizeItemDetails:
    id: str
    name: str | None = None
    size_bytes: int | None = None
    mime_type: str | None = None
    link: str | None = None
    stream_link: str | None = None
    created_at: datetime | None = None


class PremiumizeClient:
    """Small api-key authenticated Premiumize client for local probe usage."""

    def __init__(
        self,
        api_key: str,
        *,
        base_url: str = PREMIUMIZE_API_BASE_URL,
        session: SupportsRequest | None = None,
        timeout_seconds: float = 30.0,
    ) -> None:
        key = api_key.strip()
        if not key:
            raise ValueError("Premiumize API key is required.")
        self._api_key = key
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        if session is None:
            if requests is None:
                raise RuntimeError("requests is required to use PremiumizeClient without an injected session.")
            session = requests.Session()
        self._session = session

    def list_items(self) -> list[PremiumizeItem]:
        payload = self._request_json("GET", "/item/listall")
        files = payload.get("files")
        if not isinstance(files, list):
            raise PremiumizeApiError("Premiumize item/listall did not return a files list.")
        return [self._to_item(file_payload) for file_payload in files if isinstance(file_payload, dict)]

    def get_item_details(self, item_id: str) -> PremiumizeItemDetails:
        payload = self._request_json("GET", "/item/details", params={"id": item_id})
        if not isinstance(payload, dict):
            raise PremiumizeApiError("Premiumize item/details returned an invalid payload.")
        payload_id = str(payload.get("id") or item_id)
        return PremiumizeItemDetails(
            id=payload_id,
            name=_normalize_optional_string(payload.get("name")),
            size_bytes=_normalize_optional_int(payload.get("size")),
            mime_type=_normalize_optional_string(payload.get("mime_type")),
            link=_normalize_optional_string(payload.get("link")),
            stream_link=_normalize_optional_string(payload.get("stream_link")),
            created_at=_parse_unix_timestamp(payload.get("created_at")),
        )

    def _request_json(self, method: str, path: str, **kwargs: Any) -> Any:
        url = f"{self._base_url}{path}"
        params = dict(kwargs.pop("params", {}) or {})
        params["apikey"] = self._api_key
        response = self._session.request(
            method,
            url,
            params=params,
            timeout=self._timeout_seconds,
            **kwargs,
        )
        status_code = getattr(response, "status_code", None)
        if status_code is None:
            raise PremiumizeApiError(f"Missing HTTP status for {method} {path}.")
        if status_code >= 400:
            body = self._safe_response_text(response)
            raise PremiumizeApiError(f"Premiumize API {method} {path} failed with {status_code}: {body}")
        try:
            payload = response.json()
        except Exception as exc:  # pragma: no cover
            raise PremiumizeApiError(f"Premiumize API {method} {path} returned invalid JSON.") from exc
        if isinstance(payload, dict):
            status_value = _normalize_optional_string(payload.get("status"))
            if status_value is not None and status_value.lower() != "success":
                raise PremiumizeApiError(f"Premiumize API {method} {path} reported status={status_value}.")
        return payload

    @staticmethod
    def _safe_response_text(response: Any) -> str:
        text = getattr(response, "text", "")
        return text if isinstance(text, str) and text else "<no body>"

    @staticmethod
    def _to_item(payload: dict[str, Any]) -> PremiumizeItem:
        return PremiumizeItem(
            id=str(payload["id"]),
            name=_normalize_optional_string(payload.get("name")),
            created_at=_parse_unix_timestamp(payload.get("created_at")),
            size_bytes=_normalize_optional_int(payload.get("size")),
            mime_type=_normalize_optional_string(payload.get("mime_type")),
            path=_normalize_optional_string(payload.get("path")),
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


def _parse_unix_timestamp(value: Any) -> datetime | None:
    numeric = _normalize_optional_int(value)
    if numeric is None:
        return None
    try:
        return datetime.fromtimestamp(numeric, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return None
