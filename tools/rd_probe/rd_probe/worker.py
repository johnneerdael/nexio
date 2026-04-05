from __future__ import annotations

from dataclasses import dataclass
import time
import urllib.request
from urllib.error import URLError

from .range_scheduler import ByteRange


@dataclass
class WorkerResult:
    worker_id: int
    chunk: ByteRange
    bytes_read: int
    started_at: float
    first_byte_at: float | None
    last_progress_at: float | None
    completed_at: float | None
    error: str | None = None
    error_kind: str | None = None


def _classify_error_kind(exc: Exception) -> str:
    message = str(exc).lower()
    if isinstance(exc, TimeoutError) or "timed out" in message:
        return "inactivity_timeout"
    if (
        isinstance(exc, ConnectionResetError) or
        "connection reset" in message or
        "reset by peer" in message
    ):
        return "connection_reset"
    if isinstance(exc, URLError):
        reason = getattr(exc, "reason", None)
        if isinstance(reason, Exception):
            nested_kind = _classify_error_kind(reason)
            if nested_kind != "error":
                return nested_kind
        return "url_error"
    return "error"


def fetch_range(
    *,
    worker_id: int,
    url: str,
    chunk: ByteRange,
    buffer_size: int = 64 * 1024,
    inactivity_timeout_s: float = 10.0,
    force_connection_close: bool = False,
    retry_backoff_ms: int = 0,
) -> WorkerResult:
    if retry_backoff_ms > 0:
        time.sleep(retry_backoff_ms / 1000.0)
    headers = {"Range": f"bytes={chunk.start}-{chunk.end}"}
    if force_connection_close:
        headers["Connection"] = "close"
    request = urllib.request.Request(url, headers=headers)
    started_at = time.time()
    first_byte_at = None
    last_progress_at = None
    bytes_read = 0
    try:
        with urllib.request.urlopen(request, timeout=inactivity_timeout_s) as response:
            while True:
                block = response.read(buffer_size)
                if not block:
                    break
                now = time.time()
                if first_byte_at is None:
                    first_byte_at = now
                last_progress_at = now
                bytes_read += len(block)
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            started_at=started_at,
            first_byte_at=first_byte_at,
            last_progress_at=last_progress_at,
            completed_at=time.time(),
        )
    except TimeoutError as exc:  # pragma: no cover - network path
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            started_at=started_at,
            first_byte_at=first_byte_at,
            last_progress_at=last_progress_at,
            completed_at=time.time(),
            error=str(exc),
            error_kind=_classify_error_kind(exc),
        )
    except URLError as exc:  # pragma: no cover - network path
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            started_at=started_at,
            first_byte_at=first_byte_at,
            last_progress_at=last_progress_at,
            completed_at=time.time(),
            error=str(exc),
            error_kind=_classify_error_kind(exc),
        )
    except Exception as exc:  # pragma: no cover - network path
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            started_at=started_at,
            first_byte_at=first_byte_at,
            last_progress_at=last_progress_at,
            completed_at=time.time(),
            error=str(exc),
            error_kind=_classify_error_kind(exc),
        )
