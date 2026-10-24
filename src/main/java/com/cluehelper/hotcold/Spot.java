package com.cluehelper.hotcold;

import lombok.Value;

@Value
public class Spot
{
	/** Core's {@code HotColdLocation} name. */
	String name;
	boolean beginner;
	/** The cache's centre, or core's where the cache has none. */
	int x;
	int y;
	/** The hypothesis grid's half-width: max(1, core-to-cache distance). */
	int radius;
	/** Core's description, without its full stop. */
	String description;
	/** Core's area name, e.g. "Zeah". */
	String area;
	/** On the single/multi border, where the master's enemy can be one Brassican Mage or three wizards. */
	boolean border;
}
