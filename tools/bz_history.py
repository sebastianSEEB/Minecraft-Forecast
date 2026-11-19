"""Hourly bazaar history + forecasts for the Not Enough Flips public data site (GitHub Actions).

    python tools/bz_history.py SITE_DIR [max_forecast_products]

SITE_DIR is a checkout of the gh-pages branch. Each run:
  1. takes one snapshot of the Hypixel bazaar (no API key needed) and adds it to a rolling 14-day
     history kept in SITE_DIR/data/_hist/bz.json.gz (one value per product per hour);
  2. writes SITE_DIR/data/bz/<PRODUCT>.json = {"points": [[ts, buy, sell], ...]} for the last 7 days
     (what the mod's bazaar watch list charts);
  3. writes SITE_DIR/data/forecasts.json with 1 h / 6 h / 24 h forecasts for the most traded products,
     chosen per series by market/forecast.py (demand pattern → method, tracking-signal check).
"""
from __future__ import annotations

import gzip
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.forecast import forecast  # noqa: E402
from market.hypixel import HypixelClient  # noqa: E402

HOUR = 3_600_000
KEEP_HOURS = 14 * 24
CHART_HOURS = 7 * 24
HORIZONS = (1, 6, 24)
MIN_HOURS = 12


def r(x: float) -> float:
    return float(f"{x:.4g}") if abs(x) < 1000 else round(x, 1)


def load_hist(path: Path) -> dict:
    try:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            h = json.load(f)
        if isinstance(h.get("items"), dict):
            return h
    except (OSError, ValueError):
        pass
    return {"items": {}, "vol": {}}


def add_snapshot(hist: dict, bazaar: dict, now_ms: int) -> int:
    """One [hour, buy, sell] per product per hour (a later run in the same hour replaces it)."""
    hour = now_ms // HOUR
    n = 0
    for pid, p in (bazaar.get("products") or {}).items():
        q = p.get("quick_status") or {}
        buy, sell = float(q.get("buyPrice") or 0), float(q.get("sellPrice") or 0)
        if buy <= 0 and sell <= 0:
            continue
        rows = hist["items"].setdefault(pid, [])
        if rows and rows[-1][0] == hour:
            rows[-1] = [hour, r(buy), r(sell)]
        else:
            rows.append([hour, r(buy), r(sell)])
        hist["vol"][pid] = int(q.get("buyMovingWeek") or 0) + int(q.get("sellMovingWeek") or 0)
        n += 1
    cut = hour - KEEP_HOURS
    for pid in list(hist["items"]):
        rows = [x for x in hist["items"][pid] if x[0] > cut]
        if rows:
            hist["items"][pid] = rows
        else:
            del hist["items"][pid]
            hist["vol"].pop(pid, None)
    return n


def filled(rows: list, side: int, end_hour: int) -> tuple[list[float], list[int]]:
    """Hourly values up to end_hour, gaps carried forward (skipped hours of the schedule), zeros dropped."""
    by = {x[0]: x[side] for x in rows if x[side] > 0}
    if not by:
        return [], []
    D, hod, last = [], [], None
    for h in range(min(by), end_hour + 1):
        v = by.get(h, last)
        if v is None:
            continue
        D.append(v)
        hod.append(datetime.fromtimestamp(h * 3600, timezone.utc).hour)
        last = v
    return D, hod


def compact(res) -> dict | None:
    if res is None or any(h not in res.horizons for h in HORIZONS):
        return None
    hs = [res.horizons[h] for h in HORIZONS]
    pat = res.pattern
    return {"last": r(res.last_value), "f": [r(h.value) for h in hs], "lo": [r(h.low) for h in hs],
            "hi": [r(h.high) for h in hs], "mape": [round(h.acc.mape, 2) for h in hs], "biased": bool(res.biased),
            "trend": bool(pat and pat.trend), "rnd": pat.randomness if pat else None,
            "seas": bool(pat and pat.seasonal), "m": hs[0].model}


def main(site_dir: str, max_products: str = "400") -> None:
    site = Path(site_dir)
    data = site / "data"
    (data / "_hist").mkdir(parents=True, exist_ok=True)
    hist_path = data / "_hist" / "bz.json.gz"
    now_ms = int(time.time() * 1000)
    hour = now_ms // HOUR

    api = HypixelClient("https://api.hypixel.net/v2", "NotEnoughFlips data")
    hist = load_hist(hist_path)
    n = add_snapshot(hist, api.bazaar(), now_ms)
    with gzip.open(hist_path, "wt", encoding="utf-8") as f:
        json.dump(hist, f, separators=(",", ":"))

    # per-product 7-day charts
    bz = data / "bz"
    bz.mkdir(exist_ok=True)
    keep = set()
    for pid, rows in hist["items"].items():
        if not all(c.isalnum() or c in "_:" for c in pid):
            continue
        pts = [[x[0] * HOUR, x[1], x[2]] for x in rows if x[0] > hour - CHART_HOURS]
        if pts:
            (bz / f"{pid}.json").write_text(json.dumps({"id": pid, "points": pts}, separators=(",", ":")), encoding="utf-8")
            keep.add(f"{pid}.json")
    for f in bz.iterdir():
        if f.name not in keep:
            f.unlink()

    # forecasts for the most traded products
    t0 = time.time()
    top = sorted(hist["items"], key=lambda p: -hist["vol"].get(p, 0))[:int(max_products)]
    items: dict = {}
    for pid in top:
        sides = {}
        for side, col in (("buy", 1), ("sell", 2)):
            D, hod = filled(hist["items"][pid], col, hour)
            if len(D) < MIN_HOURS:
                continue
            try:
                c = compact(forecast(D, hod, HORIZONS, eval_hours=72, min_points=MIN_HOURS))
            except (ValueError, ZeroDivisionError, OverflowError):
                c = None
            if c:
                sides[side] = c
        if sides:
            items[pid] = sides
    (data / "forecasts.json").write_text(json.dumps(
        {"generated": now_ms, "horizons": list(HORIZONS), "items": items}, separators=(",", ":")), encoding="utf-8")

    hours = max((len(v) for v in hist["items"].values()), default=0)
    print(f"bazaar snapshot: {n} products · history up to {hours} h · "
          f"{len(keep)} charts · forecasts for {len(items)} products in {time.time() - t0:.0f}s")


if __name__ == "__main__":
    main(*sys.argv[1:])
