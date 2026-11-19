"""Turns an item's SkyBlock data into the price key used everywhere.

IMPORTANT: the Minecraft mod (ItemKeys.java) uses exactly the same rules, so a
key computed in-game matches the key stored in the database.

Rules
- normal items           -> their SkyBlock id            e.g. HYPERION, ENCHANTED_DIAMOND
- enchanted book, 1 ench -> ENCHANTMENT_<NAME>_<LEVEL>   e.g. ENCHANTMENT_ULTIMATE_WISE_5 (= bazaar id)
- pets                   -> PET_<TYPE>_<TIER>            e.g. PET_ENDER_DRAGON_LEGENDARY
  A pet holding a Tier Boost shows one rarity higher but sells as its real rarity plus the boost item,
  so it is keyed by the real (unboosted) rarity; the Tier Boost is valued as its held item.
Bazaar product ids are already in this format, so bazaar and AH share one key space.
"""
from __future__ import annotations

import json

from .nbt import decode_item_bytes

TIERS = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE"]
TIER_BOOST = "PET_ITEM_TIER_BOOST"


def pet_tier(pet: dict) -> str:
    """The pet's real rarity (a Tier Boost raises the shown rarity by one)."""
    tier = str(pet.get("tier") or "").upper()
    if pet.get("heldItem") == TIER_BOOST and tier in TIERS and TIERS.index(tier) > 0:
        return TIERS[TIERS.index(tier) - 1]
    return tier


def key_from_extra(extra: dict) -> str | None:
    item_id = extra.get("id")
    if not isinstance(item_id, str) or not item_id:
        return None

    if item_id == "ENCHANTED_BOOK":
        ench = extra.get("enchantments")
        if isinstance(ench, dict) and len(ench) == 1:
            (name, level), = ench.items()
            return f"ENCHANTMENT_{name.upper()}_{int(level)}"
        return item_id

    if item_id == "PET":
        info = extra.get("petInfo")
        if isinstance(info, str):
            try:
                pet = json.loads(info)
                ptype, tier = pet.get("type"), pet_tier(pet)
                if ptype and tier:
                    return f"PET_{str(ptype).upper()}_{str(tier).upper()}"
            except (ValueError, AttributeError):
                pass
        return item_id

    return item_id


def decode_listing(item_bytes):
    """(price key, raw SkyBlock id, stack count, upgrade attributes) for an auction's item_bytes."""
    from .valuation import parse_attrs
    items = decode_item_bytes(item_bytes)
    if not items:
        return None, None, 1, {}
    it = items[0]
    extra = (it.get("tag") or {}).get("ExtraAttributes") or {}
    try:
        count = max(int(it.get("Count", 1)), 1)
    except (TypeError, ValueError):
        count = 1
    return key_from_extra(extra), extra.get("id"), count, parse_attrs(extra)


def key_and_count(item_bytes) -> tuple[str | None, str | None, int]:
    """(price key, raw SkyBlock id, stack count) for an auction's item_bytes."""
    items = decode_item_bytes(item_bytes)
    if not items:
        return None, None, 1
    it = items[0]
    tag = it.get("tag") or {}
    extra = tag.get("ExtraAttributes") or {}
    count = it.get("Count", 1)
    try:
        count = max(int(count), 1)
    except (TypeError, ValueError):
        count = 1
    return key_from_extra(extra), extra.get("id"), count
