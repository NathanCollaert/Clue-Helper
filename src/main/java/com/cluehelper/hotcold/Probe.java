package com.cluehelper.hotcold;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** A tile to feel on, and what getting there costs. */
@Value
public class Probe
{
	public enum Kind
	{
		/** Where the player stands. */
		HERE,
		/** A tile in the loaded scene, walked to. */
		LOCAL,
		/** A walk-table node, by the route planner's Best rule. */
		LANDING
	}

	WorldPoint tile;
	Kind kind;
	/** The route's shown ticks. */
	int ticks;
	/** The route's ranking ticks: its ticks plus penalties such as a Magic cape use. */
	int rankTicks;
	boolean wilderness;
}
