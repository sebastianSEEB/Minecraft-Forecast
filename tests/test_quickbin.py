"""QuickBinJob: a cheaper new listing lowers the lowest BIN; the cheapest selling promotes the 2nd."""
import sys, tempfile, threading, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import fake_hypixel as fake
from market.config import Config
from market.db import Database
from market.collector import QuickBinJob


class FakeAPI:
    def __init__(self, auctions): self.a = auctions
    def auctions_page(self, p): return {"success": True, "lastUpdated": int(time.time() * 1000), "auctions": self.a}


def test_quick_bin():
    db = Database(tempfile.mkdtemp() + "/q.sqlite3")
    c = db.connect()
    now = int(time.time() * 1000)
    c.execute("INSERT INTO lbin_now(item_key, ts, lowest_bin, listings, second_bin) VALUES('HYPERION', ?, 1000, 10, 1100)", (now,))
    c.execute("INSERT INTO lbin_now(item_key, ts, lowest_bin, listings, second_bin) VALUES('ASPECT_OF_THE_END', ?, 500, 5, 550)", (now,))
    # the cheapest AOTE (500) just sold as a BIN
    c.execute("INSERT INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin) VALUES('x', ?, 'ASPECT_OF_THE_END', 'ASPECT_OF_THE_END', 500, 1, 500, 1)", (now,))
    c.commit()
    new = [{"uuid": "n1", "bin": True, "claimed": False, "starting_bid": 900, "item_bytes": fake.item_bytes({"id": "HYPERION"})},
           {"uuid": "n2", "bin": True, "claimed": False, "starting_bid": 1200, "item_bytes": fake.item_bytes({"id": "HYPERION"})}]
    job = QuickBinJob(Config(), db, FakeAPI(new), threading.Event())
    job.tick(c)
    h = c.execute("SELECT lowest_bin, second_bin FROM lbin_now WHERE item_key='HYPERION'").fetchone()
    a = c.execute("SELECT lowest_bin, listings FROM lbin_now WHERE item_key='ASPECT_OF_THE_END'").fetchone()
    assert (h[0], h[1]) == (900, 1000), tuple(h)          # cheaper new listing took over
    assert (a[0], a[1]) == (550, 4), tuple(a)             # 2nd-cheapest promoted after the cheapest sold


if __name__ == "__main__":
    test_quick_bin(); print("ok test_quick_bin")
