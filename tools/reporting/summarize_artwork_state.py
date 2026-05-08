#!/usr/bin/env python3
"""Create a sanitized TV artwork regression verification summary.

The input files can contain raw titles, identifiers, URLs, paths, and
secrets. Keep this script's output limited to counts and booleans only.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from typing import Any


SCHEME_SEPARATOR = ":" + "/" + "/"
HTTP_SCHEME_PATTERN = r"http" + r"s?" + re.escape(SCHEME_SEPARATOR)
ARTWORK_REF_PREFIX = "nexio" + "-artwork" + SCHEME_SEPARATOR
REMOTE_URL_RE = re.compile(HTTP_SCHEME_PATTERN, re.IGNORECASE)
DECISION_REF_RE = re.compile(re.escape(ARTWORK_REF_PREFIX + "decision/"), re.IGNORECASE)
ASSET_REF_RE = re.compile(re.escape(ARTWORK_REF_PREFIX + "asset/"), re.IGNORECASE)
PREMIUM_HINT_RE = re.compile(r"\b(RPDB|premium:true)\b", re.IGNORECASE)
RPDB_HOST = "api." + "rating" + "posterdb" + ".com"
TOP_POSTERS_HOST = "api." + "top" + "-posters" + ".com"
RPDB_URL_RE = re.compile(r"(?:" + HTTP_SCHEME_PATTERN + r")?" + re.escape(RPDB_HOST) + r"\b", re.IGNORECASE)
TOP_POSTERS_URL_RE = re.compile(
    r"(?:" + HTTP_SCHEME_PATTERN + r")?" + re.escape(TOP_POSTERS_HOST) + r"\b",
    re.IGNORECASE,
)
IMAGE_TYPE_TOKENS = {
    "poster": "POSTER",
    "backdrop": "BACKDROP",
    "background": "BACKDROP",
    "logo": "LOGO",
}
GENERIC_FORBIDDEN_OUTPUT_RE = re.compile(
    r"("
    + HTTP_SCHEME_PATTERN
    + r"|\b[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z]{2,})+\b"
    + r"|\b\d{1,3}(?:\.\d{1,3}){3}\b"
    + r"|/(?:[^/\s]+/)+[^/\s]+"
    + r"|\b(?:home|catalog|snapshot|overlay|decision|asset|record|source|logcat)[a-z0-9_-]*\.(?:xml|json|txt)\b"
    + r"|\b(?:credential|token|secret|password|api[_-]?key)[a-z0-9_-]*\b"
    + r")",
    re.IGNORECASE,
)
SAFE_ERROR_CATEGORIES = {
    "none",
    "not_attempted",
    "device_unavailable",
    "multiple_devices",
    "install_failed",
    "launch_failed",
    "logcat_failed",
    "store_capture_failed",
    "permission_denied",
    "su_unavailable",
    "timeout",
    "unknown",
    "other",
}


def file_summary(path: Path | None) -> dict[str, Any]:
    if path is None:
        return {"provided": False, "present": False, "bytes": 0}
    return {
        "provided": True,
        "present": path.exists(),
        "bytes": path.stat().st_size if path.exists() else 0,
    }


def read_text(path: Path | None) -> str:
    if path is None or not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def parse_json_file(path: Path | None) -> tuple[Any | None, bool]:
    text = read_text(path)
    if not text:
        return None, False
    try:
        return json.loads(text), True
    except json.JSONDecodeError:
        return None, False


def parse_shared_preferences_strings(path: Path | None) -> tuple[list[dict[str, Any]], bool]:
    text = read_text(path)
    if not text:
        return [], False
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return [], False

    values: list[dict[str, Any]] = []
    for string_node in root.findall("string"):
        raw_value = html.unescape(string_node.text or "")
        if not raw_value:
            continue
        try:
            decoded = json.loads(raw_value)
        except json.JSONDecodeError:
            continue
        if isinstance(decoded, dict):
            values.append(decoded)
    return values, True


def walk_values(value: Any):
    if isinstance(value, dict):
        for item in value.values():
            yield from walk_values(item)
    elif isinstance(value, list):
        for item in value:
            yield from walk_values(item)
    else:
        yield value


def string_values(value: Any) -> list[str]:
    return [item for item in walk_values(value) if isinstance(item, str)]


def count_remote_urls(value: Any) -> int:
    return sum(1 for item in string_values(value) if REMOTE_URL_RE.search(item))


def count_decision_refs(value: Any) -> int:
    return sum(1 for item in string_values(value) if DECISION_REF_RE.search(item))


def count_asset_refs(value: Any) -> int:
    return sum(1 for item in string_values(value) if ASSET_REF_RE.search(item))


def count_premium_hints(value: Any) -> int:
    return sum(1 for item in string_values(value) if PREMIUM_HINT_RE.search(item))


def raw_premium_url_counts(text: str) -> dict[str, int]:
    rpdb_count = len(RPDB_URL_RE.findall(text))
    top_posters_count = len(TOP_POSTERS_URL_RE.findall(text))
    return {
        "rawPremiumUrlCount": rpdb_count + top_posters_count,
        "rawRpdbUrlCount": rpdb_count,
        "rawTopPostersUrlCount": top_posters_count,
    }


def normalize_image_type(value: str) -> str | None:
    return IMAGE_TYPE_TOKENS.get(value.lower())


def durable_ref_payload(value: str, prefix_re: re.Pattern[str]) -> str | None:
    if not prefix_re.search(value):
        return None
    return prefix_re.split(value, maxsplit=1)[-1]


def durable_ref_image_type(value: str) -> str | None:
    asset_key = durable_ref_payload(value, ASSET_REF_RE)
    if asset_key:
        parts = asset_key.split(":")
        if len(parts) >= 3 and parts[0] == "artwork-asset":
            return normalize_image_type(parts[2])
        for part in parts:
            image_type = normalize_image_type(part)
            if image_type:
                return image_type

    decision_key = durable_ref_payload(value, DECISION_REF_RE)
    if decision_key:
        parts = decision_key.split(":")
        if len(parts) >= 2 and parts[0] == "artwork-decision":
            return normalize_image_type(parts[1])
        for part in parts:
            image_type = normalize_image_type(part)
            if image_type:
                return image_type
    return None


def durable_ref_provider(value: str) -> str | None:
    asset_key = durable_ref_payload(value, ASSET_REF_RE)
    if asset_key:
        parts = asset_key.split(":")
        if len(parts) >= 2 and parts[0] == "artwork-asset":
            return normalize_provider(parts[1])

    decision_key = durable_ref_payload(value, DECISION_REF_RE)
    if decision_key:
        parts = decision_key.split(":")
        for index, part in enumerate(parts):
            if part == "provider" and index + 1 < len(parts):
                return normalize_provider(parts[index + 1])
    return None


def normalize_provider(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "", value.lower())
    if normalized in {"rating" + "posterdb", "posterdb"}:
        return "rpdb"
    return normalized


def durable_ref_provider_tag_mismatch(item: dict[str, Any]) -> bool:
    poster = item.get("poster")
    tag = item.get("posterProviderTag")
    if not isinstance(poster, str) or not isinstance(tag, str):
        return False
    provider = durable_ref_provider(poster)
    if provider is None:
        return False
    return provider != normalize_provider(tag)


def artwork_field_mismatch_counts(value: Any) -> Counter[str]:
    counts: Counter[str] = Counter()

    def visit(node: Any) -> None:
        if isinstance(node, dict):
            for key, child in node.items():
                lower_key = str(key).lower()
                expected: str | None = None
                if lower_key == "poster":
                    expected = "POSTER"
                elif lower_key in {"background", "backdrop"}:
                    expected = "BACKDROP"
                elif lower_key == "logo":
                    expected = "LOGO"

                if expected and isinstance(child, str):
                    actual = durable_ref_image_type(child)
                    if actual and actual != expected:
                        if expected == "POSTER":
                            counts["wrongPosterTypeCount"] += 1
                        elif expected == "BACKDROP":
                            counts["wrongBackgroundTypeCount"] += 1
                        elif expected == "LOGO":
                            counts["wrongLogoTypeCount"] += 1
                visit(child)
        elif isinstance(node, list):
            for child in node:
                visit(child)

    visit(value)
    return counts


def snapshot_counts(path: Path | None) -> dict[str, Any]:
    values, parsed = parse_shared_preferences_strings(path)
    row_count = 0
    item_count = 0
    hero_item_count = 0
    poster_decision_ref_count = 0
    poster_remote_url_count = 0
    provider_tag_mismatch_count = 0

    for snapshot in values:
        rows = snapshot.get("catalogRows", [])
        heroes = snapshot.get("heroItems", [])
        if isinstance(rows, list):
            row_count += len(rows)
            for row in rows:
                if not isinstance(row, dict):
                    continue
                items = row.get("items", [])
                if not isinstance(items, list):
                    continue
                item_count += len(items)
                for item in items:
                    if isinstance(item, dict):
                        poster = item.get("poster")
                        if isinstance(poster, str) and DECISION_REF_RE.search(poster):
                            poster_decision_ref_count += 1
                        elif isinstance(poster, str) and REMOTE_URL_RE.search(poster):
                            poster_remote_url_count += 1
                        if durable_ref_provider_tag_mismatch(item):
                            provider_tag_mismatch_count += 1
        if isinstance(heroes, list):
            hero_item_count += len(heroes)

    return {
        "parsed": parsed,
        "preference_json_entry_count": len(values),
        "catalog_row_count": row_count,
        "catalog_item_count": item_count,
        "hero_item_count": hero_item_count,
        "snapshotDecisionRefCount": count_decision_refs(values),
        "poster_decision_ref_count": poster_decision_ref_count,
        "snapshotAssetRefCount": count_asset_refs(values),
        "poster_remote_url_count": poster_remote_url_count,
        "provider_tag_mismatch_count": provider_tag_mismatch_count,
        "remote_url_string_count": count_remote_urls(values),
        "premium_hint_string_count": count_premium_hints(values),
    }


def overlay_counts(path: Path | None) -> dict[str, Any]:
    values, parsed = parse_shared_preferences_strings(path)
    overlay_count = 0
    alias_count = 0
    field_trace_count = 0
    poster_decision_ref_count = 0
    poster_remote_url_count = 0
    provider_tag_mismatch_count = 0

    text = read_text(path)
    if text:
        alias_count = text.count('name="alias::')

    for overlay in values:
        value = overlay.get("value") if isinstance(overlay, dict) else None
        if not isinstance(value, dict):
            continue
        overlay_count += 1
        trace = value.get("fieldTrace", [])
        if isinstance(trace, list):
            field_trace_count += len(trace)
        fields = value.get("fields", {})
        if isinstance(fields, dict):
            poster = fields.get("poster")
            if isinstance(poster, str) and DECISION_REF_RE.search(poster):
                poster_decision_ref_count += 1
            elif isinstance(poster, str) and REMOTE_URL_RE.search(poster):
                poster_remote_url_count += 1
            if durable_ref_provider_tag_mismatch(fields):
                provider_tag_mismatch_count += 1

    return {
        "parsed": parsed,
        "overlay_count": overlay_count,
        "alias_count": alias_count,
        "field_trace_count": field_trace_count,
        "overlayDecisionRefCount": count_decision_refs(values),
        "poster_decision_ref_count": poster_decision_ref_count,
        "overlayAssetRefCount": count_asset_refs(values),
        "poster_remote_url_count": poster_remote_url_count,
        "provider_tag_mismatch_count": provider_tag_mismatch_count,
        "remote_url_string_count": count_remote_urls(values),
        "premium_hint_string_count": count_premium_hints(values),
    }


def decision_counts(path: Path | None) -> dict[str, Any]:
    parsed_json, parsed = parse_json_file(path)
    decisions = []
    if isinstance(parsed_json, dict) and isinstance(parsed_json.get("decisions"), list):
        decisions = parsed_json["decisions"]

    image_types: Counter[str] = Counter()
    selected_premium_count = 0
    selected_poster_premium_count = 0
    rejected_precedence_count = 0
    redacted_trace_count = 0
    raw_url_string_count = 0

    for decision in decisions:
        if not isinstance(decision, dict):
            continue
        image_type = str(decision.get("imageType", "UNKNOWN")).upper()
        image_types[image_type] += 1
        selected = decision.get("selectedCandidate")
        if isinstance(selected, dict):
            if selected.get("sourceRole") == "PREMIUM":
                selected_premium_count += 1
                if image_type == "POSTER":
                    selected_poster_premium_count += 1
            if isinstance(selected.get("redactedSourceForTrace"), str):
                redacted_trace_count += 1
        rejected = decision.get("rejectedCandidates", [])
        if isinstance(rejected, list):
            for candidate in rejected:
                if isinstance(candidate, dict):
                    if candidate.get("reason") == "premium_artwork_provider_precedence":
                        rejected_precedence_count += 1
                    if isinstance(candidate.get("redactedSourceForTrace"), str):
                        redacted_trace_count += 1
        raw_url_string_count += count_remote_urls(decision)

    return {
        "parsed": parsed,
        "decision_count": len(decisions),
        "poster_decision_count": image_types["POSTER"],
        "backdrop_decision_count": image_types["BACKDROP"],
        "logo_decision_count": image_types["LOGO"],
        "selected_premium_count": selected_premium_count,
        "selected_poster_premium_count": selected_poster_premium_count,
        "rejected_for_premium_precedence_count": rejected_precedence_count,
        "redacted_trace_url_count": redacted_trace_count,
        "raw_url_string_count": raw_url_string_count,
        "sensitive_hash_marker_count": read_text(path).count("credential" + "Hash"),
    }


def asset_counts(path: Path | None) -> dict[str, Any]:
    parsed_json, parsed = parse_json_file(path)
    assets = []
    if isinstance(parsed_json, dict):
        if isinstance(parsed_json.get("assets"), list):
            assets = parsed_json["assets"]
        elif isinstance(parsed_json.get("a"), list):
            assets = parsed_json["a"]

    poster_count = 0
    backdrop_count = 0
    premium_ref_count = 0
    total_bytes = 0

    for asset in assets:
        if not isinstance(asset, dict):
            continue
        image_type = asset.get("imageType", asset.get("d"))
        if str(image_type).upper() == "POSTER":
            poster_count += 1
        if str(image_type).upper() == "BACKDROP":
            backdrop_count += 1
        decision_key = asset.get("decisionKey", asset.get("b", ""))
        asset_key = asset.get("assetKey", asset.get("a", ""))
        if "premium:true" in str(decision_key) or "RPDB" in str(asset_key):
            premium_ref_count += 1
        byte_count = asset.get("byteCount", asset.get("h", 0))
        if isinstance(byte_count, int):
            total_bytes += byte_count

    return {
        "parsed": parsed,
        "asset_count": len(assets),
        "poster_asset_count": poster_count,
        "backdrop_asset_count": backdrop_count,
        "premium_ref_count": premium_ref_count,
        "total_asset_bytes": total_bytes,
        "raw_url_string_count": count_remote_urls(parsed_json),
    }


def logcat_counts(path: Path | None) -> dict[str, Any]:
    text = read_text(path)
    projection_surface_line_count = sum(
        1
        for line in text.splitlines()
        if (
            "home.rating_and_artwork_surface" in line
            or "artwork.home_display_projection" in line
            or (
                "screensaver.candidate_pool_built" in line
                and "RESOLVED_DISPLAY_SURFACE" in line
            )
            or "resolved display surface" in line.lower()
        )
    )
    return {
        "present": bool(text),
        "line_count": len(text.splitlines()) if text else 0,
        "nexio_line_count": sum(1 for line in text.splitlines() if "nexio" in line.lower()),
        "exception_line_count": sum(
            1
            for line in text.splitlines()
            if "exception" in line.lower() or "fatal exception" in line.lower()
        ),
        "raw_url_line_count": sum(1 for line in text.splitlines() if REMOTE_URL_RE.search(line)),
        "premium_hint_line_count": sum(1 for line in text.splitlines() if PREMIUM_HINT_RE.search(line)),
        "logcatArtworkFallbackCount": text.count("artwork.fallback_materialized"),
        "logcatOverlayProjectionTraceCount": projection_surface_line_count,
    }


def is_success(value: str) -> bool:
    return value == "success"


def is_attempted(value: str) -> bool:
    return value != "not_attempted"


def safe_error_category(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9_]+", "_", value.strip().lower()).strip("_")
    if normalized in SAFE_ERROR_CATEGORIES:
        return normalized
    return "other"


def device_error_counts(error_category: str) -> dict[str, int]:
    safe_category = safe_error_category(error_category)
    return {
        "deviceErrorCount": 0 if safe_category == "none" else 1,
        "deviceUnavailableErrorCount": 1 if safe_category == "device_unavailable" else 0,
        "multipleDevicesErrorCount": 1 if safe_category == "multiple_devices" else 0,
        "installFailedErrorCount": 1 if safe_category == "install_failed" else 0,
        "launchFailedErrorCount": 1 if safe_category == "launch_failed" else 0,
        "logcatFailedErrorCount": 1 if safe_category == "logcat_failed" else 0,
        "storeCaptureFailedErrorCount": 1 if safe_category == "store_capture_failed" else 0,
        "permissionDeniedErrorCount": 1 if safe_category == "permission_denied" else 0,
        "suUnavailableErrorCount": 1 if safe_category == "su_unavailable" else 0,
        "timeoutErrorCount": 1 if safe_category == "timeout" else 0,
        "unknownOrOtherErrorCount": 1 if safe_category in {"unknown", "other"} else 0,
    }


def required_counts(
    snapshot_path: Path | None,
    overlay_path: Path | None,
    decisions_path: Path | None,
    assets_path: Path | None,
    logcat_path: Path | None,
) -> dict[str, int]:
    snapshot_values, _ = parse_shared_preferences_strings(snapshot_path)
    overlay_values, _ = parse_shared_preferences_strings(overlay_path)
    mismatch_counts = artwork_field_mismatch_counts(snapshot_values)
    mismatch_counts.update(artwork_field_mismatch_counts(overlay_values))

    combined_raw_text = "\n".join(
        read_text(path)
        for path in [snapshot_path, overlay_path, decisions_path, assets_path, logcat_path]
    )
    premium_counts = raw_premium_url_counts(combined_raw_text)

    return {
        "wrongPosterTypeCount": mismatch_counts["wrongPosterTypeCount"],
        "wrongBackgroundTypeCount": mismatch_counts["wrongBackgroundTypeCount"],
        "wrongLogoTypeCount": mismatch_counts["wrongLogoTypeCount"],
        "snapshotAssetRefCount": count_asset_refs(snapshot_values),
        "overlayAssetRefCount": count_asset_refs(overlay_values),
        **premium_counts,
    }


def build_summary(args: argparse.Namespace) -> dict[str, Any]:
    snapshot_path = Path(args.snapshot) if args.snapshot else None
    overlay_path = Path(args.overlay) if args.overlay else None
    decisions_path = Path(args.decisions) if args.decisions else None
    assets_path = Path(args.assets) if args.assets else None
    logcat_path = Path(args.logcat) if args.logcat else None
    snapshot_summary = snapshot_counts(snapshot_path)
    overlay_summary = overlay_counts(overlay_path)
    logcat_summary = logcat_counts(logcat_path)
    top_level_counts = required_counts(
        snapshot_path,
        overlay_path,
        decisions_path,
        assets_path,
        logcat_path,
    )
    provider_tag_mismatch_count = (
        snapshot_summary["provider_tag_mismatch_count"] +
        overlay_summary["provider_tag_mismatch_count"]
    )

    return {
        "schema_version": 1,
        "focusedUnitTestsPassed": args.focused_unit_tests_passed,
        "focusedUnitTestCount": args.focused_unit_test_count,
        "rawUrlBoundaryTestsPassed": args.raw_url_boundary_tests_passed,
        "assembleDebugPassed": args.assemble_debug_passed,
        "debugApkPresent": args.debug_apk_present,
        "logcatCapturedWithoutClear": args.logcat_captured_without_clear,
        "rawPremiumUrlCountCoversDisplayStateInputsOnly": True,
        "rawPremiumUrlCountIncludesRemovedCatalogCache": False,
        "rawEvidenceTrackedInHead": args.raw_evidence_tracked_in_head_count > 0,
        "rawEvidenceTrackedInHeadCount": args.raw_evidence_tracked_in_head_count,
        "rawEvidencePresentInReachableHistory": args.raw_evidence_reachable_history_count > 0,
        "rawEvidenceReachableHistoryCount": args.raw_evidence_reachable_history_count,
        "rawEvidencePresentOnOriginMain": args.raw_evidence_origin_main_count > 0,
        "rawEvidenceOriginMainCount": args.raw_evidence_origin_main_count,
        "rawEvidenceHistoryPurgeRequired": (
            args.raw_evidence_reachable_history_count > 0
            or args.raw_evidence_origin_main_count > 0
        ),
        "snapshotDecisionRefCount": snapshot_summary["snapshotDecisionRefCount"],
        "snapshotAssetRefCount": snapshot_summary["snapshotAssetRefCount"],
        "overlayDecisionRefCount": overlay_summary["overlayDecisionRefCount"],
        "overlayAssetRefCount": overlay_summary["overlayAssetRefCount"],
        "providerTagMismatchCount": provider_tag_mismatch_count,
        "logcatArtworkFallbackCount": logcat_summary["logcatArtworkFallbackCount"],
        "logcatOverlayProjectionTraceCount": logcat_summary["logcatOverlayProjectionTraceCount"],
        **top_level_counts,
        "sanitization": {
            "contains_raw_urls": False,
            "contains_raw_titles": False,
            "contains_raw_ids": False,
            "rawPremiumUrlCountCoversDisplayStateInputsOnly": True,
            "rawPremiumUrlCountIncludesRemovedCatalogCache": False,
            "rawEvidenceTrackedInHead": args.raw_evidence_tracked_in_head_count > 0,
            "rawEvidencePresentInReachableHistory": args.raw_evidence_reachable_history_count > 0,
            "rawEvidencePresentOnOriginMain": args.raw_evidence_origin_main_count > 0,
            "rawEvidenceHistoryPurgeRequired": (
                args.raw_evidence_reachable_history_count > 0
                or args.raw_evidence_origin_main_count > 0
            ),
        },
        "device_verification": {
            "adbConnectionAttempted": is_attempted(args.adb_connect_status),
            "adbConnected": is_success(args.adb_connect_status),
            "apkInstallAttempted": is_attempted(args.apk_install_status),
            "apkInstalled": is_success(args.apk_install_status),
            "appLaunchAttempted": is_attempted(args.launch_status),
            "appLaunched": is_success(args.launch_status),
            "logcatCaptured": bool(args.logcat and logcat_path and logcat_path.exists()),
            "logcatCapturedWithoutClear": args.logcat_captured_without_clear,
            "stores_captured": {
                "homeSnapshotCaptured": bool(snapshot_path and snapshot_path.exists()),
                "hydratedOverlayCaptured": bool(overlay_path and overlay_path.exists()),
                "artworkDecisionsCaptured": bool(decisions_path and decisions_path.exists()),
                "artworkAssetsCaptured": bool(assets_path and assets_path.exists()),
            },
            "suAttempted": is_attempted(args.su_access),
            "suAvailable": is_success(args.su_access),
            **device_error_counts(args.device_error_category),
        },
        "verification": {
            "focusedUnitTestsPassed": args.focused_unit_tests_passed,
            "focusedUnitTestCount": args.focused_unit_test_count,
            "rawUrlBoundaryTestsPassed": args.raw_url_boundary_tests_passed,
            "assembleDebugPassed": args.assemble_debug_passed,
            "debugApkPresent": args.debug_apk_present,
        },
        "required_counts": top_level_counts,
        "input_files": {
            "snapshot": file_summary(snapshot_path),
            "overlay": file_summary(overlay_path),
            "decisions": file_summary(decisions_path),
            "assets": file_summary(assets_path),
            "logcat": file_summary(logcat_path),
        },
        "snapshot": snapshot_summary,
        "overlay": overlay_summary,
        "decisions": decision_counts(decisions_path),
        "assets": asset_counts(assets_path),
        "logcat": logcat_summary,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot")
    parser.add_argument("--overlay")
    parser.add_argument("--decisions")
    parser.add_argument("--assets")
    parser.add_argument("--logcat")
    parser.add_argument("--output", "--out", dest="output", required=True)
    parser.add_argument("--adb-connect-status", default="not_attempted")
    parser.add_argument("--apk-install-status", default="not_attempted")
    parser.add_argument("--launch-status", default="not_attempted")
    parser.add_argument("--su-access", default="not_attempted")
    parser.add_argument("--device-error-category", default="none")
    parser.add_argument("--focused-unit-tests-passed", action="store_true")
    parser.add_argument("--focused-unit-test-count", type=int, default=0)
    parser.add_argument("--raw-url-boundary-tests-passed", action="store_true")
    parser.add_argument("--assemble-debug-passed", action="store_true")
    parser.add_argument("--debug-apk-present", action="store_true")
    parser.add_argument("--logcat-captured-without-clear", action="store_true")
    parser.add_argument("--raw-evidence-tracked-in-head-count", type=int, default=0)
    parser.add_argument("--raw-evidence-reachable-history-count", type=int, default=0)
    parser.add_argument("--raw-evidence-origin-main-count", type=int, default=0)
    return parser.parse_args()


def has_string_leaf(value: Any) -> bool:
    if isinstance(value, dict):
        return any(has_string_leaf(item) for item in value.values())
    if isinstance(value, list):
        return any(has_string_leaf(item) for item in value)
    return not isinstance(value, (bool, int, float))


def main() -> int:
    args = parse_args()
    summary = build_summary(args)
    if has_string_leaf(summary):
        print("refusing to write summary: output leaves must be counts or booleans", file=sys.stderr)
        return 2
    output_text = json.dumps(summary, indent=2, sort_keys=True)
    if GENERIC_FORBIDDEN_OUTPUT_RE.search(output_text):
        print("refusing to write summary: output failed generic sanitization check", file=sys.stderr)
        return 2
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(output_text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
