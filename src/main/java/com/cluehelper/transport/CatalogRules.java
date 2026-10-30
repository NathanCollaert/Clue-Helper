package com.cluehelper.transport;

import java.util.*;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

public final class CatalogRules
{
	/** Never a teleport item: a glory that shows as 1704 has no charges. */
	public static final Set<Integer> UNCHARGED = Set.of(ItemID.AMULET_OF_GLORY, ItemID.TRAIL_AMULET_OF_GLORY,
		ItemID.RING_OF_WEALTH, ItemID.JEWL_NECKLACE_OF_SKILLS, ItemID.JEWL_BRACELET_OF_COMBAT,
		ItemID.XERIC_TALISMAN_EMPTY, ItemID.RING_OF_SHADOWS_UNCHARGED, ItemID.TOME_OF_FIRE_UNCHARGED,
		ItemID.TOME_OF_WATER_UNCHARGED, ItemID.TOME_OF_EARTH_UNCHARGED);

	/**
	 * What a ride's ghostspeak need takes, worn: the ghost captain talks only to a player wearing a ghostspeak amulet, or
	 * Morytania legs 2+ in its place. The legs alone in a clause are a teleport item, not this need.
	 */
	public static final Set<Integer> GHOSTSPEAK = Set.of(ItemID.AMULET_OF_GHOSTSPEAK, ItemID.AMULET_OF_GHOSTSPEAK_ENCHANTED,
		ItemID.MORYTANIA_LEGS_MEDIUM, ItemID.MORYTANIA_LEGS_HARD, ItemID.MORYTANIA_LEGS_ELITE);

	public static boolean isGhostspeakNeed(ItemClause clause)
	{
		return clause.getBranches().stream().anyMatch(branch -> branch.getIds().contains(ItemID.AMULET_OF_GHOSTSPEAK));
	}

	private static final Set<Integer> FAIRY_RING_STAVES = Set.of(ItemID.DRAMEN_STAFF, ItemID.LUNAR_MOONCLAN_LIMINAL_STAFF);
	private static final VarCheck LUMBRIDGE_ELITE_DONE =
		new VarCheck(VarCheck.Kind.VARBIT, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, VarCheck.Op.EQUAL, 1);

	private CatalogRules()
	{
	}

	/** A fairy-ring staff clause counts only when wielded, and not at all once the Lumbridge elite diary is done. */
	public static Requirements fairyRingStaff(Requirements requirements)
	{
		List<ItemClause> clauses = new ArrayList<>();
		for (ItemClause clause : requirements.getItems())
		{
			boolean staffClause = clause.getBranches().stream()
				.anyMatch(branch -> branch.getIds().stream().anyMatch(FAIRY_RING_STAVES::contains));
			clauses.add(staffClause ? clause.wornOnly(LUMBRIDGE_ELITE_DONE) : clause);
		}
		return requirements.toBuilder().items(List.copyOf(clauses)).build();
	}
}
