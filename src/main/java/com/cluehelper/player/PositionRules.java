package com.cluehelper.player;

import net.runelite.api.coords.WorldPoint;

public final class PositionRules
{
	/** Must match the POH node of Shortest Path's walk table. */
	public static final WorldPoint POH_NODE = new WorldPoint(1858, 7051, 0);

	private static final int POH_MIN_X = 1856;
	/** Template region 8302 (x 2048–2111) holds houses too, beyond Shortest Path's box. */
	private static final int POH_MAX_X = 2111;
	private static final int POH_MIN_Y = 7040;
	private static final int POH_MAX_Y = 7111;

	private static final int WILDERNESS_MIN_X = 2944;
	private static final int WILDERNESS_MAX_X_SURFACE = 3391;
	private static final int WILDERNESS_MAX_X_UNDERGROUND = 3461;
	private static final int SURFACE_MIN_Y = 3525;
	private static final int SURFACE_MAX_Y = 3972;
	private static final int SURFACE_LEVEL_BASE_Y = 3520;
	private static final int UNDERGROUND_MIN_Y = 9918;
	private static final int UNDERGROUND_MAX_Y = 10375;
	private static final int UNDERGROUND_LEVEL_BASE_Y = 9912;
	private static final int TILES_PER_LEVEL = 8;
	/** Inside the surface area but not Wilderness: Ferox Enclave and the south edge (Shortest Path's boxes: x, y, width, height). */
	private static final int[][] NOT_WILDERNESS = {{3123, 3622, 2, 10}, {3125, 3617, 16, 23}, {3138, 3636, 18, 10},
		{3141, 3625, 14, 11}, {3141, 3619, 7, 6}, {2997, 3525, 34, 9}, {3005, 3534, 21, 10}, {3000, 3534, 5, 5},
		{3031, 3525, 2, 2}};

	private PositionRules()
	{
	}

	/** Farther than running moves in a tick: a teleport, a ladder, a boat. */
	public static final int JUMP_TILES = 4;

	/** Whether getting from {@code from} to {@code to} within a tick took a teleport, a ladder or a boat; false while either is unknown. */
	public static boolean teleported(WorldPoint from, WorldPoint to)
	{
		return from != null && to != null && (from.getPlane() != to.getPlane() || from.distanceTo2D(to) > JUMP_TILES);
	}

	/**
	 * @param boatTile the boat entity's tile through {@code fromLocalInstance}; never the player's raw
	 *     location, which on a boat is the deck's own frozen coordinates
	 */
	public static Position onBoat(WorldPoint boatTile)
	{
		return boatTile == null ? Position.NONE : new Position(boatTile, Position.Kind.BOAT, boatTile);
	}

	/**
	 * @param mappedTile the player's local location through {@code fromLocalInstance}, which also maps
	 *     instances to their template
	 */
	public static Position onFoot(WorldPoint mappedTile, boolean topLevelInstance)
	{
		if (mappedTile == null)
		{
			return Position.NONE;
		}
		if (isInPoh(mappedTile))
		{
			return new Position(POH_NODE, Position.Kind.POH, mappedTile);
		}
		return new Position(mappedTile, topLevelInstance ? Position.Kind.INSTANCE : Position.Kind.WORLD, mappedTile);
	}

	/** x/y only: the POH template comes back on plane 2 from every floor. */
	public static boolean isInPoh(WorldPoint tile)
	{
		return tile.getX() >= POH_MIN_X && tile.getX() <= POH_MAX_X && tile.getY() >= POH_MIN_Y && tile.getY() <= POH_MAX_Y;
	}

	/** Must match the areas of Shortest Path's {@code WildernessChecker}. */
	public static int wildernessLevel(WorldPoint tile)
	{
		if (tile == null || tile.getX() < WILDERNESS_MIN_X)
		{
			return 0;
		}
		int x = tile.getX();
		int y = tile.getY();
		if (isSafeInsideSurfaceArea(x, y))
		{
			return 0;
		}
		if (y >= SURFACE_MIN_Y && y <= SURFACE_MAX_Y && x <= WILDERNESS_MAX_X_SURFACE)
		{
			return (y - SURFACE_LEVEL_BASE_Y) / TILES_PER_LEVEL + 1;
		}
		if (y >= UNDERGROUND_MIN_Y && y <= UNDERGROUND_MAX_Y && x <= WILDERNESS_MAX_X_UNDERGROUND)
		{
			return (y - UNDERGROUND_LEVEL_BASE_Y) / TILES_PER_LEVEL + 1;
		}
		return 0;
	}

	private static boolean isSafeInsideSurfaceArea(int x, int y)
	{
		for (int[] box : NOT_WILDERNESS)
		{
			if (x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3])
			{
				return true;
			}
		}
		return false;
	}
}
