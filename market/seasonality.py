"""Seasonality detection: which items have a repeating price cycle, and when is the next peak?

Cycles tested
  daily          24 h   (player activity: EU / US evenings)
  skyblock_year  124 h  (in-game calendar: Spooky Festival, Jerry's Workshop, … — 1 SkyBlock year = 5 d 4 h)
  weekly         168 h  (weekends)

Method (seasonal-index procedure from EXC 3650, Lecture 4–5, with a centred moving average
as the "unadjusted forecast"):
  1. Unadjusted level   CMA(t) = centred moving average over one full cycle
  2. Ratio              r(t) = Price(t) / CMA(t)      (>1: above its normal level)
  3. Seasonal index     average r for each position in the cycle, over all cycles (normalised to mean 1)
  4. Seasonal level     index × level

"Confirmed" means all three checks pass, so random noise doesn't show up as a season:
  * consistency  – indices computed from even cycles and odd cycles separately correlate (r ≥ 0.75)
  * out-of-sample – indices learned on older cycles cut the error on the newest cycle by ≥ 20 %
  * size         – peak-to-trough swing of at least 2 %
and at least 4 full cycles of data.

Thresholds were tuned by simulation (tests/test_seasonality.py): on random-walk prices fewer than
0.5 % of items get falsely confirmed, while a real 4–5 % cycle is confirmed > 90 % of the time.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

SKYBLOCK_EPOCH_S = 1560275700          # 1st Early Spring, Year 1, 00:00
MONDAY_EPOCH_S = 4 * 86400             # 1970-01-05 00:00 UTC was a Monday

CYCLES = {
    "daily": {"period": 24, "origin_s": 0, "label": "Daily"},
    "skyblock_year": {"period": 124, "origin_s": SKYBLOCK_EPOCH_S, "label": "SkyBlock year"},
    "weekly": {"period": 168, "origin_s": MONDAY_EPOCH_S, "label": "Weekly"},
}

SB_MONTHS = ["Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
             "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"]

MIN_CYCLES = 4
MIN_CONSISTENCY = 0.75
MIN_OOS_GAIN = 0.20
MIN_AMPLITUDE = 0.02


def phase_of(hour: int, cycle: str) -> int:
    c = CYCLES[cycle]
    return ((hour * 3600 - c["origin_s"]) // 3600) % c["period"]


def cycle_no(hour: int, cycle: str) -> int:
    c = CYCLES[cycle]
    return ((hour * 3600 - c["origin_s"]) // 3600) // c["period"]


def skyblock_date(phase: int) -> str:
    """Position in the SkyBlock year (hour 0–123) -> 'Late Autumn 14th'."""
    secs = phase * 3600
    month = min(secs // 37200, 11)
    day = (secs % 37200) // 1200 + 1
    suf = "th" if 10 <= day % 100 <= 20 else {1: "st", 2: "nd", 3: "rd"}.get(day % 10, "th")
    return f"{SB_MONTHS[month]} {day}{suf}"


def centred_ma(D: list[float], P: int) -> list[float | None]:
    """2×P centred moving average for even P (classical decomposition)."""
    n = len(D)
    pre = [0.0]
    for v in D:
        pre.append(pre[-1] + v)
    ma = [None] * n                       # ma[i] = mean of D[i : i+P]
    for i in range(n - P + 1):
        ma[i] = (pre[i + P] - pre[i]) / P
    out: list[float | None] = [None] * n
    h = P // 2
    for t in range(h, n - h):
        a, b = ma[t - h], ma[t - h + 1] if t - h + 1 < n else None
        if a is not None and b is not None:
            out[t] = (a + b) / 2
    return out


def _indices(ratios, phases, P, keep):
    sums, cnt = [0.0] * P, [0] * P
    for r, p, k in zip(ratios, phases, keep):
        if r is not None and k:
            sums[p] += r
            cnt[p] += 1
    if min(cnt) == 0:
        return None
    idx = [s / c for s, c in zip(sums, cnt)]
    m = sum(idx) / P
    return [v / m for v in idx]


def _corr(a, b):
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    sa = math.sqrt(sum((x - ma) ** 2 for x in a))
    sb = math.sqrt(sum((y - mb) ** 2 for y in b))
    if sa == 0 or sb == 0:
        return 0.0
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (sa * sb)


@dataclass
class Season:
    cycle: str
    period: int
    index: list[float]
    amplitude: float          # peak/trough swing, fraction
    consistency: float        # even-vs-odd cycle correlation
    oos_gain: float           # error reduction on the newest cycle
    cycles: int
    confirmed: bool
    peak_phase: int
    trough_phase: int

    def timing(self, now_hour: int) -> dict:
        P = self.period
        now = phase_of(now_hour, self.cycle)
        to_peak = (self.peak_phase - now) % P
        to_trough = (self.trough_phase - now) % P
        return {"phase_now": now, "hours_to_peak": to_peak, "hours_to_trough": to_trough,
                "rise_to_peak": self.index[self.peak_phase] / self.index[now] - 1,
                "fall_to_trough": self.index[self.trough_phase] / self.index[now] - 1,
                "peak_at": (now_hour + to_peak) * 3_600_000, "trough_at": (now_hour + to_trough) * 3_600_000}

    def to_dict(self, now_hour: int) -> dict:
        d = {"cycle": self.cycle, "label": CYCLES[self.cycle]["label"], "period": self.period,
             "index": [round(v, 4) for v in self.index], "amplitude": round(self.amplitude, 4),
             "consistency": round(self.consistency, 3), "oos_gain": round(self.oos_gain, 3),
             "cycles": self.cycles, "confirmed": self.confirmed,
             "peak_phase": self.peak_phase, "trough_phase": self.trough_phase}
        d.update(self.timing(now_hour))
        if self.cycle == "skyblock_year":
            d["peak_sb_date"] = skyblock_date(self.peak_phase)
            d["trough_sb_date"] = skyblock_date(self.trough_phase)
        return d


def detect(D: list[float], first_hour: int, cycle: str) -> Season | None:
    """D: hourly prices without gaps, D[0] at epoch-hour `first_hour`."""
    P = CYCLES[cycle]["period"]
    n = len(D)
    if n < MIN_CYCLES * P + P or min(D) <= 0:
        return None
    cma = centred_ma(D, P)
    hours = range(first_hour, first_hour + n)
    phases = [phase_of(h, cycle) for h in hours]
    cyc = [cycle_no(h, cycle) for h in hours]
    ratios = [None if c is None else d / c for d, c in zip(D, cma)]
    used = [cy for cy, r in zip(cyc, ratios) if r is not None]
    if not used:
        return None
    n_cycles = len(set(used))
    if n_cycles < MIN_CYCLES:
        return None

    idx = _indices(ratios, phases, P, [True] * n)
    if idx is None:
        return None
    even = _indices(ratios, phases, P, [c % 2 == 0 for c in cyc])
    odd = _indices(ratios, phases, P, [c % 2 == 1 for c in cyc])
    consistency = _corr(even, odd) if even and odd else 0.0

    # out-of-sample: learn indices on everything except the newest full cycle of ratios, test on it
    valid = [t for t, r in enumerate(ratios) if r is not None]
    test = set(valid[-P:])
    train = _indices(ratios, phases, P, [t not in test for t in range(n)])
    gain = 0.0
    if train:
        base = [abs(ratios[t] - 1) for t in test]
        seas = [abs(ratios[t] - train[phases[t]]) for t in test]
        if sum(base) > 0:
            gain = 1 - sum(seas) / sum(base)

    amp = max(idx) - min(idx)
    peak, trough = idx.index(max(idx)), idx.index(min(idx))
    ok = consistency >= MIN_CONSISTENCY and gain >= MIN_OOS_GAIN and amp >= MIN_AMPLITUDE
    return Season(cycle, P, idx, amp, consistency, gain, n_cycles, ok, peak, trough)


def detect_all(D: list[float], first_hour: int) -> list[Season]:
    """Daily cycle first; if confirmed, it is divided out before testing the longer cycles
    (classical multi-seasonal decomposition), so a strong daily swing can't hide them."""
    out = []
    daily = detect(D, first_hour, "daily")
    if daily:
        out.append(daily)
    base = D
    if daily and daily.confirmed:
        base = [v / daily.index[phase_of(first_hour + t, "daily")] for t, v in enumerate(D)]
    for cyc in ("skyblock_year", "weekly"):
        s = detect(base, first_hour, cyc)
        if s:
            out.append(s)
    return out
