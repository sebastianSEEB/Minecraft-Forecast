"use strict";
/* SkyBlock Market dashboard — static, reads data/summary.json and data/items/<KEY>.json */

const $ = (s, el = document) => el.querySelector(s);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const safe = k => k.replace(/:/g, "__");

function fmt(v) {
  if (v == null || Number.isNaN(v)) return "–";
  const a = Math.abs(v);
  const units = [[1e12, "T"], [1e9, "B"], [1e6, "M"], [1e3, "k"]];
  for (let u = 0; u < units.length; u++) {
    const [d, s] = units[u];
    if (a >= d * 0.9995) {
      const x = v / d, dig = Math.abs(x) >= 100 ? 0 : Math.abs(x) >= 10 ? 1 : 2, r = +x.toFixed(dig);
      if (Math.abs(r) >= 1000 && u > 0) return +(v / units[u - 1][0]).toFixed(2) + units[u - 1][1];
      return r + s;
    }
  }
  if (a >= 100) return Math.round(v).toLocaleString("en-US");
  return a >= 1 ? +v.toFixed(1) + "" : +v.toFixed(2) + "";
}
function pct(v, digits = 1) {
  if (v == null || Number.isNaN(v)) return '<span class="muted">–</span>';
  if (Math.abs(v) < 0.05) return '<span class="muted">±0%</span>';
  return `<span class="${v > 0 ? "up" : "down"}">${v > 0 ? "▲" : "▼"} ${Math.abs(v).toFixed(digits)}%</span>`;
}
const TIERS = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"];
const title = s => s.toLowerCase().replace(/_/g, " ").replace(/\b\w/g, c => c.toUpperCase());
function pretty(k) {
  let m = k.match(/^PET_(.+)_(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)$/);
  if (m) return `${title(m[1])} Pet (${title(m[2])})`;
  m = k.match(/^ENCHANTMENT_(.+)_(\d+)$/);
  if (m) return `${title(m[1].replace(/^ULTIMATE_/, ""))} ${m[2]} Book`;
  m = k.match(/^(.+):(\d+)$/);
  if (m) return `${title(m[1])} :${m[2]}`;
  return title(k);
}
const kindLabel = k => (k === "bz" ? "Bazaar" : "AH");

let SUM = null, ITEMS = [], BYKEY = {};
const state = { kind: "all", q: "", sort: "vol", asc: false, shown: 100, mover: "up", fc: "up", fcSide: "buy" };

/* ----------------------------------------------------------------------------- load */
async function load() {
  const r = await fetch("data/summary.json", { cache: "no-store" });
  if (!r.ok) { $("#kpis").innerHTML = '<div class="card empty" style="grid-column:1/-1">No data published yet. Start the collector (run.py) — the dashboard updates every hour.</div>'; return; }
  SUM = await r.json();
  ITEMS = SUM.items;
  ITEMS.forEach(i => { i.name = pretty(i.k); i.act = i.kind === "bz" ? (i.vol || 0) : (i.sales || 0); BYKEY[i.k] = i; });
  renderHeader(); renderKpis(); renderMovers(); renderFcMoves(); renderFlips(); renderTable();
  route();
}

function renderHeader() {
  const s = SUM.stats, age = (Date.now() - s.generated) / 60000;
  const p = $("#fresh");
  p.classList.toggle("stale", age > 150);
  p.querySelector("span").textContent = age < 1 ? "updated just now" :
    age < 120 ? `updated ${Math.round(age)} min ago` : `updated ${Math.round(age / 60)} h ago${age > 150 ? " — collector offline?" : ""}`;
  $("#genInfo").textContent = ` Snapshot: ${new Date(s.generated).toLocaleString()} · source: ${s.source || "collector"}.`;
}

function renderKpis() {
  const s = SUM.stats;
  const hrs = s.hours_collected || 0;
  const hist = hrs >= 48 ? `${(hrs / 24).toFixed(1)} days` : `${hrs} h`;
  const tiles = [
    ["Items tracked", (s.bazaar_items + s.auction_items).toLocaleString(), `${s.bazaar_items.toLocaleString()} bazaar · ${s.auction_items.toLocaleString()} auction`],
    ["Auction sales, last 24h", s.sales_24h.toLocaleString(), "every finished auction is recorded"],
    ["Coins traded on AH, 24h", fmt(s.coins_24h), "sum of final sale prices"],
    ["Price history collected", hist, hrs < 12 ? "forecasts start after 12 h of data" : `charts + forecasts for ${s.detailed} most active items`],
  ];
  $("#kpis").innerHTML = tiles.map(([l, v, sub]) =>
    `<div class="card kpi"><div class="l">${l}</div><div class="v num">${v}</div><div class="muted small">${sub}</div></div>`).join("");
}

