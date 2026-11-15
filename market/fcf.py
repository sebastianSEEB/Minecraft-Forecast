"""Potential free cash flow (FCF): the coins you'd have if everything you have going on the AH settled.

    Potential FCF = wallet (purse + bank)
                  + ongoing sales        your active listings, after the 1 % claim tax (listing fee already paid)
                  − ongoing bids         coins committed to auctions you're currently winning
                  + expected sale value  what the items you're winning resell for, after listing fee + claim tax
                  + outbid refunds       coins from bids you've lost, waiting to be collected

Hypixel takes a bid's coins from your purse when you place it, so with bids_already_in_purse (default) the
purse already excludes them: the "ongoing bids" line is shown but not subtracted a second time, and outbid
bids come back as refunds. Set bids_already_in_purse = false to subtract bids from the wallet instead.

"Conservative" FCF values your listings at min(listed price, the item's estimated value), because an
overpriced listing may never sell at its price.

Positions come from the public auction list (every full AH scan): auctions you created, and auctions
with a bid from you. Values use market/valuation.py (enchants, stars, recomb, pet level, …).
"""
from __future__ import annotations

import json

from .db import HOUR_MS
from .marketcheck import net_after_fees


def claim_tax(price: float) -> float:
    return price * 0.01 if price > 1_000_000 else 0.0


