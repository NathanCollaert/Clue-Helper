package com.cluehelper.step;

import net.runelite.api.gameval.ItemID;

/**
 * Built from core's public getters only. An item id is the key only when the type has one item per
 * entry. A cache row is never the key: one key can own two rows, and two keys one row.
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
		return hotColdPrefix(tier) + ClueFacts.normalise(npc);
	}

	public static String of(ClueFacts f)
	{
		String type = f.getType();
		switch (type)
		{
			case ClueTypes.CRYPTIC:
			case ClueTypes.FALO:
			case ClueTypes.THREE_STEP:
			case ClueTypes.FAIRY_RING:
				return type + ":" + ClueFacts.normalise(f.getText());
			case ClueTypes.SKILL:
				return "skill:" + f.getChallengeType() + ":" + ClueFacts.normalise(f.getRawChallenge());
			case ClueTypes.HOT_COLD:
				return hotCold(HotColdTier.of(f.getTier()), f.getNpc());
			case ClueTypes.BEGINNER_MAP:
				return "map:w" + f.getWidgetGroupId();
			case ClueTypes.COORDINATE:
			case ClueTypes.MAP:
				return hasOwnItem(f) ? type + ":" + f.getItemId() : type + ":" + tile(f.getTile());
			case ClueTypes.ANAGRAM:
			case ClueTypes.CIPHER:
			case ClueTypes.EMOTE:
			case ClueTypes.MUSIC:
				return hasOwnItem(f) ? type + ":" + f.getItemId() : type + ":" + ClueFacts.normalise(f.getText());
			default:
				return "unknown:" + type;
		}
	}

	/** Beginner and master clues of every type share one item each, so their item says nothing. */
	private static boolean hasOwnItem(ClueFacts f)
	{
		int item = f.getItemId();
		return item > 0 && item != ItemID.TRAIL_CLUE_BEGINNER && item != ItemID.TRAIL_CLUE_MASTER;
	}

	private static String tile(int[] tile)
	{
		return tile == null ? "?" : tile[0] + "," + tile[1] + "," + tile[2];
	}
}
