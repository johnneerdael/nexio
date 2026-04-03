from __future__ import annotations

import argparse
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import time

from .analyze import load_summary
from .assembler import InOrderAssembler
from .candidate_resolver import CandidateResolver
from .config import load_config
from .pcap import PcapController
from .range_scheduler import RangeScheduler
from .rd_api import RealDebridClient
from .telemetry import TelemetryWriter, classify_session, new_run_dir
from .worker import fetch_range


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rd_probe")
    subparsers = parser.add_subparsers(dest="command", required=True)

    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("--env-file", default=".env")
    run_parser.add_argument("--parallel", type=int)
    run_parser.add_argument("--chunk-mb", type=int)
    run_parser.add_argument("--duration", type=int)
    run_parser.add_argument("--byte-limit", type=int)
    run_parser.add_argument("--output-dir")
    run_parser.add_argument("--enable-pcap", action="store_true")
    run_parser.add_argument("--inactivity-timeout", type=float, default=10.0)
    run_parser.add_argument("--download-id")
    run_parser.add_argument("--torrent-id")
    run_parser.add_argument("--direct-url")

    analyze_parser = subparsers.add_parser("analyze")
    analyze_parser.add_argument("run_dir")
    return parser


def cmd_run(args: argparse.Namespace) -> int:
    config = load_config(args.env_file)
    parallel = args.parallel or config.parallel
    chunk_mb = args.chunk_mb or config.chunk_mb
    duration = args.duration or config.duration
    base_dir = Path(args.output_dir) if args.output_dir else config.output_dir
    run_dir = new_run_dir(base_dir)
    telemetry = TelemetryWriter(run_dir)

    client = RealDebridClient(config.realdebrid_api_token)
    resolver = CandidateResolver(client)
    candidate = resolver.resolve(
        download_id=args.download_id,
        torrent_id=args.torrent_id,
        direct_url=args.direct_url,
    )

    pcap = None
    pcap_info = None
    session_payload = {
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
    }
    if args.enable_pcap:
        pcap = PcapController(run_dir / "capture.pcap", candidate.host)
        pcap.start()

    try:
        file_size = candidate.size_bytes or (args.byte_limit or (parallel * chunk_mb * 1024 * 1024 * 4))
        chunk_size = chunk_mb * 1024 * 1024
        scheduler = RangeScheduler(file_size=file_size, chunk_size=chunk_size, parallelism=parallel)
        assembler = InOrderAssembler(chunk_size=chunk_size)
        deadline = time.monotonic() + max(duration, 1)
        effective_byte_limit = min(file_size, args.byte_limit or file_size)
        worker_rows = []
        total_scheduled_bytes = 0
        chunk_owner_by_index = {}
        stalled_worker_ids = set()
        longest_consumer_gap_ms = 0
        blocked_started_at = None
        previous_blocked_chunk = assembler.blocked_on_chunk

        def can_schedule_more() -> bool:
            return time.monotonic() < deadline and total_scheduled_bytes < effective_byte_limit

        with ThreadPoolExecutor(max_workers=parallel) as executor:
            futures = {}
            worker_index = 0

            while len(futures) < parallel and can_schedule_more():
                chunk = scheduler.next_chunk()
                if chunk is None:
                    break
                if chunk.start >= effective_byte_limit:
                    break
                assigned_worker_id = worker_index % parallel
                chunk_owner_by_index[chunk.index] = assigned_worker_id
                futures[
                    executor.submit(
                        fetch_range,
                        worker_id=assigned_worker_id,
                        url=candidate.direct_url,
                        chunk=chunk,
                        inactivity_timeout_s=args.inactivity_timeout,
                    )
                ] = chunk
                total_scheduled_bytes += (chunk.end - chunk.start + 1)
                worker_index += 1

            while futures:
                for future in as_completed(list(futures.keys()), timeout=None):
                    chunk = futures.pop(future)
                    result = future.result()
                    current_chunk = result.chunk if result.chunk else chunk
                    ahead_chunks_before = list(assembler.completed_ahead())
                    blocked_chunk_before = assembler.blocked_on_chunk
                    if result.error is None:
                        assembler.mark_chunk_complete(current_chunk.index, current_chunk.start, current_chunk.end)
                    ahead_chunks_after = list(assembler.completed_ahead())
                    blocked_chunk_after = assembler.blocked_on_chunk

                    telemetry.append_worker_event(
                        {
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
                            "worker_id": result.worker_id,
                            "chunk_index": current_chunk.index,
                            "start": current_chunk.start,
                            "end": current_chunk.end,
                            "bytes_read": result.bytes_read,
                            "error": result.error or "",
                            "worker_id": result.worker_id,
                        }
                    )
                    if result.error_kind == "inactivity_timeout":
                        stalled_worker_ids.add(result.worker_id)

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
                            "blocked_on_chunk_before": blocked_chunk_before,
                            "blocked_on_chunk": blocked_chunk_after,
                            "blocked_worker_id": chunk_owner_by_index.get(blocked_chunk_after) if blocked_chunk_after is not None else None,
                            "consumer_bytes_available": assembler.consumer_bytes_available,
                            "completed_ahead_before": ahead_chunks_before,
                            "completed_ahead": ahead_chunks_after,
                        }
                    )

                    while len(futures) < parallel and can_schedule_more():
                        next_chunk = scheduler.next_chunk()
                        if next_chunk is None:
                            break
                        if next_chunk.start >= effective_byte_limit:
                            break
                        assigned_worker_id = worker_index % parallel
                        chunk_owner_by_index[next_chunk.index] = assigned_worker_id
                        futures[
                            executor.submit(
                                fetch_range,
                                worker_id=assigned_worker_id,
                                url=candidate.direct_url,
                                chunk=next_chunk,
                                inactivity_timeout_s=args.inactivity_timeout,
                            )
                        ] = next_chunk
                        total_scheduled_bytes += (next_chunk.end - next_chunk.start + 1)
                        worker_index += 1
                    break
        if blocked_started_at is not None:
            longest_consumer_gap_ms = max(
                longest_consumer_gap_ms,
                int((time.monotonic() - blocked_started_at) * 1000),
            )
        telemetry.write_ranges(worker_rows)
        summary = classify_session(
            blocked_chunk=assembler.blocked_on_chunk,
            completed_ahead_chunks=list(assembler.completed_ahead()),
            worker_overlap_count=len({row["worker_id"] for row in worker_rows if row["chunk_index"] in set(assembler.completed_ahead())}),
            longest_consumer_gap_ms=longest_consumer_gap_ms,
            blocked_worker_id=chunk_owner_by_index.get(assembler.blocked_on_chunk) if assembler.blocked_on_chunk is not None else None,
            stalled_worker_ids=sorted(stalled_worker_ids),
        )
        summary_payload = summary
        if pcap:
            pcap_info = pcap.stop()
        session_payload = {
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
        print(run_dir)
        return 0
    except Exception as exc:
        session_payload = {
            **session_payload,
            "candidate": candidate.__dict__,
            "error": str(exc),
        }
        raise
    finally:
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
