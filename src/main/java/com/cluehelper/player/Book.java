package com.cluehelper.player;

/** Declared in the order of varbit 4070's values: callers compare {@code ordinal()} with the varbit. */
public enum Book
{
	STANDARD, ANCIENT, LUNAR, ARCEUUS;

	public static Book of(int varbit4070)
	{
		return varbit4070 >= 0 && varbit4070 < values().length ? values()[varbit4070] : null;
	}

	/** As the game names it: "Ancient". */
	public String label()
	{
		return name().charAt(0) + name().substring(1).toLowerCase();
	}
}
