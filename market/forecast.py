"""Forecasting with the standard operations-management toolkit (moving averages, exponential smoothing, Holt, regression, seasonal indices).

Every model below is the textbook formula, applied to an hourly price series:

  Last period            F(t+1) = D(t)
  Moving average         F(t+1) = (1/n) * Σ D(t+1-i), i=1..n
  Weighted moving avg    F(t+1) = Σ W(t+1-i) * D(t+1-i),  Σ W = 1
  Exponential smoothing  F(t+1) = α D(t) + (1-α) F(t)
  Holt (adjusted ES)     AF(t+1) = F(t+1) + T(t+1)
                         F(t+1)  = α D(t) + (1-α) F(t)
                         T(t+1)  = β (F(t+1) - F(t)) + (1-β) T(t)      (F0 = â, T0 = b̂)
  Linear regression      ŷ = â + b̂ x   (b̂ = (Σxy - ΣxΣy/n) / (Σx² - (Σx)²/n),  â = ȳ - b̂ x̄)
  Seasonal adjustment    4-step procedure, "season" = hour of day (24 indices)

Model choice works like a classic forecasting package: every model is
scored on the same recent window with MFE, MAD, MAPE and the tracking signal, and the
lowest MAD wins. This is done separately for each horizon (1h, 6h, 24h), using errors at
that horizon, because the model that is best one hour ahead (often a trend model) can be
badly wrong a day ahead. A |tracking signal| above the limit flags the model as biased.

Which models are even allowed depends on the series' demand pattern ("Selecting a forecasting
method" + "demand pattern" rules), diagnosed fresh every run, so the choice changes as data
accumulates:

  too little data          → no forecast (textbook: qualitative methods when data are scarce; the
                             mod falls back to market judgement = recent sales / lowest BIN)
  randomness around a level→ last period, moving averages, weighted MA, exponential smoothing,
                             with α picked by the usual rule: more randomness → lower α
  trend (significant slope)→ + Holt's adjusted exponential smoothing and time-series regression
                             (without a real trend these only extrapolate noise, so they're left out)
  seasonality              → hour-of-day index (4-step procedure) on top of the chosen model
A model whose |tracking signal| is over the limit is skipped for the next best one ("packages
use MFE, MAD, MAPE or the tracking signal to flag a poor model and kick off a search for a
better one").

The range shown in-game comes from the inventory formula sheet:
  σ ≈ √(π/2)·MAD ≈ 1.25·MAD   (MAD measured at that horizon)
  range = forecast ± z·σ,  z = NORM.S.INV(0.5 + level/2)
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from statistics import NormalDist
from typing import Callable, Optional

Series = list[float]
OneStep = list[Optional[float]]


# ----------------------------------------------------------------------------- formulas

def last_period(D: Series) -> OneStep:
    return [None] + D[:-1]


def moving_average(D: Series, n: int) -> OneStep:
    out: OneStep = [None] * len(D)
    for t in range(n, len(D)):
        out[t] = sum(D[t - n:t]) / n
    return out


def weighted_moving_average(D: Series, weights: list[float]) -> OneStep:
    """weights[0] applies to the most recent observation (as in Example 9.1)."""
    n = len(weights)
    out: OneStep = [None] * len(D)
    for t in range(n, len(D)):
        out[t] = sum(w * D[t - 1 - i] for i, w in enumerate(weights))
    return out


def exponential_smoothing(D: Series, alpha: float, f1: Optional[float] = None) -> tuple[OneStep, float]:
    """Returns (one-step forecasts, forecast for the period after the last one).
    F1 defaults to D1, as in Example 9.2."""
    F: OneStep = [None] * len(D)
    F[0] = D[0] if f1 is None else f1
    for t in range(len(D) - 1):
        F[t + 1] = alpha * D[t] + (1 - alpha) * F[t]
    nxt = alpha * D[-1] + (1 - alpha) * F[-1]
    return F, nxt


def linear_regression(x: list[float], y: list[float]) -> tuple[float, float]:
    """(â, b̂) least-squares formulas."""
    n = len(x)
    sx, sy = sum(x), sum(y)
    sxy = sum(a * b for a, b in zip(x, y))
    sxx = sum(a * a for a in x)
    denom = sxx - sx * sx / n
    b = 0.0 if denom == 0 else (sxy - sx * sy / n) / denom
    a = sy / n - b * sx / n
    return a, b


def holt(D: Series, alpha: float, beta: float, init_k: int = 12) -> tuple[OneStep, float, float]:
    """Adjusted exponential smoothing (textbook form).

    Initialisation ("use â as F0 and b̂ as T0"): regress the first k points on
    x = 1..k, so the level at period 0 is â and the trend is b̂. Period 1 is then
    forecast as â + b̂, with T1 = b̂, and the recursion runs from there.
    Returns (adjusted one-step forecasts AF, F(n+1), T(n+1)).
    """
    k = max(2, min(init_k, len(D)))
    a, b = linear_regression(list(range(1, k + 1)), D[:k])
    F = a + b       # F1
    T = b           # T1
    AF: OneStep = [None] * len(D)
    AF[0] = F
    for t in range(len(D)):
        F_next = alpha * D[t] + (1 - alpha) * F
        T_next = beta * (F_next - F) + (1 - beta) * T
        F, T = F_next, T_next
        if t + 1 < len(D):
            AF[t + 1] = F + T
    return AF, F, T     # F, T now refer to period n+1


def rolling_regression(D: Series, k: int) -> OneStep:
    """Time-series regression refitted each hour on the previous k observations."""
    out: OneStep = [None] * len(D)
    xs = list(range(1, k + 1))
    for t in range(k, len(D)):
        a, b = linear_regression(xs, D[t - k:t])
        out[t] = a + b * (k + 1)
    return out


def seasonal_indices(D: Series, F: OneStep, season: list[int], start: int, end: int, m: int = 24) -> Optional[list[float]]:
    """Steps 2–3 of the seasonal procedure: average (Demand / Forecast) per season."""
    sums, counts = [0.0] * m, [0] * m
    for t in range(start, end):
        if F[t] and F[t] > 0:
            sums[season[t]] += D[t] / F[t]
            counts[season[t]] += 1
    if min(counts) < 2:          # need each hour of day seen at least twice
        return None
    return [s / c for s, c in zip(sums, counts)]


# ----------------------------------------------------------------------------- accuracy

@dataclass
class Accuracy:
    mfe: float
    mad: float
    mape: float          # in percent
    tracking_signal: float
    n: int


def accuracy(D: Series, F: OneStep, start: int, end: int) -> Optional[Accuracy]:
    fe, ape = [], []
    for t in range(start, end):
        if F[t] is None:
            continue
        e = D[t] - F[t]
        fe.append(e)
        if D[t] != 0:
            ape.append(abs(e) / abs(D[t]))
    if not fe:
        return None
    n = len(fe)
    mad = sum(abs(e) for e in fe) / n
    return Accuracy(
        mfe=sum(fe) / n,
        mad=mad,
        mape=100 * sum(ape) / len(ape) if ape else float("nan"),
        tracking_signal=(sum(fe) / mad) if mad > 0 else 0.0,
        n=n,
    )


# ----------------------------------------------------------------------------- demand pattern

@dataclass
class Pattern:
    """What the recent series looks like (randomness / trend), the classic demand-pattern checklist."""
    trend: bool
    slope_pct_per_h: float     # regression slope over the window, % of the level per hour
    t_stat: float              # slope / its standard error
    randomness: str            # "low" | "medium" | "high"
    noise_pct: float           # mean |D(t) - D(t-1)| as % of the level
    window: int
    alphas: tuple = ()
    seasonal: bool = False     # set later, when the hour-of-day index is kept

    @property
    def method(self) -> str:
        base = ("trend → Holt / regression allowed" if self.trend else "level → moving averages / exp. smoothing")
        return f"{base}, {self.randomness} randomness (α {self.alphas[0]:g}–{self.alphas[-1]:g})" + \
            (", hour-of-day seasonal" if self.seasonal else "")

    def to_dict(self) -> dict:
        return {"trend": self.trend, "slope_pct_per_h": self.slope_pct_per_h, "t_stat": self.t_stat,
                "randomness": self.randomness, "noise_pct": self.noise_pct, "seasonal": self.seasonal,
                "method": self.method}


TREND_T = 2.0          # |t| of the slope (≈ 95 % that it isn't zero)
TREND_MIN_MOVE = 0.01  # and the line moves ≥ 1 % of the level over the window (else it doesn't matter)
NOISE_HIGH = 2.0       # % average hour-to-hour move
NOISE_LOW = 0.5


def diagnose(D: Series, window: int = 48) -> Pattern:
    w = D[-min(window, len(D)):]
    k = len(w)
    level = sum(w) / k if k else 0.0
    xs = list(range(1, k + 1))
    a, b = linear_regression(xs, w) if k >= 3 else (level, 0.0)
    t = 0.0
    if k >= 4:
        sse = sum((y - (a + b * x)) ** 2 for x, y in zip(xs, w))
        sxx = sum((x - (k + 1) / 2) ** 2 for x in xs)
        se = math.sqrt(sse / (k - 2) / sxx) if sxx > 0 and sse > 0 else 0.0
        t = b / se if se > 0 else (0.0 if b == 0 else math.copysign(99.0, b))
    move = abs(b) * k / level if level > 0 else 0.0
    trend = k >= 12 and abs(t) >= TREND_T and move >= TREND_MIN_MOVE
    noise = (sum(abs(w[i] - w[i - 1]) for i in range(1, k)) / (k - 1) / level * 100) if k > 1 and level > 0 else 0.0
    if noise >= NOISE_HIGH:
        rnd, alphas = "high", (0.1, 0.2, 0.3)
    elif noise < NOISE_LOW:
        rnd, alphas = "low", (0.5, 0.6, 0.7, 0.8, 0.9)
    else:
        rnd, alphas = "medium", (0.2, 0.3, 0.4, 0.5, 0.6, 0.7)
    return Pattern(trend, (b / level * 100) if level > 0 else 0.0, t, rnd, noise, k, alphas)


# ----------------------------------------------------------------------------- model zoo
#
# Every model is expressed as fc(s, h): the forecast for period s+h made when the last
# observation seen is D[s]. That gives one-step forecasts (h = 1) and h-step forecasts
# from the same formulas, so each horizon can be scored on its own errors.

@dataclass
class Model:
    name: str
    fc: Callable[[int, int], Optional[float]]
    complexity: int = 0


def _models(D: Series, pattern: Optional[Pattern] = None) -> list[Model]:
    """Every model in the toolkit; with a pattern, only the family that fits it (see the module doc)."""
    n = len(D)
    alphas = pattern.alphas if pattern else (0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)
    trend_models = pattern is None or pattern.trend
    pre = [0.0]
    for v in D:
        pre.append(pre[-1] + v)
    ms: list[Model] = [Model("Last period", lambda s, h: D[s], 0)]

    for k in (2, 3, 4, 6, 12, 24):
        if n > k + 6:
            ms.append(Model(f"Moving average (n={k})",
                            lambda s, h, k=k: (pre[s + 1] - pre[s + 1 - k]) / k if s >= k - 1 else None, 1))

    for w in ([0.4, 0.35, 0.25], [0.4, 0.3, 0.2, 0.1]):
        if n > len(w) + 6:
            label = "/".join(f"{x:g}" for x in w)
            ms.append(Model(f"Weighted MA ({label})",
                            lambda s, h, w=w: sum(wi * D[s - i] for i, wi in enumerate(w)) if s >= len(w) - 1 else None, 1))

    for a in alphas:
        F, nxt = exponential_smoothing(D, a)
        S = F[1:] + [nxt]                      # S[s] = forecast made after seeing D[s]
        ms.append(Model(f"Exp. smoothing (α={a:g})", lambda s, h, S=S: S[s], 2))

    if n >= 12 and trend_models:
        for a in (0.1, 0.2, 0.3, 0.5, 0.7, 0.9):
            for b in (0.05, 0.1, 0.2, 0.3, 0.5):
                Fs, Ts = _holt_states(D, a, b)
                ms.append(Model(f"Holt (α={a:g}, β={b:g})",
                                lambda s, h, Fs=Fs, Ts=Ts: Fs[s] + h * Ts[s], 3))

    for k in (12, 24, 48):
        if n > k + 6 and trend_models:
            xs = list(range(1, k + 1))
            ab = [None] * n
            for s in range(k - 1, n):
                ab[s] = linear_regression(xs, D[s - k + 1:s + 1])
            ms.append(Model(f"Linear regression (last {k}h)",
                            lambda s, h, ab=ab, k=k: None if ab[s] is None else ab[s][0] + ab[s][1] * (k + h), 3))
    return ms


def _holt_states(D: Series, alpha: float, beta: float, init_k: int = 12):
    """F(s+1), T(s+1) after seeing D[s], using the same recursion as holt()."""
    k = max(2, min(init_k, len(D)))
    a, b = linear_regression(list(range(1, k + 1)), D[:k])
    F, T = a + b, b
    Fs, Ts = [], []
    for d in D:
        F_next = alpha * d + (1 - alpha) * F
        T_next = beta * (F_next - F) + (1 - beta) * T
        F, T = F_next, T_next
        Fs.append(F)
        Ts.append(T)
    return Fs, Ts


# ----------------------------------------------------------------------------- driver

@dataclass
class HorizonResult:
    h: int
    model: str
    seasonal: bool
    value: float
    low: float
    high: float
    acc: Accuracy
    leaderboard: list[dict] = field(default_factory=list)


@dataclass
class ForecastResult:
    last_value: float
    horizons: dict[int, HorizonResult]
    biased: bool
    pattern: Optional[Pattern] = None

    @property
    def forecasts(self) -> dict[int, float]:
        return {h: r.value for h, r in self.horizons.items()}

    @property
    def model(self) -> str:
        return self.horizons[min(self.horizons)].model

    @property
    def seasonal(self) -> bool:
        return self.horizons[min(self.horizons)].seasonal

    @property
    def accuracy(self) -> Accuracy:
        return self.horizons[min(self.horizons)].acc

    def to_dict(self) -> dict:
        hs = {}
        for h, r in self.horizons.items():
            a = r.acc
            hs[str(h)] = {"model": r.model + (" × hour-of-day index" if r.seasonal else ""),
                          "forecast": r.value, "range": [r.low, r.high],
                          "accuracy": {"mfe": a.mfe, "mad": a.mad, "mape": a.mape,
                                       "tracking_signal": a.tracking_signal, "n_eval": a.n},
                          "leaderboard": r.leaderboard}
        first = self.horizons[min(self.horizons)]
        return {"last_value": self.last_value,
                "model": hs[str(first.h)]["model"],
                "forecasts": {k: v["forecast"] for k, v in hs.items()},
                "ranges": {k: v["range"] for k, v in hs.items()},
                "accuracy": hs[str(first.h)]["accuracy"],
                "horizons": hs, "biased": self.biased,
                "pattern": self.pattern.to_dict() if self.pattern else None}


def _hstep(D: Series, m: Model, h: int) -> OneStep:
    """F[t] = forecast of D[t] made h periods earlier (origin t-h)."""
    out: OneStep = [None] * len(D)
    for t in range(h, len(D)):
        out[t] = m.fc(t - h, h)
    return out


def forecast(D: Series, hours_of_day: list[int], horizons=(1, 6, 24), eval_hours: int = 72,
             min_points: int = 12, range_level: float = 0.80, ts_limit: float = 4.0,
             by_pattern: bool = True) -> Optional[ForecastResult]:
    """For each horizon, score every model on that horizon's errors over the recent window
    (lowest MAD wins), optionally apply the hour-of-day seasonal index, and forecast.

    D: hourly prices, oldest first, no gaps. hours_of_day[t]: hour of day (0–23) of D[t].
    """
    n = len(D)
    if n < min_points:
        return None
    pattern = diagnose(D) if by_pattern else None
    models = _models(D, pattern)
    z = NormalDist().inv_cdf(0.5 + range_level / 2)
    results: dict[int, HorizonResult] = {}

    for h in horizons:
        scored = []
        for m in models:
            F = _hstep(D, m, h)
            start = next((t for t, v in enumerate(F) if v is not None), n)
            if n - start < 6:
                continue
            scored.append((m, F, start))
        if not scored:
            continue
        eval_start = max(n - eval_hours, max(s for _, _, s in scored))
        ranked = []
        for m, F, _ in scored:
            acc = accuracy(D, F, eval_start, n)
            if acc:
                ranked.append((acc.mad, m.complexity, m, F, acc))
        if not ranked:
            continue
        ranked.sort(key=lambda r: (r[0], r[1]))
        # lowest MAD wins, but a model that keeps erring one way (|TS| over the limit) is passed over
        pick = next((r for r in ranked if abs(r[4].tracking_signal) <= ts_limit), ranked[0])
        _, _, best, F, acc = pick
        idx = None

        # Seasonal 4-step procedure with season = hour of day. Indices are learned on data
        # *before* the scoring window and kept only if they cut MAD there by > 3 %.
        if eval_start >= 48 + h:
            cand_idx = seasonal_indices(D, F, hours_of_day, h, eval_start)
            if cand_idx:
                adj = [None if f is None else f * cand_idx[hours_of_day[t]] for t, f in enumerate(F)]
                acc_adj = accuracy(D, adj, eval_start, n)
                if acc_adj and acc_adj.mad < acc.mad * 0.97:
                    idx = seasonal_indices(D, F, hours_of_day, h, n) or cand_idx
                    acc = acc_adj

        v = best.fc(n - 1, h)
        if idx:
            v *= idx[(hours_of_day[-1] + h) % 24]
        v = max(v, 0.0)
        sigma = math.sqrt(math.pi / 2) * acc.mad        # σ ≈ 1.25·MAD, MAD measured at this horizon
        results[h] = HorizonResult(
            h, best.name, idx is not None, v, max(v - z * sigma, 0.0), v + z * sigma, acc,
            [{"model": m.name, "mad": a.mad, "mape": a.mape, "mfe": a.mfe} for _, _, m, _, a in ranked[:5]])

    if not results:
        return None
    first = results[min(results)]
    if pattern:
        pattern.seasonal = any(r.seasonal for r in results.values())
    return ForecastResult(D[-1], results, abs(first.acc.tracking_signal) > ts_limit, pattern)
