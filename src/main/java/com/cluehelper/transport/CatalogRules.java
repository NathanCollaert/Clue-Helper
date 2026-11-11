package com.cluehelper.transport;

import com.cluehelper.Tables;
import java.util.*;
import net.runelite.api.gameval.ItemID;

public final class CatalogRules
{
	/** Never a teleport item: a glory that shows as 1704 has no charges. */
	public static final Set<Integer> UNCHARGED = Tables.idSet("uncharged");

	/**
	 * What a ride's ghostspeak need takes, worn: the ghost captain talks only to a player wearing a ghostspeak amulet, or
	 * Morytania legs 2+ in its place. The legs alone in a clause are a teleport item, not this need.
	 */
	public static final Set<Integer> GHOSTSPEAK = Tables.idSet("ghostspeak");

	public static boolean isGhostspeakNeed(ItemClause clause)
	{
		return clause.getBranches().stream().anyMatch(branch -> branch.getIds().contains(ItemID.AMULET_OF_GHOSTSPEAK));
	}

	private CatalogRules()
	{
	}
}
