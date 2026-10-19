package com.cluehelper;

public enum DiggingDog
{
	AUTO("Auto"),
	ALWAYS("Always"),
	NEVER("Never");

	private final String label;

	DiggingDog(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
