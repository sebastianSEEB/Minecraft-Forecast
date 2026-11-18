"""Bazaar misprice / correlation / suggestion checks on synthetic markets (market/bzsignals.py)."""
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.bzsignals import analyse, recipes_from_neu  # noqa: E402

STEP = 2 * 3_600_000
N = 84                    # 7 days of 2-hour bars
WEEK = {"buy_week": 500_000, "sell_week": 500_000}


def walk(seed, n=N, vol=0.01, start=100.0):
    r = random.Random(seed)
    v, out = start, []
    for _ in range(n):
        v *= math.exp(r.gauss(0, vol))
        out.append(v)
    return out


def pts(mids, spread=0.02):
    return [(i * STEP, m * (1 + spread / 2), m * (1 - spread / 2)) for i, m in enumerate(mids)]


def live_of(mid, spread=0.02):
    return dict(WEEK, buy=mid * (1 + spread / 2), sell=mid * (1 - spread / 2))


def test_cheap_product_out_of_line_with_its_peers():
    common = walk(1, vol=0.02)
    noise = [walk(10 + i, vol=0.004, start=1.0) for i in range(4)]
    mids = {f"P{i}": [c * n[k] * 100 for k, c in enumerate(common)] for i, n in enumerate(noise)}
    series = {k: pts(v) for k, v in mids.items()}
    live = {k: live_of(v[-1]) for k, v in mids.items()}
    live["P0"] = live_of(mids["P0"][-1] * 0.85)             # P0 dumped 15 % now, peers didn't
    out = analyse(series, live, STEP)
    cheap = [s for s in out["signals"] if s["type"] == "cheap"]
    assert cheap and cheap[0]["id"] == "P0" and cheap[0]["action"] == "buy", out["signals"]
    assert cheap[0]["conf"] == "medium" and cheap[0]["edge"] > 0.1, cheap[0]      # no proof its dumps bounce yet
    assert any("out of line" in w for w in cheap[0]["why"]), cheap[0]["why"]
    assert out["pairs"] >= 6, out["pairs"]                  # the 4 products all move together
    # same, but P0's earlier dumps (bars 10, 30, 50) were bought back within 2 bars → high confidence
    for b in (10, 30, 50):
        mids["P0"][b] *= 0.88
        mids["P0"][b + 1] *= 0.95
    series["P0"] = pts(mids["P0"])
    out = analyse(series, live, STEP)
    cheap = [s for s in out["signals"] if s["type"] == "cheap"]
    assert cheap and cheap[0]["id"] == "P0" and cheap[0]["conf"] == "high", cheap
    assert any("bounced back 3 of 3" in w for w in cheap[0]["why"]), cheap[0]["why"]


def test_market_wide_drop_is_not_high_confidence():
    common = walk(2, vol=0.02)
    noise = [walk(20 + i, vol=0.004, start=1.0) for i in range(4)]
    mids = {f"P{i}": [c * n[k] * 100 for k, c in enumerate(common)] for i, n in enumerate(noise)}
    series = {k: pts(v) for k, v in mids.items()}
    live = {k: live_of(v[-1] * 0.85) for k, v in mids.items()}   # everything dropped together
    out = analyse(series, live, STEP)
    assert all(s["conf"] != "high" for s in out["signals"] if s["type"] == "cheap"), out["signals"]


def test_uncorrelated_products_make_no_pairs_and_no_signals():
    mids = {f"R{i}": [m * 100 for m in walk(100 + i)] for i in range(6)}
    series = {k: pts(v) for k, v in mids.items()}
    live = {k: live_of(v[-1]) for k, v in mids.items()}
    out = analyse(series, live, STEP)
    assert out["pairs"] == 0 and not out["signals"], (out["pairs"], out["signals"][:2])


def test_pair_laggard():
    a = [m * 100 for m in walk(3, vol=0.02)]
    b = [x * 2 * math.exp(random.Random(i).gauss(0, 0.015)) for i, x in enumerate(a)]
    series = {"A": pts(a), "B": pts(b)}
    live = {"A": live_of(a[-1] * 0.92), "B": live_of(b[-1])}     # A lags its twin by 8 %
    out = analyse(series, live, STEP)
    pair = [s for s in out["signals"] if s["type"] == "pair"]
    assert pair and pair[0]["id"] == "A" and pair[0]["other"] == "B", out["signals"]


def test_follower():
    r = random.Random(5)
    ra = [r.gauss(0, 0.02) for _ in range(N)]
    rb = [0.0] + [0.9 * x + r.gauss(0, 0.004) for x in ra[:-1]]   # B copies A one bar later
    ra[-1] = 0.12                                                   # A just jumped 12 %
    rb[-1] = 0.9 * ra[-2]
    A, B = [100.0], [50.0]
    for i in range(1, N):
        A.append(A[-1] * math.exp(ra[i]))
        B.append(B[-1] * math.exp(rb[i]))
    series = {"A": pts(A), "B": pts(B)}
    live = {"A": live_of(A[-1]), "B": live_of(B[-1])}
    out = analyse(series, live, STEP)
    lead = [c for c in out["correlations"] if c["lag"] == 1]
    assert lead and lead[0]["a"] == "A" and lead[0]["b"] == "B", out["correlations"][:3]
    fol = [s for s in out["signals"] if s["type"] == "follow"]
    assert fol and fol[0]["id"] == "B" and fol[0]["action"] == "buy" and fol[0]["edge"] > 0.05, out["signals"]


