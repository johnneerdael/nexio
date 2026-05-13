#!/usr/bin/env python3
"""Benchmark OpenRouter subtitle translation speed and accuracy.

Set OPENROUTER_API_KEY before running, or pass --api-key explicitly.
"""

from __future__ import annotations

import argparse
import csv
import difflib
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError:  # pragma: no cover - runtime dependency guard
    requests = None


OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"

DEFAULT_MODELS = [
    "meta-llama/llama-3.3-70b-instruct",
    "meta-llama/llama-4-scout",
    "openai/gpt-oss-20b",
]

DEFAULT_SOURCE_SRT = """1
00:00:02,000 --> 00:00:07,000
A Big DickUs Production.
Cheers Youth Training Scheme

2
00:00:08,000 --> 00:00:13,000
Spaces added.
Better for old TVs

3
00:00:48,917 --> 00:00:49,917
Come on, Bro.

4
00:00:53,042 --> 00:00:55,958
Whoo! Whoo, whoo, whoo!

5
00:01:05,083 --> 00:01:06,708
Whoo!

6
00:01:10,792 --> 00:01:11,999
Whoo!

7
00:01:12,000 --> 00:01:13,832
Whoa. Whoa.

8
00:01:13,833 --> 00:01:14,916
Hey, watch it.

9
00:01:14,917 --> 00:01:16,749
You were in my way, Bro.

10
00:01:16,750 --> 00:01:18,333
Oh, okay.

11
00:01:19,000 --> 00:01:21,125
Whoo--

12
00:01:24,375 --> 00:01:25,583
Whoo!

13
00:01:29,583 --> 00:01:30,792
Whoo!

14
00:01:32,458 --> 00:01:33,957
Whoo!

15
00:01:33,958 --> 00:01:35,416
Is that
all you got?
"""

DEFAULT_REFERENCE_SRT = """1
00:00:02,000 --> 00:00:07,000
Een Big DickUs-productie.
Proost Jeugdtrainingsschema

2
00:00:08,000 --> 00:00:13,000
Spaties toegevoegd.
Beter voor oude tv's

3
00:00:48,917 --> 00:00:49,917
Kom op, bro.

4
00:00:53,042 --> 00:00:55,958
Whoo! Whoo, whoo, whoo!

5
00:01:05,083 --> 00:01:06,708
Whoo!

6
00:01:10,792 --> 00:01:11,999
Whoo!

7
00:01:12,000 --> 00:01:13,832
Whoa. Whoa.

8
00:01:13,833 --> 00:01:14,916
Hé, kijk uit.

9
00:01:14,917 --> 00:01:16,749
Je stond in de weg, bro.

10
00:01:16,750 --> 00:01:18,333
O, oké.

11
00:01:19,000 --> 00:01:21,125
Whoo--

12
00:01:24,375 --> 00:01:25,583
Whoo!

13
00:01:29,583 --> 00:01:30,792
Whoo!

14
00:01:32,458 --> 00:01:33,957
Whoo!

15
00:01:33,958 --> 00:01:35,416
Is dat
alles wat je kunt?
"""


@dataclass(frozen=True)
class SrtCue:
    index: str
    timestamp: str
    text: str


def parse_srt(raw: str) -> list[SrtCue]:
    cues: list[SrtCue] = []
    for block in raw.replace("\r\n", "\n").replace("\r", "\n").strip().split("\n\n"):
        lines = [line.rstrip() for line in block.split("\n") if line.strip()]
        if len(lines) < 3:
            continue
        cues.append(SrtCue(index=lines[0], timestamp=lines[1], text="\n".join(lines[2:])))
    return cues


def normalize_srt_output(raw: str) -> str:
    cleaned = raw.strip()
    fence_match = re.fullmatch(r"```(?:srt|text)?\s*(.*?)\s*```", cleaned, flags=re.IGNORECASE | re.DOTALL)
    if fence_match:
        cleaned = fence_match.group(1).strip()
    return cleaned.rstrip() + "\n" if cleaned else ""


def model_filename(model: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "__", model).strip("_")


def normalize_text(value: str) -> str:
    return " ".join(value.casefold().split())


def score_translation(candidate_srt: str, expected_srt: str) -> dict[str, Any]:
    candidate = parse_srt(candidate_srt)
    expected = parse_srt(expected_srt)
    expected_by_index = {cue.index: cue for cue in expected}

    matched = 0
    timestamp_matches = 0
    text_similarity_total = 0.0

    for cue in candidate:
        expected_cue = expected_by_index.get(cue.index)
        if expected_cue is None:
            continue
        matched += 1
        if cue.timestamp == expected_cue.timestamp:
            timestamp_matches += 1
        text_similarity_total += difflib.SequenceMatcher(
            None,
            normalize_text(cue.text),
            normalize_text(expected_cue.text),
        ).ratio()

    expected_count = len(expected)
    cue_coverage = matched / expected_count if expected_count else 0.0
    timestamp_accuracy = timestamp_matches / expected_count if expected_count else 0.0
    text_similarity = text_similarity_total / expected_count if expected_count else 0.0
    overall_accuracy = (0.2 * cue_coverage) + (0.3 * timestamp_accuracy) + (0.5 * text_similarity)

    return {
        "candidate_cues": len(candidate),
        "expected_cues": expected_count,
        "matched_cues": matched,
        "cue_coverage": round(cue_coverage, 4),
        "timestamp_accuracy": round(timestamp_accuracy, 4),
        "text_similarity": round(text_similarity, 4),
        "overall_accuracy": round(overall_accuracy, 4),
    }


