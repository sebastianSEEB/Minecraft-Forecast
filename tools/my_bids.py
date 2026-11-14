"""Your Auction House activity from public data (no API key needed).

    python tools/my_bids.py SpookyHawk

Scans every active auction once:
  * auctions you've bid on: your highest bid, the current top bid, whether you're winning, time left
  * auctions you're selling: bids so far, time left
  * just-ended auctions (the last ~60 s from auctions_ended) where you were the buyer or seller
For older ended auctions, the PC collector's database (ah_sale has buyer/seller) or an API key is needed.
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.hypixel import HypixelClient  # noqa: E402


def fmt(v):
    for d, s in ((1e9, "B"), (1e6, "M"), (1e3, "k")):
        if abs(v) >= d:
            return f"{v / d:.2f}".rstrip("0").rstrip(".") + s
    return f"{v:,.0f}"


def left(ms):
    s = max(0, ms - time.time() * 1000) / 1000
    return "ended" if s <= 0 else f"{int(s // 3600)}h {int(s % 3600 // 60)}m" if s >= 3600 else f"{int(s // 60)}m {int(s % 60)}s"


def main(name):
    api = HypixelClient("https://api.hypixel.net/v2", "skyblock-market my-bids")
    uuid = api.uuid_of(name)
    first = api.auctions_page(0)
    pages = int(first.get("totalPages") or 1)
    bidding, selling = [], []
    for p in range(pages):
        data = first if p == 0 else api.auctions_page(p)
        for a in data.get("auctions", []):
            mine = [b for b in a.get("bids", []) if b.get("bidder") == uuid]
            if mine:
                top = max(a["bids"], key=lambda b: b["amount"])
                bidding.append({"item": a.get("item_name"), "tier": a.get("tier"), "my_bid": max(b["amount"] for b in mine),
                                "top_bid": top["amount"], "winning": top.get("bidder") == uuid, "bids": len(a["bids"]),
                                "end": a.get("end"), "uuid": a.get("uuid")})
            if a.get("auctioneer") == uuid:
                selling.append({"item": a.get("item_name"), "bin": a.get("bin"), "price": a.get("starting_bid"),
                                "top_bid": a.get("highest_bid_amount") or 0, "bids": len(a.get("bids", [])),
                                "end": a.get("end"), "uuid": a.get("uuid")})
    ended = api.auctions_ended()
    recent = [{"role": "bought" if e.get("buyer") == uuid else "sold", "price": e.get("price"), "bin": e.get("bin"),
               "auction_id": e.get("auction_id")} for e in ended.get("auctions", []) if uuid in (e.get("buyer"), e.get("seller"))]

    out = [f"## AH activity for {name} ({time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}, {pages} pages scanned)", ""]
    out.append(f"### Bidding on {len(bidding)} auction(s)")
    if bidding:
        out += ["| Item | Your bid | Top bid | Status | Bids | Ends in | Auction |", "|---|---|---|---|---|---|---|"]
        for b in sorted(bidding, key=lambda x: x["end"]):
            out.append(f"| {b['item']} | {fmt(b['my_bid'])} | {fmt(b['top_bid'])} | {'✅ winning' if b['winning'] else '❌ outbid'} "
                       f"| {b['bids']} | {left(b['end'])} | `/viewauction {b['uuid']}` |")
    out += ["", f"### Selling {len(selling)} auction(s)"]
    if selling:
        out += ["| Item | Type | Price / top bid | Bids | Ends in |", "|---|---|---|---|---|"]
        for s in sorted(selling, key=lambda x: x["end"]):
            out.append(f"| {s['item']} | {'BIN' if s['bin'] else 'Auction'} | {fmt(s['top_bid'] or s['price'])} | {s['bids']} | {left(s['end'])} |")
    out += ["", f"### Ended in the last ~60 s: {len(recent)}"] + [f"- {r['role']} for {fmt(r['price'])}" for r in recent]
    text = "\n".join(out)
    print(text)
    print("JSON:" + json.dumps({"bidding": bidding, "selling": selling, "recent_ended": recent}))
    return text


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "SpookyHawk")
