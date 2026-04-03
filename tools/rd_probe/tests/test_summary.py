from rd_probe.telemetry import classify_session


def test_summary_classifies_head_of_line_block_when_only_one_required_chunk_stalls():
    summary = classify_session(
        blocked_chunk=0,
        completed_ahead_chunks=[1, 2],
        worker_overlap_count=1,
        longest_consumer_gap_ms=7000,
    )
    assert summary["likely_mode"] == "head_of_line_block"
