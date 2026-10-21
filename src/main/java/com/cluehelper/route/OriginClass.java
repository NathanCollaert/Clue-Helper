package com.cluehelper.route;

import com.cluehelper.player.PositionRules;
import net.runelite.api.coords.WorldPoint;

public enum OriginClass
{
	WILD0(0),
	WILD20(20),
	WILD30(30),
	/** No teleport, and no spellbook change either, since no teleport could follow. */
	WILD_DEEP(Integer.MAX_VALUE),
	/** Grouping (minigame) teleports don't work from inside a house. */
	POH(0),
	NO_TELEPORT(Integer.MAX_VALUE);

	/** Walking due south to this surface y reaches the class. */
	static final int EXIT_WILD30_Y = 3759;
	static final int EXIT_WILD20_Y = 3679;
	/** Minigame rows work only outside the Wilderness, so level 0 needs an exit too. */
	static final int EXIT_WILD0_Y = 3524;

	private final int highestLevel;

	OriginClass(int highestLevel)
	{
		this.highestLevel = highestLevel;
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
