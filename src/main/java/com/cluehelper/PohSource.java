package com.cluehelper;

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
