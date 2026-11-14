"""Everything known about one item, as one JSON document.

Used for: data/items/<KEY>.json on the dashboard (GitHub Pages), the local server's
/itemdata/<KEY> endpoint, and the Minecraft mod (which reads either of those).

  summary   now / 24h avg-high-low / change / activity (one row of the dashboard table)
  series    hourly prices, last 7 days
  daily     daily prices for the whole history (up to 365 days)
  stats     24h / 7d / 30d / all-time  avg, high, low (+ when the high/low happened)
  forecast  1h / 6h / 24h forecasts (EXC 3650 models), if computed
  seasons   tested cycles (daily / SkyBlock year / weekly), confirmed or not, with next peak/dip
"""
from __future__ import annotations

import math
import statistics
import time

from .config import Config
from .db import HOUR_MS
from .seasonality import detect_all
from .stats import item_stats

DAY_MS = 86_400_000
HIST_HOURS = 24 * 7


def r4(v):
    if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
        return None
    if v == 0:
        return 0
    return float(f"{v:.4g}")


# --------------------------------------------------------------------------- summaries

def bazaar_summary(b, rows: list, now_h: int) -> dict:
    """b: bz_now row; rows: hourly rows of bz:<id>:buy for hours now_h-24 .. now_h."""
    cur = [x for x in rows if x["hour"] > now_h - 24]
    n = sum(x["n"] for x in cur)
    old = next((x["avg"] for x in rows if x["hour"] == now_h - 24), None)
    spread = (b["buy"] - b["sell"]) if b["buy"] and b["sell"] else None
    return {
        "k": b["product"], "kind": "bz", "now": r4(b["buy"]), "sell": r4(b["sell"]),
        "avg": r4(sum(x["avg"] * x["n"] for x in cur) / n) if n else None,
        "hi": r4(max(x["high"] for x in cur)) if cur else None,
        "lo": r4(min(x["low"] for x in cur)) if cur else None,
        "chg": r4((b["buy"] / old - 1) * 100) if old and b["buy"] else None,
        "vol": r4((b["buy_week"] + b["sell_week"]) / 7),
        "spread": r4(spread),
        "margin": r4(spread / b["buy"] * 100) if spread is not None and b["buy"] else None,
        "ts": b["ts"],
    }


def auction_summaries(c, now_ms: int, keys: list[str] | None = None) -> dict[str, dict]:
    day_ago, two_days = now_ms - DAY_MS, now_ms - 2 * DAY_MS
    flt, args = ("", ()) if keys is None else (f" AND item_key IN ({','.join('?' * len(keys))})", tuple(keys))
    ah: dict[str, dict] = {}
    for r in c.execute(f"""SELECT item_key, COUNT(*) n, AVG(unit_price) a, MAX(unit_price) hi, MIN(unit_price) lo, SUM(price) coins
                           FROM ah_sale WHERE ts>? AND ts<=?{flt} GROUP BY item_key""", (day_ago, now_ms) + args):
        ah[r["item_key"]] = {"k": r["item_key"], "kind": "ah", "avg": r4(r["a"]), "hi": r4(r["hi"]), "lo": r4(r["lo"]),
                             "sales": r["n"], "coins": r4(r["coins"])}
    prev = {r[0]: r[1] for r in c.execute(
        f"SELECT item_key, AVG(unit_price) FROM ah_sale WHERE ts>? AND ts<=?{flt} GROUP BY item_key", (two_days, day_ago) + args)}
    for r in c.execute(f"SELECT item_key, lowest_bin, listings, ts FROM lbin_now WHERE 1=1{flt}", args):
        e = ah.setdefault(r[0], {"k": r[0], "kind": "ah", "sales": 0})
        e["lbin"], e["listed"], e["ts"] = r4(r[1]), r[2], r[3]
    for k, e in ah.items():
        e["now"] = e.get("lbin") or e.get("avg")
        if prev.get(k) and e.get("avg"):
            e["chg"] = r4((e["avg"] / prev[k] - 1) * 100)
    return ah


