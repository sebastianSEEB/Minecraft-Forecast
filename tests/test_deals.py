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
        "BIG": [(7e6, "x", "u1", 1, 7e6, None, {}), (10.2e6, "y", "u2", 1, 10.2e6, None, {}), (10.5e6, "z", "u3", 1, 10.5e6, None, {})],
        "CHEAP": [(100e3, "x", "u4", 1, 100e3, None, {}), (200e3, "y", "u5", 1, 200e3, None, {})],
        "RARE": [(30e6, "x", "u6", 1, 30e6, None, {}), (50e6, "y", "u7", 1, 50e6, None, {})],
        "SMALLGAP": [(8.5e6, "x", "u8", 1, 8.5e6, None, {}), (10e6, "y", "u9", 1, 10e6, None, {})],
        "WASHY": [(6e6, "x", "u10", 1, 6e6, None, {}), (10e6, "y", "u11", 1, 10e6, None, {})],
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





def test_pet_levels_matter():
    """A level-1 pet at a normal level-1 price is NOT a deal; a level-100 at that price IS."""
    db, c = setup()
    lvl100 = 25_353_230
    for i in range(10):
        for lvl_xp, price in ((0, 50e6), (lvl100, 150e6)):
            c.execute("INSERT INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin, seller, buyer, attrs) "
                      "VALUES(?,?,?,?,?,?,?,?,?,?,?)", (f"p{i}{lvl_xp}", NOW - (i + 1) * HOUR_MS, "PET_GRIFFIN_LEGENDARY", "PET",
                                                         price, 1, price, 1, f"s{i}{lvl_xp}", f"b{i}{lvl_xp}",
                                                         '{"pet":{"xp":%d}}' % lvl_xp))
    c.execute("INSERT INTO lbin_now(item_key, ts, lowest_bin, listings, second_bin, p25, median_listed, sellers, top_share) "
              "VALUES('PET_GRIFFIN_LEGENDARY', ?, 48e6, 4, 50e6, 50e6, 100e6, 4, 0.25)", (NOW,))
    c.commit()
    job = ActiveAHJob(Config(), db, None, threading.Event())
    listings = {"PET_GRIFFIN_LEGENDARY": [
        (48e6, "a", "lvl1", 1, 48e6, "PET", {"pet": {"xp": 0}}),
        (52e6, "b", "lvl1b", 1, 52e6, "PET", {"pet": {"xp": 0}}),
        (100e6, "c", "lvl100", 1, 100e6, "PET", {"pet": {"xp": lvl100}}),
        (155e6, "d", "lvl100b", 1, 155e6, "PET", {"pet": {"xp": lvl100}}),
    ]}
    job.find_deals(c, listings, NOW)
    got = {r["uuid"]: dict(r) for r in c.execute("SELECT * FROM deals")}
    assert set(got) == {"lvl100"}, got.keys()
    assert abs(got["lvl100"]["fair"] - 150e6) < 10e6, got["lvl100"]


if __name__ == "__main__":
    test_deals(); print("ok test_deals")
    test_pet_levels_matter(); print("ok test_pet_levels_matter")
