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
from .items import key_and_count

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
                key, item_id, count = key_and_count(a["item_bytes"])
            except Exception:
                bad += 1
                continue
            if not key:
                continue
            price = float(a.get("price") or 0)
            if price <= 0:
                continue
            ts = int(a.get("timestamp") or data.get("lastUpdated") or time.time() * 1000)
            rows.append((a["auction_id"], ts, key, item_id, price, count, price / count, int(bool(a.get("bin")))))
        new = 0
        with self.db.write_lock, c:
            for r in rows:
                cur = c.execute("INSERT OR IGNORE INTO ah_sale VALUES(?,?,?,?,?,?,?,?)", r)
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

    def tick(self, c):
        first = self.api.auctions_page(0)
        ts = int(first.get("lastUpdated") or time.time() * 1000)
        pages = int(first.get("totalPages") or 1)
        best: dict[str, list] = {}           # key -> [lowest unit price, listings]
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
                        k, _, cnt = key_and_count(a["item_bytes"])
                    except Exception:
                        k, cnt = None, 1
                    self.decoded[uid] = (k, cnt)
                k, cnt = self.decoded[uid]
                if not k:
                    continue
                unit = float(a.get("starting_bid") or 0) / cnt
                if unit <= 0:
                    continue
                b = best.get(k)
                if b is None:
                    best[k] = [unit, 1]
                else:
                    b[0] = min(b[0], unit)
                    b[1] += 1
        self.decoded = {u: v for u, v in self.decoded.items() if u in seen}
        hour = ts // HOUR_MS
        bar_rows = [self.bars.add(c, f"lbin:{k}", hour, v[0]) for k, v in best.items()]
        with self.db.write_lock, c:
            c.execute("DELETE FROM lbin_now")
            c.executemany("INSERT INTO lbin_now VALUES(?,?,?,?)", [(k, ts, v[0], v[1]) for k, v in best.items()])
            c.executemany(UPSERT_HOURLY, bar_rows)
            Database.set_meta(c, "active_last", ts)
        _status(self.name_, last_ts=ts, pages=pages, items=len(best))
        log.info("active auctions: %d pages, %d distinct BIN items", pages, len(best))


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
    jobs = [BazaarJob(cfg, db, api, stop), EndedJob(cfg, db, api, stop), MaintenanceJob(cfg, db, api, stop)]
    if cfg.active_ah_interval > 0:
        jobs.append(ActiveAHJob(cfg, db, api, stop))
    if cfg.publish_interval > 0:
        jobs.append(PublishJob(cfg, db, api, stop))
    for j in jobs:
        j.start()
    return jobs
