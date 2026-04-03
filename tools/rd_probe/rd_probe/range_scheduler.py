from __future__ import annotations

from dataclasses import dataclass


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
