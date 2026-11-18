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

**Where the mod gets its numbers** (no setup needed, works even when your PC is off):
1. your PC collector (`run.py`) if it's running: freshest data, updates every 30 s
2. otherwise the published data on GitHub Pages: full price history, 7d / 30d / all-time stats,
   forecasts and seasonality for every item (updated hourly by the collector)
3. live bazaar buy/sell prices straight from the public Hypixel API (refreshed every minute)

**Buy verdict** (under the 24h average line): hover over any auction listing and the mod reads its
"Buy it now" or bid price (per item for stacks). It compares that price with the item's fair value: the median sale price
over the last 72 h, with the 7-day average as fallback. You get one of **GREAT BUY**, **GOOD BUY**, **FAIR PRICE**,
**OVERPRICED** or **BAD BUY**, plus:
* the flip profit after AH fees (1/2/2.5% listing fee and 1% claim tax above 1M)
* a warning when the item rarely sells
* a "wait" hint when the forecast or a confirmed seasonal dip says it will get cheaper within 24 h
Bazaar items are judged by the live buy price against the 7-day average. Turn it off with `"showVerdict": false`.

**Flip banner** (bold, right under the item name):
* `✔ PROFIT FLIP`: a BIN you can buy and resell for at least `minFlipProfitPercent` (default 5%) after fees
* `✔ WORTH BIDDING: bid up to X`: an auction whose current bid is still under the highest bid that leaves 5% profit

Resale is capped just under the cheapest competing BIN, because you can't sell above the competition. The banner
is never shown for items with fewer than 3 sales in 24 h, or when manipulation is flagged.

