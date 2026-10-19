"""Basit SMA kesişim stratejisi. Eğitim/test amaçlıdır, kâr garantisi yoktur."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Signal:
    action: str  # "BUY", "SELL" veya "HOLD"
    reason: str


def sma(values: list[float], period: int) -> float | None:
    if len(values) < period:
        return None
    return sum(values[-period:]) / period


def decide(closes: list[float], fast: int, slow: int, in_position: bool) -> Signal:
    fast_now, slow_now = sma(closes, fast), sma(closes, slow)
    fast_prev, slow_prev = sma(closes[:-1], fast), sma(closes[:-1], slow)
    if None in (fast_now, slow_now, fast_prev, slow_prev):
        return Signal("HOLD", "yetersiz veri")

    crossed_up = fast_prev <= slow_prev and fast_now > slow_now
    crossed_down = fast_prev >= slow_prev and fast_now < slow_now

    if crossed_up and not in_position:
        return Signal("BUY", f"SMA{fast} SMA{slow}'ü yukarı kesti")
    if crossed_down and in_position:
        return Signal("SELL", f"SMA{fast} SMA{slow}'ü aşağı kesti")
    return Signal("HOLD", "sinyal yok")
