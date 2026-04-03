from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable


@dataclass
class InOrderAssembler:
    chunk_size: int
    next_required_index: int = 0
    completed: dict[int, tuple[int, int]] = field(default_factory=dict)
    consumer_bytes_available: int = 0
    blocked_on_chunk: int = 0

    def mark_chunk_complete(self, index: int, start: int, end: int) -> None:
        self.completed[index] = (start, end)
        self._flush()

    def _flush(self) -> None:
        while self.next_required_index in self.completed:
            start, end = self.completed.pop(self.next_required_index)
            self.consumer_bytes_available += end - start + 1
            self.next_required_index += 1
        self.blocked_on_chunk = self.next_required_index

    def completed_ahead(self) -> Iterable[int]:
        return sorted(idx for idx in self.completed if idx > self.next_required_index)
