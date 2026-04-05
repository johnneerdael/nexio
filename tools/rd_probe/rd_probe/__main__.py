from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import sys
import threading
import time
import warnings

warnings.filterwarnings(
    "ignore",
    message=r"urllib3 v2 only supports OpenSSL 1\.1\.1\+.*",
    category=Warning,
    module=r"urllib3.*",
)

from .analyze import load_summary
from .policies import (
    build_default_policies,
    can_schedule_next_chunk,
    effective_required_chunk,
    should_retry_blocked_chunk,
)
from .assembler import InOrderAssembler
from .candidate_resolver import CandidateResolver
from .premiumize_candidate_resolver import PremiumizeCandidateResolver
from .config import load_config
from .pcap import PcapController
from .premiumize_api import PremiumizeClient
from .range_scheduler import ChunkTracker, RangeScheduler
from .rd_api import RealDebridClient
from .telemetry import TelemetryWriter, classify_session, new_run_dir
from .worker import fetch_range


def _percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    sorted_values = sorted(values)
    k = (len(sorted_values) - 1) * q
    floor_index = math.floor(k)
    ceil_index = math.ceil(k)
    if floor_index == ceil_index:
        return sorted_values[int(k)]
    return (
        sorted_values[floor_index] * (ceil_index - k)
        + sorted_values[ceil_index] * (k - floor_index)
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rd_probe")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("--env-file", default=".env")
    run_parser.add_argument(
        "--provider",
        choices=["realdebrid", "premiumize"],
        help="Debrid provider for candidate resolution (default from config/env, fallback: realdebrid).",
    )
    run_parser.add_argument("--parallel", type=int)
    run_parser.add_argument("--chunk-mb", type=int)
    run_parser.add_argument("--duration", type=int)
    run_parser.add_argument("--byte-limit", type=int)
    run_parser.add_argument("--output-dir")
    run_parser.add_argument("--enable-pcap", action="store_true")
    run_parser.add_argument("--inactivity-timeout", type=float, default=10.0)
    run_parser.add_argument(
        "--policy",
        default="all",
        choices=[
            "all",
            "baseline",
            "fresh-session-blocked-retry",
            "fresh-session-blocked-retry-with-backoff",
        ],
    )
    run_parser.add_argument("--download-id")
    run_parser.add_argument("--torrent-id")
    run_parser.add_argument("--item-id", help="Premiumize item id override.")
    run_parser.add_argument("--direct-url")

    analyze_parser = subparsers.add_parser("analyze")
    analyze_parser.add_argument("run_dir")
    return parser


def _start_status_bar(status: dict) -> tuple[threading.Event, threading.Thread]:
    stop_event = threading.Event()
    interactive_tty = sys.stdout.isatty() and not os.environ.get("TERM", "").lower() == "dumb"

    def render() -> None:
        last_line = None
        while not stop_event.is_set():
            elapsed = int(time.monotonic() - status["started_monotonic"])
            line = (
                f"[{status['policy']}] "
                f"{elapsed:>4}s "
                f"blocked={status.get('blocked_chunk', '-')}"
                f"/w{status.get('blocked_worker_id', '-')}"
                f" state={status.get('blocked_state', '-')}"
                f" ahead={status.get('completed_ahead_count', 0)}"
                f" active={status.get('active_workers', 0)}"
                f" done={status.get('completed_chunks', 0)}"
                f" retries={status.get('retry_events', 0)}"
                f" bytes={status.get('consumer_bytes_available', 0)}"
            )
            line = line[:180].ljust(180)
            if interactive_tty:
                sys.stdout.write("\r" + line)
                sys.stdout.flush()
            elif line != last_line:
                print(line.rstrip(), flush=True)
                last_line = line
            stop_event.wait(1.0)
        if interactive_tty:
            sys.stdout.write("\r" + " " * 180 + "\r")
            sys.stdout.flush()
        else:
            print("[status complete]", flush=True)

    thread = threading.Thread(target=render, daemon=True)
    thread.start()
    return stop_event, thread


def _split_total_duration(total_duration_s: int, policy_count: int) -> list[int]:
    total = max(total_duration_s, policy_count)
    base = total // policy_count
    remainder = total % policy_count
    return [
        base + (1 if index < remainder else 0)
        for index in range(policy_count)
    ]


def cmd_run(args: argparse.Namespace) -> int:
    config = load_config(args.env_file)
    provider = (args.provider or config.provider or "realdebrid").lower()
    parallel = args.parallel or config.parallel
    chunk_mb = args.chunk_mb or config.chunk_mb
    duration = args.duration or config.duration
    base_dir = Path(args.output_dir) if args.output_dir else config.output_dir
    run_dir = new_run_dir(base_dir)
    if provider == "realdebrid":
        if not config.realdebrid_api_token:
            raise ValueError("REALDEBRID_API_TOKEN is required when --provider realdebrid is selected.")
        if args.item_id:
            raise ValueError("--item-id is only supported with --provider premiumize.")
        client = RealDebridClient(config.realdebrid_api_token)
        resolver = CandidateResolver(client)
        candidate = resolver.resolve(
            download_id=args.download_id,
            torrent_id=args.torrent_id,
            direct_url=args.direct_url,
        )
    elif provider == "premiumize":
        if not config.premiumize_api_key:
            raise ValueError("PREMIUMIZE_API_KEY is required when --provider premiumize is selected.")
        if args.download_id or args.torrent_id:
            raise ValueError("--download-id and --torrent-id are only supported with --provider realdebrid.")
        client = PremiumizeClient(config.premiumize_api_key)
        resolver = PremiumizeCandidateResolver(client)
        candidate = resolver.resolve(
            item_id=args.item_id,
            direct_url=args.direct_url,
        )
    else:
        raise ValueError(f"Unsupported provider: {provider}")

    policies = build_default_policies(parallel)
    if args.policy != "all":
        policies = [policy for policy in policies if policy.name == args.policy]
    policy_durations = _split_total_duration(duration, len(policies))

    comparison = []
    for policy, policy_duration in zip(policies, policy_durations):
        variant_dir = run_dir / policy.name if len(policies) > 1 else run_dir
        summary_payload, session_payload = run_policy_variant(
            args=args,
            candidate=candidate,
            provider=provider,
            parallel=parallel,
            chunk_mb=chunk_mb,
            duration=policy_duration,
            variant_dir=variant_dir,
            policy=policy,
        )
        comparison.append(
            {
                "policy": policy.name,
                "allocated_duration": policy_duration,
                "summary": summary_payload,
                "session": session_payload,
            }
        )

    if len(policies) > 1:
        (run_dir / "comparison.json").write_text(json.dumps(comparison, indent=2, default=str))
    print(run_dir)
    return 0


def run_policy_variant(
    *,
    args: argparse.Namespace,
    candidate,
    provider: str,
    parallel: int,
    chunk_mb: int,
    duration: int,
    variant_dir: Path,
    policy,
):
    telemetry = TelemetryWriter(variant_dir)
    pcap = None
    pcap_info = None
    session_payload = {
        "provider": provider,
        "policy": policy.name,
        "parallel": parallel,
        "chunk_mb": chunk_mb,
        "duration": duration,
    }
    summary_payload = {
        "likely_mode": "unknown",
        "longest_consumer_gap_ms": 0,
        "worker_count_with_overlap": 0,
        "blocked_chunk": None,
        "blocked_worker_id": None,
        "stalled_worker_ids": [],
        "consumer_p10_mbps": None,
    }
    status = {
        "policy": policy.name,
        "started_monotonic": time.monotonic(),
        "blocked_chunk": "-",
        "blocked_worker_id": "-",
        "blocked_state": "-",
        "completed_ahead_count": 0,
        "active_workers": 0,
        "completed_chunks": 0,
        "retry_events": 0,
        "consumer_bytes_available": 0,
    }
    status_stop_event, status_thread = _start_status_bar(status)
    if args.enable_pcap:
        pcap = PcapController(variant_dir / "capture.pcap", candidate.host)
        pcap.start()

    try:
        file_size = candidate.size_bytes or (args.byte_limit or (parallel * chunk_mb * 1024 * 1024 * 4))
        chunk_size = chunk_mb * 1024 * 1024
        scheduler = RangeScheduler(file_size=file_size, chunk_size=chunk_size, parallelism=parallel)
        assembler = InOrderAssembler(chunk_size=chunk_size)
        chunk_tracker = ChunkTracker()
        deadline = time.monotonic() + max(duration, 1)
        effective_byte_limit = min(file_size, args.byte_limit or file_size)
        worker_rows = []
        total_scheduled_bytes = 0
        stalled_worker_ids = set()
        longest_consumer_gap_ms = 0
        blocked_started_at = None
        previous_blocked_chunk = assembler.blocked_on_chunk
        retry_counts = {}
        consumer_throughput_samples_mbps = []
        previous_consumer_bytes = 0
        previous_consumer_event_at = None

        def can_schedule_more() -> bool:
            return time.monotonic() < deadline and total_scheduled_bytes < effective_byte_limit

        with ThreadPoolExecutor(max_workers=parallel) as executor:
            futures = {}
            worker_index = 0

            while len(futures) < parallel and can_schedule_more():
                chunk = scheduler.next_chunk()
                if chunk is None or chunk.start >= effective_byte_limit:
                    break
                assigned_worker_id = worker_index % parallel
                chunk_tracker.mark_assigned(
                    chunk=chunk,
                    worker_id=assigned_worker_id,
                    assigned_at=time.time(),
                    retry_count=retry_counts.get(chunk.index, 0),
                )
                futures[
                    executor.submit(
                        fetch_range,
                        worker_id=assigned_worker_id,
                        url=candidate.direct_url,
                        chunk=chunk,
                        inactivity_timeout_s=args.inactivity_timeout,
                        force_connection_close=False,
                        retry_backoff_ms=0,
                    )
                ] = chunk
                status["active_workers"] = len(futures)
                total_scheduled_bytes += (chunk.end - chunk.start + 1)
                worker_index += 1

            while futures:
                for future in as_completed(list(futures.keys()), timeout=None):
                    chunk = futures.pop(future)
                    result = future.result()
                    current_chunk = result.chunk if result.chunk else chunk
                    status["active_workers"] = len(futures)
                    chunk_tracker.mark_inflight(
                        chunk_index=current_chunk.index,
                        started_at=result.started_at,
                    )
                    if result.last_progress_at is not None:
                        chunk_tracker.mark_progress(
                            chunk_index=current_chunk.index,
                            at=result.last_progress_at,
                        )

                    ahead_chunks_before = list(assembler.completed_ahead())
                    blocked_chunk_before = assembler.blocked_on_chunk
                    if result.error is None:
                        assembler.mark_chunk_complete(current_chunk.index, current_chunk.start, current_chunk.end)
                        if result.completed_at is not None:
                            chunk_tracker.mark_completed(
                                chunk_index=current_chunk.index,
                                completed_at=result.completed_at,
                            )
                    else:
                        retryable = result.error_kind in policy.retryable_error_kinds
                        chunk_tracker.mark_failed(
                            chunk_index=current_chunk.index,
                            failed_at=result.completed_at or time.time(),
                            error_kind=result.error_kind,
                            retryable=retryable,
                        )
                    ahead_chunks_after = list(assembler.completed_ahead())
                    blocked_chunk_after = assembler.blocked_on_chunk
                    status["blocked_chunk"] = blocked_chunk_after if blocked_chunk_after is not None else "-"
                    status["completed_ahead_count"] = len(ahead_chunks_after)
                    status["consumer_bytes_available"] = assembler.consumer_bytes_available

                    telemetry.append_worker_event(
                        {
                            "policy": policy.name,
                            "worker_id": result.worker_id,
                            "chunk_index": current_chunk.index,
                            "start": current_chunk.start,
                            "end": current_chunk.end,
                            "bytes_read": result.bytes_read,
                            "error": result.error,
                            "error_kind": result.error_kind,
                            "started_at": result.started_at,
                            "first_byte_at": result.first_byte_at,
                            "last_progress_at": result.last_progress_at,
                            "completed_at": result.completed_at,
                        }
                    )
                    worker_rows.append(
                        {
                            "policy": policy.name,
                            "worker_id": result.worker_id,
                            "chunk_index": current_chunk.index,
                            "start": current_chunk.start,
                            "end": current_chunk.end,
                            "bytes_read": result.bytes_read,
                            "error": result.error or "",
                        }
                    )
                    if result.error is None:
                        status["completed_chunks"] += 1
                    if result.error_kind == "inactivity_timeout":
                        stalled_worker_ids.add(result.worker_id)

                    blocked_snapshot = chunk_tracker.snapshot_for(
                        chunk_index=blocked_chunk_after if blocked_chunk_after is not None else -1,
                        has_been_scheduled=(
                            scheduler.has_been_scheduled(blocked_chunk_after)
                            if blocked_chunk_after is not None
                            else False
                        ),
                    ) if blocked_chunk_after is not None else None

                    if blocked_chunk_after == previous_blocked_chunk:
                        if blocked_started_at is None:
                            blocked_started_at = time.monotonic()
                    else:
                        if blocked_started_at is not None:
                            longest_consumer_gap_ms = max(
                                longest_consumer_gap_ms,
                                int((time.monotonic() - blocked_started_at) * 1000),
                            )
                        blocked_started_at = time.monotonic() if blocked_chunk_after is not None else None
                        previous_blocked_chunk = blocked_chunk_after

                    telemetry.append_consumer_event(
                        {
                            "policy": policy.name,
                            "event_at": result.completed_at,
                            "blocked_on_chunk_before": blocked_chunk_before,
                            "blocked_on_chunk": blocked_chunk_after,
                            "blocked_worker_id": blocked_snapshot.get("worker_id") if blocked_snapshot else None,
                            "blocked_chunk_state": blocked_snapshot.get("state") if blocked_snapshot else None,
                            "blocked_chunk_retry_count": blocked_snapshot.get("retry_count") if blocked_snapshot else None,
                            "blocked_chunk_error_kind": blocked_snapshot.get("error_kind") if blocked_snapshot else None,
                            "consumer_bytes_available": assembler.consumer_bytes_available,
                            "completed_ahead_before": ahead_chunks_before,
                            "completed_ahead": ahead_chunks_after,
                        }
                    )

                    current_consumer_event_at = result.completed_at
                    current_consumer_bytes = assembler.consumer_bytes_available
                    if (
                        previous_consumer_event_at is not None and
                        current_consumer_event_at is not None and
                        current_consumer_event_at > previous_consumer_event_at and
                        current_consumer_bytes > previous_consumer_bytes
                    ):
                        delta_seconds = current_consumer_event_at - previous_consumer_event_at
                        delta_bytes = current_consumer_bytes - previous_consumer_bytes
                        consumer_throughput_samples_mbps.append(
                            (delta_bytes * 8.0) / delta_seconds / 1_000_000.0
                        )
                    previous_consumer_event_at = current_consumer_event_at
                    previous_consumer_bytes = current_consumer_bytes
                    if blocked_snapshot:
                        status["blocked_worker_id"] = blocked_snapshot.get("worker_id", "-")
                        status["blocked_state"] = blocked_snapshot.get("state", "-")

                    retry_count = retry_counts.get(current_chunk.index, 0)
                    retry_target_chunk = effective_required_chunk(
                        blocked_chunk_before,
                        ahead_chunks_before,
                    )
                    if result.error is not None and should_retry_blocked_chunk(
                        policy,
                        failed_chunk_index=current_chunk.index,
                        blocked_chunk_index=retry_target_chunk,
                        completed_ahead_chunks=ahead_chunks_before,
                        error_kind=result.error_kind,
                        retry_count=retry_count,
                    ):
                        next_retry_count = retry_count + 1
                        status["retry_events"] += 1
                        retry_counts[current_chunk.index] = next_retry_count
                        telemetry.append_worker_event(
                            {
                                "policy": policy.name,
                                "event_type": "retry_scheduled",
                                "worker_id": result.worker_id,
                                "chunk_index": current_chunk.index,
                                "retry_count": next_retry_count,
                                "fresh_connection": policy.force_fresh_connection_on_retry,
                                "retry_backoff_ms": policy.retry_backoff_ms,
                            }
                        )
                        chunk_tracker.mark_assigned(
                            chunk=current_chunk,
                            worker_id=result.worker_id,
                            assigned_at=time.time(),
                            retry_count=next_retry_count,
                        )
                        futures[
                            executor.submit(
                                fetch_range,
                                worker_id=result.worker_id,
                                url=candidate.direct_url,
                                chunk=current_chunk,
                                inactivity_timeout_s=args.inactivity_timeout,
                                force_connection_close=policy.force_fresh_connection_on_retry,
                                retry_backoff_ms=policy.retry_backoff_ms,
                            )
                        ] = current_chunk
                        status["active_workers"] = len(futures)
                        break

                    while len(futures) < parallel and can_schedule_more():
                        next_chunk = scheduler.next_chunk()
                        if next_chunk is None or next_chunk.start >= effective_byte_limit:
                            break
                        if not can_schedule_next_chunk(
                            policy,
                            next_chunk_index=next_chunk.index,
                            blocked_chunk_index=blocked_chunk_after,
                            blocked_chunk_state=(blocked_snapshot.get("state") if blocked_snapshot else None),
                            completed_ahead_chunks=ahead_chunks_after,
                        ):
                            break
                        assigned_worker_id = worker_index % parallel
                        chunk_tracker.mark_assigned(
                            chunk=next_chunk,
                            worker_id=assigned_worker_id,
                            assigned_at=time.time(),
                            retry_count=retry_counts.get(next_chunk.index, 0),
                        )
                        futures[
                            executor.submit(
                                fetch_range,
                                worker_id=assigned_worker_id,
                                url=candidate.direct_url,
                                chunk=next_chunk,
                                inactivity_timeout_s=args.inactivity_timeout,
                                force_connection_close=False,
                                retry_backoff_ms=0,
                            )
                        ] = next_chunk
                        status["active_workers"] = len(futures)
                        total_scheduled_bytes += (next_chunk.end - next_chunk.start + 1)
                        worker_index += 1
                    break

        if blocked_started_at is not None:
            longest_consumer_gap_ms = max(
                longest_consumer_gap_ms,
                int((time.monotonic() - blocked_started_at) * 1000),
            )
        blocked_snapshot = chunk_tracker.snapshot_for(
            chunk_index=assembler.blocked_on_chunk if assembler.blocked_on_chunk is not None else -1,
            has_been_scheduled=(
                scheduler.has_been_scheduled(assembler.blocked_on_chunk)
                if assembler.blocked_on_chunk is not None
                else False
            ),
        ) if assembler.blocked_on_chunk is not None else None
        telemetry.write_ranges(worker_rows)
        summary_payload = classify_session(
            blocked_chunk=assembler.blocked_on_chunk,
            blocked_chunk_state=blocked_snapshot.get("state") if blocked_snapshot else None,
            blocked_chunk_error_kind=blocked_snapshot.get("error_kind") if blocked_snapshot else None,
            completed_ahead_chunks=list(assembler.completed_ahead()),
            worker_overlap_count=len({row["worker_id"] for row in worker_rows if row["chunk_index"] in set(assembler.completed_ahead())}),
            longest_consumer_gap_ms=longest_consumer_gap_ms,
            blocked_worker_id=blocked_snapshot.get("worker_id") if blocked_snapshot else None,
            stalled_worker_ids=sorted(stalled_worker_ids),
        )
        summary_payload["consumer_p10_mbps"] = _percentile(consumer_throughput_samples_mbps, 0.10)
        if pcap:
            pcap_info = pcap.stop()
        session_payload = {
            "provider": provider,
            "policy": policy.name,
            "candidate": candidate.__dict__,
            "parallel": parallel,
            "chunk_mb": chunk_mb,
            "duration": duration,
            "effective_byte_limit": effective_byte_limit,
            "inactivity_timeout": args.inactivity_timeout,
            "pcap": pcap_info,
        }
        telemetry.write_session(session_payload)
        telemetry.write_summary(summary_payload)
        return summary_payload, session_payload
    except Exception as exc:
        session_payload = {
            **session_payload,
            "provider": provider,
            "policy": policy.name,
            "candidate": candidate.__dict__,
            "error": str(exc),
        }
        raise
    finally:
        status_stop_event.set()
        status_thread.join(timeout=1.5)
        if pcap and pcap_info is None:
            pcap_info = pcap.stop()
        if pcap_info is not None:
            session_payload["pcap"] = pcap_info
        telemetry.write_session(session_payload)
        telemetry.write_summary(summary_payload)


def cmd_analyze(args: argparse.Namespace) -> int:
    print(load_summary(args.run_dir))
    return 0


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.command == "run":
        return cmd_run(args)
    if args.command == "analyze":
        return cmd_analyze(args)
    parser.error("unknown command")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
