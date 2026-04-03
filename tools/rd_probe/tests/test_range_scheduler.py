from rd_probe.range_scheduler import ByteRange, ChunkTracker, RangeScheduler


def test_scheduler_assigns_expected_ranges_for_parallel_workers():
    scheduler = RangeScheduler(file_size=1024, chunk_size=128, parallelism=4)
    assigned = [scheduler.next_range() for _ in range(4)]
    assert assigned == [(0, 127), (128, 255), (256, 383), (384, 511)]


def test_chunk_tracker_marks_retried_blocked_chunk():
    tracker = ChunkTracker()
    chunk = ByteRange(index=5, start=640, end=767)

    tracker.mark_assigned(chunk=chunk, worker_id=0, assigned_at=1.0)
    tracker.mark_inflight(chunk_index=5, started_at=2.0)
    tracker.mark_failed(chunk_index=5, failed_at=3.0, error_kind="inactivity_timeout", retryable=True)
    tracker.mark_assigned(chunk=chunk, worker_id=1, assigned_at=4.0, retry_count=1)

    snapshot = tracker.snapshot_for(chunk_index=5, has_been_scheduled=True)

    assert snapshot["state"] == "retried"
    assert snapshot["worker_id"] == 1
    assert snapshot["retry_count"] == 1
