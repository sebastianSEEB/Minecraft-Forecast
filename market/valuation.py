"""Attribute-aware valuation: what is THIS copy of an item worth, given its upgrades?

    value = clean base value + Σ (component price × applied factor)

Components read from the item's SkyBlock data (ExtraAttributes / custom_data):
  enchantments   each enchant priced as its book (bazaar ENCHANTMENT_<NAME>_<LVL>, else lowest BIN of that book)
  recombobulator rarity_upgrades > 0                      -> RECOMBOBULATOR_3000
  potato books   hot_potato_count (first 10 hot, rest fuming) -> HOT_POTATO_BOOK / FUMING_POTATO_BOOK
  stars          upgrade_level / dungeon_item_level       -> the item's real essence costs (Hypixel items resource)
                 stars above the item's normal max        -> FIRST..FIFTH_MASTER_STAR
  gemstones      gems                                     -> <QUALITY>_<TYPE>_GEM (bazaar)
  art of war     art_of_war_count                         -> THE_ART_OF_WAR
  pets           level by XP: value = L1 + (L100 - L1) × XP / XP-to-max,  + held item + skin
  rarity         pets are priced per rarity (separate keys; a Tier Boost is keyed by the real rarity and
                 valued as its held item); items get +1 rarity from a recombobulator, valued as above

Applied upgrades rarely resell for their full cost, so each type has a factor (FACTORS, configurable).
The clean base value (and a pet's L1/L100 values) is fitted from real sales by subtracting the
components from every sale price: median residual for items, linear regression of residual on
XP fraction for pets (b̂ = (Σxy - ΣxΣy/n) / (Σx² - (Σx)²/n), â = ȳ - b̂x̄, as in Lecture 4).
"""
from __future__ import annotations

import json
import statistics

# total XP from level 1 to level 100 (pet XP table with rarity offsets 0/6/11/16/20)
PET_XP_MAX = {"COMMON": 5_624_785, "UNCOMMON": 8_644_220, "RARE": 12_626_665, "EPIC": 18_608_500,
              "LEGENDARY": 25_353_230, "MYTHIC": 25_353_230}
LEVEL_200_PETS = {"GOLDEN_DRAGON", "JADE_DRAGON", "ROSE_DRAGON"}
XP_MAX_200 = 210_255_385          # legendary to level 200: 25,353,230 + 0 + 5,555 + 98 × 1,886,700

FACTORS = {"enchant": 0.85, "recomb": 0.85, "hpb": 1.0, "fuming": 0.85, "stars": 0.9, "master_star": 0.95,
           "gem": 1.0, "art_of_war": 0.85, "pet_item": 0.9, "pet_skin": 0.8}
MIN_ENCHANT_VALUE = 200_000       # cheap enchants are noise
MASTER_STARS = ["FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"]
GEM_SLOT_TYPES = {"RUBY", "AMBER", "TOPAZ", "JADE", "SAPPHIRE", "AMETHYST", "JASPER", "OPAL", "ONYX",
                  "AQUAMARINE", "CITRINE", "PERIDOT"}


# --------------------------------------------------------------------------- attributes

def parse_attrs(extra: dict) -> dict:
    """Compact attribute dict from ExtraAttributes (stored with every sale/listing)."""
    a: dict = {}
    ench = extra.get("enchantments")
    if isinstance(ench, dict) and ench:
        a["e"] = {str(k).upper(): int(v) for k, v in ench.items() if isinstance(v, (int, float))}
    if int(extra.get("rarity_upgrades") or 0) > 0:
        a["r"] = 1
    hpb = int(extra.get("hot_potato_count") or 0)
    if hpb:
        a["hpb"] = hpb
    stars = int(extra.get("upgrade_level") or extra.get("dungeon_item_level") or 0)
    if stars:
        a["st"] = stars
    aow = int(extra.get("art_of_war_count") or 0)
    if aow:
        a["aow"] = aow
    gems = extra.get("gems")
    if isinstance(gems, dict):
        g = []
        for slot, q in gems.items():
            if slot == "unlocked_slots" or slot.endswith("_gem"):
                continue
            quality = q.get("quality") if isinstance(q, dict) else q
            if not isinstance(quality, str):
                continue
            base = slot.rsplit("_", 1)[0]
            gtype = base if base in GEM_SLOT_TYPES else gems.get(f"{slot}_gem")
            if isinstance(gtype, str) and gtype:
                g.append(f"{quality.upper()}_{gtype.upper()}_GEM")
        if g:
            a["g"] = g
    info = extra.get("petInfo")
    if isinstance(info, str):
        try:
            p = json.loads(info)
            a["pet"] = {"xp": float(p.get("exp") or 0), "held": p.get("heldItem"), "skin": p.get("skin"),
                        "candy": int(p.get("candyUsed") or 0), "tier": str(p.get("tier") or "").upper() or None}
        except ValueError:
            pass
    return a


