package com.cluehelper.player;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
public class Position
{
	public enum Kind
	{
		NONE,
		WORLD,
		/** The tile is the boat's. */
		BOAT,
		/** The tile is in the instance's template area, usually off the walk graph. */
		INSTANCE,
		/** The tile is {@link PositionRules#POH_NODE}. */
		POH
	}

	public static final Position NONE = new Position(null, Kind.NONE);

	/** Null for {@link Kind#NONE}. */
	WorldPoint tile;
	Kind kind;
}
