package com.cluehelper.route;

import com.cluehelper.step.GearBonus;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/** Client thread only: item stats and names. What each owned item adds to a gear bonus, cached by item. */
@Singleton
public class GearPieces
{
	private static final int AMMO = EquipmentInventorySlot.AMMO.getSlotIdx();

	private final Client client;
	private final ItemManager itemManager;
	private final Map<GearBonus, Map<Integer, Optional<GearPicker.Piece>>> cache = new EnumMap<>(GearBonus.class);

	@Inject
	GearPieces(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	public Map<Integer, GearPicker.Piece> of(GearBonus bonus, Collection<Integer> itemIds)
	{
		Map<Integer, Optional<GearPicker.Piece>> known = cache.computeIfAbsent(bonus, k -> new HashMap<>());
		Map<Integer, GearPicker.Piece> pieces = new HashMap<>();
		for (int itemId : itemIds)
		{
			known.computeIfAbsent(itemId, id -> piece(bonus, id)).ifPresent(piece -> pieces.put(itemId, piece));
		}
		return Map.copyOf(pieces);
	}

	private Optional<GearPicker.Piece> piece(GearBonus bonus, int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		ItemEquipmentStats equipment = stats == null || !stats.isEquipable() ? null : stats.getEquipment();
		if (equipment == null)
		{
			return Optional.empty();
		}
		int value = value(bonus, equipment, itemId);
		return value > 0 ? Optional.of(new GearPicker.Piece(itemId, equipment.getSlot(), equipment.isTwoHanded(), value))
			: Optional.empty();
	}

	private int value(GearBonus bonus, ItemEquipmentStats equipment, int itemId)
	{
		switch (bonus)
		{
			case MELEE_STRENGTH:
				return equipment.getStr();
			case MAGIC_ATTACK:
				return equipment.getAmagic();
			case RANGED_ATTACK:
				return equipment.getArange();
			case GUTHIX_ITEMS:
				return isGuthix(name(itemId)) ? 1 : 0;
			case ZAMORAK_ITEMS:
				return isZamorak(name(itemId)) ? 1 : 0;
			case ANY_EQUIPMENT:
				return equipment.getSlot() != AMMO ? 1 : 0;
			default:
				throw new IllegalArgumentException("unhandled gear bonus " + bonus);
		}
	}

	private String name(int itemId)
	{
		return client.getItemDefinition(itemId).getName().toLowerCase(Locale.ROOT);
	}

	/** Assumed from item names: Guthix's own gear, all Void Knight equipment and the Book of balance. */
	private static boolean isGuthix(String name)
	{
		return name.contains("guthix") || name.startsWith("void ") || name.startsWith("elite void") || name.equals("book of balance");
	}

	/** Assumed from item names: Zamorak's own gear (Zamorakian spear and hasta too) and the Unholy book. */
	private static boolean isZamorak(String name)
	{
		return name.contains("zamorak") || name.equals("unholy book");
	}
}
