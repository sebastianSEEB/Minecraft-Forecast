"""Attribute-aware valuation: parsing, component prices, fitted clean base / pet levels."""
import json, random, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from market.valuation import Prices, components, fit_model, parse_attrs, pet_fraction, value_of

P = Prices(bazaar={"ENCHANTMENT_ULTIMATE_WISE_5": 2e6, "ENCHANTMENT_SHARPNESS_5": 50e3, "RECOMBOBULATOR_3000": 7e6,
                   "HOT_POTATO_BOOK": 80e3, "FUMING_POTATO_BOOK": 1.2e6, "ESSENCE_WITHER": 3e3, "FIRST_MASTER_STAR": 10e6,
                   "PERFECT_SAPPHIRE_GEM": 15e6, "FINE_JADE_GEM": 50e3, "THE_ART_OF_WAR": 5e6},
           lbin={"PET_ITEM_TIER_BOOST": 40e6, "PET_SKIN_ENDERMAN": 20e6},
           upgrade_costs={"HYPERION": [[{"type": "ESSENCE", "essence_type": "WITHER", "amount": 150}]] * 5})


def test_parse_attrs():
    a = parse_attrs({"id": "HYPERION", "enchantments": {"ultimate_wise": 5, "sharpness": 5}, "rarity_upgrades": 1,
                     "hot_potato_count": 15, "upgrade_level": 6, "art_of_war_count": 1,
                     "gems": {"unlocked_slots": ["SAPPHIRE_0"], "SAPPHIRE_0": "PERFECT", "COMBAT_0": {"quality": "FINE", "uuid": "x"},
                              "COMBAT_0_gem": "JADE"}})
    assert a["e"] == {"ULTIMATE_WISE": 5, "SHARPNESS": 5} and a["r"] == 1 and a["hpb"] == 15 and a["st"] == 6
    assert sorted(a["g"]) == ["FINE_JADE_GEM", "PERFECT_SAPPHIRE_GEM"] and a["aow"] == 1
    pet = parse_attrs({"id": "PET", "petInfo": json.dumps({"type": "ENDERMAN", "tier": "LEGENDARY", "exp": 25353230,
                                                           "heldItem": "PET_ITEM_TIER_BOOST", "skin": "ENDERMAN"})})
    assert pet["pet"]["held"] == "PET_ITEM_TIER_BOOST" and pet_fraction(pet, "PET_ENDERMAN_LEGENDARY") == 1.0
    assert abs(pet_fraction({"pet": {"xp": 12_676_615}}, "PET_GOLDEN_DRAGON_LEGENDARY") - 12_676_615 / 210_255_385) < 1e-9


def test_components():
    a = parse_attrs({"enchantments": {"ultimate_wise": 5, "sharpness": 5}, "rarity_upgrades": 1, "hot_potato_count": 12,
                     "upgrade_level": 6})
    c = dict(components(a, "HYPERION", P))
    assert abs(c["enchants"] - 2e6 * 0.85) < 1            # sharpness 5 (50k) ignored as noise
    assert abs(c["recomb"] - 7e6 * 0.85) < 1
    assert abs(c["potato books"] - (10 * 80e3 + 2 * 1.2e6 * 0.85)) < 1
    assert abs(c["stars"] - (5 * 150 * 3e3 * 0.9 + 10e6 * 0.95)) < 1   # 5 essence stars + 1 master star


def test_fit_item_base_recovers_clean_price():
    random.seed(1)
    sales = []
    for i in range(40):
        a = {}
        if i % 2: a["r"] = 1
        if i % 3 == 0: a["e"] = {"ULTIMATE_WISE": 5}
        price = 500e6 + sum(v for _, v in components(a, "HYPERION", P)) + random.gauss(0, 5e6)
        sales.append((price, a))
    m = fit_model("HYPERION", "HYPERION", sales, [], P)
    assert abs(m["base"] - 500e6) < 5e6, m
    v, parts = value_of(m, "HYPERION", "HYPERION", {"r": 1, "e": {"ULTIMATE_WISE": 5}}, P)
    assert abs(v - (m["base"] + 7e6 * 0.85 + 2e6 * 0.85)) < 1


def test_fit_pet_levels():
    random.seed(2)
    xp_max = 25_353_230
    sales = []
    for i in range(30):
        xp = random.choice([0, 0, xp_max * 0.3, xp_max * 0.7, xp_max, xp_max])
        price = 100e6 + 300e6 * (xp / xp_max) + random.gauss(0, 5e6)
        sales.append((price, {"pet": {"xp": xp}}))
    m = fit_model("PET_ENDER_DRAGON_LEGENDARY", "PET", sales, [], P)
    assert m["type"] == "pet" and abs(m["v1"] - 100e6) < 10e6 and abs(m["v100"] - 400e6) < 10e6, m
    v, _ = value_of(m, "PET_ENDER_DRAGON_LEGENDARY", "PET", {"pet": {"xp": xp_max / 2, "held": "PET_ITEM_TIER_BOOST"}}, P)
    assert abs(v - (m["v1"] + (m["v100"] - m["v1"]) * 0.5 + 40e6 * 0.9)) < 1




def test_tier_boost_keyed_by_real_rarity():
    from market.items import key_from_extra
    boosted = {"id": "PET", "petInfo": json.dumps({"type": "GRIFFIN", "tier": "LEGENDARY", "heldItem": "PET_ITEM_TIER_BOOST", "exp": 0})}
    plain = {"id": "PET", "petInfo": json.dumps({"type": "GRIFFIN", "tier": "LEGENDARY", "exp": 0})}
    assert key_from_extra(boosted) == "PET_GRIFFIN_EPIC"
    assert key_from_extra(plain) == "PET_GRIFFIN_LEGENDARY"
    a = parse_attrs(boosted)
    assert a["pet"]["held"] == "PET_ITEM_TIER_BOOST" and a["pet"]["tier"] == "LEGENDARY"


if __name__ == "__main__":
    for n, f in list(globals().items()):
        if n.startswith("test_"):
            f(); print("ok", n)
