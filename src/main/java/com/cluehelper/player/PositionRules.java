package com.cluehelper.player;

import com.cluehelper.Tables;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;

public final class PositionRules
{
	/** Must match the POH node of Shortest Path's walk table. */
	public static final WorldPoint POH_NODE = new WorldPoint(1858, 7051, 0);

	private static final int POH_MIN_X = 1856;
	/** Template region 8302 (x 2048–2111) holds houses too, beyond Shortest Path's box. */
	private static final int POH_MAX_X = 2111;
	private static final int POH_MIN_Y = 7040;
	private static final int POH_MAX_Y = 7111;

	private static final int TILES_PER_LEVEL = 8;
	/** The surface and the underground Wilderness: x, y, width, height, then the y its levels count from. */
	private static final List<int[]> WILDERNESS = boxes("wilderness");
	/** Inside those but safe (Ferox Enclave, the south edge, the Slayer Tower basement): x, y, width, height. */
	private static final List<int[]> NOT_WILDERNESS = boxes("not wilderness");

	private PositionRules()
	{
	}

	/** Farther than running moves in a tick: a teleport, a ladder, a boat. */
	private static final int JUMP_TILES = 4;

	/** Whether getting from {@code from} to {@code to} within a tick took a teleport, a ladder or a boat; false while either is unknown. */
	public static boolean teleported(WorldPoint from, WorldPoint to)
	{
		return from != null && to != null && (from.getPlane() != to.getPlane() || from.distanceTo2D(to) > JUMP_TILES);
	}

	/** On the same floor and within {@code radius}; false while either is unknown. */
	public static boolean near(WorldPoint tile, WorldPoint goal, int radius)
	{
		return tile != null && goal != null && tile.getPlane() == goal.getPlane() && tile.distanceTo2D(goal) <= radius;
	}

	/**
	 * @param boatTile the boat entity's tile through {@code fromLocalInstance}; never the player's raw
	 *     location, which on a boat is the deck's own frozen coordinates
	 */
	public static Position onBoat(WorldPoint boatTile)
	{
		return boatTile == null ? Position.NONE : new Position(boatTile, Position.Kind.BOAT);
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
			return new Position(POH_NODE, Position.Kind.POH);
		}
		return new Position(mappedTile, topLevelInstance ? Position.Kind.INSTANCE : Position.Kind.WORLD);
	}

	/** x/y only: the POH template comes back on plane 2 from every floor. */
	public static boolean isInPoh(WorldPoint tile)
	{
		return tile.getX() >= POH_MIN_X && tile.getX() <= POH_MAX_X && tile.getY() >= POH_MIN_Y && tile.getY() <= POH_MAX_Y;
	}

	/** Must match the areas of Shortest Path's {@code WildernessChecker}. */
	public static int wildernessLevel(WorldPoint tile)
	{
		if (tile == null || NOT_WILDERNESS.stream().anyMatch(box -> isIn(box, tile)))
		{
			return 0;
		}
		return WILDERNESS.stream().filter(area -> isIn(area, tile)).mapToInt(area -> (tile.getY() - area[4]) / TILES_PER_LEVEL + 1)
			.findFirst().orElse(0);
	}

	/** {@code box} is x, y, width, height. */
	private static boolean isIn(int[] box, WorldPoint tile)
	{
		return tile.getX() >= box[0] && tile.getX() < box[0] + box[2] && tile.getY() >= box[1] && tile.getY() < box[1] + box[3];
	}

	private static List<int[]> boxes(String table)
	{
		return Tables.rows(table).stream().map(Tables.Row::ids).collect(toUnmodifiableList());
	}
}
