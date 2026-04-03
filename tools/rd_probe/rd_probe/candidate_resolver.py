from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Protocol
from urllib.parse import urlparse

from rd_probe.rd_api import RdDownload, RdResolvedLink, RdTorrent, RdTorrentFile, RdTorrentInfo


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


class CandidateResolutionError(RuntimeError):
    """Raised when no usable Real-Debrid probe candidate can be resolved."""


class RdCandidateClient(Protocol):
    def list_downloads(self, *, page: int = 1, limit: int = 200) -> list[RdDownload]: ...

    def list_torrents(self, *, page: int = 1, limit: int = 200) -> list[RdTorrent]: ...

    def get_torrent_info(self, torrent_id: str) -> RdTorrentInfo: ...

    def unrestrict_link(self, link: str, *, remote: int = 0) -> RdResolvedLink: ...


@dataclass(frozen=True)
class CandidateOverride:
    download_id: str | None = None
    torrent_id: str | None = None
    direct_url: str | None = None

    def active(self) -> tuple[str, str] | None:
        active = [
            ("download_id", self.download_id),
            ("torrent_id", self.torrent_id),
            ("direct_url", self.direct_url),
        ]
        provided = [(name, value) for name, value in active if value]
        if len(provided) > 1:
            raise ValueError("Only one candidate override may be specified.")
        return provided[0] if provided else None


@dataclass(frozen=True)
class ResolvedCandidate:
    source: str
    source_id: str
    filename: str | None
    size_bytes: int | None
    listed_at: datetime | None
    original_link: str | None
    direct_url: str
    host: str | None
    used_override: bool
    override_kind: str | None = None

    @property
    def resolved_url(self) -> str:
        return self.direct_url

    @property
    def resolved_host(self) -> str | None:
        return self.host

    @property
    def explicit_override(self) -> bool:
        return self.used_override


@dataclass(frozen=True)
class _TorrentCandidate:
    torrent_id: str
    file_id: int | str
    filename: str | None
    size_bytes: int
    listed_at: datetime | None
    link: str