function li(i, right) {
  return `<li data-k="${esc(i.k)}"><span class="name">${esc(i.name)}<span class="tag">${kindLabel(i.kind)}</span></span><span class="num">${right}</span></li>`;
}
const emptyLi = msg => `<li style="cursor:default"><span class="muted small">${msg}</span></li>`;

function renderMovers() {
  const pool = ITEMS.filter(i => i.chg != null && (i.kind === "bz" ? (i.vol || 0) > 20000 : (i.sales || 0) >= 5));
  pool.sort((a, b) => state.mover === "up" ? b.chg - a.chg : a.chg - b.chg);
  const rows = pool.filter(i => state.mover === "up" ? i.chg >= 0.05 : i.chg <= -0.05).slice(0, 8);
  $("#movers").innerHTML = rows.length ? rows.map(i => li(i, `${fmt(i.now)} &nbsp;${pct(i.chg)}`)).join("")
    : emptyLi("Needs 24 h of history to compare against.");
}
function renderFcMoves() {
  const pool = ITEMS.filter(i => i.f24chg != null && (i.mape == null || i.mape < 15));
  pool.sort((a, b) => state.fc === "up" ? b.f24chg - a.f24chg : a.f24chg - b.f24chg);
  const rows = pool.filter(i => state.fc === "up" ? i.f24chg >= 0.05 : i.f24chg <= -0.05).slice(0, 8);
  $("#fcMoves").innerHTML = rows.length ? rows.map(i => li(i, `${fmt(i.f24)} &nbsp;${pct(i.f24chg)}`)).join("")
    : emptyLi("Forecasts appear after 12 h of data (models with MAPE &lt; 15% only).");
}
function renderFlips() {
  const rows = ITEMS.filter(i => i.kind === "bz" && i.margin > 0 && (i.vol || 0) >= 50000 && i.sell > 0)
    .sort((a, b) => b.margin - a.margin).slice(0, 8);
  $("#flips").innerHTML = rows.length ? rows.map(i => li(i, `${fmt(i.spread)}/item &nbsp;<span class="muted">${i.margin.toFixed(1)}%</span>`)).join("")
    : emptyLi("No bazaar data yet.");
}

/* ----------------------------------------------------------------------------- table */
const COLS = [
  ["name", "Item", i => `${esc(i.name)}<span class="tag">${kindLabel(i.kind)}</span>`],
  ["now", "Price now", i => fmt(i.now)],
  ["avg", "24h avg", i => fmt(i.avg)],
  ["hi", "24h high", i => fmt(i.hi)],
  ["lo", "24h low", i => fmt(i.lo)],
  ["chg", "24h change", i => pct(i.chg)],
  ["act", "Activity", i => i.kind === "bz" ? `${fmt(i.vol)}/day` : `${i.sales || 0} sold${i.listed ? ` · ${i.listed} listed` : ""}`],
  ["f24chg", "Forecast 24h", i => i.f24 != null ? `${fmt(i.f24)} ${pct(i.f24chg)}` : '<span class="muted">–</span>'],
  ["mape", "MAPE", i => i.mape != null ? i.mape.toFixed(1) + "%" : '<span class="muted">–</span>'],
];
function renderTable() {
  $("#thead").innerHTML = COLS.map(([k, l]) =>
    `<th data-k="${k}" class="${state.sort === k ? "sorted" + (state.asc ? " asc" : "") : ""}">${l}</th>`).join("");
  const q = state.q.trim().toLowerCase();
  let rows = ITEMS.filter(i => (state.kind === "all" || i.kind === state.kind) &&
    (!q || i.name.toLowerCase().includes(q) || i.k.toLowerCase().includes(q.replace(/ /g, "_"))));
  const k = state.sort, dir = state.asc ? 1 : -1;
  rows.sort((a, b) => {
    const x = a[k], y = b[k];
    if (x == null && y == null) return 0; if (x == null) return 1; if (y == null) return -1;
    return (typeof x === "string" ? x.localeCompare(y) : x - y) * dir;
  });
  $("#count").textContent = `${rows.length.toLocaleString()} items`;
  $("#tbody").innerHTML = rows.slice(0, state.shown).map(i =>
    `<tr data-k="${esc(i.k)}">${COLS.map(c => `<td class="num">${c[2](i)}</td>`).join("")}</tr>`).join("") ||
    `<tr><td colspan="${COLS.length}" class="empty">No items match.</td></tr>`;
  $("#moreBtn").style.display = rows.length > state.shown ? "" : "none";
}

