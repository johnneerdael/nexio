from __future__ import annotations

from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class ByteRange:
    index: int
    start: int
    end: int


class RangeScheduler:
    def __init__(self, file_size: int, chunk_size: int, parallelism: int) -> None:
        if file_size <= 0:
            raise ValueError("file_size must be > 0")
        if chunk_size <= 0:
            raise ValueError("chunk_size must be > 0")
        if parallelism <= 0:
            raise ValueError("parallelism must be > 0")
        self.file_size = file_size
        self.chunk_size = chunk_size
        self.parallelism = parallelism
        self._next_index = 0

    def next_range(self) -> tuple[int, int] | None:
        start = self._next_index * self.chunk_size
        if start >= self.file_size:
            return None
        end = min(start + self.chunk_size - 1, self.file_size - 1)
        self._next_index += 1
        return (start, end)

    def next_chunk(self) -> ByteRange | None:
        next_range = self.next_range()
        if next_range is None:
            return None
        index = self._next_index - 1
        return ByteRange(index=index, start=next_range[0], end=next_range[1])

    def has_been_scheduled(self, chunk_index: int) -> bool:
        return chunk_index < self._next_index


@dataclass
class ChunkState:
    chunk_index: int
    worker_id: int | None = None
    state: str = "assigned"
    assigned_at: float | None = None
    inflight_at: float | None = None
    last_progress_at: float | None = None
    completed_at: float | None = None
    failed_at: float | None = None
    retry_count: int = 0
    error_kind: str | None = None


class ChunkTracker:
    def __init__(self) -> None:
        self._states: dict[int, ChunkState] = {}

    def mark_assigned(
        self,
        *,
        chunk: ByteRange,
        worker_id: int,
        assigned_at: float,
        retry_count: int = 0,
    ) -> None:
        prior = self._states.get(chunk.index)
        state = "retried" if retry_count > 0 else "assigned"
        self._states[chunk.index] = ChunkState(
            chunk_index=chunk.index,
            worker_id=worker_id,
            state=state,
            assigned_at=assigned_at,
            inflight_at=prior.inflight_at if prior else None,
            last_progress_at=prior.last_progress_at if prior else None,
            completed_at=None,
            failed_at=None,
            retry_count=retry_count,
            error_kind=None,
        )

    def mark_inflight(self, *, chunk_index: int, started_at: float) -> None:
        state = self._states.setdefault(chunk_index, ChunkState(chunk_index=chunk_index))
        state.state = "inflight" if state.retry_count == 0 else "retried"
        state.inflight_at = started_at

    def mark_progress(self, *, chunk_index: int, at: float) -> None:
        state = self._states.setdefault(chunk_index, ChunkState(chunk_index=chunk_index))
        state.last_progress_at = at

    def mark_completed(self, *, chunk_index: int, completed_at: float) -> None:
        state = self._states.setdefault(chunk_index, ChunkState(chunk_index=chunk_index))
        state.state = "completed"
        state.completed_at = completed_at

    def mark_failed(self, *, chunk_index: int, failed_at: float, error_kind: str | None, retryable: bool) -> None:
        state = self._states.setdefault(chunk_index, ChunkState(chunk_index=chunk_index))
        state.state = "failed"
        state.failed_at = failed_at
        state.error_kind = error_kind
        if retryable:
            state.retry_count += 1

    def snapshot_for(self, *, chunk_index: int, has_been_scheduled: bool) -> dict:
        if not has_been_scheduled and chunk_index not in self._states:
            return {
                "chunk_index": chunk_index,
                "state": "unscheduled",
                "worker_id": None,
                "retry_count": 0,
                "error_kind": None,
            }
        state = self._states.get(chunk_index)
        if state is None:
            return {
                "chunk_index": chunk_index,
                "state": "never_completed",
                "worker_id": None,
                "retry_count": 0,
                "error_kind": None,
            }
        payload = asdict(state)
        if payload["state"] == "assigned" and payload["retry_count"] > 0:
            payload["state"] = "retried"
        return payload
