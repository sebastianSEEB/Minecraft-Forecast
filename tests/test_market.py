"""Market check: fair value from sold + listed prices, resale cap, manipulation flags."""
import sys, tempfile, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.db import Database, HOUR_MS
from market.collector import UPSERT_HOURLY
from market.marketcheck import auction_check, bazaar_check

NOW = int(time.time() * 1000)
H = NOW // HOUR_MS
db = Database(tempfile.mkdtemp() + "/t.sqlite3")
c = db.connect()


def sale(key, i, price, seller="s%d", buyer="b%d", hours_ago=1):
    c.execute("INSERT INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin, seller, buyer) VALUES(?,?,?,?,?,?,?,?,?,?)",
              (f"{key}{i}{seller}", NOW - hours_ago * HOUR_MS, key, key, price, 1, price, 1,
               seller % i if "%" in seller else seller, buyer % i if "%" in buyer else buyer))


def listing(key, lbin, n, second, p25, med, sellers, top):
    c.execute("INSERT OR REPLACE INTO lbin_now(item_key, ts, lowest_bin, listings, second_bin, p25, median_listed, sellers, top_share) VALUES(?,?,?,?,?,?,?,?,?)",
              (key, NOW, lbin, n, second, p25, med, sellers, top))


def history(series, value, hours=720, n=1):
    c.executemany(UPSERT_HOURLY, [(series, h, value, value, value, value, n, None) for h in range(H - hours, H)])


# NORMAL item: sales ~100, listings from 98
for i in range(30): sale("NORMAL", i, 100 + (i % 5))
listing("NORMAL", 98, 20, 99, 101, 110, 15, 0.1)
history("ah:NORMAL", 101); history("lbin:NORMAL", 99); history("listn:NORMAL", 20)

# WASH: real sales ~100, plus 8 fake sales at 300 between the same two players
for i in range(10): sale("WASH", i, 100)
for i in range(8): sale("WASH", 100 + i, 300, seller="alt_seller", buyer="alt_buyer")
listing("WASH", 99, 10, 100, 102, 110, 8, 0.2)

# CORNERED: usually 40 listings at ~100, now 6 listings at 160
for i in range(20): sale("CORNER", i, 150)
listing("CORNER", 160, 6, 165, 170, 180, 2, 0.83)
history("ah:CORNER", 100); history("lbin:CORNER", 100); history("listn:CORNER", 40)

# THIN: 2 sales
sale("THIN", 1, 5000); sale("THIN", 2, 5200)
listing("THIN", 5100, 2, 5300, 5100, 5200, 2, 0.5)

# BAZAAR spike + thin book
c.execute("INSERT INTO bz_now VALUES(?,?,?,?,?,?,?,?,?,?)", ("BZ_SPIKE", NOW, 200, 120, 10, 10, 300, 300, 2, 5))
history("bz:BZ_SPIKE:buy", 100, hours=200, n=60)
c.commit()


def codes(r): return {f["code"] for f in r["flags"]}


def test_normal_item_is_clean():
    r = auction_check(c, "NORMAL", NOW)
    assert r["risk"] == 0, r["flags"]
    assert 99 <= r["fair"] <= 103, r
    assert r["resale"] <= 99 * 0.99 + 1e-9          # capped by the 2nd-cheapest BIN


def test_wash_trades_flagged_and_ignored():
    r = auction_check(c, "WASH", NOW)
    assert "wash" in codes(r) and r["risk"] == 2
    assert r["wash_sales"] == 8
    assert r["fair"] < 120, r["fair"]             # fake 300-coin sales don't inflate fair value


def test_cornering_flagged():
    r = auction_check(c, "CORNER", NOW)
    assert "cornering" in codes(r), r["flags"]
    assert "dominant" in codes(r)
    assert "spike" in codes(r)


def test_thin_market_flagged():
    assert "thin" in codes(auction_check(c, "THIN", NOW))


def test_bazaar_spike_and_thin_book():
    r = bazaar_check(c, "BZ_SPIKE", NOW)
    assert {"spike", "thin_book", "spread"} <= codes(r), r


if __name__ == "__main__":
    for n, f in list(globals().items()):
        if n.startswith("test_"):
            f(); print("ok", n)
