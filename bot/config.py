"""Yapılandırma. Varsayılan: testnet + dry-run. Gerçek ortam kodda kapalı."""
import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

TESTNET_URL = "https://testnet.binance.vision"


@dataclass(frozen=True)
class Settings:
    api_key: str
    api_secret: str
    base_url: str
    symbol: str
    interval: str
    fast_ma: int
    slow_ma: int
    max_order_usdt: float
    max_daily_loss_usdt: float
    max_position_usdt: float
    dry_run: bool
    db_path: Path
    kill_switch_file: Path
    poll_seconds: int


def load_settings() -> Settings:
    env = os.getenv("BOT_ENV", "testnet").lower()
    if env != "testnet":
        # Bu sürümde gerçek ortam bilerek kapalı. Canlıya geçiş ayrı bir sürümde,
        # testnet sonuçları ve açık onayla yapılacak.
        raise SystemExit("Bu sürüm sadece testnet'te çalışır (BOT_ENV=testnet).")

    return Settings(
        api_key=os.getenv("BINANCE_TESTNET_API_KEY", ""),
        api_secret=os.getenv("BINANCE_TESTNET_API_SECRET", ""),
        base_url=TESTNET_URL,
        symbol=os.getenv("SYMBOL", "BTCUSDT"),
        interval=os.getenv("INTERVAL", "1m"),
        fast_ma=int(os.getenv("FAST_MA", "10")),
        slow_ma=int(os.getenv("SLOW_MA", "30")),
        max_order_usdt=float(os.getenv("MAX_ORDER_USDT", "20")),
        max_daily_loss_usdt=float(os.getenv("MAX_DAILY_LOSS_USDT", "10")),
        max_position_usdt=float(os.getenv("MAX_POSITION_USDT", "50")),
        dry_run=os.getenv("DRY_RUN", "true").lower() == "true",
        db_path=Path(os.getenv("DB_PATH", "data/ledger.sqlite")),
        kill_switch_file=Path(os.getenv("KILL_SWITCH_FILE", "data/KILL")),
        poll_seconds=int(os.getenv("POLL_SECONDS", "60")),
    )
