package dev.sebastian.skyprice;

import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Turns a SkyBlock item into the same price key the Python side uses (market/items.py).
 *
 *   normal item              -> SkyBlock id                 HYPERION, ENCHANTED_DIAMOND
 *   enchanted book, 1 ench   -> ENCHANTMENT_NAME_LEVEL      ENCHANTMENT_ULTIMATE_WISE_5
 *   pet                      -> PET_TYPE_TIER               PET_ENDER_DRAGON_LEGENDARY  (real rarity if tier boosted)
 */
final class ItemKeys {
	private ItemKeys() {}

	static String keyFor(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		CompoundTag tag = data.copyTag();
		String id = tag.getStringOr("id", "");
		if (id.isEmpty()) return null;

		if (id.equals("ENCHANTED_BOOK")) {
			CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
			Set<String> names = ench.keySet();
			if (names.size() == 1) {
				String name = names.iterator().next();
				return "ENCHANTMENT_" + name.toUpperCase(Locale.ROOT) + "_" + ench.getIntOr(name, 0);
			}
			return id;
		}

		if (id.equals("PET")) {
			String info = tag.getStringOr("petInfo", "");
			try {
				JsonObject pet = JsonParser.parseString(info).getAsJsonObject();
				if (pet.has("type") && pet.has("tier")) {
					String held = pet.has("heldItem") && !pet.get("heldItem").isJsonNull() ? pet.get("heldItem").getAsString() : null;
					// a Tier Boost shows one rarity higher; priced as the real rarity + the boost (same rule as market/items.py)
					return "PET_" + pet.get("type").getAsString().toUpperCase(Locale.ROOT)
							+ "_" + Valuation.realPetTier(pet.get("tier").getAsString().toUpperCase(Locale.ROOT), held);
				}
			} catch (RuntimeException ignored) {
				// malformed petInfo: fall back to the plain id
			}
			return id;
		}

		return id;
	}
}
