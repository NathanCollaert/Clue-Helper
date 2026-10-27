package com.cluehelper.route;

import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;

/** The loaded scene's collision flags as captured, and the closed doors on it a walk may open at a cost. */
@Value
@AllArgsConstructor
public class LoadedScene
{
	private static final int FULL = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	private static final int N = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
	private static final int E = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
	private static final int S = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
	private static final int W = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
	private static final int NE = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
	private static final int NW = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
	private static final int SE = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
	private static final int SW = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
	private static final int BORDER_LOW = 1;
	private static final int BORDER_HIGH = 5;
	/** Opening a door on the way: the click and the stop it costs, in tiles of running. */
	private static final int DOOR_TILES = 3;
	private static final int[] FROM_SIDES = {W, E, S, N, W | S, E | S, W | N, E | N};
	private static final int[] TO_SIDES = {E, W, N, S, E | N, W | N, E | S, W | S};

	int baseX;
	int baseY;
	int sizeX;
	int sizeY;
	/** [plane][x][y]; a null plane has no data. */
	int[][][] flags;
	/** [plane][x][y]: the sides (wall flags) of each tile that a closed door opens through; null when none are known. */
	int[][][] doorSides;

	public LoadedScene(int baseX, int baseY, int sizeX, int sizeY, int[][][] flags)
	{
		this(baseX, baseY, sizeX, sizeY, flags, null);
	}

	/** Client thread only. Null for an instanced scene or one without collision data. */
	public static LoadedScene capture(WorldView view)
	{
		return view == null || view.isInstance() ? null : captureLoaded(view);
	}

	/** Client thread only. In an instance its tiles are the instance's own and match nothing in the walk table. */
	public static LoadedScene captureLoaded(WorldView view)
	{
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		if (maps == null)
		{
			return null;
		}
		int[][][] flags = new int[maps.length][][];
		for (int plane = 0; plane < maps.length; plane++)
		{
			flags[plane] = maps[plane] == null ? null : maps[plane].getFlags();
		}
		return new LoadedScene(view.getBaseX(), view.getBaseY(), view.getSizeX(), view.getSizeY(), copy(flags));
	}

	/** A deep copy of [plane][x][y] flags; a null plane stays null. */
	static int[][][] copy(int[][][] flags)
	{
		int[][][] copy = new int[flags.length][][];
		for (int plane = 0; plane < flags.length; plane++)
		{
			if (flags[plane] != null)
			{
				copy[plane] = new int[flags[plane].length][];
				for (int x = 0; x < flags[plane].length; x++)
				{
					copy[plane][x] = flags[plane][x].clone();
				}
			}
		}
		return copy;
	}

	/** The client blocks the scene's first row and last five. */
	public boolean contains(WorldPoint tile)
	{
		return contains(tile.getX(), tile.getY(), tile.getPlane());
	}

	boolean contains(int worldX, int worldY, int plane)
	{
		int x = worldX - baseX;
		int y = worldY - baseY;
		return x >= BORDER_LOW && y >= BORDER_LOW && x < sizeX - BORDER_HIGH && y < sizeY - BORDER_HIGH
			&& plane >= 0 && plane < flags.length && flags[plane] != null;
	}

	/** The game lets a player step from {@code from} to {@code to}, its neighbour; both in the scene. */
	public boolean canStep(WorldPoint from, WorldPoint to)
	{
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		for (int d = 0; d < Directions.DX.length; d++)
		{
			if (Directions.DX[d] == dx && Directions.DY[d] == dy)
			{
				return canMove(from.getX() - baseX, from.getY() - baseY, from.getPlane(), d);
			}
		}
		throw new IllegalArgumentException(to + " is no neighbour of " + from);
	}

	/** Scene coordinates, from here on. The game's movement rule; {@code d} indexes {@link Directions}. */
	boolean canMove(int x, int y, int plane, int d)
	{
		int[][] f = flags[plane];
		switch (d)
		{
			case 0:
				return free(f, x - 1, y, E);
			case 1:
				return free(f, x + 1, y, W);
			case 2:
				return free(f, x, y - 1, N);
			case 3:
				return free(f, x, y + 1, S);
			case 4:
				return free(f, x - 1, y - 1, N | E | NE) && free(f, x - 1, y, E) && free(f, x, y - 1, N);
			case 5:
				return free(f, x + 1, y - 1, N | W | NW) && free(f, x + 1, y, W) && free(f, x, y - 1, N);
			case 6:
				return free(f, x - 1, y + 1, S | E | SE) && free(f, x - 1, y, E) && free(f, x, y + 1, S);
			default:
				return free(f, x + 1, y + 1, S | W | SW) && free(f, x + 1, y, W) && free(f, x, y + 1, S);
		}
	}

	/** Tiles a step from ({@code x}, {@code y}) in direction {@code d} costs: one, plus a door's opening. */
	int stepCost(int plane, int x, int y, int d)
	{
		int[][] sides = doorSides == null ? null : doorSides[plane];
		if (sides == null)
		{
			return 1;
		}
		boolean throughDoor = (sides[x][y] & FROM_SIDES[d]) != 0 || (sides[x + Directions.DX[d]][y + Directions.DY[d]] & TO_SIDES[d]) != 0;
		return throughDoor ? 1 + DOOR_TILES : 1;
	}

	boolean isBlocked(int x, int y, int plane)
	{
		return (flags[plane][x][y] & FULL) != 0;
	}

	/** A wall stands between the tile and its straight neighbour {@code dx}, {@code dy}. */
	boolean isWallBetween(int x, int y, int plane, int dx, int dy)
	{
		int fromWall = dx > 0 ? E : dx < 0 ? W : dy > 0 ? N : S;
		int toWall = dx > 0 ? W : dx < 0 ? E : dy > 0 ? S : N;
		return (flags[plane][x][y] & fromWall) != 0 || (flags[plane][x + dx][y + dy] & toWall) != 0;
	}

	private static boolean free(int[][] f, int x, int y, int sides)
	{
		return x >= 0 && y >= 0 && x < f.length && y < f[x].length && (f[x][y] & (FULL | sides)) == 0;
	}
}
