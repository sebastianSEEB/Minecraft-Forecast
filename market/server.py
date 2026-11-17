"""Local HTTP server (127.0.0.1 only) that the Minecraft mod and your browser read from.

  GET /itemdata/<KEY>   full item document (same format as data/items/<KEY>.json) — used by the mod
  GET /hud/<KEY>        tooltip lines (used by the lookup page)
  GET /item/<KEY>       full stats + forecast JSON
  GET /signals           bazaar misprices, correlations and suggestions (ERP → Signals)
  GET /forecasts         every item's 1h/6h/24h forecast (compact; the mod's flip lists use it)
  GET /history/<SERIES>?hours=168   hourly bars (series: bz:<ID>:buy, ah:<KEY>, lbin:<KEY>)
  GET /lbin?keys=A,B    fresh lowest BIN for a few items (re-checked every 30 s) — the mod's watch list
  GET /deals            underpriced BINs from the latest full AH scan (every 5 min)
  GET /search?q=hyper   matching item keys
  POST /wallet          {"purse": 123, "player": "name"} from the mod (purse read from the SkyBlock sidebar)
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

        def do_POST(self):
            u = urllib.parse.urlparse(self.path)
            if u.path != "/wallet":
                return self._send(404, {"error": "not found"})
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
                purse = float(body["purse"])
                player = str(body.get("player") or "")[:32]
                c = conn()
                last = c.execute("SELECT bank, personal_bank FROM wallet WHERE source='api' ORDER BY ts DESC LIMIT 1").fetchone()
                bank = last["bank"] if last else None
                personal = last["personal_bank"] if last else None
                ts = int(time.time() * 1000)
                with db.write_lock, c:
                    c.execute("INSERT OR REPLACE INTO wallet VALUES(?,?,?,?,?,?,?,?)",
                              (ts, "mod", player, None, purse, bank, personal, purse + (bank or 0) + (personal or 0)))
                return self._send(200, {"ok": True})
            except Exception as e:
                return self._send(400, {"error": str(e)})

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
                if parts[0] == "signals":
                    f = Path(cfg.db_path).parent / "site" / "data" / "bz_signals.json"
                    if not f.exists():
                        return self._send(404, {"error": "no bazaar signals yet (needs 24 h of collector data)"})
                    return self._send(200, json.loads(f.read_text(encoding="utf-8")))
                if parts[0] == "forecasts":
                    # written by the publish job (every publish_interval), same file as data/forecasts.json
                    f = Path(cfg.db_path).parent / "site" / "data" / "forecasts.json"
                    if not f.exists():
                        return self._send(404, {"error": "no forecasts yet (publish job hasn't run)"})
                    return self._send(200, json.loads(f.read_text(encoding="utf-8")))
                if parts[0] == "components":
                    from .valuation import components_doc
                    return self._send(200, components_doc(conn()))
                if parts[0] == "wallet":
                    from .wallet import wallet_report
                    return self._send(200, wallet_report(conn(), int(time.time() * 1000)) or {})
                if parts[0] == "deals":
                    rows = conn().execute("SELECT * FROM deals ORDER BY profit DESC LIMIT 100").fetchall()
                    return self._send(200, [dict(r) for r in rows])
                if parts[0] == "lbin":
                    keys = [k.strip().upper() for k in qs.get("keys", [""])[0].split(",") if k.strip()][:50]
                    out = {}
                    for k in keys:
                        r = conn().execute("SELECT lowest_bin, second_bin, listings, ts FROM lbin_now WHERE item_key=?", (k,)).fetchone()
                        if r:
                            out[k] = {"lowest": r["lowest_bin"], "second": r["second_bin"], "listings": r["listings"], "ts": r["ts"]}
                    return self._send(200, out)
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
