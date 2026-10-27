package com.cluehelper.step;

import java.util.*;
import java.util.stream.Collectors;
import net.runelite.api.*;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Core's empty-slot requirements ("No Gloves", "Nothing at all"; "No Weapon" also takes a cormorant's glove), read through
 * core's own check on slot-indexed equipment, since the slots themselves aren't exposed.
 */
public final class EmptySlots
{
	private static final EquipmentInventorySlot[] SLOTS = EquipmentInventorySlot.values();
	private static final int SIZE = Arrays.stream(SLOTS).mapToInt(EquipmentInventorySlot::getSlotIdx).max().orElse(0) + 1;
	private static final int EMPTY = -1;
	private static final int SOME_ITEM = 1;

	private EmptySlots()
	{
	}

	/** Met with nothing worn: a slot limit, not an item to bring. */
	public static boolean metByEmptySlots(ItemRequirement requirement)
	{
		return requirement.fulfilledBy(wornOnly(null, EMPTY));
	}

	public static List<ItemRequirement> of(List<ItemRequirement> requirements)
	{
		return requirements.stream().filter(EmptySlots::metByEmptySlots).collect(Collectors.toUnmodifiableList());
	}

	/** Still met with only this item worn, in this slot. */
	public static boolean allows(ItemRequirement requirement, EquipmentInventorySlot slot, int itemId)
	{
		return requirement.fulfilledBy(wornOnly(slot, itemId));
	}

	/** "gloves, weapon and shield", or "everything" when no slot may stay filled. */
	public static String slotNames(List<ItemRequirement> emptied)
	{
		List<String> names = Arrays.stream(SLOTS)
			.filter(slot -> emptied.stream().anyMatch(requirement -> !allows(requirement, slot, SOME_ITEM)))
			.map(slot -> slot.name().toLowerCase(Locale.ROOT))
			.collect(Collectors.toList());
		if (names.size() == SLOTS.length)
		{
			return "everything";
		}
		return names.size() < 2 ? String.join("", names)
			: String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
	}

	private static Item[] wornOnly(EquipmentInventorySlot slot, int itemId)
	{
		Item[] worn = new Item[SIZE];
		Arrays.fill(worn, new Item(EMPTY, 0));
		if (slot != null)
		{
			worn[slot.getSlotIdx()] = new Item(itemId, 1);
		}
		return worn;
	}
}
