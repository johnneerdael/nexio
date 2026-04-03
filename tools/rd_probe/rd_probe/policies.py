from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ProbePolicy:
    name: str
    blocked_priority_retry: bool = False
    ahead_limit_chunks: int | None = None
    max_blocked_chunk_retries: int = 1
    retryable_error_kinds: tuple[str, ...] = ("url_error", "inactivity_timeout")
    force_fresh_connection_on_retry: bool = False
    retry_backoff_ms: int = 0


def build_default_policies(parallel: int) -> list[ProbePolicy]:
    return [
        ProbePolicy(name="baseline"),
        ProbePolicy(
            name="fresh-session-blocked-retry",
            blocked_priority_retry=True,
            force_fresh_connection_on_retry=True,
            max_blocked_chunk_retries=1,
        ),
        ProbePolicy(
            name="fresh-session-blocked-retry-with-backoff",
            blocked_priority_retry=True,
            force_fresh_connection_on_retry=True,
            retry_backoff_ms=250,
            max_blocked_chunk_retries=1,
        ),
    ]


def should_retry_blocked_chunk(
    policy: ProbePolicy,
    *,
    failed_chunk_index: int,
    blocked_chunk_index: int | None,
    completed_ahead_chunks: list[int] | None = None,
    error_kind: str | None,
    retry_count: int,
) -> bool:
    completed_ahead_chunks = completed_ahead_chunks or []
    if policy.force_fresh_connection_on_retry:
        return (
            error_kind in policy.retryable_error_kinds and
            retry_count < policy.max_blocked_chunk_retries
        )
    return (
        policy.blocked_priority_retry and
        blocked_chunk_index is not None and
        failed_chunk_index <= blocked_chunk_index + max(1, len(completed_ahead_chunks)) and
        error_kind in policy.retryable_error_kinds and
        retry_count < policy.max_blocked_chunk_retries
    )


def effective_required_chunk(
    blocked_chunk_index: int | None,
    completed_ahead_chunks: list[int],
) -> int | None:
    if blocked_chunk_index is None:
        return None
    required = blocked_chunk_index
    completed = set(completed_ahead_chunks)
    while required in completed:
        required += 1
    return required


def can_schedule_next_chunk(
    policy: ProbePolicy,
    *,
    next_chunk_index: int,
    blocked_chunk_index: int | None,
    blocked_chunk_state: str | None,
    completed_ahead_chunks: list[int],
) -> bool:
    if policy.ahead_limit_chunks is None:
        return True
    if blocked_chunk_index is None:
        return True
    if blocked_chunk_state not in {"assigned", "inflight", "retried", "failed", "never_completed"}:
        return True
    if not completed_ahead_chunks:
        return True
    return next_chunk_index <= blocked_chunk_index + policy.ahead_limit_chunks
