from __future__ import annotations

from dataclasses import dataclass
from typing import BinaryIO
import time
import urllib.request

from .range_scheduler import ByteRange


@dataclass
class WorkerResult:
    worker_id: int
    chunk: ByteRange
    bytes_read: int
    first_byte_at: float | None
    completed_at: float | None
    error: str | None = None


def fetch_range(
    *,
    worker_id: int,
    url: str,
    chunk: ByteRange,
    buffer_size: int = 64 * 1024,
) -> WorkerResult:
    request = urllib.request.Request(url, headers={"Range": f"bytes={chunk.start}-{chunk.end}"})
    first_byte_at = None
    bytes_read = 0
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            while True:
                block = response.read(buffer_size)
                if not block:
                    break
                if first_byte_at is None:
                    first_byte_at = time.time()
                bytes_read += len(block)
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            first_byte_at=first_byte_at,
            completed_at=time.time(),
        )
    except Exception as exc:  # pragma: no cover - network path
        return WorkerResult(
            worker_id=worker_id,
            chunk=chunk,
            bytes_read=bytes_read,
            first_byte_at=first_byte_at,
            completed_at=time.time(),
            error=str(exc),
        )
