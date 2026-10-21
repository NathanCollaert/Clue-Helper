package com.cluehelper;

public enum PohJewelleryBox
{
	NONE("None"),
	BASIC("Basic"),
	FANCY("Fancy"),
	ORNATE("Ornate");

	private final String label;

	PohJewelleryBox(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
