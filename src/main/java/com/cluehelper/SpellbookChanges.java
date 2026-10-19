package com.cluehelper;

public enum SpellbookChanges
{
	OFF("Off"),
	SWAP_ONLY("Spellbook Swap only"),
	ALL("All");

	private final String label;

	SpellbookChanges(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
