"""Craft flips from live data: what costs less to craft than it sells for.

    python tools/craft_flips.py NEU_REPO_DIR out_dir [budget]

Recipes: NotEnoughUpdates item repo (items/*.json, crafting recipes, NPC shop prices).
Prices:  Hypixel bazaar (buy-order / sell-offer prices + weekly volume), the whole auction house (lowest BIN of every
         item), and SkyCofl's 7-day sales volume for auction-house outputs on the shortlist.
Costs:   each ingredient at its cheapest source: bazaar buy order, NPC shop, lowest BIN, or crafting it (up to 3 levels).
Revenue: bazaar output sold with a sell offer (−1.25 % tax); auction output listed just under the lowest BIN, after
         the listing fee and claim tax.
Ranked by profit per day if you capture ~10 % of the item's daily sales, limited by the budget.
"""
import glob
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.hypixel import HypixelClient  # noqa: E402
from market.items import decode_listing  # noqa: E402
from market.marketcheck import net_after_fees  # noqa: E402

BZ_TAX = 0.0125
MIN_MARGIN = 0.08
MAX_MARGIN = 1.5


def parse_ing(s):
    if not s:
        return None
    if ":" in s:
        i, n = s.rsplit(":", 1)
        try:
            return i, float(n)
        except ValueError:
            return s, 1.0
    return s, 1.0


def neu_id(i):
    """NEU 'SHARPNESS;6' book ids -> bazaar ENCHANTMENT_SHARPNESS_6; pets keep their own form."""
    if ";" in i:
        name, lvl = i.split(";", 1)
        return f"ENCHANTMENT_{name}_{lvl}"
    return i


def load_recipes(repo):
    recipes, npc, req, soulbound, names = {}, {}, {}, set(), {}
    for f in glob.glob(os.path.join(repo, "items", "*.json")):
        try:
            d = json.load(open(f, encoding="utf-8"))
        except Exception:
            continue
        iid = neu_id(d.get("internalname", ""))
        names[iid] = d.get("displayname", iid)
        lore = " ".join(d.get("lore") or [])
        if "Soulbound" in lore:
            soulbound.add(iid)
        if d.get("crafttext"):
            req[iid] = d["crafttext"].replace("Requires: ", "")
        cands = []
        if isinstance(d.get("recipe"), dict):
            cands.append(dict(d["recipe"], _out=iid))
        for r in d.get("recipes") or []:
            if r.get("type") in (None, "crafting"):
                cands.append(dict(r, _out=neu_id(r.get("overrideOutputId") or iid)))
            elif r.get("type") == "npc_shop":
                res = parse_ing(r.get("result", ""))
                cost = [parse_ing(c) for c in r.get("cost") or []]
                if res and cost and all(c and c[0] == "SKYBLOCK_COIN" for c in cost):
                    unit = sum(c[1] for c in cost) / max(res[1], 1)
                    rid = neu_id(res[0])
                    npc[rid] = min(npc.get(rid, 1e18), unit)
        for r in cands:
            ings = {}
            for slot in ("A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"):
                p = parse_ing(r.get(slot, ""))
                if p:
                    k = neu_id(p[0])
                    ings[k] = ings.get(k, 0) + p[1]
            if ings:
                out = r["_out"]
                cnt = float(r.get("count") or 1)
                recipes.setdefault(out, []).append((ings, cnt))
    return recipes, npc, req, soulbound, names


def scan_ah(api):
    first = api.auctions_page(0)
    pages = int(first.get("totalPages") or 1)
    lbin, second, listed = {}, {}, {}
    for p in range(pages):
        data = first if p == 0 else api.auctions_page(p)
        for a in data.get("auctions", []):
            if not a.get("bin") or a.get("claimed"):
                continue
            try:
                key, _, cnt, attrs = decode_listing(a.get("item_bytes"))
            except Exception:
                continue
            if not key or (attrs and (attrs.get("e") or attrs.get("r") or attrs.get("st") or attrs.get("hpb"))):
                continue                       # upgraded copies aren't what a fresh craft competes with
            unit = float(a.get("starting_bid") or 0) / max(cnt or 1, 1)
            listed[key] = listed.get(key, 0) + 1
            if unit < lbin.get(key, 1e18):
                second[key] = lbin.get(key, 1e18)
                lbin[key] = unit
            elif unit < second.get(key, 1e18):
                second[key] = unit
    return lbin, {k: v for k, v in second.items() if v < 1e18}, listed


def cofl_week(tag):
    """(sold in 7 days, volume-weighted average price) from SkyCofl."""
    try:
        req = urllib.request.Request(f"https://sky.coflnet.com/api/item/price/{tag}/history/week",
                                     headers={"User-Agent": "NotEnoughFlips data"})
        with urllib.request.urlopen(req, timeout=15) as r:
            pts = json.loads(r.read().decode())
        vol = sum(float(p.get("volume") or 0) for p in pts or [])
        pv = sum(float(p.get("volume") or 0) * float(p.get("avg") or 0) for p in pts or [])
        return vol, (pv / vol if vol else None)
    except Exception:
        return None, None


