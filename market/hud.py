"""Formats the in-game HUD lines. The mod just draws what this returns, so you can change
the look here (colours, wording, which numbers) without rebuilding the mod."""
from __future__ import annotations

GOLD, AQUA, GRAY, WHITE, GREEN, RED, YELLOW, DARK = 0xFFAA00, 0x55FFFF, 0xAAAAAA, 0xFFFFFF, 0x55FF55, 0xFF5555, 0xFFFF55, 0x777777


def fmt(v: float | None) -> str:
    if v is None:
        return "–"
    a = abs(v)
    for div, suf in ((1e9, "B"), (1e6, "M"), (1e3, "k")):
        if a >= div:
            s = f"{v / div:.2f}".rstrip("0").rstrip(".")
            return s + suf
    if a >= 100:
        return f"{v:,.0f}"
    return f"{v:,.1f}".rstrip("0").rstrip(".") if a >= 1 else f"{v:.2f}"


def seg(text: str, color: int = WHITE) -> dict:
    return {"t": text, "c": color}


def _change(fc: float, base: float) -> list[dict]:
    if not base:
        return []
    pct = (fc / base - 1) * 100
    if abs(pct) < 0.05:
        return [seg(" ±0%", GRAY)]
    return [seg(f" {'▲' if pct > 0 else '▼'}{abs(pct):.1f}%", GREEN if pct > 0 else RED)]


def _forecast_lines(f: dict, label: str = "") -> list[list[dict]]:
    if not f or not f.get("available"):
        have, need = (f or {}).get("hours_of_data", 0), (f or {}).get("needs_hours", 12)
        return [[seg(f"Forecast{label}: ", AQUA), seg(f"collecting data ({have}/{need} h)", GRAY)]]
    hz, base = f["horizons"], f["last_value"]
    line = [seg(f"Forecast{label} ", AQUA)]
    for h in ("1", "6", "24"):
        if h in hz:
            line += [seg(f"{h}h ", GRAY), seg(fmt(hz[h]["forecast"]), WHITE)] + _change(hz[h]["forecast"], base) + [seg("  ")]
    far = hz.get("24") or hz[max(hz, key=int)]
    lo, hi = far["range"]
    span = "24h" if "24" in hz else f"{max(hz, key=int)}h"
    detail = [seg(f" 80% range {span}: {fmt(lo)} – {fmt(hi)}", GRAY),
              seg(f"  [{far['model']}, MAPE {far['accuracy']['mape']:.1f}%]", DARK)]
    out = [line, detail]
    if f.get("biased"):
        out.append([seg(f" ⚠ 1h model biased (tracking signal {f['accuracy']['tracking_signal']:+.1f})", YELLOW)])
    return out


def hud_lines(s: dict) -> list[list[dict]]:
    kind = s.get("kind")
    if kind == "bazaar":
        lines = [[seg("Bazaar ", GOLD), seg("(24h)", GRAY)]]
        for side, label in (("buy", "Buy "), ("sell", "Sell")):
            d = (s.get("day") or {}).get(side)
            if d:
                lines.append([seg(f" {label} avg ", GRAY), seg(fmt(d["avg"])), seg("  high ", GRAY), seg(fmt(d["high"]), GREEN),
                              seg("  low ", GRAY), seg(fmt(d["low"]), RED)])
        v = s.get("daily_volume") or {}
        lines.append([seg(f" ~{fmt(v.get('bought'))} bought / ~{fmt(v.get('sold'))} sold per day", DARK)])
        lines += _forecast_lines(s["forecast"]["buy"], " (buy)")
        return lines

    if kind == "auction":
        lines = [[seg("Auction House ", GOLD), seg("(24h)", GRAY)]]
        d = s.get("day")
        if d:
            lines.append([seg(" Avg sold ", GRAY), seg(fmt(d["avg"])), seg("  high ", GRAY), seg(fmt(d["high"]), GREEN),
                          seg("  low ", GRAY), seg(fmt(d["low"]), RED), seg(f"  ({d['sales']} sales)", DARK)])
        else:
            lines.append([seg(" No sales in the last 24h", GRAY)])
        lb = s.get("lowest_bin")
        if lb:
            lines.append([seg(" Lowest BIN ", GRAY), seg(fmt(lb["price"]), YELLOW), seg(f"  ({lb['listings']} listed)", DARK)])
        lines += _forecast_lines(s.get("forecast"))
        return lines

    return []      # never traded / not on the market: the mod shows nothing
