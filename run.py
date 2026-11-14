"""Start everything: collector jobs + local price server.

    python run.py              (uses config.json if present)
    python run.py --no-collect (only serve what's already in the database)
"""
import argparse
import logging
import signal
import threading

from market.collector import start_all
from market.config import load_config
from market.db import Database
from market.server import serve


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="config.json")
    ap.add_argument("--no-collect", action="store_true", help="don't poll Hypixel, just serve the database")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO if args.verbose else logging.WARNING,
                        format="%(asctime)s %(name)s %(levelname)s %(message)s")
    cfg = load_config(args.config)
    db = Database(cfg.db_path)
    stop = threading.Event()

    if not args.no_collect:
        start_all(cfg, db, stop)
    serve(cfg, db)
    print(f"SkyBlock market running. Open http://{cfg.server_host}:{cfg.server_port}/  (Ctrl+C to stop)")

    signal.signal(signal.SIGINT, lambda *_: stop.set())
    try:
        signal.signal(signal.SIGTERM, lambda *_: stop.set())
    except (AttributeError, ValueError):
        pass
    while not stop.is_set():
        stop.wait(1)
    print("stopping…")


if __name__ == "__main__":
    main()
