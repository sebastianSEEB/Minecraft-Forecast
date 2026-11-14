# SkyBlock Market — price collector, forecasts and in-game HUD

```
 Hypixel public API ──► collector (run.py) ──► SQLite database ──► local price server ──► SkyPrice mod
 bazaar, auctions_ended,   every 60 s / 5 min     data/market.sqlite3    127.0.0.1:8765        item tooltips
 active auctions                                   hourly bars + sales    forecasts (EXC 3650)
```

Hovering over any SkyBlock item adds lines like these to its tooltip:

```
Auction House (24h)
 Avg sold 1.04B  high 1.1B  low 990.17M  (45 sales)
 Lowest BIN 999.98M  (301 listed)
Forecast 1h 1.03B ±0%  6h 1.02B ▼0.9%  24h 1.03B ▲0.5%
 80% range 24h: 1.02B – 1.05B  [Moving average (n=2) × hour-of-day index, MAPE 0.9%]
```

Bazaar items show buy/sell average, high and low, plus approximate daily volume.

**Dashboard:** https://sebastianseeb.github.io/Minecraft-Forecast/
**Mod download:** [Releases → mod-latest](https://github.com/sebastianSEEB/Minecraft-Forecast/releases/tag/mod-latest)

## 1. Install the mod (drag and drop)

1. Install **Fabric Loader** for **Minecraft 1.21.11** (the version with the most SkyBlock mods: SkyHanni,
   Skyblocker, Firmament…). Get it from fabricmc.net, or use Prism, Modrinth App or CurseForge.
2. Download `skyprice-1.0.0+mc1.21.11.jar` from the release above. Drop it into your `mods` folder together with
   **Fabric API** (and any other SkyBlock mods you like).
3. Playing on 26.1.2 instead? Use the `+mc26.1.2` jar (needs Java 25).

The jars are compiled by GitHub Actions (`.github/workflows/build-mod.yml`) on every change in `mod/`.
To build one yourself, run `cd mod` then `gradlew.bat build` (needs a Java 21 JDK). See `mod/versions/README.txt` for 26.x.

## 2. Run the collector on your PC (Python 3.10+, no packages needed)

Windows: double-click `start.bat`, or run `py run.py -v` in this folder. Leave it running.

* Every minute it records the bazaar and every finished auction. Every 5 minutes it scans all active auctions.
* The tooltip HUD and http://127.0.0.1:8765/ (quick lookup) read from it live.
* **Every hour it rebuilds the dashboard and pushes it to GitHub Pages.** You need
  [Git for Windows](https://git-scm.com/download/win) installed. The first push opens a GitHub
  login window once, then it's automatic. The dashboard is also available locally at http://127.0.0.1:8765/dashboard/.
* Forecasts appear after 12 hours of data and get better over a few days. The hour-of-day pattern needs about 5 days.
  If the PC is off, the history gets gaps and the dashboard shows "collector offline?".

Settings come from `config.example.json`. Copy it to `config.json` to change intervals, retention,
how many items get full charts (`publish_top_bazaar` / `publish_top_auction`) or to stop
publishing (`"publish_repo": ""`).

### Live test on GitHub (no PC needed)
Actions → **Live snapshot** → *Run workflow* runs the collector against the real Hypixel API for a few
minutes and publishes the result to the dashboard. Use it to check that everything works. Your PC
replaces that snapshot as soon as it publishes.

## How the numbers are made

| Shown            | Bazaar                                         | Auction House                                   |
|------------------|------------------------------------------------|-------------------------------------------------|
| Avg / high / low | mean, max and min of every bazaar poll in the last 24h | every finished auction in the last 24h, **price per item** (stacks are divided by their count) |
| Lowest BIN       | –                                              | cheapest active BIN from the latest full AH scan |
| Forecast basis   | hourly average buy price (sell is in `/item`) | hourly **median** sale price; falls back to lowest BIN for items that rarely sell |

### Forecasting (`market/forecast.py`), built from the EXC 3650 lecture 4–5 formulas
* Models: last period, moving average (n = 2–24), weighted MA (0.4/0.35/0.25 and 0.4/0.3/0.2/0.1),
  exponential smoothing (α 0.1–0.9), Holt's adjusted exponential smoothing (α × β grid,
  F0 = â, T0 = b̂ from a regression), and time-series linear regression (last 12/24/48 h).
* **Model choice:** every model is scored on the last 72 hours with MFE, MAD, MAPE and the
  tracking signal, and the lowest MAD wins. This is done **separately for 1h, 6h and 24h ahead**,
  using errors at that horizon. The model that is best one hour ahead (often a trend model) can
  be badly wrong a day ahead, because it stretches a short-term swing into a big move.
* **Seasonality:** the 4-step seasonal index procedure, with "season" = hour of day. The indices
  are learned on older data and only kept if they cut MAD on the recent window by more than 3%.
* **Range:** σ ≈ √(π/2)·MAD ≈ 1.25·MAD (inventory formula sheet), giving forecast ± z·σ with
  z = NORM.S.INV(0.9) for the 80% range.
* A ⚠ line appears when |tracking signal| > 4, meaning the 1h model is consistently over- or under-forecasting.

`tests/test_forecast.py` checks the formulas against the worked examples in the slides
(Tables 9.3–9.4, Examples 9.1, 9.2, 9.3, 9.4 and 9.7).

### Item keys
Normal items use their SkyBlock id (`HYPERION`). Single-enchant books become `ENCHANTMENT_<NAME>_<LEVEL>`,
which is the same as the bazaar id. Pets become `PET_<TYPE>_<TIER>`. Reforges, stars, recombs and enchants
on gear are **not** split out yet, so a 5-star Hyperion and a clean one share one price series.

## Dashboard
Static site (`dashboard/` + JSON exported by `market/publish.py`) on the `gh-pages` branch. It shows:
* tracked items, AH sales and coins traded in the last 24h, and how much history has been collected
* the biggest 24h movers, the biggest forecast moves for the next 24h, and the best bazaar flips (margin, with volume)
* every bazaar product and auction item: price now, 24h avg/high/low/change, activity, 24h forecast and MAPE
  (searchable and sortable)
* per item: a 7-day hourly chart (buy/sell, or median sale/lowest BIN) with the 1h/6h/24h forecast and its 80% range,
  plus the model ranking for each horizon

The `gh-pages` branch is always a single commit that gets replaced, so the repo doesn't grow.

## Local server endpoints
`/hud/<KEY>` tooltip lines · `/item/<KEY>` full JSON (incl. every horizon's model leaderboard) ·
`/history/<series>?hours=168` hourly bars (`bz:<ID>:buy`, `bz:<ID>:sell`, `ah:<KEY>`, `lbin:<KEY>`) ·
`/search?q=` · `/health`

## Tests
```
python tests/test_forecast.py     # formulas vs. lecture examples
python tests/test_e2e.py          # fake Hypixel API → collector → DB → server → HUD
python tests/fake_hypixel.py      # run the fake API yourself (set api_base in config.json)
```

## Notes
* Uses only Hypixel's public SkyBlock endpoints (no API key). Bazaar data is a snapshot of the
  order book, not individual trades, so "avg" means average quoted price, not volume-weighted trades.
* Storage: roughly 30–100 MB/day, mostly auction sales (kept 90 days) and hourly bars (kept forever).
  Raw bazaar snapshots are only kept for 2 days. Lower `keep_sales_days` if the file gets too big.
* The mod only reads item data your client already has and talks to `127.0.0.1`. It doesn't automate
  anything or send anything to Hypixel. As with every client mod on Hypixel, use it at your own risk.
