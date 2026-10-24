package com.cluehelper.hotcold;

import com.cluehelper.step.HotColdTier;

/** Core's bands, measured exact for both tiers: Chebyshev distance to the spot's centre. */
public enum Band
{
	SHAKING("visibly shaking", 3, 4),
	INCREDIBLY_HOT("incredibly hot", 29, 29),
	VERY_HOT("very hot", 69, 69),
	HOT("hot", 99, 99),
	WARM("warm", 149, 149),
	COLD("cold", 199, 199),
	VERY_COLD("very cold", 499, 499),
	ICE_COLD("ice cold", Integer.MAX_VALUE, Integer.MAX_VALUE);

	private final String text;
	private final int beginnerMax;
	private final int masterMax;

	Band(String text, int beginnerMax, int masterMax)
	{
		this.text = text;
		this.beginnerMax = beginnerMax;
		this.masterMax = masterMax;
	}

	public String getText()
	{
		return text;
	}

	public static Band of(int distance, HotColdTier tier)
	{
		for (Band band : values())
		{
			if (distance <= (tier == HotColdTier.BEGINNER ? band.beginnerMax : band.masterMax))
			{
				return band;
			}
		}
		return ICE_COLD;
	}

	/** Tiles from the centre within which a dig finds the casket or the master's enemy. */
	public static int digRadius(HotColdTier tier)
	{
		return tier == HotColdTier.BEGINNER ? SHAKING.beginnerMax : SHAKING.masterMax;
	}

	/** Null unless the clause is exactly one band's text. */
	static Band fromClause(String clause)
	{
		for (Band band : values())
		{
			if (band.text.equals(clause))
			{
				return band;
			}
		}
		return null;
	}
}
