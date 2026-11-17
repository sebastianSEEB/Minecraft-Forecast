"""Export the database to a static website (dashboard + JSON) and push it to GitHub Pages.

Output (data/site/):
  index.html, app.js, style.css      the dashboard
  data/summary.json                  one row per item (table, movers, flips)
  data/seasonality.json              every confirmed seasonal cycle, with next peak / dip
  data/items/<KEY>.json              full document per item (history, stats, forecast, seasons) —
                                     also what the Minecraft mod reads when your PC is off

If `publish_repo` is set, the site is committed to the repo's `gh-pages` branch as a single
amended commit and force-pushed, so the repo never grows. GitHub Pages serves it for free.

    python -m market.publish            export once (and push if publish_repo is set)
    python -m market.publish --no-push  export only
"""
from __future__ import annotations

import argparse
import json
import logging
import shutil
import subprocess
import time
from pathlib import Path

from .config import Config, load_config
from .db import HOUR_MS, Database
from .detail import DAY_MS, auction_summaries, bazaar_summary, item_detail, r4
from .wallet import wallet_report

log = logging.getLogger("publish")
DASHBOARD_SRC = Path(__file__).resolve().parent.parent / "dashboard"


def safe_name(key: str) -> str:
    return key.replace(":", "__")


def compact_forecast(fc: dict | None) -> dict | None:
    """{side: {last, f:[1h,6h,24h], lo, hi, mape, biased, trend, rnd, seas, m}} for the sides that have a forecast."""
    if not fc:
        return None
    out = {}
    for side, f in fc.items():
        if not f or not f.get("available"):
            continue
        hs = [f["h"].get(h) or f["h"].get(int(h)) for h in ("1", "6", "24")]
        if any(h is None for h in hs):
            continue
        pat = f.get("pattern") or {}
        out[side] = {"last": f["last"], "f": [h["f"] for h in hs], "lo": [h["lo"] for h in hs], "hi": [h["hi"] for h in hs],
                     "mape": [h["mape"] for h in hs], "biased": f["biased"], "trend": bool(pat.get("trend")),
                     "rnd": pat.get("randomness"), "seas": bool(pat.get("seasonal")), "m": hs[0]["model"]}
    return out or None


