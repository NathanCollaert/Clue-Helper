package com.cluehelper.player;

import java.util.List;
import lombok.Value;
import net.runelite.api.Quest;

/** One spell as the cache describes it (enum 1981 and its per-book enums, B04). */
@Value
public class Spell
{
	Book book;
	String name;
	/** The spell's object id, which carries its params. */
	int obj;
	int level;
	boolean members;
	/** Null when no quest gates it. */
	Quest quest;
	List<RuneCost> runes;

	@Value
	public static class RuneCost
	{
		int rune;
		int quantity;
	}
}
