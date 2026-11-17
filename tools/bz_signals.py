"""Bazaar misprices, correlations and suggestions from SkyCofl's 7-day history (GitHub Actions).

    python tools/bz_signals.py out_dir [NEU_REPO_DIR] [max_products]

Live prices + weekly volume: Hypixel bazaar (no key). History: SkyCofl /api/bazaar/<id>/history/week
(2-hour bars, 7 days) for the most traded products, ~1.5 requests/s. Compression recipes (1 enchanted =
160 raw, …) from the NotEnoughUpdates repo when given. The analysis itself is market/bzsignals.py,
the same code your PC collector runs on its own hourly data. Writes out_dir/bz_signals.json.
"""
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.bzsignals import analyse, liquid, recipes_from_neu  # noqa: E402
from market.hypixel import HypixelClient  # noqa: E402

STEP = 2 * 3_600_000


def cofl_history(pid: str) -> list[tuple]:
    req = urllib.request.Request(f"https://sky.coflnet.com/api/bazaar/{pid}/history/week",
                                 headers={"User-Agent": "skyprice bz-signals"})
    with urllib.request.urlopen(req, timeout=20) as r:
        rows = json.loads(r.read().decode())
    out = []
    for p in rows or []:
        try:
            ts = datetime.fromisoformat(p["timestamp"].replace("Z", "")).replace(tzinfo=timezone.utc).timestamp() * 1000
            out.append((int(ts), float(p.get("buy") or 0), float(p.get("sell") or 0)))
        except (KeyError, TypeError, ValueError):
            continue
    return out


def main(out_dir, neu=None, max_products="260"):
    api = HypixelClient("https://api.hypixel.net/v2", "skyprice bz-signals")
    products = api.bazaar().get("products") or {}
    live = {}
    for pid, p in products.items():
        q = p.get("quick_status") or {}
        live[pid] = {"buy": float(q.get("buyPrice") or 0), "sell": float(q.get("sellPrice") or 0),
                     "buy_week": float(q.get("buyMovingWeek") or 0), "sell_week": float(q.get("sellMovingWeek") or 0)}
    ok = liquid(live)
    turnover = sorted(ok, key=lambda pid: min(live[pid]["buy_week"], live[pid]["sell_week"]) * live[pid]["sell"], reverse=True)
    pick = turnover[: int(max_products)]

    recipes, names = {}, {}
    if neu and Path(neu).exists():
        from tools.craft_flips import load_recipes
        rec, _npc, _req, _sb, names = load_recipes(neu)
        recipes = recipes_from_neu(rec, ok)
        for o, (i, _k) in recipes.items():          # both sides of a liquid compression pair get history
            if o in pick and i not in pick:
                pick.append(i)
        names = {k: v for k, v in names.items() if k in live}
        import re
        names = {k: re.sub(r"§.", "", v) for k, v in names.items()}

    series, fails = {}, 0
    t0 = time.time()
    for i, pid in enumerate(pick):
        try:
            series[pid] = cofl_history(pid)
        except Exception as e:                          # noqa: BLE001
            fails += 1
            if fails <= 5:
                print(f"  {pid}: {e}")
        time.sleep(0.65)
    print(f"{len(products)} products, {len(ok)} liquid, history for {len(series)} ({fails} failed) in {time.time() - t0:.0f}s")

    doc = analyse(series, live, STEP, recipes=recipes, names=names,
                  now_ms=int(time.time() * 1000), source="SkyCofl 7-day history (2h bars)")
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    Path(out_dir, "bz_signals.json").write_text(json.dumps(doc, separators=(",", ":")), encoding="utf-8")

    print(f"\n### Bazaar signals: {len(doc['signals'])} suggestions, {doc['pairs']} correlated pairs, {doc['leads']} lead-lag links\n")
    print("| conf | type | product | edge | what to do |\n|---|---|---|---|---|")
    for s in doc["signals"][:25]:
        print(f"| {s['conf']} | {s['type']} | {s['name']} | {s['edge'] * 100:+.1f}% | {s['text']} |")
    print("\n| ρ | lag | pair |\n|---|---|---|")
    for c in doc["correlations"][:15]:
        print(f"| {c['rho']:.2f} | {c['lag']} | {names.get(c['a'], c['a'])} ↔ {names.get(c['b'], c['b'])} |")


if __name__ == "__main__":
    main(*sys.argv[1:])
