from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
import csv
import json
import time
from typing import Any


@dataclass
class SessionSummary:
    likely_mode: str
    longest_consumer_gap_ms: int
    worker_count_with_overlap: int


class TelemetryWriter:
    def __init__(self, output_dir: Path) -> None:
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.worker_path = output_dir / "workers.jsonl"
        self.consumer_path = output_dir / "consumer.jsonl"
        self.ranges_path = output_dir / "ranges.csv"

    def write_session(self, payload: dict[str, Any]) -> None:
        (self.output_dir / "session.json").write_text(json.dumps(payload, indent=2))

    def write_summary(self, payload: dict[str, Any]) -> None:
        (self.output_dir / "summary.json").write_text(json.dumps(payload, indent=2))

    def append_worker_event(self, event: dict[str, Any]) -> None:
        with self.worker_path.open("a") as handle:
            handle.write(json.dumps(event) + "\n")

    def append_consumer_event(self, event: dict[str, Any]) -> None:
        with self.consumer_path.open("a") as handle:
            handle.write(json.dumps(event) + "\n")

    def write_ranges(self, rows: list[dict[str, Any]]) -> None:
        if not rows:
            return
        with self.ranges_path.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)


def classify_session(
    *,
    blocked_chunk: int | None,
    completed_ahead_chunks: list[int],
    worker_overlap_count: int,
    longest_consumer_gap_ms: int,
) -> dict[str, Any]:
    if blocked_chunk is not None and completed_ahead_chunks:
        likely = "head_of_line_block"
    elif worker_overlap_count >= 2 and longest_consumer_gap_ms > 0:
        likely = "multi_worker_stall"
    elif longest_consumer_gap_ms > 0:
        likely = "unknown_stall"
    else:
        likely = "clean"
    return asdict(
        SessionSummary(
            likely_mode=likely,
            longest_consumer_gap_ms=longest_consumer_gap_ms,
            worker_count_with_overlap=worker_overlap_count,
        )
    )


def new_run_dir(base_dir: Path) -> Path:
    return base_dir / time.strftime("%Y%m%d-%H%M%S")
