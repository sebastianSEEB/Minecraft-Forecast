"""Potential FCF = wallet + ongoing sales − ongoing bids + expected sale value (+ outbid refunds)."""
import json, sys, tempfile, threading, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.config import Config
from market.db import Database, HOUR_MS
from market.collector import ActiveAHJob
from market.fcf import positions_from_auctions, summarize
from market.marketcheck import net_after_fees
from market.wallet import wallet_report
from tests.fake_hypixel import item_bytes

NOW = int(time.time() * 1000)
ME = "abcdef0123456789abcdef0123456789"


def auctions():
    end = NOW + 3600_000
    return [
        # my BIN listing at 20M (worth ~12M -> overpriced)
        {"uuid": "s1", "auctioneer": ME, "bin": True, "starting_bid": 20e6, "highest_bid_amount": 0, "bids": [], "end": end,
         "item_name": "Hyperion", "tier": "LEGENDARY", "item_bytes": item_bytes({"id": "HYPE"})},
        # my bid auction that already has a 5M bid from someone else
        {"uuid": "s2", "auctioneer": ME, "bin": False, "starting_bid": 1e6, "highest_bid_amount": 5e6,
         "bids": [{"bidder": "other", "amount": 5e6}], "end": end, "item_name": "Thing", "item_bytes": item_bytes({"id": "THING"})},
        # I'm winning at 3M an item worth 6M
        {"uuid": "b1", "auctioneer": "seller1", "bin": False, "starting_bid": 1e6, "highest_bid_amount": 3e6,
         "bids": [{"bidder": "other", "amount": 2e6}, {"bidder": ME, "amount": 3e6}], "end": end, "item_name": "Gizmo",
         "item_bytes": item_bytes({"id": "GIZMO"})},
        # I was outbid (my 1M bid, top is 1.5M)
        {"uuid": "b2", "auctioneer": "seller2", "bin": False, "starting_bid": 5e5, "highest_bid_amount": 1.5e6,
         "bids": [{"bidder": ME, "amount": 1e6}, {"bidder": "other", "amount": 1.5e6}], "end": end, "item_name": "Gadget",
         "item_bytes": item_bytes({"id": "GADGET"})},
        # unrelated
        {"uuid": "x", "auctioneer": "someone", "bin": True, "starting_bid": 9e6, "bids": [], "end": end,
         "item_bytes": item_bytes({"id": "HYPE"})},
    ]


def setup():
    db = Database(tempfile.mkdtemp() + "/d.sqlite3")
    c = db.connect()
    for k, v in (("HYPE", 12e6), ("THING", 4e6), ("GIZMO", 6e6), ("GADGET", 2e6)):
        c.execute("INSERT INTO lbin_now(item_key, ts, lowest_bin, listings) VALUES(?,?,?,?)", (k, NOW, v, 5))
    for i in range(5):                                         # GIZMO has no BIN listed during the scan: valued from sales
        c.execute("INSERT INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin) VALUES(?,?,?,?,?,?,?,?)",
                  (f"g{i}", NOW - i * HOUR_MS, "GIZMO", "GIZMO", 6e6, 1, 6e6, 0))
    c.execute("INSERT INTO wallet VALUES(?,?,?,?,?,?,?,?)", (NOW - 60_000, "api", "SpookyHawk", "Apple", 30e6, 70e6, None, 100e6))
    c.commit()
    return db, c


class FakeApi:
    def __init__(self):
        self.page = {"auctions": auctions(), "totalPages": 1, "lastUpdated": NOW}

    def auctions_page(self, p):
        return self.page

    def uuid_of(self, name):
        assert name == "SpookyHawk"
        return "abcdef01-2345-6789-abcd-ef0123456789"


def test_summary_math():
    vals = {"HYPE": 12e6, "THING": 4e6, "GIZMO": 6e6, "GADGET": 2e6}
    from market.items import decode_listing
    pos = positions_from_auctions(auctions(), ME, decode_listing, lambda k, i, a: vals.get(k), NOW)
    assert {p["uuid"] for p in pos} == {"s1", "s2", "b1", "b2"}
    s = summarize(pos, 100e6, bids_already_in_purse=True)
    sales = (20e6 - 0.2e6) + (5e6 - 0.05e6)                      # claim tax only, listing fee already paid
    assert abs(s["sales"] - sales) < 1
    assert abs(s["sales_conservative"] - ((12e6 - 0.12e6) + (5e6 - 0.05e6))) < 1   # overpriced listing valued at 12M
    assert s["bids"] == 3e6 and abs(s["expected"] - net_after_fees(6e6)) < 1 and s["refunds"] == 1e6
    assert abs(s["fcf"] - (100e6 + sales + net_after_fees(6e6) + 1e6)) < 1   # bid coins already left the purse
    s2 = summarize(pos, 100e6, bids_already_in_purse=False)
    assert abs(s2["fcf"] - (100e6 + sales - 3e6 + net_after_fees(6e6))) < 1  # literal formula
    over = next(p for p in pos if p["uuid"] == "s1")
    assert over["overpriced"] and next(p for p in pos if p["uuid"] == "b1")["status"] == "winning"


def test_scan_stores_and_reports():
    db, c = setup()
    cfg = Config()
    cfg.player_name = "SpookyHawk"
    job = ActiveAHJob(cfg, db, FakeApi(), threading.Event())
    job.tick(c)
    rep = wallet_report(c, NOW + 1000)
    f = rep["fcf"]
    assert f["now"]["n_sales"] == 2 and f["now"]["n_winning"] == 1 and f["now"]["n_outbid"] == 1
    assert f["now"]["wallet"] == 100e6 and abs(f["now"]["expected"] - net_after_fees(6e6)) < 1
    assert f["now"]["expected_profit_on_wins"] > 2e6 and f["now"]["unvalued_wins"] == 0
    assert f["positions"][0]["uuid"] == "b1"                      # winning bids first
    assert len(f["series"]) == 1 and rep["now"]["total"] == 100e6
    json.dumps(rep)


if __name__ == "__main__":
    test_summary_math(); print("ok test_summary_math")
    test_scan_stores_and_reports(); print("ok test_scan_stores_and_reports")
