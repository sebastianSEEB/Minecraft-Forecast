"""Background jobs that poll Hypixel and fill the database.

  BazaarJob   every 60 s   full bazaar (≈1,900 products): latest prices + hourly bars
  EndedJob    every 60 s   auctions_ended: every finished auction = one real sale price
  ActiveAHJob every 5 min  all active auctions: lowest BIN per item
  Maintenance every hour   retention clean-up
"""
from __future__ import annotations

import logging
import statistics
import threading
import time
import traceback

from .config import Config
from .db import HOUR_MS, Database
from .hypixel import HypixelClient
import json

from .items import decode_listing, key_and_count

log = logging.getLogger("collector")

STATUS: dict[str, dict] = {}     # shown on /health


def _status(job: str, **kw):
    STATUS.setdefault(job, {}).update(kw, updated=int(time.time() * 1000))


class StreamingBars:
    """Hourly avg/high/low for series that are sampled (bazaar prices, lowest BIN).

    Every sample counts equally, so the hourly average is the mean of all polls in
    that hour. If the program restarts mid-hour, the bar is resumed from the DB.
    """

    def __init__(self):
        self.acc: dict[str, list] = {}     # series -> [hour, sum, n, high, low]

    def add(self, c, series: str, hour: int, value: float) -> tuple:
        a = self.acc.get(series)
        if a is None or a[0] != hour:
            row = c.execute("SELECT avg, n, high, low FROM hourly WHERE series=? AND hour=?", (series, hour)).fetchone()
            a = [hour, row["avg"] * row["n"], row["n"], row["high"], row["low"]] if row else [hour, 0.0, 0, value, value]
            self.acc[series] = a
        a[1] += value
        a[2] += 1
        a[3] = max(a[3], value)
        a[4] = min(a[4], value)
        avg = a[1] / a[2]
        return (series, hour, avg, avg, a[3], a[4], a[2], None)


UPSERT_HOURLY = """INSERT INTO hourly(series, hour, avg, median, high, low, n, volume) VALUES(?,?,?,?,?,?,?,?)
ON CONFLICT(series, hour) DO UPDATE SET avg=excluded.avg, median=excluded.median, high=excluded.high,
low=excluded.low, n=excluded.n, volume=excluded.volume"""


class Job(threading.Thread):
    name_ = "job"
    interval = 60

    def __init__(self, cfg: Config, db: Database, api: HypixelClient, stop: threading.Event):
        super().__init__(daemon=True, name=self.name_)
        self.cfg, self.db, self.api, self.stop_ev = cfg, db, api, stop

    def run(self):
        c = self.db.connect()
        while not self.stop_ev.is_set():
            t0 = time.time()
            try:
                self.tick(c)
            except Exception as e:  # keep running whatever happens
                log.error("%s failed: %s\n%s", self.name_, e, traceback.format_exc())
                _status(self.name_, error=str(e))
            self.stop_ev.wait(max(1.0, self.interval - (time.time() - t0)))

    def tick(self, c):
        raise NotImplementedError


class BazaarJob(Job):
    name_ = "bazaar"

    def __init__(self, *a):
        super().__init__(*a)
        self.interval = self.cfg.bazaar_interval
        self.bars = StreamingBars()
        self.last_raw: dict[str, tuple] = {}
        self.last_ts = None

    def tick(self, c):
        data = self.api.bazaar()
        ts = int(data.get("lastUpdated") or time.time() * 1000)
        if ts == self.last_ts:
            return
        self.last_ts = ts
        hour = ts // HOUR_MS
        now_rows, raw_rows, bar_rows = [], [], []
        keep_raw = self.cfg.keep_raw_bazaar_days > 0
        for pid, prod in data.get("products", {}).items():
            q = prod.get("quick_status") or {}
            buy, sell = float(q.get("buyPrice") or 0), float(q.get("sellPrice") or 0)
            vals = (buy, sell, int(q.get("buyVolume") or 0), int(q.get("sellVolume") or 0),
                    int(q.get("buyMovingWeek") or 0), int(q.get("sellMovingWeek") or 0),
                    int(q.get("buyOrders") or 0), int(q.get("sellOrders") or 0))
            now_rows.append((pid, ts) + vals)
            if keep_raw:
                prev = self.last_raw.get(pid)
                if prev is None or prev[1] != vals or ts - prev[0] >= 30 * 60_000:
                    raw_rows.append((pid, ts) + vals)
                    self.last_raw[pid] = (ts, vals)
            if buy > 0:
                bar_rows.append(self.bars.add(c, f"bz:{pid}:buy", hour, buy))
            if sell > 0:
                bar_rows.append(self.bars.add(c, f"bz:{pid}:sell", hour, sell))
        with self.db.write_lock, c:
            c.executemany("INSERT OR REPLACE INTO bz_now VALUES(?,?,?,?,?,?,?,?,?,?)", now_rows)
            if raw_rows:
                c.executemany("INSERT OR IGNORE INTO bz_raw VALUES(?,?,?,?,?,?,?,?,?,?)", raw_rows)
            c.executemany(UPSERT_HOURLY, bar_rows)
            Database.set_meta(c, "bazaar_last", ts)
        _status(self.name_, last_ts=ts, products=len(now_rows), raw_written=len(raw_rows))
        log.info("bazaar: %d products, %d raw changes", len(now_rows), len(raw_rows))


