package com.cluehelper.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/** Item rules the Shortest Path syntax can't say, applied to rows as they load. */
public final class CatalogRules
{
	/**
	 * Uncharged ids never count as a teleport item (a glory that shows as 1704 has no charges). B09's
	 * schema test also keeps them out of the catalog.
	 */
	public static final Set<Integer> UNCHARGED = Set.of(ItemID.AMULET_OF_GLORY, ItemID.TRAIL_AMULET_OF_GLORY,
		ItemID.RING_OF_WEALTH, ItemID.JEWL_NECKLACE_OF_SKILLS, ItemID.JEWL_BRACELET_OF_COMBAT,
		ItemID.XERIC_TALISMAN_EMPTY, ItemID.RING_OF_SHADOWS_UNCHARGED, ItemID.TOME_OF_FIRE_UNCHARGED,
		ItemID.TOME_OF_WATER_UNCHARGED, ItemID.TOME_OF_EARTH_UNCHARGED);

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
