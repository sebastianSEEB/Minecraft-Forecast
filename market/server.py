"""Local HTTP server (127.0.0.1 only) that the Minecraft mod and your browser read from.

  GET /itemdata/<KEY>   full item document (same format as data/items/<KEY>.json) — used by the mod
  GET /hud/<KEY>        tooltip lines (used by the lookup page)
  GET /item/<KEY>       full stats + forecast JSON
  GET /history/<SERIES>?hours=168   hourly bars (series: bz:<ID>:buy, ah:<KEY>, lbin:<KEY>)
  GET /search?q=hyper   matching item keys
  GET /health           collector status
  GET /                 small lookup page
"""
from __future__ import annotations

import json
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .collector import STATUS
from .config import Config
from .db import HOUR_MS, Database
from .hud import hud_lines
from .detail import item_detail
from .stats import item_stats, search

_CACHE: dict[str, tuple[float, dict]] = {}
_CACHE_LOCK = threading.Lock()
CACHE_SECONDS = 30
INDEX_HTML = (Path(__file__).parent / "index.html").read_text(encoding="utf-8")


def make_handler(cfg: Config, db: Database):
    local = threading.local()

    def conn():
        if not hasattr(local, "c"):
            local.c = db.connect()
        return local.c

    def cached_stats(key: str) -> dict:
        now = time.time()
        with _CACHE_LOCK:
            hit = _CACHE.get(key)
            if hit and now - hit[0] < CACHE_SECONDS:
                return hit[1]
        s = item_stats(conn(), key, cfg)
        with _CACHE_LOCK:
            _CACHE[key] = (now, s)
            if len(_CACHE) > 5000:
                _CACHE.clear()
        return s

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code: int, body, ctype="application/json"):
            data = body.encode() if isinstance(body, str) else json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def _static(self, root: Path, parts: list[str]):
            root = root.resolve()
            f = (root.joinpath(*parts) if parts else root / "index.html").resolve()
            if root not in f.parents and f != root / "index.html" or not f.is_file():
                if not (root / "index.html").exists():
                    return self._send(404, "Dashboard not built yet - it is exported a few minutes after start "
                                           "(or run: python -m market.publish --no-push)", "text/plain")
                return self._send(404, {"error": "not found"})
            ctype = {".html": "text/html", ".js": "text/javascript", ".css": "text/css",
                     ".json": "application/json"}.get(f.suffix, "application/octet-stream")
            data = f.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", ctype + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            u = urllib.parse.urlparse(self.path)
            parts = [urllib.parse.unquote(p) for p in u.path.strip("/").split("/") if p]
            qs = urllib.parse.parse_qs(u.query)
            try:
                if not parts:
                    return self._send(200, INDEX_HTML, "text/html")
                if u.path == "/dashboard":
                    self.send_response(301); self.send_header("Location", "/dashboard/"); self.end_headers(); return
                if parts[0] == "dashboard":
                    return self._static(Path(cfg.db_path).parent / "site", parts[1:])
                if parts[0] == "hud" and len(parts) == 2:
                    s = cached_stats(parts[1].upper())
                    return self._send(200, {"key": s["key"], "kind": s["kind"], "lines": hud_lines(s)})
                if parts[0] == "itemdata" and len(parts) == 2:
                    key = parts[1].upper().replace("__", ":")
                    with _CACHE_LOCK:
                        hit = _CACHE.get("doc:" + key)
                    if hit and time.time() - hit[0] < CACHE_SECONDS:
                        doc = hit[1]
                    else:
                        doc = item_detail(conn(), key, cfg)
                        with _CACHE_LOCK:
                            _CACHE["doc:" + key] = (time.time(), doc)
                    return self._send(200 if doc else 404, doc or {"error": "unknown item"})
                if parts[0] == "item" and len(parts) == 2:
                    return self._send(200, cached_stats(parts[1].upper()))
                if parts[0] == "history" and len(parts) == 2:
                    hours = int(qs.get("hours", ["168"])[0])
                    now_h = int(time.time() * 1000) // HOUR_MS
                    rows = conn().execute("SELECT hour, avg, median, high, low, n, volume FROM hourly WHERE series=? AND hour>? ORDER BY hour",
                                          (parts[1], now_h - hours)).fetchall()
                    return self._send(200, [dict(r) | {"ts": r["hour"] * HOUR_MS} for r in rows])
                if parts[0] == "search":
                    return self._send(200, search(conn(), qs.get("q", [""])[0]))
                if parts[0] == "health":
                    c = conn()
                    counts = {t: c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
                              for t in ("bz_now", "ah_sale", "lbin_now", "hourly")}
                    return self._send(200, {"jobs": STATUS, "rows": counts})
                return self._send(404, {"error": "not found"})
            except Exception as e:
                return self._send(500, {"error": str(e)})

    return Handler


def serve(cfg: Config, db: Database) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((cfg.server_host, cfg.server_port), make_handler(cfg, db))
    threading.Thread(target=srv.serve_forever, daemon=True, name="server").start()
    return srv
