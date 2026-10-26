package com.cluehelper.route;

import com.cluehelper.player.PositionRules;
import net.runelite.api.coords.WorldPoint;

public enum OriginClass
{
	/** Minigame rows work only outside the Wilderness, so level 0 needs an exit too. */
	WILD0(0, 3524),
	WILD20(20, 3679),
	WILD30(30, 3759),
	/** No teleport, and no spellbook change either, since no teleport could follow. */
	WILD_DEEP(Integer.MAX_VALUE),
	/** Grouping (minigame) teleports don't work from inside a house. */
	POH(0),
	NO_TELEPORT(Integer.MAX_VALUE);

	private static final int NO_EXIT = Integer.MIN_VALUE;

	private final int highestLevel;
	/** Walking due south to this surface y reaches the class. */
	private final int exitY;

	OriginClass(int highestLevel)
	{
		this(highestLevel, NO_EXIT);
	}

	OriginClass(int highestLevel, int exitY)
	{
		this.highestLevel = highestLevel;
		this.exitY = exitY;
	}

	int exitY()
	{
		return exitY;
	}

	public static OriginClass of(WorldPoint tile, NoTeleportAreas noTeleport)
	{
		if (PositionRules.isInPoh(tile))
		{
			return POH;
		}
		if (noTeleport.contains(tile))
		{
			return NO_TELEPORT;
		}
		int level = PositionRules.wildernessLevel(tile);
		return level <= 0 ? WILD0 : level <= 20 ? WILD20 : level <= 30 ? WILD30 : WILD_DEEP;
	}

	public boolean allowsTeleports()
	{
		return this != WILD_DEEP && this != NO_TELEPORT;
	}

	public boolean allows(int maxWildernessLevel)
	{
		return allowsTeleports() && highestLevel <= maxWildernessLevel;
	}

	public boolean isWilderness()
	{
		return this == WILD20 || this == WILD30 || this == WILD_DEEP;
	}
}
