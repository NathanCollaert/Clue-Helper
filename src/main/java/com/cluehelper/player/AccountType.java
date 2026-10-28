package com.cluehelper.player;

/** Declared in the order of varbit 1777's values. A value the game adds later counts as a normal account. */
public enum AccountType
{
	NORMAL,
	IRONMAN,
	ULTIMATE_IRONMAN,
	HARDCORE_IRONMAN,
	GROUP_IRONMAN,
	HARDCORE_GROUP_IRONMAN,
	UNRANKED_GROUP_IRONMAN;

	public static AccountType of(int varbitValue)
	{
		return varbitValue >= 0 && varbitValue < values().length ? values()[varbitValue] : NORMAL;
	}

	/** Ironmen of every kind gather and make things themselves; only a normal account buys on the Grand Exchange. */
	public boolean canTrade()
	{
		return this == NORMAL;
	}

	/** An ultimate ironman has no bank to plan with. */
	public boolean hasBank()
	{
		return this != ULTIMATE_IRONMAN;
	}

	public boolean isHardcore()
	{
		return this == HARDCORE_IRONMAN || this == HARDCORE_GROUP_IRONMAN;
	}

	/** Shares a group storage. */
	public boolean isGroup()
	{
		return this == GROUP_IRONMAN || this == HARDCORE_GROUP_IRONMAN || this == UNRANKED_GROUP_IRONMAN;
	}
}
