package com.cluehelper;

public enum PohGarden
{
	NONE("None"),
	SPIRIT_TREE("Spirit tree"),
	FAIRY_RING("Fairy ring"),
	SPIRITUAL_FAIRY_TREE("Spiritual fairy tree");

	private final String label;

	PohGarden(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