def pet_fraction(attrs: dict, key: str) -> float:
    """XP progress from level 1 (0.0) to max level (1.0) for PET_<TYPE>_<TIER> keys."""
    pet = attrs.get("pet") or {}
    parts = key.split("_")
    tier = pet.get("tier") or (parts[-1] if parts else "LEGENDARY")   # shown rarity sets the XP curve
    ptype = "_".join(parts[1:-1])
    xp_max = XP_MAX_200 if ptype in LEVEL_200_PETS else PET_XP_MAX.get(tier, PET_XP_MAX["LEGENDARY"])
    return max(0.0, min(1.0, float(pet.get("xp") or 0) / xp_max))


# --------------------------------------------------------------------------- component prices

class Prices:
    """Looks up component prices: bazaar instant-buy first, else lowest BIN."""

    def __init__(self, bazaar: dict[str, float], lbin: dict[str, float], upgrade_costs: dict[str, list]):
        self.bazaar, self.lbin, self.upgrade_costs = bazaar, lbin, upgrade_costs

    @classmethod
    def from_db(cls, c, upgrade_costs: dict | None = None) -> "Prices":
        bz = {r[0]: r[1] for r in c.execute("SELECT product, buy FROM bz_now WHERE buy > 0")}
        lb = {r[0]: r[1] for r in c.execute("SELECT item_key, lowest_bin FROM lbin_now")}
        if upgrade_costs is None:
            row = c.execute("SELECT v FROM meta WHERE k='upgrade_costs'").fetchone()
            upgrade_costs = json.loads(row[0]) if row else {}
        return cls(bz, lb, upgrade_costs)

    def get(self, pid: str | None) -> float:
        if not pid:
            return 0.0
        return self.bazaar.get(pid) or self.lbin.get(pid) or 0.0


def components(attrs: dict, item_id: str | None, prices: Prices, factors: dict = FACTORS) -> list[tuple[str, float]]:
    """(label, coins) for every priced upgrade on the item, already multiplied by its factor."""
    out: list[tuple[str, float]] = []
    ench_total = 0.0
    for name, lvl in (attrs.get("e") or {}).items():
        v = prices.get(f"ENCHANTMENT_{name}_{lvl}")
        if v >= MIN_ENCHANT_VALUE:
            ench_total += v
    if ench_total:
        out.append(("enchants", ench_total * factors["enchant"]))
    if attrs.get("r"):
        out.append(("recomb", prices.get("RECOMBOBULATOR_3000") * factors["recomb"]))
    hpb = attrs.get("hpb") or 0
    if hpb:
        v = min(hpb, 10) * prices.get("HOT_POTATO_BOOK") * factors["hpb"] \
            + max(0, hpb - 10) * prices.get("FUMING_POTATO_BOOK") * factors["fuming"]
        out.append(("potato books", v))
    stars = attrs.get("st") or 0
    if stars and item_id:
        tiers = prices.upgrade_costs.get(item_id) or []
        v = 0.0
        for t in tiers[:stars]:
            for cost in t:
                if cost.get("type") == "ESSENCE":
                    v += cost.get("amount", 0) * prices.get(f"ESSENCE_{cost.get('essence_type')}")
                elif cost.get("item_id"):
                    v += cost.get("amount", 1) * prices.get(cost["item_id"])
        v *= factors["stars"]
        if tiers and stars > len(tiers):
            for i in range(min(stars - len(tiers), 5)):
                v += prices.get(MASTER_STARS[i]) * factors["master_star"]
        if v:
            out.append(("stars", v))
    gems = sum(prices.get(g) for g in attrs.get("g") or [])
    if gems:
        out.append(("gems", gems * factors["gem"]))
    if attrs.get("aow"):
        out.append(("art of war", prices.get("THE_ART_OF_WAR") * factors["art_of_war"]))
    pet = attrs.get("pet") or {}
    if pet.get("held"):
        out.append(("pet item", prices.get(pet["held"]) * factors["pet_item"]))
    if pet.get("skin"):
        out.append(("skin", prices.get(f"PET_SKIN_{pet['skin']}") * factors["pet_skin"]))
    return [(k, v) for k, v in out if v > 0]


