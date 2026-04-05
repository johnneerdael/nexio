from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os


@dataclass(frozen=True)
class ProbeConfig:
    realdebrid_api_token: str
    premiumize_api_key: str
    output_dir: Path
    provider: str = "realdebrid"
    parallel: int = 4
    chunk_mb: int = 16
    duration: int = 120


def _parse_env_file(env_file: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not env_file.exists():
        return values
    for raw_line in env_file.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def load_config(env_file: str | Path | None = None) -> ProbeConfig:
    env_path = Path(env_file) if env_file is not None else Path(".env")
    values = dict(os.environ)
    values.update(_parse_env_file(env_path))
    token = values.get("REALDEBRID_API_TOKEN", "").strip()
    premiumize_api_key = values.get("PREMIUMIZE_API_KEY", "").strip()
    provider = values.get("RD_PROBE_PROVIDER", "realdebrid").strip().lower() or "realdebrid"
    if not token:
        token = ""
    output_dir = Path(values.get("RD_PROBE_OUTPUT_DIR", "./runs")).expanduser()
    parallel = int(values.get("RD_PROBE_PARALLEL", "4"))
    chunk_mb = int(values.get("RD_PROBE_CHUNK_MB", "16"))
    duration = int(values.get("RD_PROBE_DURATION", "120"))
    return ProbeConfig(
        realdebrid_api_token=token,
        premiumize_api_key=premiumize_api_key,
        output_dir=output_dir,
        provider=provider,
        parallel=parallel,
        chunk_mb=chunk_mb,
        duration=duration,
    )
