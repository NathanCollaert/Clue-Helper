package com.cluehelper.step;

import net.runelite.api.gameval.ItemID;

/**
 * The step key: core's identity for a step, built only from public getters (PLAN §4 Data). Item
 * ids are aliases, never the key, unless the type owns one item per entry. Cache rows hang off
 * the key and are never the key themselves: one key can own two rows, and two keys one row.
 */
public final class StepKey
{
	private StepKey()
	{
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
				return "hotcold:" + f.getTier() + ":" + ClueFacts.normalise(f.getNpc());
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
	static boolean hasOwnItem(ClueFacts f)
	{
		int item = f.getItemId();
		return item > 0 && item != ItemID.TRAIL_CLUE_BEGINNER && item != ItemID.TRAIL_CLUE_MASTER;
	}

	private static String tile(int[] tile)
	{
		return tile == null ? "?" : tile[0] + "," + tile[1] + "," + tile[2];
	}
}
