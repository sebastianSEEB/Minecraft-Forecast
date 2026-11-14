"""Tiny client for the public Hypixel SkyBlock endpoints (stdlib only)."""
from __future__ import annotations

import json
import time
import urllib.error
import urllib.request


class HypixelClient:
    def __init__(self, base: str, user_agent: str, timeout: float = 30):
        self.base = base.rstrip("/")
        self.ua = user_agent
        self.timeout = timeout

    def _get(self, path: str, retries: int = 3) -> dict:
        url = f"{self.base}{path}"
        last = None
        for attempt in range(retries):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": self.ua, "Accept-Encoding": "identity"})
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    data = json.loads(r.read().decode("utf-8"))
                if not data.get("success", False):
                    raise RuntimeError(f"API returned success=false for {path}: {data.get('cause')}")
                return data
            except (urllib.error.URLError, TimeoutError, RuntimeError, ValueError) as e:
                last = e
                time.sleep(2 * (attempt + 1))
        raise RuntimeError(f"GET {url} failed: {last}")

    def bazaar(self) -> dict:
        return self._get("/skyblock/bazaar")

    def auctions_ended(self) -> dict:
        return self._get("/skyblock/auctions_ended")

    def auctions_page(self, page: int) -> dict:
        return self._get(f"/skyblock/auctions?page={page}")
