"""Fair value from sold AND listed prices, realistic resale price, and manipulation checks.

Auction House
  fair value  = median of three robust estimates:
                  • median of clean sale prices, last 72 h (wash trades and extreme outliers removed)
                  • 25th percentile of current BIN listings (what sellers realistically ask)
                  • 7-day average sale price
  resale      = what you could realistically sell for right now: fair value, but never above
                the cheapest competing BIN (you have to undercut it)

Manipulation / risk flags
  wash        the same seller→buyer pair trading the item repeatedly (fake volume / fake prices)
  cornering   listings dropped sharply vs their 7-day average while the price jumped:
              someone bought up supply and relisted higher
  spike       price far above its 30-day average
  dominant    one seller owns most of the current listings (sets the price)
  thin        very few sales: averages are easy to push around
Bazaar
  spike       buy price far above the 7-day average
  thin_book   very few sell offers / tiny daily volume
  spread      instant-buy far above instant-sell (order book being walled)
"""
from __future__ import annotations

import statistics

from .db import HOUR_MS

DAY_MS = 86_400_000


def net_after_fees(sell: float) -> float:
    """What you keep when a BIN sells: listing fee 1 % (< 10M) / 2 % (10M–100M) / 2.5 % (>= 100M), 1 % claim tax above 1M."""
    fee = 0.025 if sell >= 100e6 else 0.02 if sell >= 10e6 else 0.01
    return sell * (1 - fee - (0.01 if sell > 1e6 else 0))


def _avg(c, series: str, now_h: int, hours: int):
    r = c.execute("SELECT SUM(avg*n)/SUM(n), AVG(avg) FROM hourly WHERE series=? AND hour>? AND hour<=?",
                  (series, now_h - hours, now_h)).fetchone()
    return r[0] if r[0] is not None else r[1]


def _flag(code: str, level: int, text: str) -> dict:
    return {"code": code, "level": level, "text": text}      # level: 1 = caution, 2 = warning


