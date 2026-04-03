from __future__ import annotations

from pathlib import Path
import subprocess


def build_tcpdump_command(output: str | Path, host: str | None = None) -> list[str]:
    cmd = ["tcpdump", "-w", str(output)]
    if host:
        cmd.extend(["host", host])
    return cmd


class PcapController:
    def __init__(self, output: Path, host: str | None = None) -> None:
        self.output = output
        self.host = host
        self.process: subprocess.Popen | None = None

    def start(self) -> None:
        self.process = subprocess.Popen(
            build_tcpdump_command(self.output, self.host),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def stop(self) -> dict[str, str | bool]:
        if self.process is None:
            return {"started": False, "path": str(self.output), "exists": self.output.exists()}
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
        return {"started": True, "path": str(self.output), "exists": self.output.exists()}
