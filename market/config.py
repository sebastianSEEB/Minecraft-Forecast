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
    quick_bin_interval: int = 30       # re-check lowest BIN from the newest listings + just-sold BINs (0 = off)

    # Deal finder (runs on every full AH scan)
    deal_min_discount: float = 0.20    # BIN at least 20 % under fair value
    deal_min_value: float = 1_000_000  # fair value of the listing (all items in the stack) at least 1M
    deal_min_sales_72h: int = 3        # skip items that barely sell

    # Retention (days). Hourly bars are kept forever; they are what forecasts use.
    keep_raw_bazaar_days: float = 2    # change-only raw snapshots (0 = don't store raw)
    keep_sales_days: float = 90

    # Wallet tracking (your coins): Hypixel API key + player name, checked every wallet_interval seconds.
    # Put the key in hypixel_api_key.txt next to run.py (kept out of git), or set HYPIXEL_API_KEY.
    player_name: str = ""
    hypixel_api_key: str = ""
    wallet_interval: int = 3600
    publish_wallet: bool = True        # show the Wallet tab on the public dashboard
    track_positions: bool = True       # your AH listings + bids from every full AH scan -> potential FCF (market/fcf.py)
    bids_already_in_purse: bool = True # Hypixel takes bid coins when you bid, so they aren't subtracted from the wallet again

    # Local server the Minecraft mod talks to
    server_host: str = "127.0.0.1"
    server_port: int = 8765

    # Forecasting
    history_hours: int = 24 * 14       # how much hourly history the models see
    eval_hours: int = 24 * 3           # window used to score models (MFE/MAD/MAPE/TS)
    min_hours_for_forecast: int = 12
    range_level: float = 0.80          # two-sided range shown in-game (80 % by default)
    tracking_signal_limit: float = 4.0 # |TS| above this flags the model as biased
    season_lookback_hours: int = 24 * 42   # history used to detect daily / SkyBlock-year / weekly cycles

    # Dashboard (static website, see market/publish.py)
    publish_interval: int = 3600       # rebuild the dashboard every hour (0 = never)
    publish_repo: str = ""             # e.g. "https://github.com/sebastianSEEB/skyblock-market.git" -> GitHub Pages
    forecast_all: bool = True          # forecast every item with enough data (the set grows as data accumulates);
                                       # False = only the publish_top_* most active ones
    publish_top_bazaar: int = 600      # most active products that get forecasts (all items get history + seasonality)
    publish_top_auction: int = 600
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
    if not cfg.hypixel_api_key:
        import os
        keyfile = p.parent / "hypixel_api_key.txt"
        cfg.hypixel_api_key = os.environ.get("HYPIXEL_API_KEY", "") or (
            keyfile.read_text(encoding="utf-8").strip() if keyfile.exists() else "")
    return cfg
