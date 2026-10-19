package com.cluehelper;

public enum AvoidWilderness
{
	NEVER("Never"),
	HARDCORE_ONLY("Hardcore only"),
	ALWAYS("Always");

	private final String label;

	AvoidWilderness(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
