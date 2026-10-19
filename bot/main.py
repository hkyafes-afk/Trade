"""Ana döngü: veri → strateji → risk → emir (dry-run veya testnet) → kayıt."""
import json
import logging
import time
from datetime import datetime, timezone

from .binance_client import BinanceClient
from .config import load_settings
from .ledger import Ledger
from .risk import RiskGate
from .strategy import decide

log = logging.getLogger("bot")

# Panelin okuyacağı canlı durum. Her döngüde status.json'a yazılır.
STATUS: dict = {
    "updated": None, "price": None, "in_position": False, "position_qty": 0.0,
    "signal": "HOLD", "signal_reason": "", "last_action": None,
    "risk": {"state": "ACTIVE", "reason": ""}, "last_error": None,
}


def step(settings, client, ledger, gate):
    closes = [float(k[4]) for k in client.klines(settings.symbol, settings.interval, limit=100)]
    price = closes[-1]
    qty, cost = ledger.open_position(settings.symbol)
    in_position = qty > 0

    signal = decide(closes, settings.fast_ma, settings.slow_ma, in_position)
    log.info("fiyat=%.4f pozisyon=%s sinyal=%s (%s)", price, in_position, signal.action, signal.reason)
    STATUS.update(price=price, in_position=in_position, position_qty=qty,
                  signal=signal.action, signal_reason=signal.reason)

    if signal.action == "BUY":
        min_notional = _min_notional(client, settings.symbol)
        amount = settings.max_order_usdt
        verdict = gate.check_buy(
            amount, min_notional,
            ledger.position_value_usdt(settings.symbol, price),
            ledger.daily_realized_pnl(),
        )
        STATUS["risk"] = {"state": "ACTIVE" if verdict.ok else "BLOCKED", "reason": verdict.reason}
        if not verdict.ok:
            log.warning("ALIM REDDEDİLDİ: %s", verdict.reason)
            return
        STATUS["last_action"] = f"ALIM {amount} USDT"
        if settings.dry_run:
            est_qty = amount / price
            ledger.record(settings.symbol, "BUY", est_qty, price, amount, 0, "dry-run", signal.reason)
            log.info("[DRY-RUN] ALIM %.2f USDT ≈ %.8f", amount, est_qty)
        else:
            res = client.market_buy_quote(settings.symbol, amount)
            fill_qty = sum(float(f["qty"]) for f in res["fills"])
            fill_quote = sum(float(f["qty"]) * float(f["price"]) for f in res["fills"])
            ledger.record(settings.symbol, "BUY", fill_qty, fill_quote / fill_qty, fill_quote, 0,
                          "testnet", signal.reason)
            log.info("[TESTNET] ALIM gerçekleşti: %.8f adet", fill_qty)

    elif signal.action == "SELL" and in_position:
        verdict = gate.check_sell()
        if not verdict.ok:
            log.warning("SATIŞ REDDEDİLDİ: %s", verdict.reason)
            return
        avg_cost = cost / qty
        realized = (price - avg_cost) * qty
        if settings.dry_run:
            ledger.record(settings.symbol, "SELL", qty, price, qty * price, realized, "dry-run", signal.reason)
            log.info("[DRY-RUN] SATIŞ %.8f, tahmini K/Z %.4f USDT", qty, realized)
        else:
            res = client.market_sell_qty(settings.symbol, f"{qty:.6f}")
            fill_quote = sum(float(f["qty"]) * float(f["price"]) for f in res["fills"])
            ledger.record(settings.symbol, "SELL", qty, price, fill_quote, realized, "testnet", signal.reason)
            log.info("[TESTNET] SATIŞ gerçekleşti, K/Z %.4f USDT", realized)


def _min_notional(client, symbol) -> float:
    info = client.exchange_info(symbol)["symbols"][0]
    for f in info["filters"]:
        if f["filterType"] in ("MIN_NOTIONAL", "NOTIONAL"):
            return float(f.get("minNotional", f.get("notional", 0)))
    return 0.0


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    s = load_settings()
    if not s.api_key or not s.api_secret:
        raise SystemExit(".env içinde testnet API anahtarı yok.")

    client = BinanceClient(s.base_url, s.api_key, s.api_secret)
    ledger = Ledger(s.db_path)
    gate = RiskGate(s.max_order_usdt, s.max_daily_loss_usdt, s.max_position_usdt, s.kill_switch_file)
    log.info("Başlatıldı: %s %s dry_run=%s", s.symbol, s.interval, s.dry_run)

    status_file = s.db_path.parent / "status.json"
    while True:
        try:
            step(s, client, ledger, gate)
            STATUS["last_error"] = None
        except Exception as e:
            log.exception("Döngü hatası, bir sonraki turda tekrar denenecek")
            STATUS["last_error"] = str(e)[:300]
        STATUS["updated"] = datetime.now(timezone.utc).isoformat()
        STATUS["risk"]["state"] = "HALTED" if s.kill_switch_file.exists() else STATUS["risk"]["state"]
        status_file.write_text(json.dumps(STATUS, ensure_ascii=False, indent=2))
        time.sleep(s.poll_seconds)


if __name__ == "__main__":
    main()
