package com.cluehelper.route;

import com.cluehelper.player.PositionRules;
import net.runelite.api.coords.WorldPoint;

/** Which teleports work from a tile (PLAN §5.4 Origin class). */
public enum OriginClass
{
	WILD0(0),
	/** Wilderness levels 1–20. */
	WILD20(20),
	/** Levels 21–30: only rows that work to level 30. */
	WILD30(30),
	/** Above 30: no teleport, and no spellbook change, since no teleport could follow. */
	WILD_DEEP(Integer.MAX_VALUE),
	/** Inside a house: no Grouping (minigame) teleports. */
	POH(0),
	/** A bundled area where teleports don't work. */
	NO_TELEPORT(Integer.MAX_VALUE);

	/** The surface y of each Wilderness exit: walking due south to it reaches the class (B02, PLAN §5.4). */
	static final int EXIT_WILD30_Y = 3759;
	static final int EXIT_WILD20_Y = 3679;
	/** Not in PLAN's list: minigame rows work only outside the Wilderness (level 0), so they need one too. */
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

	/** Whether a row that works up to {@code maxWildernessLevel} works anywhere in this class. */
	public boolean allows(int maxWildernessLevel)
	{
		return allowsTeleports() && highestLevel <= maxWildernessLevel;
	}

	public boolean isWilderness()
	{
		return this == WILD20 || this == WILD30 || this == WILD_DEEP;
	}
}