def main(repo, out_dir, budget="20000000"):
    budget = float(budget)
    api = HypixelClient("https://api.hypixel.net/v2", "NotEnoughFlips data")
    recipes, npc, req, soulbound, names = load_recipes(repo)
    products = api.bazaar().get("products") or {}
    bz = {}
    for pid, p in products.items():
        q = p.get("quick_status") or {}
        if q.get("buyPrice") and q.get("sellPrice"):
            bz[pid] = {"offer": float(q["buyPrice"]), "order": float(q["sellPrice"]),
                       "buy_week": float(q.get("buyMovingWeek") or 0), "sell_week": float(q.get("sellMovingWeek") or 0)}
    lbin, second, listed = scan_ah(api)
    print(f"{len(recipes)} craftable items, {len(bz)} bazaar products, {len(lbin)} auction items")

    memo = {}

    def cost(i, depth=0):
        """(unit cost, how) for an ingredient: cheapest of bazaar buy order, NPC, lowest BIN, crafting it."""
        if (i, depth) in memo:
            return memo[(i, depth)]
        best = (None, None)
        opts = []
        if i in bz and bz[i]["offer"] > 0:
            b = bz[i]
            # a buy order only fills when the spread is tight; otherwise you pay the instant-buy price
            if b["order"] >= 0.9 * b["offer"] and b["sell_week"] >= 7 * 24 * 10:
                opts.append((b["order"] + 0.1, "bazaar order"))
            else:
                opts.append((b["offer"], "bazaar instant buy"))
        elif i in npc:
            opts.append((npc[i], "NPC"))
        if i in lbin:
            opts.append((lbin[i], "lowest BIN"))
        if depth < 3 and i in recipes:
            for ings, cnt in recipes[i]:
                c = craft_cost(ings, depth + 1)
                if c is not None:
                    opts.append((c / cnt, "craft"))
        if opts:
            best = min(opts)
        memo[(i, depth)] = best
        return best

    def craft_cost(ings, depth):
        total = 0.0
        for k, n in ings.items():
            c, _ = cost(k, depth)
            if c is None:
                return None
            total += c * n
        return total

    rows = []
    for out, rs in recipes.items():
        if out in soulbound:
            continue
        for ings, cnt in rs:
            c = craft_cost(ings, 1)
            if c is None or c <= 0 or c > budget:
                continue
            per = c / cnt
            if out in bz:
                b = bz[out]
                # sell offer just under the cheapest one, but not above 1.3× the best buy order (wide = thin market)
                sell = min(b["offer"] - 0.1, b["order"] * 1.3) if b["order"] > 0 else b["offer"] - 0.1
                net = sell * (1 - BZ_TAX)
                per_day = b["buy_week"] / 7
                where = "bazaar"
            elif out in lbin:
                sell = lbin[out] * 0.99 if out not in second else min(lbin[out], second[out]) * 0.99
                net = net_after_fees(sell)
                per_day = None
                where = "AH"
            else:
                continue
            profit = net - per
            margin = profit / per
            if margin < MIN_MARGIN or margin > MAX_MARGIN:
                continue                          # > 150 % is almost always bad data or a thin/manipulated market
            parts = []
            for k, n in sorted(ings.items(), key=lambda kv: -kv[1] * (cost(kv[0], 1)[0] or 0)):
                uc, how = cost(k, 1)
                parts.append({"id": k, "n": n, "unit": round(uc, 1), "how": how})
            rows.append({"item": out, "name": names.get(out, out), "where": where, "cost": round(per), "sell": round(sell),
                         "profit": round(profit), "margin": round(margin, 3), "sales_per_day": per_day,
                         "listed": listed.get(out), "requires": req.get(out), "ingredients": parts, "makes": cnt})
    # AH outputs: how many actually sell (SkyCofl), for the most promising ones only
    ah = sorted([r for r in rows if r["where"] == "AH"], key=lambda r: -r["profit"])[:80]
    for r in ah:
        v, avg = cofl_week(r["item"])
        r["sales_per_day"] = None if v is None else v / 7
        if avg:                                   # never assume it sells above what it actually sold for
            r["sell"] = round(min(r["sell"], avg))
            r["profit"] = round(net_after_fees(r["sell"]) - r["cost"])
            r["margin"] = round(r["profit"] / r["cost"], 3)
        time.sleep(0.5)
    rows = [r for r in rows if MIN_MARGIN <= r["margin"] <= MAX_MARGIN]
    good = []
    for r in rows:
        spd = r["sales_per_day"]
        if spd is None or spd < 1:
            continue
        crafts = min(spd * 0.10, budget / max(r["cost"], 1))         # ~10 % of the market, within your budget
        r["profit_per_day"] = round(r["profit"] * crafts)
        r["crafts_per_day"] = round(crafts, 1)
        good.append(r)
    good.sort(key=lambda r: -r["profit_per_day"])
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    Path(out_dir, "craft_flips.json").write_text(json.dumps({"updated": int(time.time() * 1000), "budget": budget,
                                                              "flips": good[:100]}))
    def f(v):
        return f"{v/1e6:.2f}M" if abs(v) >= 1e6 else f"{v/1e3:.1f}k" if abs(v) >= 1e3 else f"{v:.0f}"
    print("| # | Item | Sell on | Cost | Sells for | Profit/craft | Margin | Sold/day | Profit/day (10% share) | Requires |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for i, r in enumerate(good[:30], 1):
        print(f"| {i} | {r['name']} | {r['where']} | {f(r['cost'])} | {f(r['sell'])} | {f(r['profit'])} | {r['margin']*100:.0f}% | "
              f"{r['sales_per_day']:.0f} | {f(r['profit_per_day'])} | {r['requires'] or ''} |")
    print()
    for r in good[:12]:
        print(r["name"], "=", ", ".join(f"{p['n']:g}× {p['id']} @ {f(p['unit'])} ({p['how']})" for p in r["ingredients"]))


if __name__ == "__main__":
    main(*sys.argv[1:])