/* ----------------------------------------------------------------------------- detail */
let CURRENT = null;
function route() {
  const m = location.hash.match(/item=([^&]+)/);
  if (m) openItem(decodeURIComponent(m[1]), false);
}
async function openItem(k, push = true) {
  if (push) history.replaceState(null, "", "#item=" + encodeURIComponent(k));
  const box = $("#detail"), s = BYKEY[k];
  box.classList.add("open");
  if (!s) { box.innerHTML = `<div class="empty">Unknown item ${esc(k)}</div>`; return; }
  CURRENT = null;
  let d = null;
  if (s.d) {
    box.innerHTML = `<div class="empty">Loading ${esc(s.name)}…</div>`;
    try { d = await (await fetch(`data/items/${encodeURIComponent(safe(k))}.json`, { cache: "no-store" })).json(); } catch (e) { d = null; }
  }
  CURRENT = { s, d };
  state.fcSide = s.kind === "bz" ? "buy" : "main";
  renderDetail();
  box.scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderDetail() {
  const { s, d } = CURRENT, bz = s.kind === "bz";
  const statTiles = bz ? [
    ["Instant buy", fmt(s.now)], ["Instant sell", fmt(s.sell)], ["24h average", fmt(s.avg)],
    ["24h high / low", `${fmt(s.hi)} / ${fmt(s.lo)}`], ["Traded / day", fmt(s.vol)], ["Flip margin", s.margin != null ? `${fmt(s.spread)} (${s.margin.toFixed(1)}%)` : "–"],
  ] : [
    ["Lowest BIN", fmt(s.lbin)], ["24h avg sale", fmt(s.avg)], ["24h high / low", `${fmt(s.hi)} / ${fmt(s.lo)}`],
    ["Sales 24h", (s.sales || 0).toLocaleString()], ["Listed now", s.listed != null ? s.listed.toLocaleString() : "–"], ["Coins traded 24h", fmt(s.coins)],
  ];
  const legend = bz
    ? [["var(--series-1)", "Buy price (hourly avg)"], ["var(--series-2)", "Sell price (hourly avg)"]]
    : [["var(--series-1)", "Median sale price (hourly)"], ["var(--series-2)", "Lowest BIN (hourly avg)"]];
  const fc = d && d.forecast[state.fcSide];
  const sideTabs = bz ? `<div class="tabs" id="sideTabs"><button data-v="buy" class="${state.fcSide === "buy" ? "on" : ""}">Forecast buy</button><button data-v="sell" class="${state.fcSide === "sell" ? "on" : ""}">Forecast sell</button></div>` : "";

  $("#detail").innerHTML = `
    <div class="dhead">
      <div><h2>${esc(s.name)}</h2><div class="muted small">${esc(s.k)} · ${bz ? "Bazaar product" : "Auction House item"} ${pct(s.chg)} <span class="muted">vs 24h ago</span></div></div>
      <div style="display:flex;gap:8px;align-items:center">${sideTabs}<button class="link" id="closeDetail">Close</button></div>
    </div>
    <div class="grid stats">${statTiles.map(([l, v]) => `<div class="stat"><div class="l">${l}</div><div class="v num">${v}</div></div>`).join("")}</div>
    ${d ? `
      <div class="legend">${legend.map(([c, l]) => `<span><span class="sw" style="background:${c}"></span>${l}</span>`).join("")}
        ${fc && fc.available ? '<span><span class="sw dash"></span>Forecast</span><span><span class="sw band"></span>80% range</span>' : ""}</div>
      <div class="chart" id="chart"><div class="tip" id="tip"></div></div>
      ${forecastCards(fc)}
      <details class="how"><summary>How the forecast is made</summary>
        <p>Every hour, each model from the course (last period, moving average, weighted moving average, exponential smoothing,
        Holt's trend-adjusted smoothing, time-series linear regression) is scored on the last 72 hours. This is done separately for each horizon (1h, 6h, 24h),
        and the model with the lowest <b>MAD</b> (mean absolute deviation) wins. An hour-of-day seasonal index (the four-step seasonal procedure) is applied when it
        lowers MAD. The 80% range is forecast ± 1.28 · σ, with σ ≈ 1.25 · MAD. <b>MAPE</b> = average % error; <b>MFE</b> &gt; 0 means the model
        under-forecasts; |<b>tracking signal</b>| &gt; 4 flags a biased model.</p></details>`
      : `<div class="empty">Full chart and forecast are published for the ${SUM.stats.detailed} most active items. This one isn't in that set right now.</div>`}`;

  $("#closeDetail").onclick = () => { $("#detail").classList.remove("open"); history.replaceState(null, "", location.pathname); };
  const st = $("#sideTabs");
  if (st) st.onclick = e => { if (e.target.dataset.v) { state.fcSide = e.target.dataset.v; renderDetail(); } };
  if (d) drawChart();
}

function forecastCards(fc) {
  if (!fc) return "";
  if (!fc.available) return `<div class="empty">Forecast available after ${fc.needs_hours} h of data (have ${fc.hours_of_data} h).</div>`;
  const cards = ["1", "6", "24"].filter(h => fc.h[h]).map(h => {
    const x = fc.h[h], ch = fc.last ? (x.f / fc.last - 1) * 100 : null;
    return `<div class="fcard"><div class="h">In ${h} hour${h === "1" ? "" : "s"}</div>
      <div class="v num">${fmt(x.f)} <span style="font-size:13px">${pct(ch)}</span></div>
      <div class="m">80% range ${fmt(x.lo)} – ${fmt(x.hi)}</div>
      <div class="m">${esc(x.model)} · MAPE ${x.mape != null ? x.mape.toFixed(1) : "–"}% · MAD ${fmt(x.mad)}</div></div>`;
  }).join("");
  const top = fc.h["24"] || fc.h[Object.keys(fc.h).pop()];
  const board = `<table style="margin-top:12px"><thead><tr><th style="cursor:default">Model ranking for ${fc.h["24"] ? "24h" : "longest"} horizon (scored on last 72 h, before hour-of-day adjustment)</th><th style="cursor:default">MAD</th><th style="cursor:default">MAPE</th></tr></thead>
    <tbody>${top.board.map((b, n) => `<tr style="cursor:default"><td>${n + 1}. ${esc(b.model)}</td><td class="num">${fmt(b.mad)}</td><td class="num">${b.mape != null ? b.mape.toFixed(2) + "%" : "–"}</td></tr>`).join("")}</tbody></table>`;
  const warn = fc.biased ? `<div class="warn">⚠ The 1-hour model is consistently off in one direction (tracking signal ${fc.h["1"].ts}). Treat with care.</div>` : "";
  return `<div class="grid fc">${cards}</div>${warn}${board}`;
}

/* ----------------------------------------------------------------------------- chart */
function niceTicks(lo, hi, n = 5) {
  const span = hi - lo || Math.abs(hi) || 1, step0 = span / n, mag = 10 ** Math.floor(Math.log10(step0));
  const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => span / s <= n) || 10 * mag;
  const out = []; for (let v = Math.ceil(lo / step) * step; v <= hi + 1e-9; v += step) out.push(v);
  return out;
}

function drawChart() {
  const { s, d } = CURRENT, bz = s.kind === "bz";
  const box = $("#chart"), W = Math.max(320, box.clientWidth), H = W < 600 ? 240 : 300;
  const P = { l: 56, r: 16, t: 12, b: 26 };
  const n = d.series[bz ? "buy" : "median"].length;
  const ts = i => d.start + i * d.step;
  const keys = bz ? ["buy", "sell"] : ["median", "lbin"];
  const names = bz ? ["Buy", "Sell"] : ["Median sale", "Lowest BIN"];
  const colors = ["var(--series-1)", "var(--series-2)"];
  const fc = d.forecast[state.fcSide];
  const last = (() => { const a = d.series[keys[state.fcSide === "sell" ? 1 : 0]]; for (let i = a.length - 1; i >= 0; i--) if (a[i] != null) return i; return -1; })();
  const fpts = fc && fc.available && last >= 0 ? ["1", "6", "24"].filter(h => fc.h[h]).map(h => ({ t: ts(last) + h * 3600e3, ...fc.h[h], h })) : [];
  const fColor = colors[state.fcSide === "sell" ? 1 : 0];

  let vals = []; keys.forEach(k => d.series[k].forEach(v => v != null && vals.push(v)));
  fpts.forEach(p => vals.push(p.lo, p.hi));
  if (!vals.length) { box.insertAdjacentHTML("beforeend", '<div class="empty">No history yet.</div>'); return; }
  let lo = Math.min(...vals), hi = Math.max(...vals); const pad = (hi - lo) * 0.08 || hi * 0.02 || 1; lo -= pad; hi += pad;
  const t0 = d.start, t1 = fpts.length ? fpts[fpts.length - 1].t : ts(n - 1);
  const X = t => P.l + (t - t0) / (t1 - t0 || 1) * (W - P.l - P.r);
  const Y = v => P.t + (1 - (v - lo) / (hi - lo)) * (H - P.t - P.b);

  const yt = niceTicks(lo, hi, 5);
  let svg = `<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="Price history chart for ${esc(s.name)}">`;
  yt.forEach(v => { svg += `<line x1="${P.l}" x2="${W - P.r}" y1="${Y(v)}" y2="${Y(v)}" stroke="var(--grid)"/><text x="${P.l - 8}" y="${Y(v) + 4}" text-anchor="end">${fmt(v)}</text>`; });
  // day ticks at local midnight
  const dd = new Date(t0); dd.setHours(24, 0, 0, 0);
  for (let t = dd.getTime(); t <= t1; t += 86400e3) {
    svg += `<line x1="${X(t)}" x2="${X(t)}" y1="${P.t}" y2="${H - P.b}" stroke="var(--grid)" stroke-dasharray="2 4"/>`;
    svg += `<text x="${X(t)}" y="${H - 8}" text-anchor="middle">${new Date(t).toLocaleDateString(undefined, { weekday: "short", day: "numeric" })}</text>`;
  }
  // forecast band + line
  if (fpts.length) {
    const lv = d.series[keys[state.fcSide === "sell" ? 1 : 0]][last];
    const top = [[ts(last), lv], ...fpts.map(p => [p.t, p.hi])], bot = [[ts(last), lv], ...fpts.map(p => [p.t, p.lo])].reverse();
    svg += `<polygon points="${[...top, ...bot].map(([t, v]) => `${X(t)},${Y(v)}`).join(" ")}" fill="${fColor}" opacity="0.15"/>`;
    svg += `<polyline points="${[[ts(last), lv], ...fpts.map(p => [p.t, p.f])].map(([t, v]) => `${X(t)},${Y(v)}`).join(" ")}" fill="none" stroke="${fColor}" stroke-width="2" stroke-dasharray="5 4"/>`;
    svg += `<line x1="${X(ts(last))}" x2="${X(ts(last))}" y1="${P.t}" y2="${H - P.b}" stroke="var(--text-muted)" stroke-width="1" opacity=".5"/>`;
    svg += `<text x="${X(ts(last)) + 4}" y="${P.t + 10}">now</text>`;
  }
  // history lines (gaps where an hour has no data)
  // history lines. Auction sale prices bridge hours without sales; bazaar/BIN gaps mean the collector was off.
  keys.forEach((k, si) => {
    const arr = d.series[k], bridge = !bz && k === "median";
    let dpath = "", pen = false, dots = "";
    arr.forEach((v, i) => {
      if (v == null) { if (!bridge) pen = false; return; }
      dpath += `${pen ? "L" : "M"}${X(ts(i)).toFixed(1)},${Y(v).toFixed(1)}`; pen = true;
      const lone = !bridge && (i === 0 || arr[i - 1] == null) && (i === arr.length - 1 || arr[i + 1] == null);
      if (lone) dots += `<circle cx="${X(ts(i))}" cy="${Y(v)}" r="2.5" fill="${colors[si]}"/>`;
    });
    svg += `<path d="${dpath}" fill="none" stroke="${colors[si]}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>${dots}`;
  });
  fpts.forEach(p => { svg += `<circle cx="${X(p.t)}" cy="${Y(p.f)}" r="4" fill="${fColor}" stroke="var(--surface-1)" stroke-width="2"/>`; });
  svg += `<g id="hover" style="display:none"><line id="hx" y1="${P.t}" y2="${H - P.b}" stroke="var(--text-muted)"/><circle id="h0" r="4" fill="${colors[0]}" stroke="var(--surface-1)" stroke-width="2"/><circle id="h1" r="4" fill="${colors[1]}" stroke="var(--surface-1)" stroke-width="2"/></g>`;
  svg += `<rect id="hit" x="${P.l}" y="${P.t}" width="${W - P.l - P.r}" height="${H - P.t - P.b}" fill="transparent"/></svg>`;
  box.querySelectorAll("svg, .empty").forEach(e => e.remove());
  box.insertAdjacentHTML("afterbegin", svg);

  const tip = $("#tip"), g = $("#hover", box), hit = $("#hit", box);
  hit.addEventListener("mousemove", e => {
    const r = hit.getBoundingClientRect(), sx = W / box.clientWidth;
    const t = t0 + ((e.clientX - r.left) * sx) / (W - P.l - P.r) * (t1 - t0);
    let html = "", x;
    const fp = fpts.length && t > ts(last) + 1800e3 ? fpts.reduce((a, b) => Math.abs(b.t - t) < Math.abs(a.t - t) ? b : a) : null;
    if (fp) {
      x = X(fp.t);
      html = `<div class="small muted">Forecast, ${fp.h} h ahead</div><div class="row"><span class="k"><span class="dot" style="background:${fColor}"></span>Forecast</span><b class="num">${fmt(fp.f)}</b></div>
        <div class="row"><span class="k">80% range</span><span class="num">${fmt(fp.lo)} – ${fmt(fp.hi)}</span></div><div class="small muted">${esc(fp.model)}</div>`;
      $("#h0", box).style.display = $("#h1", box).style.display = "none";
    } else {
      const i = Math.max(0, Math.min(n - 1, Math.round((t - t0) / d.step)));
      x = X(ts(i));
      html = `<div class="small muted">${new Date(ts(i)).toLocaleString(undefined, { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })}</div>`;
      keys.forEach((k, si) => {
        const v = d.series[k][i], c = $("#h" + si, box);
        c.style.display = v == null ? "none" : ""; if (v != null) { c.setAttribute("cx", x); c.setAttribute("cy", Y(v)); }
        html += `<div class="row"><span class="k"><span class="dot" style="background:${colors[si]}"></span>${names[si]}</span><b class="num">${fmt(v)}</b></div>`;
      });
      if (!bz) html += `<div class="row"><span class="k">Sales that hour</span><span class="num">${d.series.sales[i] || 0}</span></div>`;
    }
    $("#hx", box).setAttribute("x1", x); $("#hx", box).setAttribute("x2", x); g.style.display = "";
    tip.innerHTML = html; tip.style.display = "block";
    const px = x / sx, left = px + 14 + tip.offsetWidth > box.clientWidth ? px - tip.offsetWidth - 14 : px + 14;
    tip.style.left = left + "px"; tip.style.top = "10px";
  });
  hit.addEventListener("mouseleave", () => { g.style.display = "none"; tip.style.display = "none"; });
}

/* ----------------------------------------------------------------------------- events */
function tabs(sel, key, after) {
  $(sel).addEventListener("click", e => {
    const v = e.target.dataset.v; if (!v) return;
    state[key] = v; [...$(sel).children].forEach(b => b.classList.toggle("on", b.dataset.v === v)); after();
  });
}
tabs("#moverTabs", "mover", renderMovers);
tabs("#fcTabs", "fc", renderFcMoves);
tabs("#kindTabs", "kind", () => { state.shown = 100; renderTable(); });
$("#q").addEventListener("input", e => { state.q = e.target.value; state.shown = 100; renderTable(); });
$("#thead").addEventListener("click", e => {
  const k = e.target.dataset.k; if (!k) return;
  if (state.sort === k) state.asc = !state.asc; else { state.sort = k; state.asc = k === "name"; }
  renderTable();
});
$("#moreBtn").onclick = () => { state.shown += 200; renderTable(); };
document.addEventListener("click", e => {
  const el = e.target.closest("[data-k]");
  if (el && (el.closest("#tbody") || el.closest(".list"))) openItem(el.dataset.k);
});
window.addEventListener("hashchange", route);
let rt; window.addEventListener("resize", () => { clearTimeout(rt); rt = setTimeout(() => CURRENT && CURRENT.d && drawChart(), 150); });
load();