**Bidding helper and watch list** (manual bidding; the mod never clicks or bids for you, because automated
bidding is a macro under Hypixel's rules):
* auction tooltips show the **bid to enter**: the lowest valid bid. That's the starting bid, or the current bid + 2.5%
  (Hypixel's minimum), rounded up. It's capped at `maxBidPercentOfLowestBin` (default **70%**) of the lowest BIN. If the next
  valid bid is already above the cap, the tooltip says to skip it.
* **click an auction** and that bid is copied to your clipboard. Paste it into the bid sign (Ctrl+V) and confirm it yourself.
* the auction then goes on your **watch list**. Its lowest BIN is re-checked every 30 s and you get a chat message when it
  moves ≥ 2%, including a red warning if your bid is no longer profitable. Commands: `/skyprice watching`,
  `/skyprice unwatch <item>`, `/skyprice unwatchall`. Entries expire after 48 h.
* fresh lowest BIN comes from your PC collector, which re-checks every 30 s: the newest listings, plus BINs that just sold. If
  the collector isn't running, it falls back to the hourly published data.

**Outbid HUD**: a panel to the right of your inventory (E) lists the auctions you've been outbid on. It reads Hypixel's
"outbid you" chat messages, which carry the auction link. Click a row to open that auction and bid again yourself, or click
✕ to dismiss it. Rows disappear when you bid on that item again, or after 12 h (`outbidHours`). Turn it off with
`"outbidHud": false`.

**AH highlight**: in the Auction House, auctions you could flip for at least `minFlipProfitPercent` after fees (or that are
worth bidding on) **and** that sell regularly (≥ 3 sales in 24 h) **and** have no manipulation flags get a **yellow border**.
Ones at least 20% under fair value also get a **gold glow**. Turn it off with `"highlightDeals": false`.

**Underpriced BIN finder** (every full AH scan, every 5 min): finds BINs at least 20% under fair value
(`deal_min_discount`) on items worth 1M+ (`deal_min_value`) that still make a profit after fees when resold just
under the next-cheapest BIN. Items with fewer than 3 sales in 72 h or a high manipulation risk are skipped.
* mod: chat alert for each new deal (max 3 per minute), `/skyprice deals` lists them, and `/skyprice open <n>` opens
  that auction with `/viewauction`. You buy it yourself.
* dashboard: "Underpriced BINs" table with a copy button for `/viewauction <id>`. It updates hourly, and good BINs go fast,
  so use the mod alerts for live deals.
* local: `GET /deals`

**Fair value and manipulation checks** (`market/marketcheck.py`, also shown on the dashboard as "Market check"):
* fair value = median of three estimates: clean sold median (72 h), cheapest-25% of current listings, and 7-day average
* **wash trading**: the same seller→buyer pair trading repeatedly. These sales are flagged and left out of fair value.
* **cornering**: listings down ≥ 50% vs the 7-day average while the price is up ≥ 30%
* **spike**: ≥ 40% above the 30-day average
* **dominant seller**: one seller owns ≥ 50% of the listings
* **thin market**: few sales
* bazaar only: spike vs the 7-day average, thin order book, huge buy/sell spread

In-game you get the tooltip lines plus **`/skyprice`** (held item) or **`/skyprice <item>`** (e.g. `/skyprice hyperion`),
which prints the full summary, the last 14 daily prices and every tested seasonal cycle in chat.
Settings: `.minecraft/config/skyprice.json` (sources, refresh times, which lines to show).

The jars are compiled by GitHub Actions (`.github/workflows/build-mod.yml`) on every change in `mod/`.
To build one yourself, run `cd mod` then `gradlew.bat build` (needs a Java 25 JDK to run the build tool; the 1.21.11 jar still runs on Java 21). See `mod/versions/README.txt` for 26.x.

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
* **Which method on what (1.21.0+)**, from the "Selecting a forecasting method" and "Demand patterns" slides. Each
  series is diagnosed fresh every run (last 48 h), so the choice follows the data as it accumulates:

  | Pattern found | Allowed models | Rule from the slides |
  |---|---|---|
  | < 12 h of data | none: the mod uses recent sales / lowest BIN | qualitative methods when data are scarce |
  | randomness around a level | last period, MA, weighted MA, exponential smoothing | α by randomness: high (avg hourly move ≥ 2 %) → α 0.1–0.3, medium → 0.2–0.7, low (< 0.5 %) → 0.5–0.9 |
  | trend (slope \|t\| ≥ 2 and ≥ 1 % over the window) | the above + Holt's adjusted ES + time-series regression | trend models only where a trend exists (else they extrapolate noise) |
  | hour-of-day seasonality | best model × seasonal index | 4-step procedure, kept only if it cuts MAD > 3 % |
  | any model with \|TS\| > 4 | skipped for the next-lowest-MAD model | "flag a poor model and search for a better one" |

  Lowest MAD within the allowed family wins, per horizon (1h / 6h / 24h).
* **Every item with enough data is forecast** (`forecast_all`, default on), not only the 600 most active: the set grows
  as the collector runs. All forecasts go into one small file, `data/forecasts.json` (local: `GET /forecasts`), that
  the mod's flip lists read every 10 min.

`tests/test_forecast.py` checks the formulas against the worked examples in the slides
(Tables 9.3–9.4, Examples 9.1, 9.2, 9.3, 9.4 and 9.7).

### Item keys
Normal items use their SkyBlock id (`HYPERION`). Single-enchant books become `ENCHANTMENT_<NAME>_<LEVEL>`,
which is the same as the bazaar id. Pets become `PET_<TYPE>_<TIER>`, one series per rarity; a pet holding a
Tier Boost is keyed by its real rarity (the boost is valued as its held item).

### Valuing each copy: enchants, stars, recomb, rarity, pet level (`market/valuation.py`, `Valuation.java`)
Two copies of the same item can be worth very different amounts, so each copy is valued from its own data:

    value = clean base + Σ upgrade price × factor
    pets:  value = L1 + (Lmax − L1) × XP / XP-to-max  + held item + skin

| Upgrade | Priced as | Factor |
|---|---|---|
| enchantments (≥ 200k each) | the enchanted book (bazaar, else lowest BIN) | 0.85 |
| recombobulator (+1 rarity) | RECOMBOBULATOR_3000 | 0.85 |
| hot / fuming potato books | first 10 hot, rest fuming | 1.0 / 0.85 |
| stars | the item's real essence + item cost per star (Hypixel items resource), master stars above max | 0.9 / 0.95 |
| gemstones | `<QUALITY>_<TYPE>_GEM` | 1.0 |
| art of war | THE_ART_OF_WAR | 0.85 |
| pet held item / skin | lowest BIN / bazaar | 0.9 / 0.8 |

Factors are below 1 because upgrades rarely resell for what they cost. The **clean base** is fitted from real
sales by subtracting every sale's upgrades (median of the remainders, mixed with the cheapest-25% listing). For
pets, **L1 and Lmax** come from a regression of price (minus held item and skin) on XP share, using Lecture 4's
b̂ = (Σxy − ΣxΣy/n)/(Σx² − (Σx)²/n), â = ȳ − b̂x̄. Level-200 pets (Golden/Jade/Rose Dragon) use the level-200 XP curve.

The underpriced-BIN scan compares each listing with **its own** value, so a level-1 pet at a normal level-1 price
is not a deal and a level-100 at that price is. In game, the tooltip shows `this copy ≈ 152M = clean 120M + stars …`,
the rarity (recombobulated / tier-boosted) and, for your own items, what to list them for. The models build up as
the collector records sales with their upgrades (from version 1.9.0 on).

## Wallet (your coins, hourly)
Dashboard → **Wallet** tab shows:
* KPIs: total coins (purse + bank), last 24 h, last 7 days, since tracking started, all-time high, average per hour
  and per day, best and worst hour, % of hours with a gain
* charts: coins over time (total, purse and bank; 7 / 30 / 90 days) and gain or loss per hour

Two sources, both optional:
1. **Hypixel API (purse + bank, every hour, even when you're offline).** Create a permanent key at
   https://developer.hypixel.net/dashboard → Create App → Personal API Key. Put just the key in a file named
   `hypixel_api_key.txt` next to `run.py`. That file is in `.gitignore`, so it never gets pushed. The player is set by
   `player_name` (SpookyHawk) in `config.example.json`. For the bank balance, turn on the Banking API in SkyBlock settings.
2. **The mod (purse only, no key needed).** While you play, it reads "Purse:" from the sidebar every 5 minutes and sends it
   to your PC collector. The bank is filled in from the last API reading, if there is one.

The Wallet tab is on the public dashboard. Set `"publish_wallet": false` in config.json to keep it local only
(http://127.0.0.1:8765/dashboard/ still shows it).

### Potential free cash flow (`market/fcf.py`)
The coins you'd have if everything you have going on the AH settled now:

    Potential FCF = wallet (purse + bank)
                  + ongoing sales         your listings at their price, after the 1 % claim tax (listing fee already paid)
                  − ongoing bids          coins in auctions you're winning
                  + expected sale value   what you're winning resells for (its own value, incl. upgrades), after fees
                  + outbid refunds        coins from bids you've lost, waiting to be collected

Hypixel takes a bid's coins from your purse when you place it, so by default (`bids_already_in_purse: true`) the
winning bids are shown but not subtracted from the wallet a second time. Set it to `false` to subtract them.
**Conservative FCF** values each listing at min(listed price, estimated value), since an overpriced listing may
not sell at its price. Your listings and bids are found in every full AH scan (every 5 min) using `player_name`;
the Wallet tab shows the waterfall, the FCF line over time and every open position. Turn off with `track_positions: false`.

In game (mod 1.10.0+): open your inventory and click **FCF ▸** above it. A panel opens to the left with the same
waterfall (your live purse from the sidebar + bank), the conservative FCF marker and your open positions (tab **Flow**), a pie of where your coins are tied up (tab **Tied up**) and a line chart of your FCF over 24 h / 7 d (tab **Trend**, your purse history is logged from the sidebar while you play); click a
position to open that auction, click ⟳ to refresh. `/skyprice fcf` prints it in chat. Turn off with `"fcfButton": false`.

**In the mod (1.14.0+):** while you play, the mod scans the whole AH itself every 15 minutes (and when you open the
FCF panel or ERP if the last scan is over 2 minutes old), streamed and gzip-compressed in the background. It identifies
each of your items from its item_bytes and values it like the tooltip; history goes to `.minecraft/config/skyprice-fcf.csv`
(7 days). The freshest of the mod scan, your PC collector and the website is used. Turn off with `"modScan": false`.

**Without your PC:** `.github/workflows/positions.yml` scans the AH for SpookyHawk every 15 minutes on GitHub and
publishes `data/positions.json` (valued with the published item models). The mod reads it whenever your PC collector
isn't running and adds your live purse from the sidebar. Add a repository secret `HYPIXEL_API_KEY` to include the bank.

### ERP mode in game (mod 1.12.0+)
Click **ERP** (orange, left of the FCF button above your inventory) or type `/skyprice erp`. Your inventory screen turns
into a console laid out like the RealGame / BioCounter plant: team name, clock, **PROFIT** gauge (expected margin on what
you're winning) and **CASH** gauge (share of your coins that are free cash), an orange icon bar, and one window at a time:

| RealGame window | SkyBlock version |
|---|---|
| Inventory | everything you have on the AH: (L)isted, (W)inning, (O)utbid, with cost and value |
| Purchases | your bids as purchase orders: your bid, top bid, value, profit, time left |
| Sales offers | your listings: price vs value, after the claim tax, flagged when above value |
| Forthcoming events | auctions ending, soonest first (red within an hour) |
| Deliver incoming orders | **Collect**: ended auctions and outbid coins to claim |
| Investments & Funding | **Cash flow**: the potential-FCF waterfall |
| Business Intelligence | General (FCF trend, last week / last 24h), Market Shares (pie of where your coins are), Reports (income statement of open positions) |
| Coach | live warnings: overpriced listings, losses, outbid bids, too much tied up, stale data, deals |
| Messages | outbid notices from chat |
| Product development | **Bazaar**: your chosen products (default Stock of Stonks + carrot/Spooky candy) with live sell/buy, 24 h avg/high/low, spread and a 7-day chart |

Click a row to open that auction (you act yourself); ✕ Exit ERP returns to the normal inventory. Change the bazaar list
with `/skyprice bz add <id>`, `/skyprice bz remove <id>`, `/skyprice bz reset`. The GitHub AH job also records the
bazaar list every 15 min (`data/bazaar_watch.json`, 7 days) so the chart works without your PC; set a repository
variable `BAZAAR_WATCH` (comma-separated ids) to change what it records.

### Flipping made simple (mod 1.15.0+)

Press **E**: a **FLIP** tab sits on the right of your inventory and every chest menu. The first time, a 3-tap setup
asks how much of your purse one flip may use (10 / 25 / 50 %), your risk (Safe ≥10 % profit + 10 sales/day,
Normal ≥5 % + 4/day, Bold ≥3 % + 1/day) and how long you'll wait to resell (2 / 8 / 24 h). Redo it with ⚙ or
`/skyprice setup`.

| Tab | What you see | Click |
|---|---|---|
| **AH** | the best auctions on the AH page you're looking at (numbered 1–5 on the slots too) + **market flips** from the mod's own AH scan: cheapest BIN clearly under the next copies, resale capped at fair value, sells often enough, fits your budget | opens the auction (`/viewauction`) |
| **Bazaar** | **your orders** (✔ top / ⚠ outbid / ⚠ undercut, read when you open *Your Bazaar Orders*) + **volume-sized flips** (1.16.0+): buy order at top + 0.1 → sell offer at cheapest − 0.1 after the 1.25 % tax; your order fills from instant sells and your offer from instant buys (10 % of each), so it says **how many to order**, how long filling and selling take, the **total profit** and profit per hour (one round ≤ 2 h, `bazaarRoundHours`); today's realized bazaar profit in the header | click it, then in the Bazaar click **Search** (green box): the search sign is **pre-filled** with the name, press Done → the item (cyan box) → Create Buy Order: the **amount and price signs are pre-filled** too (live, volume-sized). You press every button yourself; with a Booster Cookie the click opens it directly (`/bz <name>`). Clicking the row again copies amount / price to the clipboard as a fallback. The list is a snapshot refreshed every 5 min (`bazaarRefreshMinutes`); in Bazaar menus the top 20 flips get a green border + their rank |
| **Craft** | best craft flips (published every 3 h) that fit your budget | shopping list in chat |
| **Profit** | realized profit today / 7 days, best flip, recent flips (buys and sells from chat, matched first-in first-out) | |

- **Fair value can't be dragged up by one outlier sale**: SkyCofl prices use the median of the 24 h / 7 d median sale and the
  lowest BIN (an Aspect of the End with one 75M sale was "fair 876k", now ≈ 430k).
- **Your own items say SELL**, never GREAT BUY: worth ≈ X · list at Y (just under the cheapest BIN) · you get Z after fees ·
  sells in ~T. Click the item in *Create Auction* to copy the price; *Manage Auctions* tells you when you've been
  undercut or a listing expired (relist price).
- **Every flip shows liquidity**: sales/day, time to resell, confidence (high / medium / low). Manipulation (price jumped
  vs its 7-day level, listings far above sales) blocks the flip banner.
- **Simple mode** (default): tooltips show only what to do. **Pro** (footer chip or `/skyprice mode pro`): every number.
- **Alerts with a ping**: item sold, outbid, bazaar order filled / outbid / undercut, the best new AH flips (max 3 per scan).
- Commands: `/skyprice flips`, `/skyprice flip <n>`, `/skyprice bzflips`, `/skyprice profit`, `/skyprice feed on|off`, `/skyprice forecast on|off`, `/skyprice signals`, `/skyprice snipe [on|off]`.
- Nothing is automated: the mod only shows, opens what you click and copies prices. You buy, bid, list and claim yourself.

### Flips with forecasts + identical copies only (mod 1.21.0+)

- **AH market flips compare identical copies only**: same stars, recomb, potato books (fuming counted), big enchants
  (level VI+ and ultimates), gems, attributes, skin/dye, scrolls. A plain copy next to upgraded ones is no longer a "gap".
- **Resale is capped at what the cheapest copies really sold for** (median of each period's lowest sale over 3 days,
  SkyCofl). Other listings' asking prices can sit far above that and never sell — that's what made a 7M Mantid
  Boots look like +2.8M.
- **Forecasts in both flip lists** (FC chip in the FLIP footer, `/skyprice forecast on|off`, `/skyprice forecast` for
  status). AH: the resale is moved by the forecast at the time it should sell. Bazaar: the sell offer is moved by the
  forecast of the instant-buy price at fill + half the selling time; the list re-ranks on it. A fall counts fully, a rise
  only beyond the model's typical error (MAPE), a biased model (|TS| > 4) counts nothing, and nothing goes above
  copies already listed. Rows show `fc −3%` when it changed something; the bazaar tooltip says by how much.
- Forecasts come from **your collector** (run.py: local `/forecasts`, else the published `forecasts.json`). With no
  collector data yet the flips work exactly as before.

### Bidders' coins in the Auction View (mod 1.25.0+)

Open a normal auction (not BIN) and a panel under the menu lists the other bidders (from the Bid History and the
"Bidder:" line) with **how far each can go**: purse + bank of their selected profile, plus their current bid (it comes
back to them when they're outbid). The footer names the richest: bid above that and none of the current bidders can
follow. Net worth also counts items, but those can't be bid with until sold, so coins are what matters here. "bank
hidden" = their Banking API is off, so only the purse is known (they may have more). The lookup goes through your
collector (`GET /wealth/<name>`, your API key, cached 15 min) — start it with start.bat for this to work.

### Undervalued-BIN alert + live bazaar flipper (mod 1.23.0+)

- **⚡ Undervalued BIN banner** at the top of the screen, with a ping and a chat line, when a BIN is listed clearly under
  the market. Hypixel publishes the auction list about once a minute; the mod watches for that update and checks only the
  new listings, so the alert comes seconds after Hypixel publishes (within ~1 min of the listing — the API's limit).
  Only BINs at **half the lowest BIN or less** (1.24.0+, `snipeMinPercent` = 50) of identical copies (≥ 3 listed at the
  last full scan); resale capped at fair value and the recent clearing price, profit after fees ≥ `minFlipProfitCoins`
  and ≥ 5 %, fits your purse. Pets and enchanted books are skipped.
  **Not manipulated** (1.24.1+): the item must have real sales history (fetched first, a few seconds; none within 30 s
  = no alert), no spike / crash / buyout / inflated-lowest-BIN flag, and the lowest BIN itself must be within 1.5× of
  what copies really sell for — otherwise "half the lowest BIN" is just half of a pumped price.
  **The auction opens by itself** (`snipeAutoOpen`, `/skyprice snipe auto on|off`): the mod sends `/viewauction` for you
  — at most once per 10 s and never while chat or a sign is open — and you press *Buy Item Right Now* and *Confirm*.
  It is a command sent without a keypress, so it's off-able if you'd rather not risk it on Hypixel. `/skyprice snipe`
  opens the latest alert; `/skyprice snipe on|off`. Nothing is ever bought for you.
- **Bazaar flips are live**: re-ranked on every bazaar update (every 20 s; `bazaarRefreshMinutes` > 0 brings back the
  snapshot), header shows "live Ns". Only products that **buy and sell fast** are listed: after competition both sides
  fill ≥ 10 units/h for you, the slower side is ≥ 1/5 of the faster, and a round fits `bazaarRoundHours`.
- **Competition counts**: only orders within ~0.5 % of the best price fight you for the top (1.23.1+, from the bazaar's
  order-book summary; orders far down the book only fill after yours). With N of them you catch at most 1 / (1 + N) of
  that side's flow, never more than `bazaarSharePercent` (9 → 10 %, 30 → 3 %); the tooltip says when a product
  "fills unevenly" and when a flip is **capped by your flip budget** (then a bigger budget % = more profit/h).
- **Sliding prices count**: without a collector forecast, the 7-day trend of the instant-buy price (SkyCofl, regression of
  log price) moves your sell price by the time it sells when it's falling (rows show `trend −3%`). Rising isn't counted.
- Products in a market event (ERP → Signals "!") stay out of the list.

### Bazaar misprices, correlations and suggestions — ERP → Signals (mod 1.22.0+)

Press **E → ERP → Signals**. `market/bzsignals.py` checks every liquid bazaar product (≥ 2,000 units and 5M coins a
week on both sides, spread ≤ 60 %) and turns what it finds into suggestions with a reason list, a purse-sized amount
and a 7-day chart. Data: your collector's hourly bars once it has 24 h (local `GET /signals`), otherwise a GitHub job
every 2 h on SkyCofl's 7-day history of the ~300 most traded products (`data/bz_signals.json`).

| Badge | What it found | Suggestion |
|---|---|---|
| **B** cheap / **S** rich | the move over the last hours is ≥ 2.5× what this product normally moves in that time (robust σ of its 2 h returns × √hours) and ≥ 4 % | buy order now → sell offer back at the price before the drop (after tax), or sell what you hold |
| **P** pair | two products whose price changes correlate (Spearman ρ ≥ 0.5) drifted ≥ 4 % / 2σ apart, and their spread mean-reverts (half-life ≤ 48 h) | buy the laggard |
| **F** follows | B has moved one bar after A (lag ρ ≥ 0.5, stronger than same-bar ρ); A just moved ≥ 2.5σ and B hasn't | buy / sell B before it follows |
| **C** craft / **$** cheaper form | 1 enchanted = k raw (NEU recipes): crafting margin after tax ≥ 6 %, or ≥ 3 % and unusually high this week; or the ready-made form is unusually cheap | craft and sell / buy it ready-made |
| **!** market event | ≥ 6 products (or ≥ 3 correlated ones) moved ≥ 15 % the same way at once — an update, a mayor | don't buy the dip blindly; those products leave the flip list and their tooltips warn you until it settles |

Confidence: **high** needs the move to be out of line with the product's correlated peers, no drift the same way
before it, and proof its past sudden moves bounced back (≥ 3, ≥ 60 %). A single move ≥ 30 % is treated as news (low).
Click a row for the reasons; **Open in Bazaar** starts the usual click flow (name → amount → price), **+ Watch** adds
it to ERP → Bazaar. The Coach shows the two strongest; `/skyprice signals` prints the top 6 in chat.
Correlation isn't causation and a week is a short sample: each suggestion is a lead with its reasons, not a promise.

### Pets priced by level + rarity (mod 1.20.0+)

Pets are valued from **sales of the same pet, same rarity and the same level range** over the last 7 days (SkyCofl's
PetLevel filter): max level exactly, then 95–99, 90–94, 80–89, 70–79, 50–69, 30–49, 10–29, 1–9 % of max (Golden
Dragon on its 200 scale). The cheapest BIN, resale, flip check, bid cap, position values and the 7-day chart all use
that range, e.g. a Lvl 100 Legendary Tiger is compared with Lvl 100 Legendary sales (~34M), not with Lvl 1 copies
(~17M). The tooltip says which range it used and how many sold; with fewer than 3 sales in 7 days it falls back to the
level interpolation.

### 7-day chart + manipulation warnings (mod 1.19.0+)

- Hover any auction item (AH, inventory, chests): a **big 7-day price chart** appears beside the menu: average sale
  line, cheapest→highest band, volume bars, cheapest BIN (yellow), fair value (green dashes) and the hovered listing's
  price (white). Outliers (one maxed copy) don't squash it: the y-axis uses the 5th–95th percentile and marks far-off
  sales with a red tick. Turn off with `"priceChart": false`.
- **Manipulation** is flagged on every AH item: a red **!** on the slot in the AH, a red banner on the chart and
  "⚠ POSSIBLE MANIPULATION" at the top of the tooltip. Triggers: 24 h median ≥ 40 % above the 7-day level, the cheapest
  BIN far above recent sales, or a buyout (3× the usual daily sales while the price climbs ≥ 20 %). Flagged items never
  get a flip border. Prices for visible items are fetched gradually (≤ 12 per 20 s) to stay within SkyCofl's limits.

### SkyCofl fallback (mod 1.13.0+)
When our own data has too little auction history for an item (no PC collector running, or the item wasn't traded
while the website snapshot ran), the tooltip uses **SkyCofl** (sky.coflnet.com, free public SkyBlock price history):
`/api/item/price/{tag}/history/week` for the 24 h / 7 d average, high, low and number sold, and
`/api/item/price/{tag}/bin` for the lowest and 2nd-lowest BIN. Pets are looked up as `PET_TYPE?Rarity=TIER`. Requests
are spaced 400 ms apart (SkyCofl allows 30 per 10 s) and cached 10 min (BIN 3 min); the yellow border only uses what's
already cached. The tooltip says "Prices provided by SkyCofl" when it uses them. Turn off with `"coflFallback": false`.
SkyCofl asks for a Premium+ subscription for commercial use; this is a personal, non-commercial mod.

## Dashboard
Static site (`dashboard/` + JSON exported by `market/publish.py`) on the `gh-pages` branch. It shows:
* tracked items, AH sales and coins traded in the last 24h, and how much history has been collected
* the biggest 24h movers, the biggest forecast moves for the next 24h, and the best bazaar flips (margin, with volume)
* every bazaar product and auction item: price now, 24h avg/high/low/change, activity, 24h forecast and MAPE
  (searchable and sortable)
* per item: a 7-day hourly chart (buy/sell, or median sale/lowest BIN) with the 1h/6h/24h forecast and its 80% range,
  plus the model ranking for each horizon

The `gh-pages` branch is always a single commit that gets replaced, so the repo doesn't grow.

## Seasonality (dashboard → Seasonality tab)
Every item is tested for three repeating cycles: **daily** (24 h), **SkyBlock year** (124 h, the in-game calendar
with Spooky Festival, Jerry's Workshop and so on) and **weekly** (168 h). It uses the course's seasonal-index procedure:
price ÷ centred moving average, averaged per position in the cycle. A strong daily cycle is divided out before
the longer cycles are tested. A cycle is **confirmed** only if:
* even and odd cycles give the same pattern (r ≥ 0.75)
* indices learned on older data cut the error on the newest cycle by ≥ 20% (out-of-sample)
* the swing is at least 2%
* there are at least 5 cycles of data (5 days / 26 days / 35 days)

In simulation (`tests/test_seasonality.py`) fewer than 0.5% of random-walk prices get falsely confirmed, while real
4–5% cycles are found more than 90% of the time. The tab is sorted so the **soonest upcoming peaks are at the top**,
showing time to the next peak and dip, the expected % move, swing and confidence. Click a row to see the full seasonal
profile.

## Local server endpoints
`/itemdata/<KEY>` item document (what the mod reads) · `/lbin?keys=A,B` fresh lowest BIN (watch list) · `/hud/<KEY>` tooltip lines · `/item/<KEY>` stats JSON (incl. every horizon's model leaderboard) ·
`/history/<series>?hours=168` hourly bars (`bz:<ID>:buy`, `bz:<ID>:sell`, `ah:<KEY>`, `lbin:<KEY>`) ·
`/components` upgrade prices + valuation constants (also published as `data/components.json`) · `/search?q=` · `/health`

## Tests
```
python tests/test_forecast.py     # formulas vs. lecture examples
python tests/test_seasonality.py  # cycle detection, false-positive rate, SkyBlock calendar
python tests/test_market.py       # fair value + manipulation scenarios
python tests/test_quickbin.py     # 30-second lowest-BIN re-check
python tests/test_deals.py        # underpriced BIN finder (incl. pet levels)
python tests/test_valuation.py    # upgrade parsing, component prices, clean base and pet-level fits
python tests/test_wallet.py       # wallet parsing, mod + API merge, KPIs
python tests/test_fcf.py          # potential FCF: your listings, bids, refunds, conservative value
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
