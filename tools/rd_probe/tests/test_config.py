from pathlib import Path

from rd_probe.config import load_config


def test_loads_realdebrid_token_from_env(tmp_path: Path):
    env = tmp_path / ".env"
    env.write_text("REALDEBRID_API_TOKEN=abc\n")

    config = load_config(env_file=env)

    assert config.realdebrid_api_token == "abc"
