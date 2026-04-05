from rd_probe.__main__ import _split_total_duration
from rd_probe.policies import (
    build_default_policies,
    can_schedule_next_chunk,
    effective_required_chunk,
    should_retry_blocked_chunk,
)


def test_build_default_policies_returns_three_variants():
    policies = build_default_policies(4)
    assert [policy.name for policy in policies] == [
        "baseline",
        "fresh-session-blocked-retry",
        "fresh-session-blocked-retry-with-backoff",
    ]


def test_fresh_session_policy_retries_failed_blocked_chunk():
    policies = build_default_policies(4)
    assert should_retry_blocked_chunk(
        policies[1],
        failed_chunk_index=10,
        blocked_chunk_index=10,
        completed_ahead_chunks=[],
        error_kind="url_error",
        retry_count=0,
    ) is True


def test_baseline_allows_future_chunk_scheduling():
    policies = build_default_policies(4)
    assert can_schedule_next_chunk(
        policies[0],
        next_chunk_index=20,
        blocked_chunk_index=10,
        blocked_chunk_state="failed",
        completed_ahead_chunks=[11, 12, 13],
    ) is True


def test_fresh_session_policy_requests_connection_reset_style_retry():
    policies = build_default_policies(4)
    assert policies[1].force_fresh_connection_on_retry is True
    assert policies[2].retry_backoff_ms == 250


def test_fresh_session_policy_retries_non_blocked_chunk_with_same_retryable_error():
    policies = build_default_policies(4)
    assert should_retry_blocked_chunk(
        policies[1],
        failed_chunk_index=23,
        blocked_chunk_index=22,
        completed_ahead_chunks=[],
        error_kind="url_error",
        retry_count=0,
    ) is True


def test_fresh_session_policy_retries_generic_error_kind():
    policies = build_default_policies(4)
    assert should_retry_blocked_chunk(
        policies[1],
        failed_chunk_index=10,
        blocked_chunk_index=10,
        completed_ahead_chunks=[],
        error_kind="error",
        retry_count=0,
    ) is True


def test_total_duration_is_split_across_selected_policies():
    assert _split_total_duration(120, 3) == [40, 40, 40]
    assert _split_total_duration(122, 3) == [41, 41, 40]


def test_effective_required_chunk_skips_contiguous_completed_ahead():
    assert effective_required_chunk(597, [598]) == 597
    assert effective_required_chunk(598, [598]) == 599
    assert effective_required_chunk(597, [597, 598, 600]) == 599


def test_retry_trigger_uses_effective_required_chunk_case_like_chunk_599():
    policies = build_default_policies(4)
    effective_blocked = effective_required_chunk(598, [598])
    assert should_retry_blocked_chunk(
        policies[1],
        failed_chunk_index=599,
        blocked_chunk_index=effective_blocked,
        completed_ahead_chunks=[],
        error_kind="url_error",
        retry_count=0,
    ) is True