def summary_for(c, key: str, now_ms: int) -> dict | None:
    b = c.execute("SELECT * FROM bz_now WHERE product=?", (key,)).fetchone()
    now_h = now_ms // HOUR_MS
    if b:
        rows = c.execute("SELECT hour, avg, high, low, n FROM hourly WHERE series=? AND hour>=? AND hour<=?",
                         (f"bz:{key}:buy", now_h - 24, now_h)).fetchall()
        return bazaar_summary(b, rows, now_h)
    return auction_summaries(c, now_ms, [key]).get(key)


# --------------------------------------------------------------------------- history

def _hourly(c, series: str, start_h: int, end_h: int, col: str = "avg"):
    rows = dict(c.execute(f"SELECT hour, {col} FROM hourly WHERE series=? AND hour>=? AND hour<=?",
                          (series, start_h, end_h)).fetchall())
    return [r4(rows.get(h)) for h in range(start_h, end_h + 1)]


def _daily(c, series: str, end_h: int, days: int = 365, col: str = "avg", how: str = "avg"):
    """Daily values (UTC days) for the whole history of a series."""
    rows = c.execute(f"SELECT hour, {col} AS v, n FROM hourly WHERE series=? AND hour>? AND hour<=? ORDER BY hour",
                     (series, end_h - days * 24, end_h)).fetchall()
    if not rows:
        return None, []
    by_day: dict[int, list] = {}
    for r in rows:
        if r["v"] is not None:
            by_day.setdefault(r["hour"] // 24, []).append((r["v"], r["n"] or 1))
    if not by_day:
        return None, []
    d0, d1 = min(by_day), end_h // 24
    out = []
    for d in range(d0, d1 + 1):
        v = by_day.get(d)
        if not v:
            out.append(None)
        elif how == "sum":
            out.append(sum(n for _, n in v))
        else:
            out.append(r4(sum(x * n for x, n in v) / sum(n for _, n in v)))
    return d0 * DAY_MS, out


def _window_stats(c, series: str, now_h: int, hours: int | None) -> dict | None:
    lo_h = now_h - hours + 1 if hours else 0
    rows = c.execute("SELECT hour, avg, high, low, n FROM hourly WHERE series=? AND hour>=? AND hour<=?",
                     (series, lo_h, now_h)).fetchall()
    n = sum(r["n"] or 0 for r in rows)
    if not rows or not n:
        return None
    hi = max(rows, key=lambda r: r["high"])
    lo = min(rows, key=lambda r: r["low"])
    return {"avg": r4(sum(r["avg"] * (r["n"] or 0) for r in rows) / n), "hi": r4(hi["high"]), "hi_at": hi["hour"] * HOUR_MS,
            "lo": r4(lo["low"]), "lo_at": lo["hour"] * HOUR_MS, "n": n, "since": rows[0]["hour"] * HOUR_MS}


WINDOWS = {"24h": 24, "7d": 168, "30d": 720, "all": None}


def _slim_forecast(f: dict | None) -> dict | None:
    if not f or not f.get("available"):
        return {"available": False, "hours_of_data": (f or {}).get("hours_of_data", 0),
                "needs_hours": (f or {}).get("needs_hours", 12)}
    return {"available": True, "last": r4(f["last_value"]), "biased": f["biased"], "basis": f.get("basis"),
            "h": {h: {"model": v["model"], "f": r4(v["forecast"]), "lo": r4(v["range"][0]), "hi": r4(v["range"][1]),
                      "mad": r4(v["accuracy"]["mad"]), "mape": r4(v["accuracy"]["mape"]),
                      "mfe": r4(v["accuracy"]["mfe"]), "ts": r4(v["accuracy"]["tracking_signal"]),
                      "board": [{"model": b["model"], "mad": r4(b["mad"]), "mape": r4(b["mape"])} for b in v["leaderboard"]]}
                  for h, v in f["horizons"].items()}}


def _season_input(c, series: str, now_h: int, lookback: int, col: str = "avg"):
    """Contiguous hourly values for seasonality (carry forward short gaps). None if coverage < 80 %."""
    rows = c.execute(f"SELECT hour, {col} FROM hourly WHERE series=? AND hour>? AND hour<? ORDER BY hour",
                     (series, now_h - lookback, now_h)).fetchall()          # complete hours only
    if not rows:
        return None, None
    first = rows[0][0]
    span = now_h - first
    if span <= 0 or len(rows) / span < 0.8:
        return None, None
    by = {h: v for h, v in rows if v}
    vals, last = [], None
    for h in range(first, now_h):
        last = by.get(h, last)
        vals.append(last)
    return vals, first


# --------------------------------------------------------------------------- the document

def item_detail(c, key: str, cfg: Config, now_ms: int | None = None, summary: dict | None = None,
                with_forecast: bool = True) -> dict | None:
    now_ms = now_ms or int(time.time() * 1000)
    now_h = now_ms // HOUR_MS
    summary = summary or summary_for(c, key, now_ms)
    if summary is None:
        return None
    bz = summary["kind"] == "bz"
    start_h = now_h - HIST_HOURS + 1
    doc = {"k": key, "kind": summary["kind"], "generated": now_ms, "summary": summary,
           "start": start_h * HOUR_MS, "step": HOUR_MS}

    if bz:
        main_series, main_col = f"bz:{key}:buy", "avg"
        doc["series"] = {"buy": _hourly(c, f"bz:{key}:buy", start_h, now_h), "sell": _hourly(c, f"bz:{key}:sell", start_h, now_h)}
        d0, buy_d = _daily(c, f"bz:{key}:buy", now_h)
        _, sell_d = _daily(c, f"bz:{key}:sell", now_h)
        sell_d = ([None] * max(0, len(buy_d) - len(sell_d)) + sell_d)[-len(buy_d):] if buy_d else []
        doc["daily"] = {"start": d0, "buy": buy_d, "sell": sell_d}
        doc["stats"] = {w: {"buy": _window_stats(c, f"bz:{key}:buy", now_h, h), "sell": _window_stats(c, f"bz:{key}:sell", now_h, h)}
                        for w, h in WINDOWS.items()}
    else:
        doc["series"] = {"median": _hourly(c, f"ah:{key}", start_h, now_h, "median"),
                         "lbin": _hourly(c, f"lbin:{key}", start_h, now_h),
                         "sales": [int(v) if v else 0 for v in _hourly(c, f"ah:{key}", start_h, now_h, "n")]}
        d0, med_d = _daily(c, f"ah:{key}", now_h, col="median")
        l0, lbin_d = _daily(c, f"lbin:{key}", now_h)
        _, sales_d = _daily(c, f"ah:{key}", now_h, col="n", how="sum")
        doc["daily"] = {"start": d0, "median": med_d, "sales": sales_d, "lbin_start": l0, "lbin": lbin_d}
        doc["stats"] = {w: {"sold": _window_stats(c, f"ah:{key}", now_h, h), "lbin": _window_stats(c, f"lbin:{key}", now_h, h)}
                        for w, h in WINDOWS.items()}
        main_series, main_col = f"ah:{key}", "median"

    if with_forecast:
        s = item_stats(c, key, cfg, now_ms)
        doc["forecast"] = ({"buy": _slim_forecast(s["forecast"]["buy"]), "sell": _slim_forecast(s["forecast"]["sell"])}
                           if bz else {"main": _slim_forecast(s.get("forecast"))})
    else:
        doc["forecast"] = None

    # seasonality on the main price series (auction: sales if they trade often, otherwise lowest BIN)
    vals, first = _season_input(c, main_series, now_h, cfg.season_lookback_hours, main_col)
    basis = "buy price" if bz else "median sale"
    if vals is None and not bz:
        vals, first = _season_input(c, f"lbin:{key}", now_h, cfg.season_lookback_hours)
        basis = "lowest BIN"
    doc["seasons"] = [dict(s.to_dict(now_h), basis=basis) for s in detect_all(vals, first)] if vals else []
    doc["season_hours"] = len(vals) if vals else 0
    return doc
