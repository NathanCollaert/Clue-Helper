package com.cluehelper.step;

import java.util.Locale;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;

/**
 * The step data's key for a clue, built by {@link CoreClueReader#facts} from core's public getters only. An item id is
 * the key only when the type has one item per entry. A cache row is never the key: one key can own two rows, and two
 * keys one row.
 */
public final class StepKey
{
	public static final String HOT_COLD_PREFIX = "hotcold:";

	private StepKey()
	{
	}

	/** The keys of one tier's hot/cold steps start with this. */
	public static String hotColdPrefix(HotColdTier tier)
	{
		return HOT_COLD_PREFIX + tier.getTierName() + ":";
	}

	/** @param npc the NPC the clue names, as core gives it or as a key already names him */
	public static String hotCold(HotColdTier tier, String npc)
	{
		return hotColdPrefix(tier) + normalise(npc);
	}

	public static String normalise(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replaceAll("<[^>]*>", " ")
			.replaceAll("\\s+", " ")
			.trim()
			.toLowerCase(Locale.ROOT);
	}

	/** Beginner and master clues of every type share one item each, so their item says nothing. */
	static boolean hasOwnItem(int itemId)
	{
		return itemId > 0 && itemId != ItemID.TRAIL_CLUE_BEGINNER && itemId != ItemID.TRAIL_CLUE_MASTER;
	}

	static String tile(WorldPoint tile)
	{
		return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
	}
}
