package com.cluehelper.transport;

import lombok.Value;

@Value
public class Spend
{
	public enum Kind
	{
		RUNES, ITEM, CHARGE, COOLDOWN, DAILY, CAPE_USE
	}

	static final int NO_VAR = -1;

	Kind kind;
	/** The cooldown varp or the daily counter varbit; {@link #NO_VAR} for the other kinds. */
	int var;
}
