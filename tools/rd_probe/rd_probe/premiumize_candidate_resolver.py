from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol
from urllib.parse import urlparse

from .candidate_resolver import CandidateResolutionError, ResolvedCandidate
from .premiumize_api import PremiumizeItem, PremiumizeItemDetails


VIDEO_EXTENSIONS = (
    ".mkv",
    ".mp4",
    ".avi",
    ".mov",
    ".wmv",
    ".ts",
    ".m2ts",
    ".webm",
    ".mpg",
    ".mpeg",
)


class PremiumizeCandidateClient(Protocol):
    def list_items(self) -> list[PremiumizeItem]: ...

    def get_item_details(self, item_id: str) -> PremiumizeItemDetails: ...


@dataclass(frozen=True)
class _ItemCandidate:
    id: str
    filename: str | None
    size_bytes: int
    listed_at: datetime | None


class PremiumizeCandidateResolver:
    def __init__(self, client: PremiumizeCandidateClient) -> None:
        self._client = client

    def resolve(
        self,
        *,
        item_id: str | None = None,
        direct_url: str | None = None,
    ) -> ResolvedCandidate:
        if item_id and direct_url:
            raise ValueError("Only one Premiumize override may be specified.")
        if item_id:
            return self.resolve_item_id(item_id)
        if direct_url:
            return self.resolve_direct_url(direct_url)
        return self.resolve_large_candidate()

    def resolve_large_candidate(self) -> ResolvedCandidate:
        candidates = [
            _ItemCandidate(
                id=item.id,
                filename=_best_filename(item.name, item.path),
                size_bytes=item.size_bytes,
                listed_at=item.created_at,
            )
            for item in self._client.list_items()
            if item.size_bytes is not None and item.size_bytes > 0
            if _is_playable(filename=_best_filename(item.name, item.path), mime_type=item.mime_type)
        ]
        if not candidates:
            raise CandidateResolutionError("No playable Premiumize item with known size was found.")
        best = sorted(candidates, key=_item_sort_key)[0]
        details = self._client.get_item_details(best.id)
        return _resolved_candidate_from_details(
            details=details,
            fallback_filename=best.filename,
            fallback_size=best.size_bytes,
            fallback_listed_at=best.listed_at,
            used_override=False,
            override_kind=None,
        )

    def resolve_item_id(self, item_id: str) -> ResolvedCandidate:
        details = self._client.get_item_details(item_id)
        return _resolved_candidate_from_details(
            details=details,
            fallback_filename=details.name,
            fallback_size=details.size_bytes,
            fallback_listed_at=details.created_at,
            used_override=True,
            override_kind="item_id",
        )

    def resolve_direct_url(self, direct_url: str) -> ResolvedCandidate:
        parsed = urlparse(direct_url)
        filename = _extract_filename(parsed.path)
        return ResolvedCandidate(
            source="direct_url",
            source_id=direct_url,
            filename=filename,
            size_bytes=None,
            listed_at=None,
            original_link=None,
            direct_url=direct_url,
            host=parsed.netloc or None,
            used_override=True,
            override_kind="direct_url",
        )


def _resolved_candidate_from_details(
    *,
    details: PremiumizeItemDetails,
    fallback_filename: str | None,
    fallback_size: int | None,
    fallback_listed_at: datetime | None,
    used_override: bool,
    override_kind: str | None,
) -> ResolvedCandidate:
    direct_url = details.stream_link or details.link
    if not direct_url:
        raise CandidateResolutionError(f"Premiumize item {details.id} has no streamable URL.")
    if not _is_playable(filename=details.name or fallback_filename, mime_type=details.mime_type):
        raise CandidateResolutionError(f"Premiumize item {details.id} is not a playable video file.")
    parsed = urlparse(direct_url)
    return ResolvedCandidate(
        source="premiumize_item",
        source_id=details.id,
        filename=details.name or fallback_filename,
        size_bytes=details.size_bytes or fallback_size,
        listed_at=details.created_at or fallback_listed_at,
        original_link=details.link,
        direct_url=direct_url,
        host=parsed.netloc or None,
        used_override=used_override,
        override_kind=override_kind,
    )


def _best_filename(name: str | None, path: str | None) -> str | None:
    return name or _extract_filename(path)


def _extract_filename(path: str | None) -> str | None:
    if not path:
        return None
    cleaned = path.strip().rstrip("/")
    if not cleaned:
        return None
    return cleaned.rsplit("/", 1)[-1] or None


def _is_playable(*, filename: str | None, mime_type: str | None) -> bool:
    normalized_mime = (mime_type or "").strip().lower()
    if normalized_mime.startswith("video/"):
        return True
    normalized_name = (filename or "").strip().lower()
    return any(normalized_name.endswith(ext) for ext in VIDEO_EXTENSIONS)


def _listed_at_epoch(value: datetime | None) -> float:
    if value is None:
        return float("-inf")
    return value.timestamp()


def _item_sort_key(candidate: _ItemCandidate) -> tuple[float, float, str, str]:
    return (
        -float(candidate.size_bytes),
        -_listed_at_epoch(candidate.listed_at),
        candidate.id,
        candidate.filename or "",
    )
