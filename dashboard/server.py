"""Salt-okunur panel sunucusu. Sadece defter (SQLite) ve status.json okur, emir göndermez.
Varsayılan olarak sadece bu bilgisayardan erişilir (127.0.0.1)."""
import json
import os
import sqlite3
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = Path(os.getenv("DATA_DIR", ROOT.parent / "data"))
DB = DATA / "ledger.sqlite"
STATUS = DATA / "status.json"


def read_state() -> dict:
    status = {}
    if STATUS.exists():
        status = json.loads(STATUS.read_text())
    trades, equity = [], []
    if DB.exists():
        con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
        con.row_factory = sqlite3.Row
        trades = [dict(r) for r in con.execute(
            "SELECT ts, symbol, side, qty, price, quote_usdt, realized_pnl, mode, note "
            "FROM trades ORDER BY id DESC LIMIT 50")]
        cum = 0.0
        for r in con.execute("SELECT ts, realized_pnl FROM trades WHERE realized_pnl != 0 ORDER BY id"):
            cum += r["realized_pnl"]
            equity.append({"ts": r["ts"], "cum_pnl": round(cum, 4)})
        con.close()
    return {"status": status, "trades": trades, "pnl_series": equity}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT), **kw)

    def do_GET(self):
        if self.path.startswith("/api/state"):
            body = json.dumps(read_state(), ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path in ("/", ""):
            self.path = "/index.html"
        return super().do_GET()

    def do_POST(self):
        # Panelden emir yok. Yalnızca kill switch dosyasını oluşturur/siler.
        if self.path == "/api/kill":
            STATUS.parent.mkdir(parents=True, exist_ok=True)
            (DATA / "KILL").touch()
            return self._ok({"halted": True})
        if self.path == "/api/resume":
            (DATA / "KILL").unlink(missing_ok=True)
            return self._ok({"halted": False})
        self.send_error(404)

    def _ok(self, payload):
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    host = os.getenv("DASH_HOST", "127.0.0.1")
    port = int(os.getenv("DASH_PORT", "8787"))
    print(f"Panel: http://{host}:{port}")
    ThreadingHTTPServer((host, port), Handler).serve_forever()
