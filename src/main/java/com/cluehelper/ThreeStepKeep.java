package com.cluehelper;

public enum ThreeStepKeep
{
	QUICKEST_NOW("Quickest now"),
	QUICKEST_MAXED("Quickest with all teleports");

	final String label;

	ThreeStepKeep(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
