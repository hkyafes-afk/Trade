"""SQLite defteri: her emir, her işlem ve gerçekleşen kâr/zarar kaydı."""
import sqlite3
from datetime import datetime, timezone
from pathlib import Path


class Ledger:
    def __init__(self, db_path: Path):
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(db_path)
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts TEXT NOT NULL,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                qty REAL NOT NULL,
                price REAL NOT NULL,
                quote_usdt REAL NOT NULL,
                realized_pnl REAL NOT NULL DEFAULT 0,
                mode TEXT NOT NULL,
                note TEXT
            )"""
        )
        self.conn.commit()

    def record(self, symbol, side, qty, price, quote_usdt, realized_pnl, mode, note=""):
        ts = datetime.now(timezone.utc).isoformat()
        self.conn.execute(
            "INSERT INTO trades (ts, symbol, side, qty, price, quote_usdt, realized_pnl, mode, note)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            (ts, symbol, side, qty, price, quote_usdt, realized_pnl, mode, note),
        )
        self.conn.commit()

    def daily_realized_pnl(self) -> float:
        today = datetime.now(timezone.utc).date().isoformat()
        row = self.conn.execute(
            "SELECT COALESCE(SUM(realized_pnl), 0) FROM trades WHERE ts LIKE ?", (today + "%",)
        ).fetchone()
        return float(row[0])

    def open_position(self, symbol) -> tuple[float, float]:
        """(qty, ortalama maliyet USDT) döndürür. Sadece spot long pozisyon."""
        rows = self.conn.execute(
            "SELECT side, qty, price FROM trades WHERE symbol=? ORDER BY id", (symbol,)
        ).fetchall()
        qty, cost = 0.0, 0.0
        for side, q, p in rows:
            if side == "BUY":
                qty += q
                cost += q * p
            elif side == "SELL" and qty > 0:
                avg = cost / qty
                sell_q = min(q, qty)
                qty -= sell_q
                cost -= sell_q * avg
        return qty, cost

    def position_value_usdt(self, symbol, price: float) -> float:
        qty, _ = self.open_position(symbol)
        return qty * price

    def recent_trades(self, limit: int = 30) -> list[dict]:
        rows = self.conn.execute(
            "SELECT ts, symbol, side, price, quote_usdt, realized_pnl, mode FROM trades ORDER BY id DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [
            {"ts": r[0], "symbol": r[1], "side": r[2], "price": r[3],
             "quote_usdt": r[4], "realized_pnl": r[5], "mode": r[6]}
            for r in rows
        ]

    def pnl_series(self) -> list[dict]:
        rows = self.conn.execute(
            "SELECT ts, realized_pnl FROM trades WHERE realized_pnl != 0 ORDER BY id"
        ).fetchall()
        cum, out = 0.0, []
        for ts, pnl in rows:
            cum += pnl
            out.append({"ts": ts, "cum_pnl": round(cum, 4)})
        return out
