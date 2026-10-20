package com.cluehelper;

/** The cape on the house's cape hanger, for {@code pohSource} Manual. */
public enum PohMountedCape
{
	NONE("None"),
	MAGIC("Magic cape"),
	MAGIC_TRIMMED("Magic cape (t)"),
	MAX("Max cape");

	private final String label;

	PohMountedCape(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
