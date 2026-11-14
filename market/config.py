"""Settings. Defaults work out of the box; override any of them in config.json."""
from __future__ import annotations

import json
from dataclasses import dataclass, fields
from pathlib import Path


@dataclass
class Config:
    # Where data lives
    db_path: str = "data/market.sqlite3"

    # Hypixel public API (no key needed for these endpoints)
    api_base: str = "https://api.hypixel.net/v2"
    user_agent: str = "skyblock-market-collector/1.0 (personal price tracker)"

    # Polling intervals (seconds)
    bazaar_interval: int = 60          # full bazaar snapshot
    ended_interval: int = 60           # auctions_ended covers the last ~60 s, so keep this <= 60
    active_ah_interval: int = 300      # full active-auction scan for lowest BIN (0 = off)

    # Retention (days). Hourly bars are kept forever; they are what forecasts use.
    keep_raw_bazaar_days: float = 2    # change-only raw snapshots (0 = don't store raw)
    keep_sales_days: float = 90

    # Local server the Minecraft mod talks to
    server_host: str = "127.0.0.1"
    server_port: int = 8765

    # Forecasting
    history_hours: int = 24 * 14       # how much hourly history the models see
    eval_hours: int = 24 * 3           # window used to score models (MFE/MAD/MAPE/TS)
    min_hours_for_forecast: int = 12
    range_level: float = 0.80          # two-sided range shown in-game (80 % by default)
    tracking_signal_limit: float = 4.0 # |TS| above this flags the model as biased

    # Dashboard (static website, see market/publish.py)
    publish_interval: int = 3600       # rebuild the dashboard every hour (0 = never)
    publish_repo: str = ""             # e.g. "https://github.com/sebastianSEEB/skyblock-market.git" -> GitHub Pages
    publish_top_bazaar: int = 250      # products that get full charts + forecasts on the dashboard
    publish_top_auction: int = 250
    publish_git_name: str = "skyblock-market collector"
    publish_git_email: str = "collector@localhost"
    publish_label: str = "home PC collector"


def load_config(path: str | Path = "config.json") -> Config:
    """Reads config.json; if it doesn't exist, falls back to config.example.json next to it."""
    cfg = Config()
    p = Path(path)
    if not p.exists() and (p.parent / "config.example.json").exists():
        p = p.parent / "config.example.json"
    if p.exists():
        data = json.loads(p.read_text(encoding="utf-8"))
        known = {f.name for f in fields(Config)}
        for k, v in data.items():
            if k in known:
                setattr(cfg, k, v)
    return cfg
