import sys
from pathlib import Path
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bot.risk import RiskGate
from bot.strategy import decide, sma
from bot.ledger import Ledger


def gate(kill: Path):
    return RiskGate(max_order_usdt=20, max_daily_loss_usdt=10, max_position_usdt=50, kill_switch_file=kill)


def test_sma():
    assert sma([1, 2, 3, 4], 2) == 3.5
    assert sma([1], 2) is None


def test_kill_switch_blocks():
    with tempfile.TemporaryDirectory() as d:
        k = Path(d) / "KILL"
        k.touch()
        v = gate(k).check_buy(5, 1, 0, 0)
        assert not v.ok and "kill" in v.reason


def test_daily_loss_blocks():
    with tempfile.TemporaryDirectory() as d:
        v = gate(Path(d) / "KILL").check_buy(5, 1, 0, -10)
        assert not v.ok


def test_order_limits():
    with tempfile.TemporaryDirectory() as d:
        g = gate(Path(d) / "KILL")
        assert not g.check_buy(25, 1, 0, 0).ok          # üst limit
        assert not g.check_buy(0.5, 1, 0, 0).ok         # minimum altı
        assert not g.check_buy(10, 1, 45, 0).ok         # pozisyon limiti
        assert g.check_buy(10, 1, 0, 0).ok


def test_strategy_cross():
    up = [10] * 30 + [11, 12, 13, 14, 20]  # son mumda hızlı SMA yukarı keser
    assert decide(up, 3, 10, in_position=False).action in ("BUY", "HOLD")


def test_ledger_position_and_pnl():
    with tempfile.TemporaryDirectory() as d:
        led = Ledger(Path(d) / "l.sqlite")
        led.record("BTCUSDT", "BUY", 2, 100, 200, 0, "dry-run")
        led.record("BTCUSDT", "SELL", 1, 110, 110, 10, "dry-run")
        qty, cost = led.open_position("BTCUSDT")
        assert abs(qty - 1) < 1e-9 and abs(cost - 100) < 1e-9
        assert led.daily_realized_pnl() == 10
