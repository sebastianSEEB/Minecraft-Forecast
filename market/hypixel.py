"""Tiny client for the public Hypixel SkyBlock endpoints (stdlib only)."""
from __future__ import annotations

import gzip
import json
import time
import urllib.error
import urllib.request


class HypixelClient:
    def __init__(self, base: str, user_agent: str, timeout: float = 30):
        self.base = base.rstrip("/")
        self.ua = user_agent
        self.timeout = timeout

    def _get(self, path: str, retries: int = 3, key: str | None = None, absolute: bool = False) -> dict:
        url = path if absolute else f"{self.base}{path}"
        last = None
        for attempt in range(retries):
            try:
                headers = {"User-Agent": self.ua, "Accept-Encoding": "gzip"}
                if key:
                    headers["API-Key"] = key
                req = urllib.request.Request(url, headers=headers)
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    raw = r.read()
                    if r.headers.get("Content-Encoding", "").lower() == "gzip" or raw[:2] == b"\x1f\x8b":
                        raw = gzip.decompress(raw)
                    data = json.loads(raw.decode("utf-8"))
                if not absolute and not data.get("success", False):
                    raise RuntimeError(f"API returned success=false for {path}: {data.get('cause')}")
                return data
            except urllib.error.HTTPError as e:
                if e.code in (403, 404):            # bad / expired key, unknown player: retrying won't help
                    raise RuntimeError(f"HTTP {e.code} for {path.split('?')[0]}"
                                       + (" (API key invalid or expired)" if e.code == 403 else "")) from e
                last = e
                time.sleep(2 * (attempt + 1))
            except (urllib.error.URLError, TimeoutError, RuntimeError, ValueError) as e:
                last = e
                time.sleep(2 * (attempt + 1))
        raise RuntimeError(f"GET {url} failed: {last}")

    def bazaar(self) -> dict:
        return self._get("/skyblock/bazaar")

    def auctions_ended(self) -> dict:
        return self._get("/skyblock/auctions_ended")

    def uuid_of(self, name: str) -> str:
        """Minecraft name -> UUID (Mojang API, no key needed)."""
        return self._get(f"https://api.mojang.com/users/profiles/minecraft/{name}", absolute=True)["id"]

    def profiles(self, uuid: str, key: str) -> dict:
        return self._get(f"/skyblock/profiles?uuid={uuid}", key=key)

    def items_resource(self) -> dict:
        return self._get("/resources/skyblock/items")

    def auctions_page(self, page: int) -> dict:
        return self._get(f"/skyblock/auctions?page={page}")
