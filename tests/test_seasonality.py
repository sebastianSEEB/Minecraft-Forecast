"""Seasonality detector: finds real cycles, rejects random walks, timing of next peak is right."""
import math, random, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.seasonality import detect, phase_of, skyblock_date, SKYBLOCK_EPOCH_S

H0 = 1794600000000 // 3_600_000 - 24 * 40


def rw(n, vol=0.01, seed=None):
    p = [1000.0]
    for _ in range(n - 1):
        p.append(p[-1] * math.exp(random.gauss(0, vol)))
    return p


def wave(n, P, amp, origin_s, noise=0.01):
    base = rw(n, 0.003)
    return [b * (1 + amp * math.sin(2 * math.pi * (((H0 + t) * 3600 - origin_s) // 3600 % P) / P)) * (1 + random.gauss(0, noise))
            for t, b in enumerate(base)]


def test_daily_cycle_found_and_timed():
    random.seed(3)
    s = detect(wave(24 * 30, 24, 0.04, 0), H0, "daily")
    assert s and s.confirmed
    assert abs(s.peak_phase - 6) <= 1          # sin peaks a quarter into the cycle (06 UTC)
    t = s.timing(H0 + 24 * 30)
    assert 0 <= t["hours_to_peak"] < 24 and t["rise_to_peak"] >= -1e-9


def test_skyblock_year_cycle_found():
    random.seed(4)
    s = detect(wave(24 * 30, 124, 0.05, SKYBLOCK_EPOCH_S), H0, "skyblock_year")
    assert s and s.confirmed and abs(s.peak_phase - 31) <= 6     # flat top of a sine: ±6 h is within noise


def test_random_walks_rarely_confirmed():
    random.seed(5)
    fp = sum(bool((s := detect(rw(24 * 35), H0, c)) and s.confirmed)
             for _ in range(100) for c in ("daily", "skyblock_year", "weekly"))
    assert fp <= 3, fp


def test_skyblock_calendar():
    assert skyblock_date(0) == "Early Spring 1st"
    assert phase_of(SKYBLOCK_EPOCH_S // 3600 + 1, "skyblock_year") == 0   # bar 17:00-18:00 after the 17:55 epoch
    assert skyblock_date(123).startswith("Late Winter")


if __name__ == "__main__":
    for n, f in list(globals().items()):
        if n.startswith("test_"):
            f(); print("ok", n)
