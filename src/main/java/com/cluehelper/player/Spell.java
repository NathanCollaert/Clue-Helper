package com.cluehelper.player;

import java.util.List;
import lombok.Value;
import net.runelite.api.Quest;

@Value
public class Spell
{
	Book book;
	String name;
	/** The cache object that carries the spell's params. */
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
