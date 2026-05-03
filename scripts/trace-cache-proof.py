#!/usr/bin/env python3
import json
import sys
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


HEADER = [
    "runtimeOperationId",
    "provider",
    "apiShapeId",
    "operationKey",
    "cacheDecision",
    "networkSuppressed",
    "httpRequestCount",
    "cacheKey",
]


@dataclass
class CacheDecision:
    decision: str
    network_suppressed: Optional[bool]


@dataclass
class OperationProof:
    runtimeOperationId: str
    provider: str = ""
    apiShapeId: str = ""
    operationKey: str = ""
    cacheKey: str = ""
    decisions: List[CacheDecision] = field(default_factory=list)
    httpRequestCount: int = 0

    @property
    def cacheDecision(self) -> str:
        return self.decisions[0].decision if self.decisions else ""

    @property
    def networkSuppressed(self) -> str:
        value = self.decisions[0].network_suppressed if self.decisions else None
        if value is None:
            return ""
        return str(value).lower()


def usage() -> int:
    print("usage: trace-cache-proof.py trace-events.jsonl", file=sys.stderr)
    return 2


def payload_for(event: Dict[str, Any]) -> Dict[str, Any]:
    payload = event.get("payload")
    if isinstance(payload, dict):
        return payload
    return event


def text(value: Any) -> str:
    return "" if value is None else str(value)


def bool_or_none(value: Any) -> Optional[bool]:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.lower()
        if lowered == "true":
            return True
        if lowered == "false":
            return False
    return None


def proof_for(proofs: Dict[str, OperationProof], runtimeOperationId: str) -> OperationProof:
    return proofs.setdefault(runtimeOperationId, OperationProof(runtimeOperationId=runtimeOperationId))


def apply_identity(proof: OperationProof, payload: Dict[str, Any]) -> None:
    for field_name in ("provider", "apiShapeId", "operationKey", "cacheKey"):
        value = payload.get(field_name)
        if value is not None and not getattr(proof, field_name):
            setattr(proof, field_name, text(value))


def read_proofs(path: str) -> Dict[str, OperationProof]:
    proofs: Dict[str, OperationProof] = {}
    with open(path, "r", encoding="utf-8") as trace_file:
        for line_number, line in enumerate(trace_file, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                event = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc

            event_type = event.get("eventType")
            if event_type not in {"runtime.operation_start", "runtime.cache_decision", "http.request"}:
                continue

            payload = payload_for(event)
            runtimeOperationId = payload.get("runtimeOperationId")
            if not runtimeOperationId:
                continue

            proof = proof_for(proofs, text(runtimeOperationId))
            apply_identity(proof, payload)

            if event_type == "runtime.cache_decision":
                decision = payload.get("decision") or payload.get("cacheDecision")
                if decision:
                    proof.decisions.append(
                        CacheDecision(
                            decision=text(decision),
                            network_suppressed=bool_or_none(payload.get("networkSuppressed")),
                        )
                    )
            elif event_type == "http.request":
                proof.httpRequestCount += 1

    return proofs


def print_table(proofs: Dict[str, OperationProof]) -> None:
    print("\t".join(HEADER))
    for proof in proofs.values():
        print(
            "\t".join(
                [
                    proof.runtimeOperationId,
                    proof.provider,
                    proof.apiShapeId,
                    proof.operationKey,
                    proof.cacheDecision,
                    proof.networkSuppressed,
                    str(proof.httpRequestCount),
                    proof.cacheKey,
                ]
            )
        )


def print_miss_then_network_summary(proofs: Dict[str, OperationProof]) -> None:
    misses = [
        proof
        for proof in proofs.values()
        if any(decision.decision == "MISS_THEN_NETWORK" for decision in proof.decisions)
    ]
    if not misses:
        return

    print()
    print("MISS_THEN_NETWORK")
    print("runtimeOperationId\tprovider\tapiShapeId\toperationKey\thttpRequestCount\tcacheKey")
    for proof in misses:
        print(
            "\t".join(
                [
                    proof.runtimeOperationId,
                    proof.provider,
                    proof.apiShapeId,
                    proof.operationKey,
                    str(proof.httpRequestCount),
                    proof.cacheKey,
                ]
            )
        )


def violations(proofs: Dict[str, OperationProof]) -> List[str]:
    failures: List[str] = []
    for proof in proofs.values():
        for decision in proof.decisions:
            if (
                decision.decision in {"HIT", "STALE_HIT"}
                and decision.network_suppressed is True
                and proof.httpRequestCount > 0
            ):
                failures.append(
                    f"{proof.runtimeOperationId}: {decision.decision} suppressed network "
                    f"but observed {proof.httpRequestCount} http.request event(s)"
                )
    return failures


def main(argv: List[str]) -> int:
    if len(argv) != 2:
        return usage()

    try:
        proofs = read_proofs(argv[1])
    except OSError as exc:
        print(f"trace-cache-proof.py: {exc}", file=sys.stderr)
        return 2
    except ValueError as exc:
        print(f"trace-cache-proof.py: {exc}", file=sys.stderr)
        return 2

    print_table(proofs)
    print_miss_then_network_summary(proofs)

    failures = violations(proofs)
    if failures:
        print("Cache/network proof violations:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
