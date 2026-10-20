package com.cluehelper;

/** Where the house's furniture comes from (PLAN §6 {@code pohSource}). */
public enum PohSource
{
	AUTO("Auto"),
	MANUAL("Manual");

	private final String label;

	PohSource(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
