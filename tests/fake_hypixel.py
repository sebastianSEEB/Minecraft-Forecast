"""A stand-in for api.hypixel.net with the same JSON shapes, for offline testing.

    python tests/fake_hypixel.py 8799
then set "api_base": "http://127.0.0.1:8799/v2" in config.json.
"""
import base64
import gzip
import io
import json
import math
import random
import struct
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ------------------------------------------------------------------ NBT writer (test only)

def _w_str(b, s):
    e = s.encode()
    b.write(struct.pack(">H", len(e)) + e)


def _tag_of(v):
    if isinstance(v, int):
        return 3
    if isinstance(v, str):
        return 8
    if isinstance(v, list):
        return 9
    if isinstance(v, dict):
        return 10
    raise TypeError(v)


def _w_payload(b, v, tag):
    if tag == 1:
        b.write(struct.pack(">b", v))
    elif tag == 2:
        b.write(struct.pack(">h", v))
    elif tag == 3:
        b.write(struct.pack(">i", v))
    elif tag == 8:
        _w_str(b, v)
    elif tag == 9:
        inner = _tag_of(v[0]) if v else 0
        b.write(struct.pack(">bi", inner, len(v)))
        for x in v:
            _w_payload(b, x, inner)
    elif tag == 10:
        for k, x in v.items():
            t = x[0] if isinstance(x, tuple) else _tag_of(x)
            x = x[1] if isinstance(x, tuple) else x
            b.write(bytes([t]))
            _w_str(b, k)
            _w_payload(b, x, t)
        b.write(b"\x00")


def item_bytes(extra: dict, count: int = 1, name: str = "Item") -> str:
    """Encode like Hypixel: {i:[{id:short, Count:byte, tag:{ExtraAttributes, display}, Damage:short}]}"""
    item = {"id": (2, 276), "Count": (1, count),
            "tag": {"ExtraAttributes": extra, "display": {"Name": name, "Lore": ["§7fake lore"]}},
            "Damage": (2, 0)}
    b = io.BytesIO()
    b.write(b"\x0a")
    _w_str(b, "")
    _w_payload(b, {"i": [item]}, 10)
    return base64.b64encode(gzip.compress(b.getvalue())).decode()


# ------------------------------------------------------------------ fake market

BAZAAR = {"ENCHANTED_DIAMOND": 1650.0, "ENCHANTED_COAL": 3.1, "BOOSTER_COOKIE": 2_400_000.0,
          "ENCHANTMENT_ULTIMATE_WISE_5": 1_100_000.0, "INK_SACK:3": 6.5}
AH = [  # (extra attributes, base price, count)
    ({"id": "HYPERION"}, 1_050_000_000, 1),
    ({"id": "ASPECT_OF_THE_END"}, 95_000, 1),
    ({"id": "ENCHANTED_BOOK", "enchantments": {"ultimate_legion": 5}}, 31_000_000, 1),
    ({"id": "PET", "petInfo": json.dumps({"type": "ENDER_DRAGON", "tier": "LEGENDARY", "exp": 0.0})}, 520_000_000, 1),
    ({"id": "ENCHANTED_BOOK", "enchantments": {"sharpness": 5, "smite": 5}}, 9_000, 1),     # mixed book
    ({"id": "SUMMONING_EYE"}, 1_200_000, 4),                                                # stack of 4
]


def _price(base, t):
    day = math.sin(2 * math.pi * ((t / 3600) % 24) / 24)
    return base * (1 + 0.04 * day + random.gauss(0, 0.01))


def bazaar_payload():
    now = int(time.time() * 1000)
    prods = {}
    for pid, base in BAZAAR.items():
        buy = _price(base, now / 1000)
        sell = buy * 0.97
        prods[pid] = {"product_id": pid,
                      "sell_summary": [{"amount": 100, "pricePerUnit": sell, "orders": 3}],
                      "buy_summary": [{"amount": 80, "pricePerUnit": buy, "orders": 2}],
                      "quick_status": {"productId": pid, "sellPrice": sell, "sellVolume": 50000, "sellMovingWeek": 7_000_000,
                                       "sellOrders": 120, "buyPrice": buy, "buyVolume": 40000,
                                       "buyMovingWeek": 6_500_000, "buyOrders": 90}}
    return {"success": True, "lastUpdated": now, "products": prods}


def ended_payload():
    now = int(time.time() * 1000)
    auctions = []
    for _ in range(random.randint(5, 15)):
        extra, base, cnt = random.choice(AH)
        auctions.append({"auction_id": uuid.uuid4().hex, "seller": uuid.uuid4().hex, "seller_profile": uuid.uuid4().hex,
                         "buyer": uuid.uuid4().hex, "buyer_profile": uuid.uuid4().hex,
                         "timestamp": now - random.randint(0, 59_000), "price": round(_price(base, now / 1000) * cnt),
                         "bin": random.random() < 0.8, "item_bytes": item_bytes(extra, cnt)})
    return {"success": True, "lastUpdated": now, "auctions": auctions}


ACTIVE = []


def active_page(page):
    if not ACTIVE:
        for i in range(2500):
            extra, base, cnt = random.choice(AH)
            ACTIVE.append({"uuid": uuid.uuid4().hex, "auctioneer": "x", "profile_id": "y", "coop": [],
                           "start": 0, "end": 0, "item_name": "Item", "item_lore": "", "extra": "", "category": "weapon",
                           "tier": "LEGENDARY", "starting_bid": round(base * cnt * random.uniform(0.95, 1.4)),
                           "item_bytes": item_bytes(extra, cnt), "claimed": False, "claimed_bidders": [],
                           "highest_bid_amount": 0, "bin": random.random() < 0.7, "bids": []})
    per = 1000
    pages = math.ceil(len(ACTIVE) / per)
    return {"success": True, "page": page, "totalPages": pages, "totalAuctions": len(ACTIVE),
            "lastUpdated": int(time.time() * 1000), "auctions": ACTIVE[page * per:(page + 1) * per]}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        p = self.path
        if p.startswith("/v2/skyblock/bazaar"):
            body = bazaar_payload()
        elif p.startswith("/v2/skyblock/auctions_ended"):
            body = ended_payload()
        elif p.startswith("/v2/skyblock/auctions"):
            page = int(p.split("page=")[1]) if "page=" in p else 0
            body = active_page(page)
        else:
            self.send_response(404); self.end_headers(); return
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
    print(f"fake Hypixel API on http://127.0.0.1:{port}/v2")
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
