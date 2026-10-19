package com.cluehelper.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * B03: the public facts a step key is built from, as the B03 probe recorded them off core's live
 * clue (src/test/resources/b03/), with the key rule it used. Test support only: B07 builds the
 * real StepKey and replays the same fixtures through ClueLink.
 */
public class ClueFields
{
	static final int SHARED_BEGINNER_ITEM = 23182;
	static final int SHARED_MASTER_ITEM = 19835;

	public String type;
	public int itemId = -1;
	public String text;
	public int[] tile;
	public int widgetGroupId = -1;
	public String tier;
	public String challengeType;
	public String rawChallenge;
	public List<int[]> targets = new ArrayList<>();
	public int targetCount;
	public List<String> npcs = new ArrayList<>();
	public int[] objectIds;
	public String error;

	/** PLAN §4 Data key forms, plus what B03 found public on 1.13.2. */
	public static String key(ClueFields f)
	{
		switch (f.type)
		{
			case "cryptic":
				return "cryptic:" + normalise(f.text);
			case "falo":
				return "falo:" + normalise(f.text);
			case "threestep":
				return "threestep:" + normalise(f.text);
			case "fairyring":
				return "fairyring:" + normalise(f.text);
			case "skill":
				return "skill:" + f.challengeType + ":" + normalise(f.rawChallenge);
			case "hotcold":
				return "hotcold:" + f.tier;
			case "beginnermap":
				return "map:w" + f.widgetGroupId;
			case "coordinate":
				return isSharedItem(f.itemId) || f.itemId <= 0
					? "coordinate:" + f.tile[0] + "," + f.tile[1] + "," + f.tile[2]
					: "coordinate:" + f.itemId;
			case "anagram":
			case "emote":
			case "music":
				return isSharedItem(f.itemId) || f.itemId <= 0
					? f.type + ":" + normalise(f.text)
					: f.type + ":" + f.itemId;
			case "cipher":
			case "map":
				return f.type + ":" + f.itemId;
			default:
				return "unknown:" + f.type;
		}
	}

	static boolean isSharedItem(int itemId)
	{
		return itemId == SHARED_BEGINNER_ITEM || itemId == SHARED_MASTER_ITEM;
	}

	static String normalise(String text)
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
}