class CandidateResolver:
    def __init__(self, client: RdCandidateClient) -> None:
        self._client = client

    def resolve(
        self,
        *,
        download_id: str | None = None,
        torrent_id: str | None = None,
        direct_url: str | None = None,
        override: CandidateOverride | None = None,
    ) -> ResolvedCandidate:
        selected_override = override or CandidateOverride(
            download_id=download_id,
            torrent_id=torrent_id,
            direct_url=direct_url,
        )
        active = selected_override.active()
        if active is None:
            return self.resolve_large_candidate()
        kind, value = active
        if kind == "download_id":
            return self.resolve_download_id(value)
        if kind == "torrent_id":
            return self.resolve_torrent_id(value)
        return self.resolve_direct_url(value)

    def resolve_large_candidate(self) -> ResolvedCandidate:
        candidates: list[_TorrentCandidate] = []
        for torrent in self._list_torrents():
            if not torrent.status or not torrent.status.lower().startswith("downloaded"):
                continue
            info = self._client.get_torrent_info(torrent.id)
            listed_at = info.listed_at or torrent.listed_at
            for file, link in _iter_torrent_file_pairs(info):
                candidate = self._build_torrent_candidate(
                    torrent_id=torrent.id,
                    file=file,
                    link=link,
                    listed_at=listed_at,
                )
                if candidate is not None:
                    candidates.append(candidate)
        if not candidates:
            raise CandidateResolutionError("No playable Real-Debrid torrent candidate with known size was found.")
        best = sorted(candidates, key=_torrent_sort_key)[0]
        resolved = self._client.unrestrict_link(best.link)
        return ResolvedCandidate(
            source="torrent",
            source_id=best.torrent_id,
            filename=resolved.filename or best.filename,
            size_bytes=resolved.size_bytes or best.size_bytes,
            listed_at=best.listed_at,
            original_link=resolved.link,
            direct_url=resolved.direct_url,
            host=resolved.host,
            used_override=False,
            override_kind=None,
        )

    def resolve_download_id(self, download_id: str) -> ResolvedCandidate:
        for download in self._list_downloads():
            if download.id != download_id:
                continue
            if not _is_playable(filename=download.filename, mime_type=download.mime_type):
                raise CandidateResolutionError(f"Download {download_id} is not a playable video file.")
            resolved = self._resolve_download(download)
            return ResolvedCandidate(
                source="download",
                source_id=download.id,
                filename=resolved.filename or download.filename,
                size_bytes=resolved.size_bytes or download.size_bytes,
                listed_at=download.listed_at,
                original_link=resolved.link,
                direct_url=resolved.direct_url,
                host=resolved.host,
                used_override=True,
                override_kind="download_id",
            )
        raise CandidateResolutionError(f"Download override {download_id} was not found.")

    def resolve_torrent_id(self, torrent_id: str) -> ResolvedCandidate:
        matching_torrent = next((torrent for torrent in self._list_torrents() if torrent.id == torrent_id), None)
        info = self._client.get_torrent_info(torrent_id)
        listed_at = info.listed_at or (matching_torrent.listed_at if matching_torrent else None)
        candidates = [
            candidate
            for file, link in _iter_torrent_file_pairs(info)
            if (candidate := self._build_torrent_candidate(torrent_id=torrent_id, file=file, link=link, listed_at=listed_at)) is not None
        ]
        if not candidates:
            raise CandidateResolutionError(f"Torrent override {torrent_id} has no playable file with known size.")
        best = sorted(candidates, key=_torrent_sort_key)[0]
        resolved = self._client.unrestrict_link(best.link)
        return ResolvedCandidate(
            source="torrent",
            source_id=torrent_id,
            filename=resolved.filename or best.filename,
            size_bytes=resolved.size_bytes or best.size_bytes,
            listed_at=best.listed_at,
            original_link=resolved.link,
            direct_url=resolved.direct_url,
            host=resolved.host,
            used_override=True,
            override_kind="torrent_id",
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

    def _build_torrent_candidate(
        self,
        *,
        torrent_id: str,
        file: RdTorrentFile,
        link: str,
        listed_at: datetime | None,
    ) -> _TorrentCandidate | None:
        if file.size_bytes is None or file.size_bytes <= 0:
            return None
        filename = _extract_filename(file.path)
        if not _is_playable(filename=filename, mime_type=None):
            return None
        return _TorrentCandidate(
            torrent_id=torrent_id,
            file_id=file.id,
            filename=filename,
            size_bytes=file.size_bytes,
            listed_at=listed_at,
            link=link,
        )

    def _resolve_download(self, download: RdDownload) -> RdResolvedLink:
        if download.direct_url:
            return RdResolvedLink(
                link=download.link or download.direct_url,
                direct_url=download.direct_url,
                filename=download.filename,
                mime_type=download.mime_type,
                size_bytes=download.size_bytes,
                host=download.host,
            )
        if not download.link:
            raise CandidateResolutionError(f"Download {download.id} does not contain a direct URL or link to unrestrict.")
        return self._client.unrestrict_link(download.link)

    def _list_downloads(self) -> list[RdDownload]:
        if hasattr(self._client, "list_downloads"):
            return self._client.list_downloads()
        return getattr(self._client, "get_downloads")()

    def _list_torrents(self) -> list[RdTorrent]:
        if hasattr(self._client, "list_torrents"):
            return self._client.list_torrents()
        return getattr(self._client, "get_torrents")()



def _iter_torrent_file_pairs(info: RdTorrentInfo) -> list[tuple[RdTorrentFile, str]]:
    selected_files = [file for file in info.files if file.selected]
    if selected_files and len(selected_files) == len(info.links):
        return list(zip(selected_files, info.links))
    return list(zip(info.files, info.links))



def _extract_filename(path: str | None) -> str | None:
    if not path:
        return None
    cleaned = path.strip().rstrip("/")
    if not cleaned:
        return None
    candidate = cleaned.rsplit("/", 1)[-1]
    return candidate or None



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



def _torrent_sort_key(candidate: _TorrentCandidate) -> tuple[float, float, str, str]:
    return (
        -float(candidate.size_bytes),
        -_listed_at_epoch(candidate.listed_at),
        str(candidate.torrent_id),
        candidate.filename or "",
    )
