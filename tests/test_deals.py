"""Deal finder: flags BINs >= 20 % under fair value on 1M+ items, skips cheap/illiquid/manipulated/unprofitable ones."""
import sys, tempfile, threading, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.config import Config
from market.db import Database, HOUR_MS
from market.collector import ActiveAHJob, UPSERT_HOURLY

NOW = int(time.time() * 1000)
H = NOW // HOUR_MS


def setup():
    db = Database(tempfile.mkdtemp() + "/d.sqlite3")
    c = db.connect()
    def sales(key, price, n, wash=False):
        for i in range(n):
            s, b = ("alt1", "alt2") if wash else (f"s{i}", f"b{i}")
            c.execute("INSERT INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin, seller, buyer) VALUES(?,?,?,?,?,?,?,?,?,?)",
                      (f"{key}{i}{wash}", NOW - (i + 1) * HOUR_MS, key, key, price, 1, price, 1, s, b))
    def listed(key, lowest, second, p25, n):
        c.execute("INSERT INTO lbin_now(item_key, ts, lowest_bin, listings, second_bin, p25, median_listed, sellers, top_share) VALUES(?,?,?,?,?,?,?,?,?)",
                  (key, NOW, lowest, n, second, p25, p25, n, 0.1))
    sales("BIG", 10e6, 20); listed("BIG", 7e6, 10.2e6, 10.1e6, 15)            # one listing 30 % under 10M
    sales("CHEAP", 200e3, 20); listed("CHEAP", 100e3, 200e3, 200e3, 15)        # 50 % under, but worth < 1M
    sales("RARE", 50e6, 1); listed("RARE", 30e6, 50e6, 50e6, 3)                # barely sells
    sales("SMALLGAP", 10e6, 20); listed("SMALLGAP", 8.5e6, 10e6, 10e6, 15)     # only 15 % under
    sales("WASHY", 10e6, 6); sales("WASHY", 30e6, 10, wash=True); listed("WASHY", 6e6, 10e6, 10e6, 10)
    c.commit()
    return db, c


def test_deals():
    db, c = setup()
    job = ActiveAHJob(Config(), db, None, threading.Event())
    listings = {
        "BIG": [(7e6, "x", "u1", 1, 7e6), (10.2e6, "y", "u2", 1, 10.2e6), (10.5e6, "z", "u3", 1, 10.5e6)],
        "CHEAP": [(100e3, "x", "u4", 1, 100e3), (200e3, "y", "u5", 1, 200e3)],
        "RARE": [(30e6, "x", "u6", 1, 30e6), (50e6, "y", "u7", 1, 50e6)],
        "SMALLGAP": [(8.5e6, "x", "u8", 1, 8.5e6), (10e6, "y", "u9", 1, 10e6)],
        "WASHY": [(6e6, "x", "u10", 1, 6e6), (10e6, "y", "u11", 1, 10e6)],
    }
    n = job.find_deals(c, listings, NOW)
    got = {r["item_key"]: dict(r) for r in c.execute("SELECT * FROM deals")}
    assert set(got) == {"BIG"}, got.keys()
    d = got["BIG"]
    assert d["uuid"] == "u1" and abs(d["discount"] - 0.3) < 0.02, d
    assert 2.5e6 < d["profit"] < 3.0e6, d["profit"]                         # resell ~10.1M after fees
    # next scan: same listing keeps its first_seen; a vanished listing is removed
    first = d["first_seen"]
    job.find_deals(c, listings, NOW + 300_000)
    assert c.execute("SELECT first_seen FROM deals WHERE uuid='u1'").fetchone()[0] == first
    job.find_deals(c, {"BIG": listings["BIG"][1:]}, NOW + 600_000)
    assert c.execute("SELECT COUNT(*) FROM deals").fetchone()[0] == 0


if __name__ == "__main__":
    test_deals(); print("ok test_deals")