def auction_check(c, key: str, now_ms: int) -> dict:
    now_h = now_ms // HOUR_MS
    rows = c.execute("SELECT unit_price, seller, buyer FROM ah_sale WHERE item_key=? AND ts>? AND ts<=?",
                     (key, now_ms - 3 * DAY_MS, now_ms)).fetchall()
    prices = [r["unit_price"] for r in rows]
    flags = []

    # --- wash trades: same seller -> buyer pair more than once
    pairs: dict = {}
    for r in rows:
        if r["seller"] and r["buyer"]:
            pairs[(r["seller"], r["buyer"])] = pairs.get((r["seller"], r["buyer"]), 0) + 1
    wash_pairs = {p for p, n in pairs.items() if n >= 2}
    clean = [r["unit_price"] for r in rows if (r["seller"], r["buyer"]) not in wash_pairs]
    wash_n = len(rows) - len(clean)
    if wash_n >= 2 and wash_n / max(1, len(rows)) >= 0.15:
        flags.append(_flag("wash", 2, f"{wash_n} of {len(rows)} recent sales are repeat trades between the same "
                                      f"seller and buyer (possible wash trading); ignored in fair value"))

    # --- outliers (fat-finger or fake sales) removed around the raw median
    outliers = 0
    if clean:
        m0 = statistics.median(clean)
        kept = [p for p in clean if m0 / 3 <= p <= m0 * 3]
        outliers = len(clean) - len(kept)
        clean = kept
    sold_med = statistics.median(clean) if clean else None

    lst = c.execute("SELECT lowest_bin, listings, second_bin, p25, median_listed, sellers, top_share FROM lbin_now WHERE item_key=?",
                    (key,)).fetchone()
    lbin = lst["lowest_bin"] if lst else None
    listings = lst["listings"] if lst else 0
    p25 = lst["p25"] if lst and lst["p25"] is not None else None
    w7 = _avg(c, f"ah:{key}", now_h, 168)
    w30 = _avg(c, f"ah:{key}", now_h, 720)

    estimates = [v for v in (sold_med, p25 if listings >= 3 else None, w7) if v]
    fair = statistics.median(estimates) if estimates else lbin
    second = lst["second_bin"] if lst else None
    cap = None
    if lbin:
        cap = (second if second else lbin * 1.05) * 0.99          # after you buy the cheapest, undercut the next
    resale = min(fair, cap) if fair and cap else fair

    # --- cornering: supply fell, price rose
    n7 = _avg(c, f"listn:{key}", now_h, 168)
    lbin7 = _avg(c, f"lbin:{key}", now_h, 168)
    if n7 and lbin7 and listings and lbin:
        drop, rise = 1 - listings / n7, lbin / lbin7 - 1
        if drop >= 0.5 and rise >= 0.3:
            flags.append(_flag("cornering", 2, f"Listings down {drop * 100:.0f}% vs 7-day average while the price is up "
                                               f"{rise * 100:.0f}%: supply may have been bought up to push the price"))

    # --- spike vs 30 days
    ref_now = sold_med or lbin
    if w30 and ref_now and ref_now / w30 - 1 >= 0.4:
        flags.append(_flag("spike", 1 if ref_now / w30 < 1.8 else 2,
                           f"Price is {(ref_now / w30 - 1) * 100:.0f}% above its 30-day average"))

    # --- one seller sets the price
    if lst and listings >= 4 and (lst["top_share"] or 0) >= 0.5:
        flags.append(_flag("dominant", 1, f"One seller owns {lst['top_share'] * 100:.0f}% of the {listings} listings"))

    # --- thin market
    if len(rows) < 5:
        flags.append(_flag("thin", 1, f"Only {len(rows)} sales in 3 days: prices are easy to move"))

    level = max((f["level"] for f in flags), default=0)
    return {
        "fair": fair, "resale": resale, "sold_median_72h": sold_med, "sales_72h": len(rows), "clean_sales": len(clean),
        "wash_sales": wash_n, "outliers": outliers, "avg_7d": w7, "avg_30d": w30,
        "listed": None if not lst else {"count": listings, "lowest": lbin, "second": second, "p25": p25,
                                        "median": lst["median_listed"], "sellers": lst["sellers"], "top_share": lst["top_share"]},
        "risk": level, "flags": flags,
    }


def bazaar_check(c, key: str, now_ms: int) -> dict:
    now_h = now_ms // HOUR_MS
    b = c.execute("SELECT buy, sell, buy_week, sell_week, buy_orders, sell_orders FROM bz_now WHERE product=?", (key,)).fetchone()
    flags = []
    if not b:
        return {"fair": None, "risk": 0, "flags": []}
    w7 = _avg(c, f"bz:{key}:buy", now_h, 168)
    w30 = _avg(c, f"bz:{key}:buy", now_h, 720)
    fair = w7 or b["buy"]
    if w7 and b["buy"] and b["buy"] / w7 - 1 >= 0.3:
        flags.append(_flag("spike", 2 if b["buy"] / w7 >= 1.6 else 1,
                           f"Buy price is {(b['buy'] / w7 - 1) * 100:.0f}% above its 7-day average"))
    daily = (b["buy_week"] + b["sell_week"]) / 7
    if (b["sell_orders"] or 0) < 3 or daily < 100:
        flags.append(_flag("thin_book", 1, f"Thin order book ({b['sell_orders']} sell offers, ~{daily:.0f} traded/day): "
                                           f"easy to manipulate"))
    if b["buy"] and b["sell"] and (b["buy"] - b["sell"]) / b["buy"] >= 0.3:
        flags.append(_flag("spread", 1, f"Instant-buy is {(b['buy'] / b['sell'] - 1) * 100:.0f}% above instant-sell"))
    return {"fair": fair, "avg_7d": w7, "avg_30d": w30, "risk": max((f["level"] for f in flags), default=0), "flags": flags}
