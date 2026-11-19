"""SQLite storage. One file, WAL mode so the collector can write while the server reads."""
from __future__ import annotations

import sqlite3
import threading
from pathlib import Path

HOUR_MS = 3_600_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS bz_now (            -- latest bazaar quick_status per product
    product TEXT PRIMARY KEY, ts INTEGER,
    buy REAL, sell REAL, buy_vol INTEGER, sell_vol INTEGER,
    buy_week INTEGER, sell_week INTEGER, buy_orders INTEGER, sell_orders INTEGER
);
CREATE TABLE IF NOT EXISTS bz_raw (            -- change-only raw snapshots (short retention)
    product TEXT, ts INTEGER, buy REAL, sell REAL, buy_vol INTEGER, sell_vol INTEGER,
    buy_week INTEGER, sell_week INTEGER, buy_orders INTEGER, sell_orders INTEGER,
    PRIMARY KEY (product, ts)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS ah_sale (           -- every finished auction (auctions_ended)
    auction_id TEXT PRIMARY KEY, ts INTEGER, item_key TEXT, item_id TEXT,
    price REAL, count INTEGER, unit_price REAL, bin INTEGER
);
CREATE INDEX IF NOT EXISTS ah_sale_key_ts ON ah_sale(item_key, ts);
CREATE INDEX IF NOT EXISTS ah_sale_ts ON ah_sale(ts);
CREATE TABLE IF NOT EXISTS lbin_now (          -- lowest BIN per item from the latest full AH scan
    item_key TEXT PRIMARY KEY, ts INTEGER, lowest_bin REAL, listings INTEGER
);
CREATE TABLE IF NOT EXISTS hourly (            -- hourly bars; what the forecasts are built on
    series TEXT, hour INTEGER,                  -- series: bz:<id>:buy | bz:<id>:sell | ah:<key> | lbin:<key>
    avg REAL, median REAL, high REAL, low REAL, n INTEGER, volume REAL,
    PRIMARY KEY (series, hour)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS deals (            -- underpriced BINs from the latest full AH scan
    uuid TEXT PRIMARY KEY, item_key TEXT, first_seen INTEGER, ts INTEGER, price REAL, count INTEGER,
    unit_price REAL, fair REAL, discount REAL, resale REAL, profit REAL, seller TEXT, listings INTEGER, sales_72h INTEGER
);
CREATE TABLE IF NOT EXISTS wallet (            -- your coins over time
    ts INTEGER, source TEXT, player TEXT, profile TEXT,   -- source: api (purse + bank, hourly) | mod (purse from sidebar)
    purse REAL, bank REAL, personal_bank REAL, total REAL,
    PRIMARY KEY (ts, source)
) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS models (            -- fitted clean-base / pet-level value models (market/valuation.py)
    item_key TEXT PRIMARY KEY, ts INTEGER, item_id TEXT, model TEXT
);
CREATE TABLE IF NOT EXISTS my_positions (      -- your current AH listings and bids (market/fcf.py), replaced every scan
    uuid TEXT PRIMARY KEY, ts INTEGER, doc TEXT
);
CREATE TABLE IF NOT EXISTS fcf (               -- potential free cash flow after every full AH scan
    ts INTEGER PRIMARY KEY, wallet REAL, sales REAL, bids REAL, expected REAL, refunds REAL, fcf REAL, fcf_conservative REAL
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
"""

# Columns added after the first release; created on existing databases automatically.
MIGRATIONS = {
    "ah_sale": [("seller", "TEXT"), ("buyer", "TEXT"), ("attrs", "TEXT")],
    "lbin_now": [("second_bin", "REAL"), ("p25", "REAL"), ("median_listed", "REAL"),
                 ("sellers", "INTEGER"), ("top_share", "REAL")],
}


class Database:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.write_lock = threading.Lock()
        with self.connect() as c:
            c.executescript(SCHEMA)
            for table, cols in MIGRATIONS.items():
                have = {r[1] for r in c.execute(f"PRAGMA table_info({table})")}
                for name, typ in cols:
                    if name not in have:
                        c.execute(f"ALTER TABLE {table} ADD COLUMN {name} {typ}")

    def connect(self) -> sqlite3.Connection:
        c = sqlite3.connect(self.path, timeout=30, check_same_thread=False)
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA synchronous=NORMAL")
        c.row_factory = sqlite3.Row
        return c

    @staticmethod
    def set_meta(c: sqlite3.Connection, k: str, v) -> None:
        c.execute("INSERT INTO meta(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, str(v)))

    @staticmethod
    def get_meta(c: sqlite3.Connection, k: str, default=None):
        row = c.execute("SELECT v FROM meta WHERE k=?", (k,)).fetchone()
        return row[0] if row else default
