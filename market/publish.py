"""Export the database to a static website (dashboard + JSON) and push it to GitHub Pages.

The site lives in data/site/. If `publish_repo` is set in config.json, every export is
committed to the repo's `gh-pages` branch (always as a single amended commit, force-pushed,
so the repo never grows) and GitHub Pages serves it for free.

    python -m market.publish            export once (and push if publish_repo is set)
    python -m market.publish --no-push  export only
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import shutil
import subprocess
import time
from pathlib import Path

from .config import Config, load_config
from .db import HOUR_MS, Database
from .stats import item_stats

log = logging.getLogger("publish")
DASHBOARD_SRC = Path(__file__).resolve().parent.parent / "dashboard"
HIST_HOURS = 24 * 7


def r4(v):
    """Round to 4 significant digits to keep the JSON small."""
    if v is None or (isinstance(v, float) and (math.isnan(v) or math.isinf(v))):
        return None
    if v == 0:
        return 0
    return float(f"{v:.4g}")


def safe_name(key: str) -> str:
    return key.replace(":", "__")


def _series(c, series: str, start_hour: int, end_hour: int, col: str = "avg"):
    rows = dict(c.execute(f"SELECT hour, {col} FROM hourly WHERE series=? AND hour>=? AND hour<=?",
                          (series, start_hour, end_hour)).fetchall())
    return [r4(rows.get(h)) for h in range(start_hour, end_hour + 1)]


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


def export_site(cfg: Config, db: Database, out_dir: str | Path | None = None, now_ms: int | None = None) -> dict:
    out = Path(out_dir or Path(cfg.db_path).parent / "site")
    items_dir = out / "data" / "items"
    if items_dir.exists():
        shutil.rmtree(items_dir)
    items_dir.mkdir(parents=True, exist_ok=True)
    for f in DASHBOARD_SRC.iterdir():
        if f.is_file():
            shutil.copy2(f, out / f.name)

    c = db.connect()
    now_ms = now_ms or int(time.time() * 1000)
    now_h = now_ms // HOUR_MS
    t0 = time.time()

    # ---------------- bazaar summary (all products)
    bz_rows = c.execute("SELECT * FROM bz_now").fetchall()
    day = {}
    for r in c.execute("SELECT series, hour, avg, high, low, n FROM hourly WHERE series LIKE 'bz:%:buy' AND hour>=? AND hour<=?",
                       (now_h - 24, now_h)):
        day.setdefault(r["series"], []).append(r)
    items = []
    for b in bz_rows:
        rows = day.get(f"bz:{b['product']}:buy", [])
        cur = [x for x in rows if x["hour"] > now_h - 24]
        n = sum(x["n"] for x in cur)
        old = next((x["avg"] for x in rows if x["hour"] == now_h - 24), None)
        spread = (b["buy"] - b["sell"]) if b["buy"] and b["sell"] else None
        items.append({
            "k": b["product"], "kind": "bz", "now": r4(b["buy"]), "sell": r4(b["sell"]),
            "avg": r4(sum(x["avg"] * x["n"] for x in cur) / n) if n else None,
            "hi": r4(max((x["high"] for x in cur), default=None) if cur else None),
            "lo": r4(min((x["low"] for x in cur), default=None) if cur else None),
            "chg": r4((b["buy"] / old - 1) * 100) if old and b["buy"] else None,
            "vol": r4((b["buy_week"] + b["sell_week"]) / 7),
            "spread": r4(spread),
            "margin": r4(spread / b["buy"] * 100) if spread is not None and b["buy"] else None,
        })

    # ---------------- auction summary (every key sold in the last 48h or currently listed)
    day_ago, two_days = now_ms - 86_400_000, now_ms - 2 * 86_400_000
    ah = {}
    for r in c.execute("""SELECT item_key, COUNT(*) n, AVG(unit_price) a, MAX(unit_price) hi, MIN(unit_price) lo, SUM(price) coins
                          FROM ah_sale WHERE ts>? AND ts<=? GROUP BY item_key""", (day_ago, now_ms)):
        ah[r["item_key"]] = {"k": r["item_key"], "kind": "ah", "avg": r4(r["a"]), "hi": r4(r["hi"]), "lo": r4(r["lo"]),
                             "sales": r["n"], "coins": r4(r["coins"])}
    prev = {r[0]: r[1] for r in c.execute(
        "SELECT item_key, AVG(unit_price) FROM ah_sale WHERE ts>? AND ts<=? GROUP BY item_key", (two_days, day_ago))}
    for r in c.execute("SELECT item_key, lowest_bin, listings FROM lbin_now"):
        e = ah.setdefault(r[0], {"k": r[0], "kind": "ah", "sales": 0})
        e["lbin"], e["listed"] = r4(r[1]), r[2]
    for k, e in ah.items():
        e["now"] = e.get("lbin") or e.get("avg")
        if prev.get(k) and e.get("avg"):
            e["chg"] = r4((e["avg"] / prev[k] - 1) * 100)
    items += list(ah.values())

    # ---------------- detailed files (history + forecast) for the most active items
    top_bz = sorted([i for i in items if i["kind"] == "bz"], key=lambda i: -(i["vol"] or 0))[:cfg.publish_top_bazaar]
    top_ah = sorted([i for i in items if i["kind"] == "ah"],
                    key=lambda i: -((i.get("sales") or 0) * 10 + (i.get("listed") or 0)))[:cfg.publish_top_auction]
    start_h = now_h - HIST_HOURS + 1
    for it in top_bz + top_ah:
        k = it["k"]
        s = item_stats(c, k, cfg, now_ms)
        if it["kind"] == "bz":
            series = {"buy": _series(c, f"bz:{k}:buy", start_h, now_h), "sell": _series(c, f"bz:{k}:sell", start_h, now_h)}
            fc = {"buy": _slim_forecast(s["forecast"]["buy"]), "sell": _slim_forecast(s["forecast"]["sell"])}
            main = fc["buy"]
        else:
            series = {"median": _series(c, f"ah:{k}", start_h, now_h, "median"),
                      "lbin": _series(c, f"lbin:{k}", start_h, now_h),
                      "sales": [int(v) if v else 0 for v in _series(c, f"ah:{k}", start_h, now_h, "n")]}
            fc = {"main": _slim_forecast(s["forecast"])}
            main = fc["main"]
        detail = {"k": k, "kind": it["kind"], "start": start_h * HOUR_MS, "step": HOUR_MS,
                  "series": series, "forecast": fc, "summary": it}
        (items_dir / f"{safe_name(k)}.json").write_text(json.dumps(detail, separators=(",", ":")), encoding="utf-8")
        it["d"] = 1                                   # dashboard: detail file exists
        if main and main.get("available"):
            f24 = main["h"].get("24") or main["h"][max(main["h"], key=int)]
            it["f24"] = f24["f"]
            it["f24chg"] = r4((f24["f"] / main["last"] - 1) * 100) if main["last"] else None
            it["mape"] = f24["mape"]

    hours = c.execute("SELECT COUNT(DISTINCT hour) FROM hourly WHERE series LIKE 'bz:%'").fetchone()[0]
    first = c.execute("SELECT MIN(hour) FROM hourly WHERE series LIKE 'bz:%'").fetchone()[0]
    stats = {
        "generated": now_ms, "hours_collected": hours, "first_hour": first * HOUR_MS if first else None,
        "bazaar_items": len(bz_rows), "auction_items": len(ah),
        "sales_24h": c.execute("SELECT COUNT(*) FROM ah_sale WHERE ts>?", (day_ago,)).fetchone()[0],
        "coins_24h": r4(c.execute("SELECT SUM(price) FROM ah_sale WHERE ts>?", (day_ago,)).fetchone()[0] or 0),
        "detailed": len(top_bz) + len(top_ah), "export_seconds": None,
        "source": cfg.publish_label,
    }
    stats["export_seconds"] = round(time.time() - t0, 1)
    (out / "data" / "summary.json").write_text(json.dumps({"stats": stats, "items": items}, separators=(",", ":")),
                                               encoding="utf-8")
    log.info("exported %d items (%d detailed) in %.1fs", len(items), stats["detailed"], stats["export_seconds"])
    return stats


def _git(site: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=site, check=check, capture_output=True, text=True)


def push_site(cfg: Config, site: Path | None = None) -> None:
    """Commit data/site as the only commit on gh-pages and force-push it."""
    if not cfg.publish_repo:
        return
    site = site or Path(cfg.db_path).parent / "site"
    (site / ".nojekyll").touch()
    if not (site / ".git").exists():
        _git(site, "init", "-q")
        _git(site, "checkout", "-q", "-B", "gh-pages")
        _git(site, "remote", "add", "origin", cfg.publish_repo)
    _git(site, "config", "user.name", cfg.publish_git_name)
    _git(site, "config", "user.email", cfg.publish_git_email)
    _git(site, "add", "-A")
    has_commit = _git(site, "rev-parse", "--verify", "HEAD", check=False).returncode == 0
    msg = f"Market snapshot {time.strftime('%Y-%m-%d %H:%M')}"
    if has_commit:
        _git(site, "commit", "-q", "--amend", "-m", msg, check=False)
    else:
        _git(site, "commit", "-q", "-m", msg)
    res = _git(site, "push", "-q", "--force", "origin", "gh-pages", check=False)
    if res.returncode != 0:
        raise RuntimeError(f"git push failed: {res.stderr.strip()}")
    log.info("pushed dashboard to %s (gh-pages)", cfg.publish_repo)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="config.json")
    ap.add_argument("--out", default=None)
    ap.add_argument("--no-push", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    cfg = load_config(args.config)
    db = Database(cfg.db_path)
    print(json.dumps(export_site(cfg, db, args.out), indent=1))
    if not args.no_push:
        push_site(cfg, Path(args.out) if args.out else None)


if __name__ == "__main__":
    main()
