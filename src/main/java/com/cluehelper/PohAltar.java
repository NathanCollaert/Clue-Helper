package com.cluehelper;

/** The house's spellbook altar, for {@code pohSource} Manual. */
public enum PohAltar
{
	NONE("None"),
	OCCULT("Occult"),
	ANCIENT("Ancient"),
	LUNAR("Lunar"),
	DARK("Dark");

	private final String label;

	PohAltar(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
