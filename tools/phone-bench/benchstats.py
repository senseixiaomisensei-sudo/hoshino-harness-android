"""Statistics shared by the phone-bench host scripts. Same definitions as StepStats.kt (nearest-rank percentiles)."""
import math


def percentile(values, p):
    """Nearest-rank percentile for p in (0, 100]; None when empty. Always returns an observed value."""
    if not 0 < p <= 100:
        raise ValueError("percentile must be in (0, 100]")
    if not values:
        return None
    s = sorted(values)
    rank = min(max(math.ceil(p / 100.0 * len(s)), 1), len(s))
    return s[rank - 1]


def summarize(values):
    values = [int(v) for v in values]
    if not values:
        return {"n": 0, "p50": None, "p90": None, "min": None, "max": None, "mean": None}
    return {
        "n": len(values),
        "p50": percentile(values, 50),
        "p90": percentile(values, 90),
        "min": min(values),
        "max": max(values),
        "mean": round(sum(values) / len(values), 1),
    }


if __name__ == "__main__":
    assert percentile(list(range(1, 11)), 50) == 5
    assert percentile(list(range(1, 11)), 90) == 9
    assert percentile([400, 600], 50) == 400
    assert percentile([], 50) is None
    assert summarize([500, 100, 300, 900, 200]) == {"n": 5, "p50": 300, "p90": 900, "min": 100, "max": 900, "mean": 400.0}
    print("benchstats ok")
