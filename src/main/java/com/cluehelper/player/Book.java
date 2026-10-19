package com.cluehelper.player;

/** A spellbook, numbered as varbit 4070 holds it. */
public enum Book
{
	STANDARD, ANCIENT, LUNAR, ARCEUUS;

	/** Null for a value 4070 never takes. */
	public static Book of(int varbit4070)
	{
		return varbit4070 >= 0 && varbit4070 < values().length ? values()[varbit4070] : null;
	}
}
