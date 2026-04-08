#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from collections import Counter
from pathlib import Path


EXPECTED_TRACKED_METHODS = {
    "addHistory",
    "addToWatchlist",
    "addUserListItems",
    "checkin",
    "createUserList",
    "deletePlayback",
    "deleteUserList",
    "hideRecommendation",
    "removeFromWatchlist",
    "removeHistory",
    "removeUserListItems",
    "reorderUserLists",
    "scrobblePause",
    "scrobbleStart",
    "scrobbleStop",
    "updateUserList",
}

EXPECTED_CALL_SITE_COUNTS = {
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:addToWatchlist": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:addUserListItems": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:createUserList": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:deleteUserList": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:removeFromWatchlist": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:removeUserListItems": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:reorderUserLists": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktLibraryMutationExecutor.kt:updateUserList": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt:addHistory": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt:addHistory": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt:deletePlayback": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt:deletePlayback": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressMutationExecutor.kt:removeHistory": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktProgressHistoryMutationAdapter.kt:removeHistory": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:checkin": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:scrobblePause": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:scrobbleStart": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktScrobbleMutationAdapter.kt:scrobbleStop": 1,
    "app/src/main/java/com/nexio/tv/data/repository/trakt/TraktDiscoveryMutationAdapter.kt:hideRecommendation": 1,
}

CALL_PATTERN = re.compile(r"traktApi\.(\w+)\(")


def resolve_repo_root() -> Path:
    for candidate in (Path.cwd(), Path.cwd().parent):
        if (candidate / "app/src/main/java/com/nexio/tv/data/repository").is_dir():
            return candidate.resolve()
    raise SystemExit("Unable to resolve repository root for Trakt mutation routing audit")


def main() -> int:
    repo_root = resolve_repo_root()
    repository_dir = repo_root / "app/src/main/java/com/nexio/tv/data/repository"

    call_sites: list[str] = []
    methods_seen: set[str] = set()
    for file in sorted(repository_dir.rglob("*.kt")):
        rel_path = file.relative_to(repo_root).as_posix()
        for line in file.read_text().splitlines():
            match = CALL_PATTERN.search(line)
            if not match:
                continue
            method = match.group(1)
            if method not in EXPECTED_TRACKED_METHODS:
                continue
            methods_seen.add(method)
            call_sites.append(f"{rel_path}:{method}")

    actual_counts = Counter(call_sites)
    ok = True

    if methods_seen != EXPECTED_TRACKED_METHODS:
        ok = False
        print("Tracked Trakt mutation methods drifted.")
        print(f"Expected methods: {sorted(EXPECTED_TRACKED_METHODS)}")
        print(f"Actual methods:   {sorted(methods_seen)}")
        print()

    expected_counts = Counter(EXPECTED_CALL_SITE_COUNTS)
    if actual_counts != expected_counts:
        ok = False
        print("Unexpected direct Trakt mutation call sites detected.")
        print("Expected counts:")
        for key in sorted(expected_counts):
            print(f"  {key} -> {expected_counts[key]}")
        print("Actual counts:")
        for key in sorted(actual_counts):
            print(f"  {key} -> {actual_counts[key]}")
        print()

    if ok:
        print("PASS: Trakt mutation routing audit matched expected direct-write ownership.")
        print(f"Tracked methods: {', '.join(sorted(methods_seen))}")
        return 0

    print("FAIL: Route new writes through the shared Trakt outbox/adapter layer,")
    print("or update this audit only when ownership intentionally changes.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
