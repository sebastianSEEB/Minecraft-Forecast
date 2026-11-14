"""Builds what the HUD shows for one item: 24h average / high / low, lowest BIN, forecast."""
from __future__ import annotations

import statistics
import time
from datetime import datetime

from .config import Config
from .db import HOUR_MS
from .forecast import forecast

HORIZONS = (1, 6, 24)


def hourly_series(c, series: str, end_hour: int, hours: int, value_col: str = "avg"):
    """Hourly values ending at end_hour (inclusive), gaps filled by carrying the last value forward.
    Returns (values, hour_of_day list, number of hours that had real data)."""
    rows = c.execute(f"SELECT hour, {value_col} AS v FROM hourly WHERE series=? AND hour>? AND hour<=? ORDER BY hour",
                     (series, end_hour - hours, end_hour)).fetchall()
    if not rows:
        return [], [], 0
    by_hour = {r["hour"]: r["v"] for r in rows}
    values, hod, last = [], [], None
    for h in range(rows[0]["hour"], end_hour + 1):
        v = by_hour.get(h, last)
        if v is None:
            continue
        values.append(v)
        hod.append(datetime.fromtimestamp(h * 3600).hour)   # local time of day
        last = v
    return values, hod, len(rows)


def _forecast(c, cfg: Config, series: str, now_hour: int, value_col: str = "avg"):
    D, hod, real = hourly_series(c, series, now_hour, cfg.history_hours, value_col)
    r = forecast(D, hod, HORIZONS, cfg.eval_hours, cfg.min_hours_for_forecast, cfg.range_level, cfg.tracking_signal_limit)
    if r is None:
        return {"available": False, "hours_of_data": len(D),
                "needs_hours": cfg.min_hours_for_forecast, "series": series}
    d = r.to_dict()
    d.update(available=True, hours_of_data=len(D), hours_with_trades=real, series=series)
    return d


def _bar_stats(c, series: str, from_hour: int, to_hour: int):
    rows = c.execute("SELECT avg, high, low, n FROM hourly WHERE series=? AND hour>=? AND hour<=?",
                     (series, from_hour, to_hour)).fetchall()
    n = sum(r["n"] for r in rows)
    if not n:
        return None
    return {"avg": sum(r["avg"] * r["n"] for r in rows) / n,
            "high": max(r["high"] for r in rows), "low": min(r["low"] for r in rows), "samples": n}


def item_stats(c, key: str, cfg: Config, now_ms: int | None = None) -> dict:
    now_ms = now_ms or int(time.time() * 1000)
    now_hour = now_ms // HOUR_MS
    day_ago = now_ms - 86_400_000
    out: dict = {"key": key, "generated": now_ms}

    bz = c.execute("SELECT * FROM bz_now WHERE product=?", (key,)).fetchone()
    if bz:
        out["kind"] = "bazaar"
        out["now"] = {"buy": bz["buy"], "sell": bz["sell"], "buy_orders": bz["buy_orders"],
                      "sell_orders": bz["sell_orders"], "ts": bz["ts"]}
        out["daily_volume"] = {"bought": bz["buy_week"] / 7, "sold": bz["sell_week"] / 7}
        out["day"] = {side: _bar_stats(c, f"bz:{key}:{side}", now_hour - 23, now_hour) for side in ("buy", "sell")}
        out["forecast"] = {side: _forecast(c, cfg, f"bz:{key}:{side}", now_hour) for side in ("buy", "sell")}
        return out

    out["kind"] = "auction"
    sales = [r["unit_price"] for r in c.execute(
        "SELECT unit_price FROM ah_sale WHERE item_key=? AND ts>=? AND ts<=?", (key, day_ago, now_ms))]
    out["day"] = None if not sales else {
        "avg": sum(sales) / len(sales), "median": statistics.median(sales),
        "high": max(sales), "low": min(sales), "sales": len(sales)}
    lb = c.execute("SELECT lowest_bin, listings, ts FROM lbin_now WHERE item_key=?", (key,)).fetchone()
    out["lowest_bin"] = None if not lb else {"price": lb["lowest_bin"], "listings": lb["listings"], "ts": lb["ts"]}

    # Forecast on hourly median sale price if the item trades often enough, otherwise on lowest BIN.
    _, _, real = hourly_series(c, f"ah:{key}", now_hour, cfg.history_hours)
    if real >= cfg.min_hours_for_forecast:
        out["forecast"] = _forecast(c, cfg, f"ah:{key}", now_hour, "median")
        out["forecast"]["basis"] = "hourly median sale price"
    else:
        out["forecast"] = _forecast(c, cfg, f"lbin:{key}", now_hour)
        out["forecast"]["basis"] = "lowest BIN"
    if out["day"] is None and out["lowest_bin"] is None:
        out["kind"] = "unknown"
    return out


def search(c, q: str, limit: int = 25) -> list[str]:
    q = f"%{q.upper().replace(' ', '_')}%"
    keys = [r[0] for r in c.execute("SELECT product FROM bz_now WHERE product LIKE ? LIMIT ?", (q, limit))]
    keys += [r[0] for r in c.execute("SELECT item_key FROM lbin_now WHERE item_key LIKE ? LIMIT ?", (q, limit))]
    keys += [r[0] for r in c.execute("SELECT DISTINCT item_key FROM ah_sale WHERE item_key LIKE ? LIMIT ?", (q, limit))]
    return sorted(set(keys))[:limit]
