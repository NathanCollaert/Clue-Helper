package com.cluehelper;

public enum GuideBoxStyle
{
	NONE("None"),
	SEPARATE("Separate"),
	COMBINED("Combined"),
	HELPER_ONLY("Helper only");

	/** Whether the Clue Scroll plugin's own box is hidden. */
	public boolean hidesCoreBox()
	{
		return this == COMBINED || this == HELPER_ONLY;
	}

	final String label;

	GuideBoxStyle(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
