package com.cluehelper.hotcold;

import com.cluehelper.step.HotColdTier;
import lombok.Value;

@Value
public class Spot
{
	/** Core's {@code HotColdLocation} name. */
	String name;
	HotColdTier tier;
	/** The cache's centre, or core's where the cache has none. */
	int x;
	int y;
	/** The hypothesis grid's half-width: max(1, core-to-cache distance). */
	int radius;
	/** Core's area name. */
	String area;
	/** On the single/multi border, where the master's enemy can be one Brassican Mage or three wizards. */
	boolean border;
}