# --------------------------------------------------------------------------- models fitted from sales

def _regression(xs, ys):
    n = len(xs)
    sx, sy = sum(xs), sum(ys)
    sxx = sum(x * x for x in xs)
    sxy = sum(x * y for x, y in zip(xs, ys))
    den = sxx - sx * sx / n
    if den <= 1e-9:
        return None
    b = (sxy - sx * sy / n) / den
    return sy / n - b * sx / n, b


def fit_model(key: str, item_id: str | None, sales: list[tuple[float, dict]], listings: list[tuple[float, dict]],
              prices: Prices) -> dict | None:
    """sales/listings: (unit price, attrs). Returns the clean-value model for the key."""
    def residual(price, attrs):
        return price - sum(v for _, v in components(attrs, item_id, prices))

    if key.startswith("PET_"):
        pts = [(pet_fraction(a, key), residual(p, a)) for p, a in sales if a.get("pet") is not None]
        if len(pts) < 3:
            return None
        xs, ys = [x for x, _ in pts], [y for _, y in pts]
        reg = _regression(xs, ys) if len(pts) >= 4 and statistics.pstdev(xs) > 0.15 else None
        if reg and reg[1] >= 0:
            v1, v100 = reg[0], reg[0] + reg[1]
        else:
            lo = [y for x, y in pts if x < 0.1]
            hi = [y for x, y in pts if x > 0.95]
            med = statistics.median(ys)
            v1 = statistics.median(lo) if lo else med
            v100 = statistics.median(hi) if hi else max(med, v1)
        v1 = max(v1, 0.0)
        return {"type": "pet", "v1": v1, "v100": max(v100, v1), "n_sales": len(pts)}

    res = [residual(p, a) for p, a in sales]
    if len(res) < 3:
        return None
    base = statistics.median(res)
    # cheapest quarter of current listings, leaving out the single cheapest one: an underpriced listing
    # (the very thing the deal finder looks for) must not drag the fair value down with it
    lres = sorted(residual(p, a) for p, a in listings)[1:]
    if len(lres) >= 3:
        base = statistics.median([base, lres[int(0.25 * (len(lres) - 1))]])
    return {"type": "item", "base": max(base, 0.0), "n_sales": len(res), "n_listings": len(lres)}


def value_of(model: dict, key: str, item_id: str | None, attrs: dict, prices: Prices) -> tuple[float, list[tuple[str, float]]]:
    """(estimated value, breakdown) for one specific copy of an item."""
    comps = components(attrs, item_id, prices)
    if model["type"] == "pet":
        f = pet_fraction(attrs, key)
        lvl = model["v1"] + (model["v100"] - model["v1"]) * f
        base = [("pet (by level)", lvl)]
    else:
        base = [("clean base", model["base"])]
    parts = base + comps
    return sum(v for _, v in parts), parts


def components_doc(c) -> dict:
    """Everything the mod needs to price upgrades itself (published as data/components.json)."""
    p = Prices.from_db(c)
    return {"bazaar": {k: round(v, 1) for k, v in p.bazaar.items()},
            "lbin": {k: round(v, 1) for k, v in p.lbin.items()},
            "upgrade_costs": p.upgrade_costs, "factors": FACTORS, "min_enchant_value": MIN_ENCHANT_VALUE,
            "pet_xp_max": PET_XP_MAX, "level_200_pets": sorted(LEVEL_200_PETS), "xp_max_200": XP_MAX_200,
            "master_stars": MASTER_STARS}