def build_payload(model: str, source_srt: str, max_tokens: int = 1200) -> dict[str, Any]:
    return {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": (
                    "Translate English subtitles to Dutch. Preserve SRT numbering, timestamps, "
                    "blank lines, punctuation, and line breaks. Return only valid SRT text."
                ),
            },
            {
                "role": "user",
                "content": source_srt,
            },
        ],
        "provider": {
            "only": ["groq"],
        },
        "reasoning": {
            "effort": "none",
            "enabled": False,
        },
        "temperature": 0,
        "max_tokens": max_tokens,
    }


def extract_text(response_json: dict[str, Any]) -> str:
    choices = response_json.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    content = message.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = [part.get("text", "") for part in content if isinstance(part, dict)]
        return "".join(parts).strip()
    return ""


def benchmark_model(
    session: Any,
    api_key: str,
    model: str,
    source_srt: str,
    reference_srt: str,
    timeout: float,
    max_tokens: int,
) -> dict[str, Any]:
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/jneerdael/nexio",
        "X-Title": "Nexio translation benchmark",
    }
    payload = build_payload(model, source_srt, max_tokens=max_tokens)

    started = time.perf_counter()
    response = session.post(OPENROUTER_CHAT_URL, headers=headers, json=payload, timeout=timeout)
    elapsed_seconds = time.perf_counter() - started

    result: dict[str, Any] = {
        "model": model,
        "provider_only": payload["provider"]["only"],
        "elapsed_seconds": round(elapsed_seconds, 4),
        "chars_per_second": round(len(source_srt) / elapsed_seconds, 2) if elapsed_seconds else None,
        "http_status": response.status_code,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }

    try:
        response_json = response.json()
    except ValueError:
        response_json = {"raw_response": response.text}

    if response.status_code >= 400:
        result["ok"] = False
        result["error"] = response_json
        return result

    translation = extract_text(response_json)
    usage = response_json.get("usage") if isinstance(response_json, dict) else None
    result.update(
        {
            "ok": True,
            "translation": translation,
            "usage": usage,
            **score_translation(translation, reference_srt),
        }
    )
    return result


def read_text_or_default(path: str | None, default: str) -> str:
    if path is None:
        return default
    return Path(path).read_text(encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = [
        "model",
        "ok",
        "http_status",
        "elapsed_seconds",
        "chars_per_second",
        "overall_accuracy",
        "text_similarity",
        "timestamp_accuracy",
        "cue_coverage",
        "matched_cues",
        "candidate_cues",
        "expected_cues",
        "srt_path",
        "created_at",
    ]
    with path.open("w", encoding="utf-8", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field) for field in fields})


def write_successful_srt_files(output_dir: Path, rows: list[dict[str, Any]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    for row in rows:
        if not row.get("ok"):
            continue

        translation = normalize_srt_output(str(row.get("translation") or ""))
        if not parse_srt(translation):
            row["srt_error"] = "No valid SRT cues found in model response."
            continue

        run = row.get("run", 1)
        path = output_dir / f"{model_filename(str(row['model']))}__run-{run}.srt"
        path.write_text(translation, encoding="utf-8")
        row["srt_path"] = str(path)


def print_summary(rows: list[dict[str, Any]]) -> None:
    headers = ["model", "ok", "sec", "chars/s", "accuracy", "text", "timestamps", "srt"]
    print(" | ".join(headers))
    print(" | ".join("-" * len(header) for header in headers))
    for row in rows:
        print(
            " | ".join(
                [
                    str(row.get("model")),
                    str(row.get("ok")),
                    str(row.get("elapsed_seconds", "")),
                    str(row.get("chars_per_second", "")),
                    str(row.get("overall_accuracy", "")),
                    str(row.get("text_similarity", "")),
                    str(row.get("timestamp_accuracy", "")),
                    str(row.get("srt_path", "")),
                ]
            )
        )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-key", default=os.getenv("OPENROUTER_API_KEY"))
    parser.add_argument("--model", action="append", dest="models", help="Model slug. Repeat to test many.")
    parser.add_argument("--input-srt", help="Path to English source SRT. Defaults to embedded sample.")
    parser.add_argument("--reference-srt", help="Path to Dutch reference SRT. Defaults to embedded sample.")
    parser.add_argument("--runs", type=int, default=1, help="Runs per model.")
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--max-tokens", type=int, default=1200)
    parser.add_argument("--srt-out-dir", default=".tmp/openrouter_translation_benchmark/srt")
    parser.add_argument("--jsonl-out", default=".tmp/openrouter_translation_benchmark/results.jsonl")
    parser.add_argument("--csv-out", default=".tmp/openrouter_translation_benchmark/results.csv")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if requests is None:
        print("requests is required: python3 -m pip install requests", file=sys.stderr)
        return 2
    if not args.api_key:
        print("Set OPENROUTER_API_KEY or pass --api-key.", file=sys.stderr)
        return 2
    if args.runs < 1:
        print("--runs must be at least 1.", file=sys.stderr)
        return 2

    models = args.models or DEFAULT_MODELS
    source_srt = read_text_or_default(args.input_srt, DEFAULT_SOURCE_SRT)
    reference_srt = read_text_or_default(args.reference_srt, DEFAULT_REFERENCE_SRT)

    session = requests.Session()
    rows: list[dict[str, Any]] = []
    for model in models:
        for run_index in range(args.runs):
            row = benchmark_model(
                session=session,
                api_key=args.api_key,
                model=model,
                source_srt=source_srt,
                reference_srt=reference_srt,
                timeout=args.timeout,
                max_tokens=args.max_tokens,
            )
            row["run"] = run_index + 1
            rows.append(row)
            write_successful_srt_files(Path(args.srt_out_dir), [row])
            print_summary([row])

    write_jsonl(Path(args.jsonl_out), rows)
    write_csv(Path(args.csv_out), rows)
    print(f"\nWrote {args.jsonl_out}, {args.csv_out}, and successful SRTs under {args.srt_out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
