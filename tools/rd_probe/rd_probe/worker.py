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


def fetch_range(
    *,
    worker_id: int,
    url: str,
    chunk: ByteRange,
    buffer_size: int = 64 * 1024,
    inactivity_timeout_s: float = 10.0,
) -> WorkerResult:
    request = urllib.request.Request(url, headers={"Range": f"bytes={chunk.start}-{chunk.end}"})
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
            error_kind="inactivity_timeout",
        )
    except URLError as exc:  # pragma: no cover - network path
        kind = "inactivity_timeout" if "timed out" in str(exc).lower() else "url_error"
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            started_at=started_at,
            first_byte_at=first_byte_at,
            last_progress_at=last_progress_at,
            completed_at=time.time(),
            error=str(exc),
            error_kind=kind,
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
            error_kind="error",
        )
