"""Checks the formulas against the worked examples in Lectures 4–5 (EXC 3650)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from market.forecast import (accuracy, exponential_smoothing, forecast, linear_regression,
                             moving_average, weighted_moving_average)

ER = [84, 81, 89, 90, 99, 106, 127, 117, 127, 103, 96, 96, 86, 101, 109]   # Table 9.3


def close(a, b, tol):
    assert abs(a - b) <= tol, f"{a} != {b}"


def test_moving_average_table_9_4():
    ma2 = moving_average(ER + [0], 2)
    ma4 = moving_average(ER + [0], 4)
    close(ma2[2], 82.5, 1e-9)       # week 3
    close(ma4[4], 86.0, 1e-9)       # week 5
    close(ma4[8], 112.25, 1e-9)     # week 9 (slide rounds to 112.3)
    close(ma2[15], 105.0, 1e-9)     # week 16
    close(ma4[15], 98.0, 1e-9)      # week 16


def test_weighted_ma_example_9_1():
    pizzas = [62, 45, 55, 73, 60]
    close(weighted_moving_average(pizzas + [0], [0.4, 0.35, 0.25])[5], 63.3, 0.01)
    close(moving_average(pizzas + [0], 2)[5], 66.5, 1e-9)


def test_exponential_smoothing_example_9_2():
    D = [10, 11, 9, 11, 10, 8]
    F, nxt = exponential_smoothing(D, 0.8)
    for got, want in zip(F[1:], [10, 10.8, 9.36, 10.67, 10.13]):
        close(got, want, 0.01)
    close(nxt, 8.43, 0.01)          # F7


def test_regression_example_9_3():
    y = [8, 12, 25, 40, 50, 65, 36, 61, 88, 63]
    a, b = linear_regression(list(range(1, 11)), y)
    close(a, 4.47, 0.01)
    close(b, 7.33, 0.01)
    close(a + b * 11, 85.1, 0.1)


def test_accuracy_example_9_7():
    D = [18, 14, 21, 26, 26, 29, 19, 19, 25, 15]
    m1 = [20, 18, 19, 21, 23, 24, 25, 22, 23, 24]
    m2 = [21, 21, 21, 25, 25, 25, 19, 19, 19, 19]
    a1 = accuracy(D, m1, 0, 10)
    a2 = accuracy(D, m2, 0, 10)
    close(a1.mfe, -0.70, 1e-9); close(a1.mad, 4.10, 1e-9); close(a1.mape, 21.26, 0.01)
    close(a2.mfe, -0.20, 1e-9); close(a2.mad, 2.60, 1e-9); close(a2.mape, 13.88, 0.01)
    close(a2.tracking_signal, -0.77, 0.01)   # running ΣFE = -2, MAD = 2.60 (slide prints 0.77 without the sign)


def test_seasonal_index_example_9_4():
    # January index = average of Demand/Forecast for Jan 2019 and Jan 2020
    close(((51 / 106.9) + (112 / 205.6)) / 2, 0.511, 0.001)


def test_forecast_picks_trend_model_on_trend():
    D = [100 + 2 * t for t in range(60)]
    r = forecast(D, [t % 24 for t in range(60)])
    assert "regression" in r.model.lower() or "holt" in r.model.lower(), r.model
    close(r.forecasts[1], 220, 1.0)


def test_forecast_detects_daily_pattern():
    import math
    D = [1000 * (1 + 0.15 * math.sin(2 * math.pi * (t % 24) / 24)) for t in range(24 * 7)]
    r = forecast(D, [t % 24 for t in range(len(D))])
    assert r.seasonal, r.to_dict()
    assert r.accuracy.mape < 2


def test_holt_model_matches_slide_recursion():
    from market.forecast import holt, _models
    D = [100 + 3 * t + (5 if t % 3 == 0 else -2) for t in range(40)]
    AF, _, _ = holt(D, 0.3, 0.2)
    m = next(m for m in _models(D) if m.name == "Holt (α=0.3, β=0.2)")
    for t in range(1, 40):
        close(m.fc(t - 1, 1), AF[t], 1e-9)


def test_long_horizon_not_fooled_by_daily_wave():
    import math
    D = [1000 * (1 + 0.05 * math.sin(2 * math.pi * (t % 24) / 24)) for t in range(24 * 10)]
    r = forecast(D, [t % 24 for t in range(len(D))])
    target = 1000 * (1 + 0.05 * math.sin(2 * math.pi * ((len(D) - 1 + 24) % 24) / 24))
    close(r.forecasts[24], target, 15)


def test_pattern_picks_the_method_family():
    """Demand-pattern slide: trend → trend models allowed; pure randomness → level models with low α."""
    import random
    from market.forecast import diagnose, _models
    trend = [100 + 2 * t for t in range(60)]
    p = diagnose(trend)
    assert p.trend and p.slope_pct_per_h > 0, p
    rnd = random.Random(7)
    noisy = [1000 * (1 + 0.05 * rnd.uniform(-1, 1)) for _ in range(120)]
    q = diagnose(noisy)
    assert not q.trend and q.randomness == "high" and max(q.alphas) <= 0.3, q
    names = [m.name for m in _models(noisy, q)]
    assert not any("Holt" in n or "regression" in n for n in names), names
    calm = [1000 + (0.5 if t % 2 else -0.5) for t in range(80)]
    assert diagnose(calm).randomness == "low"
    r = forecast(noisy, [t % 24 for t in range(len(noisy))])
    assert r.pattern and not r.pattern.trend and "level" in r.pattern.method
    assert "Holt" not in r.model and "regression" not in r.model, r.model


def test_biased_model_is_passed_over():
    """A model with |tracking signal| over the limit loses to the next one that isn't biased."""
    D = [100 + 2 * t for t in range(60)]
    r = forecast(D, [t % 24 for t in range(60)], ts_limit=0.5)
    assert abs(r.horizons[1].acc.tracking_signal) <= 0.5 or r.biased


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