def test_craft_ratio():
    raw = [m * 10 for m in walk(7, vol=0.01)]
    ench = [x * 160 * 1.0 for x in raw]                           # normally exactly 160 raw
    series = {"RAW": pts(raw, 0.01), "ENCH": pts(ench, 0.01)}
    live = {"RAW": live_of(raw[-1], 0.01), "ENCH": live_of(ench[-1] * 1.12, 0.01)}
    recipes = recipes_from_neu({"ENCH": [({"RAW": 160}, 1)], "X": [({"RAW": 2, "Y": 1}, 1)]}, ["RAW", "ENCH", "X", "Y"])
    assert recipes == {"ENCH": ("RAW", 160)}, recipes
    out = analyse(series, live, STEP, recipes=recipes)
    craft = [s for s in out["signals"] if s["type"] == "craft"]
    assert craft and craft[0]["edge"] > 0.06, out["signals"]


def test_market_wide_crash_is_one_event():
    """Many products crashing together (a game update) = one event, not a list of 'cheap' buys."""
    mids = {f"F{i}": [m * 100 for m in walk(200 + i)] for i in range(8)}
    series = {k: pts(v) for k, v in mids.items()}
    live = {k: live_of(v[-1] * (0.5 + 0.03 * i)) for i, (k, v) in enumerate(mids.items())}
    out = analyse(series, live, STEP)
    ev = [s for s in out["signals"] if s["type"] == "event"]
    assert len(ev) == 1 and ev[0]["action"] == "avoid" and "8 products" in ev[0]["why"][0], out["signals"]
    assert not [s for s in out["signals"] if s["type"] == "cheap"], out["signals"]


def test_thin_products_are_skipped():
    mids = [m * 100 for m in walk(9)]
    out = analyse({"T": pts(mids)}, {"T": dict(buy=mids[-1] * 0.5, sell=mids[-1] * 0.49, buy_week=100, sell_week=100)}, STEP)
    assert out["products"] == 0 and not out["signals"]


def test_from_collector_db():
    """The PC collector path: hourly bars from the database → same analysis."""
    import tempfile
    from market.db import Database
    from market.bzsignals import from_db
    db = Database(str(Path(tempfile.mkdtemp()) / "m.sqlite3"))
    c = db.connect()
    now_h = 500_000
    common = walk(11, n=120, vol=0.01)
    for i in range(3):
        mids = [x * 100 * (1 + 0.002 * i) for x in common]
        for j, m in enumerate(mids):
            h = now_h - 120 + j
            c.execute("INSERT INTO hourly(series,hour,avg,median,high,low,n,volume) VALUES(?,?,?,?,?,?,?,?)",
                      (f"bz:P{i}:buy", h, m * 1.01, m * 1.01, m, m, 1, 0))
            c.execute("INSERT INTO hourly(series,hour,avg,median,high,low,n,volume) VALUES(?,?,?,?,?,?,?,?)",
                      (f"bz:P{i}:sell", h, m * 0.99, m * 0.99, m, m, 1, 0))
        last = mids[-1] * (0.85 if i == 0 else 1)
        c.execute("INSERT INTO bz_now(product,ts,buy,sell,buy_vol,sell_vol,buy_week,sell_week,buy_orders,sell_orders)"
                  " VALUES(?,?,?,?,?,?,?,?,?,?)", (f"P{i}", now_h * 3_600_000, last * 1.01, last * 0.99, 0, 0, 500_000, 500_000, 5, 5))
    c.commit()
    out = from_db(c, None, now_h * 3_600_000)
    assert out and out["products"] == 3 and out["pairs"] == 3, out and (out["products"], out["pairs"])
    assert any(s["id"] == "P0" and s["type"] == "cheap" for s in out["signals"]), out["signals"]


def test_wealth_lookup_caches_and_sums_coins():
    """GET /wealth/<name>: purse + bank of the selected profile, cached 15 min (market/server.py)."""
    from market.server import wealth_lookup

    class Fake:
        calls = 0

        def uuid_of(self, n):
            self.calls += 1
            return "abc"

        def profiles(self, u, k):
            self.calls += 1
            return {"profiles": [{"selected": True, "cute_name": "Apple", "banking": {"balance": 40e6},
                                  "members": {"abc": {"currencies": {"coin_purse": 5e6}}}}]}
    api, cache = Fake(), {}
    w = wealth_lookup(api, "KEY", "Rich_Guy", cache, now=1000)
    assert w["total"] == 45e6 and w["bank_visible"], w
    wealth_lookup(api, "KEY", "rich_guy", cache, now=1500)
    assert api.calls == 2
    wealth_lookup(api, "KEY", "Rich_Guy", cache, now=1000 + 16 * 60)
    assert api.calls == 3


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
    print("all bzsignals tests passed")
