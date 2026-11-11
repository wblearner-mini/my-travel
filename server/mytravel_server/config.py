"""Server settings. Upstream credentials are read from a key=value file (for local runs, the
project's git-ignored local.properties, unchanged); nothing is hard-coded."""

import os
from dataclasses import dataclass, field
from pathlib import Path


def read_properties(path: str) -> dict:
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


@dataclass
class Limits:
    """Per-account limits. Days and months are Asia/Shanghai."""
    turns_per_day: int = 40
    model_calls_per_turn: int = 8
    tool_calls_per_response: int = 6
    chat_max_tokens: int = 1500
    hotel_max_tokens: int = 1000
    input_tokens_per_day: int = 800_000
    web_search_per_day: int = 20
    hotel_parse_per_day: int = 10
    weather_upstream_per_day: int = 300
    amap_tools_per_day: int = 200
    cost_per_month_yuan: float = 10.0
    # Shared by all accounts except the owners' (the owner keeps a reserve,
    # so others using up the shared amount can't block the owner, who has only their own monthly limit).
    global_cost_per_month_yuan: float = 20.0
    weather_cache_s: int = 30 * 60
    weather_fresh_min_s: int = 5 * 60
    session_days: int = 30
    max_image_bytes: int = 4 * 1024 * 1024


@dataclass
class Prices:
    """Yuan. Bailian qwen3.8-flash per 1M tokens (【官方文档】2026-11-06); DeepSeek search 5 per 1000 (2026-11-10)."""
    chat_in_per_m: float = 0.8
    chat_out_per_m: float = 2.7
    web_search_each: float = 0.005


@dataclass
class Config:
    dashscope_key: str
    dashscope_base: str
    dashscope_model: str
    deepseek_key: str
    qweather_host: str
    qweather_project_id: str
    qweather_credential_id: str
    qweather_developer_id: str
    qweather_private_key_pem: str
    db_path: str
    # Owner accounts (comma-separated MYTRAVEL_OWNERS): not counted in, nor stopped by, the shared monthly amount.
    owners: tuple = ()
    limits: Limits = field(default_factory=Limits)
    prices: Prices = field(default_factory=Prices)


def load_config() -> Config:
    here = Path(__file__).resolve().parent.parent
    props = read_properties(os.environ.get("MYTRAVEL_CONFIG", str(here.parent / "local.properties")))
    key_path = props.get("QWEATHER_PRIVATE_KEY_PATH", "")
    return Config(
        dashscope_key=props.get("DASHSCOPE_API_KEY", ""),
        dashscope_base=props.get("DASHSCOPE_BASE_URL", ""),
        dashscope_model=props.get("DASHSCOPE_MODEL", ""),
        deepseek_key=props.get("DEEPSEEK_API_KEY", ""),
        qweather_host=props.get("QWEATHER_API_HOST", ""),
        qweather_project_id=props.get("QWEATHER_PROJECT_ID", ""),
        qweather_credential_id=props.get("QWEATHER_CREDENTIAL_ID", ""),
        qweather_developer_id=props.get("QWEATHER_DEVELOPER_ID", ""),
        qweather_private_key_pem=Path(key_path).read_text() if key_path else "",
        db_path=os.environ.get("MYTRAVEL_DB", str(here / "data" / "mytravel.db")),
        owners=tuple(n.strip() for n in os.environ.get("MYTRAVEL_OWNERS", "").split(",") if n.strip()),
    )
