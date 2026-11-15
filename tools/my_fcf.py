"""Potential FCF without your PC: scan the AH for your listings + bids and publish data/positions.json.

    python tools/my_fcf.py SpookyHawk out_dir [site_url]

Runs on GitHub Actions every 15 minutes (.github/workflows/positions.yml). Item values come from the published
dashboard data (data/components.json + data/items/<KEY>.json: the fitted clean-base / pet-level models), so the
same valuation as the PC collector is used (market/valuation.py). With a HYPIXEL_API_KEY secret, purse + bank are
included too; otherwise the mod uses your live purse from the sidebar.
Output has the same shape as the PC collector's /wallet report ({"fcf": {...}}), so the mod reads either.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market import fcf  # noqa: E402
from market.collector import wallet_from_profiles  # noqa: E402
from market.hypixel import HypixelClient  # noqa: E402
from market.items import decode_listing  # noqa: E402
from market.publish import safe_name  # noqa: E402
from market.valuation import Prices, value_of  # noqa: E402


def get_json(url):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "skyprice-fcf"}), timeout=20) as r:
            return json.loads(r.read().decode())
    except Exception:
        return None


def main(name, out_dir, site="https://sebastianseeb.github.io/Minecraft-Forecast"):
    api = HypixelClient("https://api.hypixel.net/v2", "skyprice my-fcf")
    me = api.uuid_of(name).replace("-", "").lower()
    first = api.auctions_page(0)
    pages = int(first.get("totalPages") or 1)
    mine = []
    for p in range(pages):
        data = first if p == 0 else api.auctions_page(p)
        for a in data.get("auctions", []):
            if str(a.get("auctioneer") or "").replace("-", "").lower() == me \
                    or any(str(b.get("bidder") or "").replace("-", "").lower() == me for b in a.get("bids") or []):
                mine.append(a)
    ts = int(first.get("lastUpdated") or time.time() * 1000)

    comp = get_json(f"{site}/data/components.json") or {}
    prices = Prices(comp.get("bazaar") or {}, comp.get("lbin") or {}, comp.get("upgrade_costs") or {})
    docs = {}

    def value(key, iid, attrs):
        if not key:
            return None
        if key not in docs:
            docs[key] = get_json(f"{site}/data/items/{urllib.parse.quote(safe_name(key))}.json") or {}
        d = docs[key]
        model = (d.get("market") or {}).get("model")
        if model:
            try:
                return value_of(model, key, iid, attrs or {}, prices)[0]
            except (KeyError, TypeError):
                pass
        s = d.get("summary") or {}
        return prices.lbin.get(key) or (d.get("market") or {}).get("fair") or s.get("lbin") or s.get("sell") or s.get("avg")

    positions = fcf.positions_from_auctions(mine, me, decode_listing, value, ts)
    wallet, wallet_now = None, None
    key = os.environ.get("HYPIXEL_API_KEY", "").strip()
    if key:
        try:
            w = wallet_from_profiles(api.profiles(me, key), me)
            if w:
                wallet = w["total"]
                wallet_now = {"total": w["total"], "purse": w["purse"], "bank": w["bank"], "personal_bank": w["personal_bank"]}
        except Exception as e:
            print("wallet:", e)
    summary = fcf.summarize(positions, wallet, True)
    positions.sort(key=lambda p: (p["role"] != "bid", p.get("status") != "winning", -(p.get("amount") or 0)))
    out = {"player": name, "updated": ts, "source": "GitHub Actions AH scan",
           "fcf": {"updated": ts, "now": summary, "series": [], "positions": positions}}
    if wallet_now:
        out["now"] = wallet_now
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    Path(out_dir, "positions.json").write_text(json.dumps(out))
    print(f"{name}: {len(positions)} positions ({summary['n_sales']} selling, {summary['n_winning']} winning, "
          f"{summary['n_outbid']} outbid), on the AH {summary['on_ah']:,.0f}, wallet {wallet}")


if __name__ == "__main__":
    main(*sys.argv[1:])
