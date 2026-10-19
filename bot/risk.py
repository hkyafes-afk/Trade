"""Risk kapısı. Tüm emirler buradan geçer; sınırı aşan emir reddedilir."""
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Verdict:
    ok: bool
    reason: str


class RiskGate:
    def __init__(
        self,
        max_order_usdt: float,
        max_daily_loss_usdt: float,
        max_position_usdt: float,
        kill_switch_file: Path,
    ):
        self.max_order_usdt = max_order_usdt
        self.max_daily_loss_usdt = max_daily_loss_usdt
        self.max_position_usdt = max_position_usdt
        self.kill_switch_file = kill_switch_file

    def check_buy(self, amount_usdt: float, min_notional: float, position_usdt: float,
                  daily_realized_pnl: float) -> Verdict:
        if self.kill_switch_file.exists():
            return Verdict(False, "kill switch aktif")
        if daily_realized_pnl <= -self.max_daily_loss_usdt:
            return Verdict(False, f"günlük kayıp limiti doldu ({daily_realized_pnl:.2f} USDT)")
        if amount_usdt < min_notional:
            return Verdict(False, f"tutar minimum emirden küçük ({amount_usdt} < {min_notional})")
        if amount_usdt > self.max_order_usdt:
            return Verdict(False, f"emir tutarı üst limiti aşıyor ({amount_usdt} > {self.max_order_usdt})")
        if position_usdt + amount_usdt > self.max_position_usdt:
            return Verdict(False, "toplam pozisyon limiti aşılıyor")
        return Verdict(True, "onaylandı")

    def check_sell(self) -> Verdict:
        # Satış riski azaltır; sadece kill switch tamamen durdurur ama yine de
        # açık pozisyonu kapatmak için satışa izin verilir. Bu kural bilinçli.
        return Verdict(True, "satış onaylandı")
