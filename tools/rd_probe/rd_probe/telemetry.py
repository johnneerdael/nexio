from __future__ import annotations

from dataclasses import asdict, dataclass, is_dataclass
from datetime import date, datetime
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
    blocked_chunk: int | None = None
    blocked_worker_id: int | None = None
    stalled_worker_ids: list[int] | None = None


class TelemetryWriter:
    def __init__(self, output_dir: Path) -> None:
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.worker_path = output_dir / "workers.jsonl"
        self.consumer_path = output_dir / "consumer.jsonl"
        self.ranges_path = output_dir / "ranges.csv"

    def write_session(self, payload: dict[str, Any]) -> None:
        (self.output_dir / "session.json").write_text(_json_dumps(payload))

    def write_summary(self, payload: dict[str, Any]) -> None:
        (self.output_dir / "summary.json").write_text(_json_dumps(payload))

    def append_worker_event(self, event: dict[str, Any]) -> None:
        with self.worker_path.open("a") as handle:
            handle.write(_json_dumps(event, indent=None) + "\n")

    def append_consumer_event(self, event: dict[str, Any]) -> None:
        with self.consumer_path.open("a") as handle:
            handle.write(_json_dumps(event, indent=None) + "\n")

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
    blocked_worker_id: int | None = None,
    stalled_worker_ids: list[int] | None = None,
) -> dict[str, Any]:
    stalled_worker_ids = stalled_worker_ids or []
    if blocked_chunk is not None and completed_ahead_chunks and blocked_worker_id is not None:
        likely = "single_worker_head_of_line_stall"
    elif worker_overlap_count >= 2 and len(stalled_worker_ids) >= 2 and longest_consumer_gap_ms > 0:
        likely = "multi_worker_global_stall"
    elif blocked_chunk is not None and completed_ahead_chunks:
        likely = "head_of_line_block"
    elif longest_consumer_gap_ms > 0:
        likely = "unknown_stall"
    else:
        likely = "clean"
    return asdict(
        SessionSummary(
            likely_mode=likely,
            longest_consumer_gap_ms=longest_consumer_gap_ms,
            worker_count_with_overlap=worker_overlap_count,
            blocked_chunk=blocked_chunk,
            blocked_worker_id=blocked_worker_id,
            stalled_worker_ids=stalled_worker_ids,
        )
    )


def new_run_dir(base_dir: Path) -> Path:
    return base_dir / time.strftime("%Y%m%d-%H%M%S")


def _json_dumps(payload: Any, indent: int | None = 2) -> str:
    return json.dumps(payload, indent=indent, default=_json_default)


def _json_default(value: Any) -> Any:
    if is_dataclass(value):
        return asdict(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, Path):
        return str(value)
    raise TypeError(f"Object of type {value.__class__.__name__} is not JSON serializable")