def recompute_ah_hours(c, touched: set[tuple[str, int]]):
    rows = []
    for key, hour in touched:
        sales = c.execute("SELECT unit_price, count FROM ah_sale WHERE item_key=? AND ts>=? AND ts<?",
                          (key, hour * HOUR_MS, (hour + 1) * HOUR_MS)).fetchall()
        if not sales:
            continue
        prices = [r["unit_price"] for r in sales]
        rows.append((f"ah:{key}", hour, sum(prices) / len(prices), statistics.median(prices),
                     max(prices), min(prices), len(prices), sum(r["count"] for r in sales)))
    c.executemany(UPSERT_HOURLY, rows)


class EndedJob(Job):
    name_ = "auctions_ended"

    def __init__(self, *a):
        super().__init__(*a)
        self.interval = self.cfg.ended_interval

    def tick(self, c):
        data = self.api.auctions_ended()
        rows, touched, bad = [], set(), 0
        for a in data.get("auctions", []):
            try:
                key, item_id, count, attrs = decode_listing(a["item_bytes"])
            except Exception:
                bad += 1
                continue
            if not key:
                continue
            price = float(a.get("price") or 0)
            if price <= 0:
                continue
            ts = int(a.get("timestamp") or data.get("lastUpdated") or time.time() * 1000)
            rows.append((a["auction_id"], ts, key, item_id, price, count, price / count, int(bool(a.get("bin"))),
                         a.get("seller"), a.get("buyer"), json.dumps(attrs, separators=(",", ":")) if attrs else None))
        new = 0
        with self.db.write_lock, c:
            for r in rows:
                cur = c.execute("INSERT OR IGNORE INTO ah_sale(auction_id, ts, item_key, item_id, price, count, unit_price, bin, seller, buyer, attrs) "
                                "VALUES(?,?,?,?,?,?,?,?,?,?,?)", r)
                if cur.rowcount:
                    new += 1
                    touched.add((r[2], r[1] // HOUR_MS))
            recompute_ah_hours(c, touched)
            Database.set_meta(c, "ended_last", data.get("lastUpdated", 0))
        _status(self.name_, last_ts=data.get("lastUpdated"), new_sales=new, undecodable=bad)
        log.info("auctions_ended: %d new sales", new)


class ActiveAHJob(Job):
    name_ = "active_auctions"

    def __init__(self, *a):
        super().__init__(*a)
        self.interval = self.cfg.active_ah_interval
        self.bars = StreamingBars()
        self.decoded: dict[str, tuple] = {}    # auction uuid -> (key, count); listings don't change
        self.fair_cache: dict[str, tuple] = {} # item key -> (time, auction_check result)

    def tick(self, c):
        first = self.api.auctions_page(0)
        ts = int(first.get("lastUpdated") or time.time() * 1000)
        pages = int(first.get("totalPages") or 1)
        listings: dict[str, list] = {}       # key -> [(unit price, seller, uuid, count, total price), ...]
        seen = set()
        for p in range(pages):
            data = first if p == 0 else self.api.auctions_page(p)
            for a in data.get("auctions", []):
                if not a.get("bin") or a.get("claimed"):
                    continue
                uid = a.get("uuid")
                seen.add(uid)
                if uid not in self.decoded:
                    try:
                        k, iid, cnt, attrs = decode_listing(a["item_bytes"])
                    except Exception:
                        k, iid, cnt, attrs = None, None, 1, {}
                    self.decoded[uid] = (k, cnt, iid, attrs)
                k, cnt, iid, attrs = self.decoded[uid]
                if not k:
                    continue
                unit = float(a.get("starting_bid") or 0) / cnt
                if unit <= 0:
                    continue
                listings.setdefault(k, []).append((unit, a.get("auctioneer"), uid, cnt, float(a.get("starting_bid") or 0), iid, attrs))
        self.decoded = {u: v for u, v in self.decoded.items() if u in seen}
        hour = ts // HOUR_MS
        now_rows, bar_rows = [], []
        for k, lst in listings.items():
            prices = sorted(x[0] for x in lst)
            n = len(prices)
            per_seller: dict = {}
            for _, sel, *_ in lst:
                per_seller[sel] = per_seller.get(sel, 0) + 1
            now_rows.append((k, ts, prices[0], n, prices[1] if n > 1 else None, prices[int(0.25 * (n - 1))],
                             statistics.median(prices), len(per_seller), max(per_seller.values()) / n))
            bar_rows.append(self.bars.add(c, f"lbin:{k}", hour, prices[0]))
            bar_rows.append(self.bars.add(c, f"listn:{k}", hour, float(n)))
        with self.db.write_lock, c:
            c.execute("DELETE FROM lbin_now")
            c.executemany("INSERT INTO lbin_now(item_key, ts, lowest_bin, listings, second_bin, p25, median_listed, sellers, top_share) "
                          "VALUES(?,?,?,?,?,?,?,?,?)", now_rows)
            c.executemany(UPSERT_HOURLY, bar_rows)
            Database.set_meta(c, "active_last", ts)
        n_deals = self.find_deals(c, listings, ts)
        _status(self.name_, last_ts=ts, pages=pages, items=len(listings), deals=n_deals)
        log.info("active auctions: %d pages, %d distinct BIN items, %d deals", pages, len(listings), n_deals)

    def fit_models(self, c, listings: dict, ts: int, prices) -> dict:
        """Clean-base / pet-level models per item, refitted every 30 min from 72 h of sales + current listings."""
        from .valuation import fit_model
        models = {}
        cached = {r["item_key"]: (r["ts"], r["model"]) for r in c.execute("SELECT item_key, ts, model FROM models")}
        rows = []
        for k, lst in listings.items():
            hit = cached.get(k)
            if hit and ts - hit[0] < 1_800_000:
                models[k] = json.loads(hit[1]) if hit[1] else None
                continue
            sales = c.execute("SELECT unit_price, attrs, item_id FROM ah_sale WHERE item_key=? AND ts>? ORDER BY ts DESC LIMIT 400",
                              (k, ts - 3 * 86_400_000)).fetchall()
            if len(sales) < 5:
                sales = c.execute("SELECT unit_price, attrs, item_id FROM ah_sale WHERE item_key=? AND ts>? ORDER BY ts DESC LIMIT 400",
                                  (k, ts - 7 * 86_400_000)).fetchall()
            iid = next((x[5] for x in lst if x[5]), None) or (sales[0]["item_id"] if sales else None)
            m = fit_model(k, iid, [(r["unit_price"], json.loads(r["attrs"]) if r["attrs"] else {}) for r in sales],
                          [(x[0], x[6]) for x in lst], prices)
            if m:
                m["item_id"] = iid
            models[k] = m
            rows.append((k, ts, iid, json.dumps(m) if m else None))
        with self.db.write_lock, c:
            c.executemany("INSERT OR REPLACE INTO models VALUES(?,?,?,?)", rows)
        return models

    def find_deals(self, c, listings: dict, ts: int) -> int:
        """BINs at least deal_min_discount under THEIR OWN estimated value (base + upgrades, pet level),
        worth >= deal_min_value, and still profitable after fees vs the next-cheapest comparable listing."""
        from .marketcheck import auction_check, net_after_fees
        from .valuation import Prices, components, pet_fraction, value_of
        cfg = self.cfg
        prices = Prices.from_db(c)
        models = self.fit_models(c, listings, ts, prices)
        rows = []
        for k, lst in listings.items():
            hit = self.fair_cache.get(k)
            if hit is None or time.time() - hit[0] > 1800:
                hit = (time.time(), auction_check(c, k, ts))
                self.fair_cache[k] = hit
            m = hit[1]
            if m.get("risk", 0) >= 2 or m.get("sales_72h", 0) < cfg.deal_min_sales_72h:
                continue
            model = models.get(k)
            priced = []                                  # (unit, value, base-equivalent price, listing)
            for x in lst:
                unit, attrs, iid = x[0], x[6], x[5]
                if model:
                    val, _ = value_of(model, k, iid, attrs, prices)
                    extra = val - (model["v1"] if model["type"] == "pet" else model["base"])
                else:
                    val, extra = m.get("fair") or 0, 0.0
                if val > 0:
                    priced.append((unit, val, unit - extra, x))
            priced.sort(key=lambda p: p[2])              # cheapest relative to its own upgrades first
            for i, (unit, val, beq, x) in enumerate(priced):
                _, seller, uid, cnt, total, iid, attrs = x
                if unit > val * (1 - cfg.deal_min_discount) or val * cnt < cfg.deal_min_value:
                    continue
                others = [p[2] for j, p in enumerate(priced) if j != i]
                resale = min(val, ((min(others) + (unit - beq)) if others else unit * 1.05) * 0.99)
                profit = net_after_fees(resale * cnt) - total
                if profit <= 0:
                    continue
                rows.append((uid, k, ts, ts, total, cnt, unit, val, 1 - unit / val, resale, profit, seller, len(lst),
                             m.get("sales_72h", 0)))
        with self.db.write_lock, c:
            keep = [r[0] for r in rows]
            c.execute(f"DELETE FROM deals WHERE uuid NOT IN ({','.join('?' * len(keep))})" if keep else "DELETE FROM deals", keep)
            c.executemany("""INSERT INTO deals(uuid, item_key, first_seen, ts, price, count, unit_price, fair, discount, resale, profit,
                             seller, listings, sales_72h) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                             ON CONFLICT(uuid) DO UPDATE SET ts=excluded.ts, fair=excluded.fair, discount=excluded.discount,
                             resale=excluded.resale, profit=excluded.profit, listings=excluded.listings""", rows)
        return len(rows)


class QuickBinJob(Job):
    """Keeps lowest BIN fresh between full scans (every 30 s by default).

    The auctions endpoint is sorted newest-first, so page 0 holds every listing created since the last
    check: a cheaper BIN there replaces the lowest BIN at once. When the cheapest BIN sells
    (seen in auctions_ended), the 2nd-cheapest takes its place until the next full scan.
    """
    name_ = "quick_bin"

    def __init__(self, *a):
        super().__init__(*a)
        self.interval = self.cfg.quick_bin_interval

    def tick(self, c):
        data = self.api.auctions_page(0)
        ts = int(data.get("lastUpdated") or time.time() * 1000)
        cheaper: dict[str, float] = {}
        for a in data.get("auctions", []):
            if not a.get("bin") or a.get("claimed"):
                continue
            try:
                k, _, cnt = key_and_count(a["item_bytes"])
            except Exception:
                continue
            if not k:
                continue
            unit = float(a.get("starting_bid") or 0) / cnt
            if unit > 0 and unit < cheaper.get(k, float("inf")):
                cheaper[k] = unit
        sold_cheapest = []
        recent = c.execute("SELECT item_key, MIN(unit_price) p FROM ah_sale WHERE bin=1 AND ts>? GROUP BY item_key",
                           (ts - 2 * self.interval * 1000,)).fetchall()
        updated = 0
        with self.db.write_lock, c:
            for k, unit in cheaper.items():
                cur = c.execute("UPDATE lbin_now SET second_bin=lowest_bin, lowest_bin=?, ts=? WHERE item_key=? AND lowest_bin>?",
                                (unit, ts, k, unit))
                updated += cur.rowcount
            for r in recent:                      # the cheapest listing just sold -> next one is the new lowest
                cur = c.execute("""UPDATE lbin_now SET lowest_bin=COALESCE(second_bin, lowest_bin), second_bin=NULL, ts=?,
                                   listings=MAX(listings-1, 0) WHERE item_key=? AND ABS(lowest_bin-?) < lowest_bin*0.001""",
                                (ts, r["item_key"], r["p"]))
                if cur.rowcount:
                    sold_cheapest.append(r["item_key"])
            Database.set_meta(c, "quick_bin_last", ts)
        _status(self.name_, last_ts=ts, cheaper_listings=updated, cheapest_sold=len(sold_cheapest))


def wallet_from_profiles(data: dict, uuid: str) -> dict | None:
    """Purse + bank of the selected SkyBlock profile (bank needs the in-game Banking API setting on)."""
    profiles = data.get("profiles") or []
    prof = next((p for p in profiles if p.get("selected")), profiles[0] if profiles else None)
    if not prof:
        return None
    member = (prof.get("members") or {}).get(uuid.replace("-", ""), {})
    purse = ((member.get("currencies") or {}).get("coin_purse")) or member.get("coin_purse") or 0
    bank = (prof.get("banking") or {}).get("balance")
    personal = ((member.get("profile") or {}).get("bank_account"))
    total = float(purse) + float(bank or 0) + float(personal or 0)
    return {"profile": prof.get("cute_name") or prof.get("profile_id"), "purse": float(purse),
            "bank": None if bank is None else float(bank), "personal_bank": None if personal is None else float(personal),
            "total": total}


class WalletJob(Job):
    """Your coins every hour via the Hypixel API (needs a personal API key)."""
    name_ = "wallet"

    def __init__(self, *a):
        super().__init__(*a)
        self.interval = self.cfg.wallet_interval
        self.uuid = None

    def tick(self, c):
        if not self.uuid:
            self.uuid = self.api.uuid_of(self.cfg.player_name)
        w = wallet_from_profiles(self.api.profiles(self.uuid, self.cfg.hypixel_api_key), self.uuid)
        if not w:
            raise RuntimeError("no SkyBlock profile found")
        ts = int(time.time() * 1000)
        with self.db.write_lock, c:
            c.execute("INSERT OR REPLACE INTO wallet VALUES(?,?,?,?,?,?,?,?)",
                      (ts, "api", self.cfg.player_name, w["profile"], w["purse"], w["bank"], w["personal_bank"], w["total"]))
        _status(self.name_, last_ts=ts, total=w["total"], bank_visible=w["bank"] is not None, error=None)
        log.info("wallet: %s purse %.0f bank %s total %.0f", self.cfg.player_name, w["purse"], w["bank"], w["total"])


class ResourcesJob(Job):
    """Once a day: star (essence) upgrade costs per item from Hypixel's public items resource."""
    name_ = "resources"
    interval = 86_400

    def tick(self, c):
        data = self.api.items_resource()
        costs = {it["id"]: it["upgrade_costs"] for it in data.get("items", []) if it.get("upgrade_costs") and it.get("id")}
        with self.db.write_lock, c:
            Database.set_meta(c, "upgrade_costs", json.dumps(costs, separators=(",", ":")))
        _status(self.name_, items_with_stars=len(costs))


class MaintenanceJob(Job):
    name_ = "maintenance"
    interval = 3600

    def tick(self, c):
        now = int(time.time() * 1000)
        with self.db.write_lock, c:
            c.execute("DELETE FROM bz_raw WHERE ts < ?", (now - int(self.cfg.keep_raw_bazaar_days * 86_400_000),))
            c.execute("DELETE FROM ah_sale WHERE ts < ?", (now - int(self.cfg.keep_sales_days * 86_400_000),))
        _status(self.name_, ok=True)


class PublishJob(Job):
    """Rebuilds the dashboard website and (optionally) pushes it to GitHub Pages."""
    name_ = "publish"

    def __init__(self, *a):
        super().__init__(*a)
        self.interval = self.cfg.publish_interval
        self.first = True

    def tick(self, c):
        from .publish import export_site, push_site
        if self.first:                      # give the other jobs a few minutes to fetch data first
            self.first = False
            if self.stop_ev.wait(180):
                return
        st = export_site(self.cfg, self.db)
        pushed = False
        if self.cfg.publish_repo:
            push_site(self.cfg)
            pushed = True
        _status(self.name_, items=st["bazaar_items"] + st["auction_items"], detailed=st["detailed"],
                pushed=pushed, error=None)


def start_all(cfg: Config, db: Database, stop: threading.Event) -> list[Job]:
    api = HypixelClient(cfg.api_base, cfg.user_agent)
    jobs = [BazaarJob(cfg, db, api, stop), EndedJob(cfg, db, api, stop), MaintenanceJob(cfg, db, api, stop),
            ResourcesJob(cfg, db, api, stop)]
    if cfg.active_ah_interval > 0:
        jobs.append(ActiveAHJob(cfg, db, api, stop))
    if cfg.quick_bin_interval > 0:
        jobs.append(QuickBinJob(cfg, db, api, stop))
    if cfg.publish_interval > 0:
        jobs.append(PublishJob(cfg, db, api, stop))
    if cfg.player_name and cfg.hypixel_api_key and cfg.wallet_interval > 0:
        jobs.append(WalletJob(cfg, db, api, stop))
    for j in jobs:
        j.start()
    return jobs
