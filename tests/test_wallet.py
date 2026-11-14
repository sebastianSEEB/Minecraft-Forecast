"""Wallet: profile parsing, mod + API merge, KPIs."""
import json, sys, tempfile, threading, time, urllib.request
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.collector import wallet_from_profiles
from market.config import Config
from market.db import Database, HOUR_MS
from market.server import serve
from market.wallet import wallet_report

UUID = "0123456789abcdef0123456789abcdef"


def test_profile_parsing():
    data = {"profiles": [
        {"profile_id": "a", "cute_name": "Apple", "selected": False, "members": {UUID: {"currencies": {"coin_purse": 5}}}},
        {"profile_id": "b", "cute_name": "Banana", "selected": True, "banking": {"balance": 1_000_000.5},
         "members": {UUID: {"currencies": {"coin_purse": 250_000.25}, "profile": {"bank_account": 50_000}}}}]}
    w = wallet_from_profiles(data, UUID)
    assert w["profile"] == "Banana" and w["purse"] == 250_000.25 and w["bank"] == 1_000_000.5
    assert abs(w["total"] - 1_300_000.75) < 1e-6
    nob = wallet_from_profiles({"profiles": [{"selected": True, "members": {UUID: {"currencies": {"coin_purse": 7}}}}]}, UUID)
    assert nob["bank"] is None and nob["total"] == 7          # Banking API off: purse only


def test_report_and_mod_post():
    cfg = Config(db_path=tempfile.mkdtemp() + "/w.sqlite3", server_port=8771)
    db = Database(cfg.db_path)
    c = db.connect()
    now = int(time.time() * 1000)
    h0 = now // HOUR_MS - 48
    for i in range(48):                                         # +100k per hour, bank fixed at 10M
        c.execute("INSERT INTO wallet VALUES(?,?,?,?,?,?,?,?)", ((h0 + i) * HOUR_MS + 60_000, "api", "SpookyHawk", "Banana",
                                                                  1e6 + i * 1e5, 10e6, None, 11e6 + i * 1e5))
    c.commit()
    serve(cfg, db)
    req = urllib.request.Request("http://127.0.0.1:8771/wallet", data=json.dumps({"purse": 6e6, "player": "SpookyHawk"}).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    assert json.loads(urllib.request.urlopen(req).read())["ok"]
    r = wallet_report(c, int(time.time() * 1000))
    assert r["now"]["purse"] == 6e6 and r["now"]["bank"] == 10e6 and r["now"]["total"] == 16e6, r["now"]   # mod purse + API bank
    assert r["kpi"]["ath"] == 16e6
    assert 2.4e6 <= r["kpi"]["change_24h"] <= 2.7e6, r["kpi"]   # 13.4M a day ago -> 16M now
    assert r["kpi"]["best_hour"] == 0.3e6 and r["kpi"]["worst_hour"] == 0.1e6, r["kpi"]   # last hour: 15.7M -> 16M
    got = json.loads(urllib.request.urlopen("http://127.0.0.1:8771/wallet").read())
    assert got["player"] == "SpookyHawk" and got["hours_tracked"] >= 48


if __name__ == "__main__":
    test_profile_parsing(); print("ok test_profile_parsing")
    test_report_and_mod_post(); print("ok test_report_and_mod_post")
