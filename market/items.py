"""Turns an item's SkyBlock data into the price key used everywhere.

IMPORTANT: the Minecraft mod (ItemKeys.java) uses exactly the same rules, so a
key computed in-game matches the key stored in the database.

Rules
- normal items           -> their SkyBlock id            e.g. HYPERION, ENCHANTED_DIAMOND
- enchanted book, 1 ench -> ENCHANTMENT_<NAME>_<LEVEL>   e.g. ENCHANTMENT_ULTIMATE_WISE_5 (= bazaar id)
- pets                   -> PET_<TYPE>_<TIER>            e.g. PET_ENDER_DRAGON_LEGENDARY
Bazaar product ids are already in this format, so bazaar and AH share one key space.
"""
from __future__ import annotations

import json

from .nbt import decode_item_bytes


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
                ptype, tier = pet.get("type"), pet.get("tier")
                if ptype and tier:
                    return f"PET_{str(ptype).upper()}_{str(tier).upper()}"
            except (ValueError, AttributeError):
                pass
        return item_id

    return item_id


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
