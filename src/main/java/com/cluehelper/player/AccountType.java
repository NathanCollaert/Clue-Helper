package com.cluehelper.player;

/** Varbit 1777. A value the game adds later counts as a normal account. */
public enum AccountType
{
	NORMAL(0),
	IRONMAN(1),
	ULTIMATE_IRONMAN(2),
	HARDCORE_IRONMAN(3),
	GROUP_IRONMAN(4),
	HARDCORE_GROUP_IRONMAN(5),
	UNRANKED_GROUP_IRONMAN(6);

	private final int varbitValue;

	AccountType(int varbitValue)
	{
		this.varbitValue = varbitValue;
	}

	public static AccountType of(int varbitValue)
	{
		for (AccountType type : values())
		{
			if (type.varbitValue == varbitValue)
			{
				return type;
			}
		}
		return NORMAL;
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
