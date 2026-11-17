"""End-to-end: fake Hypixel API -> collector -> SQLite -> local server -> HUD lines."""
import json, math, os, random, statistics, sys, tempfile, threading, time, urllib.request
from pathlib import Path
from http.server import ThreadingHTTPServer
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import fake_hypixel as fake
from market.config import Config
from market.db import Database, HOUR_MS
from market.collector import start_all, recompute_ah_hours, UPSERT_HOURLY, STATUS
from market.server import serve

FAKE_PORT, PORT = 8799, 8766
threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", FAKE_PORT), fake.H).serve_forever, daemon=True).start()

tmp = tempfile.mkdtemp()
cfg = Config(db_path=f"{tmp}/m.sqlite3", api_base=f"http://127.0.0.1:{FAKE_PORT}/v2",
             bazaar_interval=2, ended_interval=2, active_ah_interval=5, server_port=PORT)
db = Database(cfg.db_path)

# --- backfill 10 days of history so the forecasts have something to chew on
now_h = int(time.time() * 1000) // HOUR_MS
c = db.connect()
rows, touched = [], set()
from market.seasonality import phase_of
walk = 3.1
for h in range(now_h - 24 * 30, now_h):
    walk *= math.exp(random.gauss(0, 0.01))
    for pid, base in fake.BAZAAR.items():
        if pid == "ENCHANTED_COAL":            # pure random walk: must NOT be flagged as seasonal
            p = walk
        else:
            p = fake._price(base, h * 3600) * (1 + 0.0004 * (h - now_h))   # gentle uptrend + daily wave
        if pid == "BOOSTER_COOKIE":            # + a SkyBlock-year cycle (peaks ~Early Summer)
            p *= 1 + 0.06 * math.sin(2 * math.pi * phase_of(h, "skyblock_year") / 124)
        rows.append((f"bz:{pid}:buy", h, p, p, p * 1.01, p * 0.99, 60, None))
        rows.append((f"bz:{pid}:sell", h, p * .97, p * .97, p * .98, p * .96, 60, None))
    for extra, base, cnt in fake.AH:
        from market.items import key_from_extra
        k = key_from_extra(extra)
        for i in range(random.randint(0, 3)):
            ts = h * HOUR_MS + random.randint(0, HOUR_MS - 1)
            price = fake._price(base, ts / 1000) * cnt
            c.execute("INSERT INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin, seller, buyer) VALUES(?,?,?,?,?,?,?,?,?,?)",
                      (f"bf{h}{k}{i}", ts, k, extra["id"], price, cnt, price / cnt, 1, f"s{h}{i}", f"b{h}{i}"))
            touched.add((k, h))
c.executemany(UPSERT_HOURLY, rows)
recompute_ah_hours(c, touched)
c.commit()

stop = threading.Event()
start_all(cfg, db, stop)
serve(cfg, db)
time.sleep(14)

def get(path):
    return json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}", timeout=30).read())

h = get("/health")
print("HEALTH", json.dumps(h, indent=1)[:900])
from market.publish import export_site
st = export_site(cfg, db)
print("EXPORT", st)
seas = json.load(open(f"{tmp}/site/data/seasonality.json"))
found = {(r["k"], r["cycle"]) for r in seas["rows"]}
print("SEASONS", sorted(found))
assert ("BOOSTER_COOKIE", "skyblock_year") in found, found
assert ("ENCHANTED_DIAMOND", "daily") in found, found
assert not any(k == "ENCHANTED_COAL" for k, _ in found), found
hp = [r["hours_to_peak"] for r in seas["rows"]]
assert hp == sorted(hp)
fcs = get("/forecasts")
print("FORECASTS", len(fcs["items"]), "items;", json.dumps(fcs["items"].get("BOOSTER_COOKIE"))[:300])
fc = fcs["items"]["BOOSTER_COOKIE"]["buy"]
assert len(fc["f"]) == 3 and fc["last"] > 0 and fc["rnd"] in ("low", "medium", "high"), fc
doc = get("/itemdata/BOOSTER_COOKIE")
assert doc["stats"]["30d"]["buy"]["avg"] and len(doc["daily"]["buy"]) >= 30, doc["stats"]
lb = get("/lbin?keys=HYPERION,ASPECT_OF_THE_END,NOPE")
print("LBIN", lb, "quick:", STATUS.get("quick_bin"))
assert "HYPERION" in lb and lb["HYPERION"]["lowest"] > 0 and "NOPE" not in lb
assert "quick_bin" in STATUS and "error" not in STATUS["quick_bin"], STATUS.get("quick_bin")
deals = get("/deals")
print("DEALS", len(deals), [(d["item_key"], round(d["discount"], 2)) for d in deals[:3]])
assert isinstance(deals, list)
hyp = get("/itemdata/HYPERION")
print("MARKET HYPERION", {k: hyp["market"][k] for k in ("fair", "resale", "risk", "flags")}, hyp["market"]["listed"])
assert hyp["market"]["listed"]["count"] > 50 and hyp["market"]["fair"] > 9e8
doc2 = json.load(open(f"{tmp}/site/data/items/INK_SACK__3.json"))
assert doc2["k"] == "INK_SACK:3"
for k in ["ENCHANTED_DIAMOND", "BOOSTER_COOKIE", "HYPERION", "PET_ENDER_DRAGON_LEGENDARY",
          "ENCHANTMENT_ULTIMATE_LEGION_5", "SUMMONING_EYE", "ENCHANTED_BOOK", "NOT_A_REAL_ITEM"]:
    r = get(f"/hud/{k}")
    print(f"\n[{k}] kind={r['kind']}")
    for line in r["lines"]:
        print("  " + "".join(s["t"] for s in line))
it = get("/item/SUMMONING_EYE")
assert 1_000_000 < it["day"]["avg"] < 1_400_000, it["day"]        # per-unit, not per stack of 4
assert it["lowest_bin"]["price"] < 1_800_000
fd = get("/item/ENCHANTED_DIAMOND")["forecast"]["buy"]
print("\nENCHANTED_DIAMOND leaderboard:", [(m["model"], round(m["mape"], 2)) for m in fd["horizons"]["24"]["leaderboard"]])
print("search hyp:", get("/search?q=hyp"))
print("history rows:", len(get("/history/bz:ENCHANTED_DIAMOND:buy?hours=48")))
html = urllib.request.urlopen(f"http://127.0.0.1:{PORT}/").read()
assert b"SkyBlock Market" in html
stop.set()
print("\nE2E OK")
