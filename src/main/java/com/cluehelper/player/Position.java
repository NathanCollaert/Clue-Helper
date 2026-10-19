package com.cluehelper.player;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** The player's place as routing sees it (PLAN §4 item 3, B02/B03). */
@Value
public class Position
{
	public enum Kind
	{
		/** Logged out, or no local player. */
		NONE,
		WORLD,
		/** On a Sailing boat: the tile is the boat's. */
		BOAT,
		/** In an instance: the tile is in its template area, usually off the walk graph. */
		INSTANCE,
		/** Inside a player-owned house: the tile is the POH node. */
		POH
	}

	public static final Position NONE = new Position(null, Kind.NONE, null);

	/** The tile routes start from; null for {@link Kind#NONE}. */
	WorldPoint tile;
	Kind kind;
	/** What the client's mapping gave before the POH rule, for the dev dump. */
	WorldPoint mapped;
}
