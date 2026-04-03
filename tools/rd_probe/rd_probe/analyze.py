from __future__ import annotations

from pathlib import Path
import json


def load_summary(run_dir: str | Path) -> dict:
    run_path = Path(run_dir)
    return json.loads((run_path / "summary.json").read_text())
