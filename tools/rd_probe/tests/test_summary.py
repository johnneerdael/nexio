from datetime import datetime, timezone
from pathlib import Path

from rd_probe.telemetry import classify_session
from rd_probe.telemetry import TelemetryWriter


def test_summary_classifies_head_of_line_block_when_only_one_required_chunk_stalls():
    summary = classify_session(
        blocked_chunk=0,
        completed_ahead_chunks=[1, 2],
        worker_overlap_count=1,
        longest_consumer_gap_ms=7000,
        blocked_worker_id=3,
        stalled_worker_ids=[3],
    )
    assert summary["likely_mode"] == "single_worker_head_of_line_stall"
    assert summary["blocked_worker_id"] == 3


def test_summary_classifies_multi_worker_global_stall():
    summary = classify_session(
        blocked_chunk=10,
        completed_ahead_chunks=[],
        worker_overlap_count=4,
        longest_consumer_gap_ms=7000,
        blocked_worker_id=None,
        stalled_worker_ids=[0, 1, 2, 3],
    )
    assert summary["likely_mode"] == "multi_worker_global_stall"


def test_telemetry_writer_serializes_datetime_and_path_in_session(tmp_path: Path):
    writer = TelemetryWriter(tmp_path)

    writer.write_session(
        {
            "measured_at": datetime(2024, 1, 1, tzinfo=timezone.utc),
            "capture_path": tmp_path / "capture.pcap",
        }
    )

    content = (tmp_path / "session.json").read_text()
    assert "2024-01-01T00:00:00+00:00" in content
    assert "capture.pcap" in content
