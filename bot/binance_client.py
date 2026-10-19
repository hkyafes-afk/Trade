"""Binance Spot REST istemcisi (testnet). İmzalı istekler HMAC-SHA256 ile."""
import hashlib
import hmac
import time
from urllib.parse import urlencode

import requests


class BinanceError(RuntimeError):
    pass


class BinanceClient:
    def __init__(self, base_url: str, api_key: str, api_secret: str, timeout: int = 10):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.api_secret = api_secret.encode()
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers["X-MBX-APIKEY"] = api_key

    def _request(self, method: str, path: str, params: dict | None = None, signed: bool = False):
        params = dict(params or {})
        if signed:
            params["timestamp"] = int(time.time() * 1000)
            params["recvWindow"] = 5000
            query = urlencode(params)
            params["signature"] = hmac.new(self.api_secret, query.encode(), hashlib.sha256).hexdigest()

        resp = self.session.request(method, self.base_url + path, params=params, timeout=self.timeout)
        if resp.status_code >= 400:
            raise BinanceError(f"{resp.status_code} {path}: {resp.text}")
        return resp.json()

    # Piyasa verisi (imzasız)
    def exchange_info(self, symbol: str) -> dict:
        return self._request("GET", "/api/v3/exchangeInfo", {"symbol": symbol})

    def klines(self, symbol: str, interval: str, limit: int = 100) -> list:
        return self._request("GET", "/api/v3/klines", {"symbol": symbol, "interval": interval, "limit": limit})

    def price(self, symbol: str) -> float:
        return float(self._request("GET", "/api/v3/ticker/price", {"symbol": symbol})["price"])

    # Hesap ve emirler (imzalı)
    def account(self) -> dict:
        return self._request("GET", "/api/v3/account", signed=True)

    def balance(self, asset: str) -> float:
        for b in self.account()["balances"]:
            if b["asset"] == asset:
                return float(b["free"])
        return 0.0

    def market_buy_quote(self, symbol: str, quote_usdt: float) -> dict:
        return self._request(
            "POST",
            "/api/v3/order",
            {"symbol": symbol, "side": "BUY", "type": "MARKET", "quoteOrderQty": f"{quote_usdt:.2f}"},
            signed=True,
        )

    def market_sell_qty(self, symbol: str, qty: str) -> dict:
        return self._request(
            "POST",
            "/api/v3/order",
            {"symbol": symbol, "side": "SELL", "type": "MARKET", "quantity": qty},
            signed=True,
        )
