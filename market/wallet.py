"""Your coins over time: hourly series + KPIs for the dashboard's Wallet tab."""
from __future__ import annotations

from .db import HOUR_MS


def wallet_report(c, now_ms: int) -> dict | None:
    from .fcf import fcf_report
    rep = _wallet_only(c, now_ms)
    f = fcf_report(c, now_ms)
    if f:
        rep = rep or {}
        rep["fcf"] = f
    return rep


def _wallet_only(c, now_ms: int) -> dict | None:
    rows = c.execute("SELECT ts, source, player, profile, purse, bank, personal_bank, total FROM wallet ORDER BY ts").fetchall()
    if not rows:
        return None
    # one point per hour: last API snapshot in that hour, else last mod snapshot (bank carried forward)
    by_hour: dict[int, dict] = {}
    bank = personal = None
    for r in rows:
        if r["source"] == "api":
            bank, personal = r["bank"], r["personal_bank"]
        b = r["bank"] if r["bank"] is not None else bank
        pb = r["personal_bank"] if r["personal_bank"] is not None else personal
        pt = {"ts": r["ts"], "purse": r["purse"], "bank": b, "personal_bank": pb,
              "total": r["purse"] + (b or 0) + (pb or 0), "source": r["source"]}
        h = r["ts"] // HOUR_MS
        cur = by_hour.get(h)
        if cur is None or r["source"] == "api" or cur["source"] != "api":
            by_hour[h] = pt
    pts = [dict(v, hour=h) for h, v in sorted(by_hour.items())]
    last = pts[-1]

    def value_at(ms_ago: int):
        target = now_ms - ms_ago
        older = [p for p in pts if p["ts"] <= target]
        return older[-1] if older else None

    def change(ms_ago: int):
        p = value_at(ms_ago)
        return None if p is None else last["total"] - p["total"]

    diffs = [(b["hour"], b["total"] - a["total"]) for a, b in zip(pts, pts[1:]) if b["hour"] - a["hour"] == 1]
    week = [d for h, d in diffs if h > now_ms // HOUR_MS - 168]
    ath = max(pts, key=lambda p: p["total"])
    first = pts[0]
    return {
        "player": rows[-1]["player"], "profile": next((r["profile"] for r in reversed(rows) if r["profile"]), None),
        "updated": last["ts"], "since": first["ts"], "hours_tracked": len(pts),
        "now": {"total": last["total"], "purse": last["purse"], "bank": last["bank"], "personal_bank": last["personal_bank"]},
        "kpi": {
            "change_24h": change(24 * HOUR_MS), "change_7d": change(168 * HOUR_MS),
            "change_all": last["total"] - first["total"],
            "ath": ath["total"], "ath_at": ath["ts"],
            "avg_per_hour_7d": (sum(week) / len(week)) if week else None,
            "best_hour": max(diffs, key=lambda x: x[1])[1] if diffs else None,
            "worst_hour": min(diffs, key=lambda x: x[1])[1] if diffs else None,
            "positive_hours_pct": (100 * sum(1 for _, d in diffs if d > 0) / len(diffs)) if diffs else None,
        },
        "series": [{"ts": p["hour"] * HOUR_MS, "total": p["total"], "purse": p["purse"], "bank": p["bank"]} for p in pts[-24 * 90:]],
        "bank_known": any(p["bank"] is not None for p in pts),
    }