def item_value(c, key: str | None, item_id: str | None, attrs: dict, prices, models: dict | None = None) -> float | None:
    """This copy's estimated value: fitted model + upgrades, else lowest BIN, else bazaar sell price."""
    from .valuation import value_of
    if not key:
        return None
    model = (models or {}).get(key)
    if model is None:
        row = c.execute("SELECT model FROM models WHERE item_key=?", (key,)).fetchone()
        model = json.loads(row[0]) if row and row[0] else None
    if model:
        try:
            return value_of(model, key, item_id, attrs or {}, prices)[0]
        except (KeyError, TypeError):
            pass
    row = c.execute("SELECT lowest_bin FROM lbin_now WHERE item_key=?", (key,)).fetchone()
    if row and row[0]:
        return float(row[0])
    sold = sorted(r[0] for r in c.execute("SELECT unit_price FROM ah_sale WHERE item_key=? ORDER BY ts DESC LIMIT 30", (key,)))
    if sold:
        return float(sold[len(sold) // 2])                      # median of recent sales (no BIN listed right now)
    row = c.execute("SELECT sell FROM bz_now WHERE product=?", (key,)).fetchone()
    return float(row[0]) if row and row[0] else None


def positions_from_auctions(auctions: list[dict], my_uuid: str, decode, value, now_ms: int) -> list[dict]:
    """auctions: raw Hypixel auction objects that involve me. decode(item_bytes) -> (key, item_id, count, attrs);
    value(key, item_id, attrs) -> per-item value or None."""
    me = my_uuid.replace("-", "").lower()
    out = []
    for a in auctions:
        if a.get("claimed"):
            continue
        try:
            key, iid, cnt, attrs = decode(a.get("item_bytes"))
        except Exception:
            key, iid, cnt, attrs = None, None, 1, {}
        cnt = cnt or 1
        v = value(key, iid, attrs)
        val = v * cnt if v else None
        bids = a.get("bids") or []
        top = float(a.get("highest_bid_amount") or 0) or (max((float(b.get("amount") or 0) for b in bids), default=0.0))
        ended = (a.get("end") or 0) <= now_ms
        base = {"uuid": a.get("uuid"), "item": a.get("item_name") or key, "key": key, "tier": a.get("tier"), "count": cnt,
                "end": a.get("end"), "ended": ended, "value": val}
        if str(a.get("auctioneer") or "").replace("-", "").lower() == me:
            if a.get("bin"):
                gross = float(a.get("starting_bid") or 0)
                status = "listed (BIN)"
            elif top > 0:
                gross, status = top, "has bids"
            else:
                gross, status = float(a.get("starting_bid") or 0), "no bids yet"
            realistic = gross if (status == "has bids" or val is None) else min(gross, val)
            out.append(dict(base, role="sale", bin=bool(a.get("bin")), status=status, amount=gross,
                            net=gross - claim_tax(gross), net_conservative=realistic - claim_tax(realistic),
                            overpriced=val is not None and gross > val * 1.1 and status != "has bids"))
            continue
        mine = [float(b.get("amount") or 0) for b in bids if str(b.get("bidder") or "").replace("-", "").lower() == me]
        if not mine:
            continue
        my_bid = max(mine)
        winning = my_bid >= top - 0.5
        out.append(dict(base, role="bid", bin=False, status="winning" if winning else "outbid", amount=my_bid, top_bid=top,
                        expected_net=net_after_fees(val) if (winning and val) else None,
                        profit=(net_after_fees(val) - my_bid) if (winning and val) else None))
    return out


def summarize(positions: list[dict], wallet_total: float | None, bids_already_in_purse: bool = True) -> dict:
    sales = sum(p["net"] for p in positions if p["role"] == "sale")
    sales_cons = sum(p["net_conservative"] for p in positions if p["role"] == "sale")
    winning = [p for p in positions if p["role"] == "bid" and p["status"] == "winning"]
    bids = sum(p["amount"] for p in winning)
    expected = sum(p["expected_net"] or 0 for p in winning)
    unvalued = sum(1 for p in winning if p["expected_net"] is None)
    refunds = sum(p["amount"] for p in positions if p["role"] == "bid" and p["status"] == "outbid") if bids_already_in_purse else 0.0
    w = wallet_total or 0.0
    minus_bids = 0.0 if bids_already_in_purse else bids
    fcf = w + sales - minus_bids + expected + refunds
    return {"wallet": wallet_total, "sales": sales, "sales_conservative": sales_cons, "bids": bids,
            "bids_subtracted": not bids_already_in_purse, "expected": expected, "refunds": refunds,
            "fcf": fcf, "fcf_conservative": w + sales_cons - minus_bids + expected + refunds,
            "on_ah": fcf - w, "n_sales": sum(1 for p in positions if p["role"] == "sale"), "n_winning": len(winning),
            "n_outbid": sum(1 for p in positions if p["role"] == "bid" and p["status"] == "outbid"), "unvalued_wins": unvalued,
            "expected_profit_on_wins": sum(p["profit"] or 0 for p in winning)}


def latest_wallet(c) -> float | None:
    """Most recent purse + bank (bank carried forward from the last API snapshot if the mod only sent the purse)."""
    row = c.execute("SELECT ts, source, purse, bank, personal_bank, total FROM wallet ORDER BY ts DESC LIMIT 1").fetchone()
    if not row:
        return None
    if row["source"] == "api" or row["bank"] is not None:
        return float(row["total"])
    api = c.execute("SELECT bank, personal_bank FROM wallet WHERE source='api' ORDER BY ts DESC LIMIT 1").fetchone()
    return float(row["purse"]) + float((api["bank"] if api else 0) or 0) + float((api["personal_bank"] if api else 0) or 0)


def store(c, ts: int, positions: list[dict], summary: dict) -> None:
    c.execute("INSERT INTO meta(k, v) VALUES('bids_in_purse', ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v",
              ("0" if summary["bids_subtracted"] else "1",))
    c.execute("DELETE FROM my_positions")
    c.executemany("INSERT OR REPLACE INTO my_positions(uuid, ts, doc) VALUES(?,?,?)",
                  [(p["uuid"], ts, json.dumps(p)) for p in positions])
    c.execute("INSERT OR REPLACE INTO fcf VALUES(?,?,?,?,?,?,?,?)",
              (ts, summary["wallet"], summary["sales"], summary["bids"], summary["expected"], summary["refunds"],
               summary["fcf"], summary["fcf_conservative"]))


def fcf_report(c, now_ms: int) -> dict | None:
    rows = c.execute("SELECT * FROM fcf ORDER BY ts").fetchall()
    if not rows:
        return None
    last = rows[-1]
    positions = [json.loads(r[0]) for r in c.execute("SELECT doc FROM my_positions ORDER BY ts")]
    wallet_now = latest_wallet(c)
    flag = c.execute("SELECT v FROM meta WHERE k='bids_in_purse'").fetchone()
    in_purse = flag is None or flag[0] != "0"
    now = summarize(positions, wallet_now if wallet_now is not None else last["wallet"], in_purse)
    wr = c.execute("SELECT purse FROM wallet ORDER BY ts DESC LIMIT 1").fetchone()
    if wr is not None and wallet_now is not None:
        now["purse"] = float(wr["purse"])
        now["bank"] = max(0.0, wallet_now - float(wr["purse"]))
    by_hour: dict[int, dict] = {}
    for r in rows:
        by_hour[r["ts"] // HOUR_MS] = {"ts": (r["ts"] // HOUR_MS) * HOUR_MS, "fcf": r["fcf"], "fcf_conservative": r["fcf_conservative"],
                                       "wallet": r["wallet"], "on_ah": r["fcf"] - (r["wallet"] or 0), "sales": r["sales"],
                                       "bids": r["bids"], "expected": r["expected"], "refunds": r["refunds"]}
    series = [v for _, v in sorted(by_hour.items())][-24 * 90:]

    def ago(ms):
        older = [p for p in series if p["ts"] <= now_ms - ms]
        return None if not older else now["fcf"] - older[-1]["fcf"]

    positions.sort(key=lambda p: (p["role"] != "bid", p.get("status") != "winning", -(p.get("amount") or 0)))
    return {"updated": last["ts"], "now": now, "change_24h": ago(24 * HOUR_MS), "change_7d": ago(168 * HOUR_MS),
            "series": series, "positions": positions}