def export_site(cfg: Config, db: Database, out_dir: str | Path | None = None, now_ms: int | None = None) -> dict:
    out = Path(out_dir or Path(cfg.db_path).parent / "site")
    tmp_items = out / "data" / "items.tmp"
    if tmp_items.exists():
        shutil.rmtree(tmp_items)
    tmp_items.mkdir(parents=True, exist_ok=True)
    for f in DASHBOARD_SRC.iterdir():
        if f.is_file():
            shutil.copy2(f, out / f.name)

    c = db.connect()
    now_ms = now_ms or int(time.time() * 1000)
    now_h = now_ms // HOUR_MS
    t0 = time.time()

    # ---- one summary row per item
    day = {}
    for r in c.execute("SELECT series, hour, avg, high, low, n FROM hourly WHERE series LIKE 'bz:%:buy' AND hour>=? AND hour<=?",
                       (now_h - 24, now_h)):
        day.setdefault(r["series"], []).append(r)
    bz_rows = c.execute("SELECT * FROM bz_now").fetchall()
    items = [bazaar_summary(b, day.get(f"bz:{b['product']}:buy", []), now_h) for b in bz_rows]
    ah = auction_summaries(c, now_ms)
    items += list(ah.values())

    # ---- forecasts for the most active items, history + stats + seasonality for all
    top_bz = {i["k"] for i in sorted([i for i in items if i["kind"] == "bz"], key=lambda i: -(i["vol"] or 0))[:cfg.publish_top_bazaar]}
    top_ah = {i["k"] for i in sorted([i for i in items if i["kind"] == "ah"],
                                     key=lambda i: -((i.get("sales") or 0) * 10 + (i.get("listed") or 0)))[:cfg.publish_top_auction]}
    seasons = []
    fc_all: dict = {}
    for it in items:
        k = it["k"]
        doc = item_detail(c, k, cfg, now_ms, summary=it,
                          with_forecast=cfg.forecast_all or k in top_bz or k in top_ah)
        if doc is None:
            continue
        (tmp_items / f"{safe_name(k)}.json").write_text(json.dumps(doc, separators=(",", ":")), encoding="utf-8")
        fc = doc["forecast"]
        cf = compact_forecast(fc)
        if cf:
            fc_all[k] = cf
        main = (fc or {}).get("buy") or (fc or {}).get("main")
        if main and main.get("available"):
            f24 = main["h"].get("24") or main["h"][max(main["h"], key=int)]
            it["f24"] = f24["f"]
            it["f24chg"] = r4((f24["f"] / main["last"] - 1) * 100) if main["last"] else None
            it["mape"] = f24["mape"]
        conf = [s for s in doc["seasons"] if s["confirmed"]]
        if conf:
            first = min(conf, key=lambda s: s["hours_to_peak"])
            it["season"] = first["label"]
            it["peak_in"] = first["hours_to_peak"]
        for s in conf:
            seasons.append({"k": k, "kind": it["kind"], "now": it.get("now"), **{x: s[x] for x in (
                "cycle", "label", "period", "index", "amplitude", "consistency", "oos_gain", "cycles", "phase_now",
                "hours_to_peak", "hours_to_trough", "rise_to_peak", "fall_to_trough", "peak_at", "trough_at", "basis")},
                "peak_sb_date": s.get("peak_sb_date"), "trough_sb_date": s.get("trough_sb_date")})
    items_dir = out / "data" / "items"
    if items_dir.exists():
        shutil.rmtree(items_dir)
    tmp_items.rename(items_dir)

    seasons.sort(key=lambda s: (s["hours_to_peak"], -s["amplitude"]))
    season_hours = c.execute("SELECT COUNT(DISTINCT hour) FROM hourly WHERE series LIKE 'bz:%' AND hour<?", (now_h,)).fetchone()[0]
    (out / "data" / "seasonality.json").write_text(json.dumps({
        "generated": now_ms, "now_hour": now_h, "hours_of_data": season_hours,
        "needs": {"daily": 24 * 5, "skyblock_year": 124 * 5, "weekly": 168 * 5},
        "rows": seasons}, separators=(",", ":")), encoding="utf-8")

    # every forecast in one small file: what the mod's flip lists use (data/forecasts.json, local /forecasts)
    (out / "data" / "forecasts.json").write_text(json.dumps(
        {"generated": now_ms, "horizons": [1, 6, 24], "items": fc_all}, separators=(",", ":")), encoding="utf-8")

    # bazaar misprices / correlations from the collector's hourly bars (ERP → Signals); without 24 h of data
    # the GitHub job's SkyCofl-based file is kept instead (see push below)
    from .bzsignals import from_db
    sig_file = out / "data" / "bz_signals.json"
    try:
        sig = from_db(c, cfg, now_ms)
    except Exception as e:  # noqa: BLE001
        log.warning("bazaar signals failed: %s", e)
        sig = None
    if sig:
        sig_file.write_text(json.dumps(sig, separators=(",", ":")), encoding="utf-8")
    elif sig_file.exists():
        sig_file.unlink()

    from .valuation import components_doc
    (out / "data" / "components.json").write_text(json.dumps(components_doc(c), separators=(",", ":")), encoding="utf-8")
    deals = [dict(r) for r in c.execute("SELECT * FROM deals ORDER BY profit DESC LIMIT 200")]
    (out / "data" / "deals.json").write_text(json.dumps({"generated": now_ms, "deals": deals}, separators=(",", ":")),
                                             encoding="utf-8")
    wfile = out / "data" / "wallet.json"
    rep = wallet_report(c, now_ms) if cfg.publish_wallet else None
    if rep:
        wfile.write_text(json.dumps(rep, separators=(",", ":")), encoding="utf-8")
    elif wfile.exists():
        wfile.unlink()
    first = c.execute("SELECT MIN(hour) FROM hourly WHERE series LIKE 'bz:%'").fetchone()[0]
    stats = {
        "generated": now_ms, "hours_collected": season_hours, "first_hour": first * HOUR_MS if first else None,
        "bazaar_items": len(bz_rows), "auction_items": len(ah),
        "sales_24h": c.execute("SELECT COUNT(*) FROM ah_sale WHERE ts>?", (now_ms - DAY_MS,)).fetchone()[0],
        "coins_24h": r4(c.execute("SELECT SUM(price) FROM ah_sale WHERE ts>?", (now_ms - DAY_MS,)).fetchone()[0] or 0),
        "detailed": len(items), "forecasted": len(fc_all), "seasonal": len({s["k"] for s in seasons}),
        "source": cfg.publish_label, "export_seconds": round(time.time() - t0, 1),
    }
    (out / "data" / "summary.json").write_text(json.dumps({"stats": stats, "items": items}, separators=(",", ":")),
                                               encoding="utf-8")
    log.info("exported %d items (%d forecast, %d seasonal) in %.1fs", len(items), stats["forecasted"],
             stats["seasonal"], stats["export_seconds"])
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
    # keep the files the 15-min GitHub AH job maintains (your positions + bazaar price history)
    if _git(site, "fetch", "-q", "--depth", "1", "origin", "gh-pages", check=False).returncode == 0:
        keep = ["data/positions.json", "data/bazaar_watch.json", "data/craft_flips.json"]
        if not (site / "data" / "bz_signals.json").exists():      # no collector signals yet: keep the GitHub job's
            keep.append("data/bz_signals.json")
        for f in keep:
            _git(site, "checkout", "FETCH_HEAD", "--", f, check=False)
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
