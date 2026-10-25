package com.cluehelper.route;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Where a walk can step: the loaded scene as the client has it, its closed doors opened at a cost, and Shortest Path's
 * world map past the scene's edge. The open walk rows (stairs, ladders, shortcuts) join tiles apart. In an instance,
 * whose tiles are on no map, only its scene. Any thread once built.
 */
public final class Ground
{
	private static final int[] NO_ROWS = new int[0];
	private static final int WORLD_TILE_LIMIT = 150_000;

	private final WorldMap map;
	private final LoadedScene scene;
	private final WalkTable table;
	private final IntPredicate rowOpen;

	private Ground(WorldMap map, LoadedScene scene, WalkTable table, IntPredicate rowOpen)
	{
		this.map = map;
		this.scene = scene;
		this.table = table;
		this.rowOpen = rowOpen;
	}

	/** {@code scene}: the loaded scene with its doors open, or null; {@code rowOpen}: by row of {@code table}. */
	public static Ground world(WorldMap map, LoadedScene scene, WalkTable table, IntPredicate rowOpen)
	{
		return new Ground(map, scene, table, rowOpen);
	}

	/** An instance's scene, or the loaded scene alone for a quick walk inside it. */
	public static Ground sceneOnly(LoadedScene scene)
	{
		return new Ground(null, scene, null, row -> false);
	}

	LoadedScene getScene()
	{
		return scene;
	}

	/** Past this many tiles reached, a search gives up on its goal. */
	int tileLimit()
	{
		return map != null ? WORLD_TILE_LIMIT : scene.getFlags().length * scene.getSizeX() * scene.getSizeY();
	}

	boolean canMove(int x, int y, int plane, int direction)
	{
		int nx = x + Directions.DX[direction];
		int ny = y + Directions.DY[direction];
		if (inScene(x, y, plane) && inScene(nx, ny, plane))
		{
			return scene.canMove(x - scene.getBaseX(), y - scene.getBaseY(), plane, direction);
		}
		return map != null && map.canMove(x, y, plane, direction);
	}

	/** One tile, or more through a closed door the scene shows. */
	int stepCost(int x, int y, int plane, int direction)
	{
		boolean inside = inScene(x, y, plane) && inScene(x + Directions.DX[direction], y + Directions.DY[direction], plane);
		return inside ? scene.stepCost(plane, x - scene.getBaseX(), y - scene.getBaseY(), direction) : 1;
	}

	/** An object stands on the tile: stairs, a ladder, a crate. */
	boolean isBlocked(int x, int y, int plane)
	{
		if (inScene(x, y, plane))
		{
			return scene.isBlocked(x - scene.getBaseX(), y - scene.getBaseY(), plane);
		}
		return map == null || map.isBlocked(x, y, plane);
	}

	/** A wall or door stands between straight neighbours; an object on the far tile is no wall. */
	boolean isWallBetween(int x, int y, int plane, int dx, int dy)
	{
		if (inScene(x, y, plane) && inScene(x + dx, y + dy, plane))
		{
			return scene.isWallBetween(x - scene.getBaseX(), y - scene.getBaseY(), plane, dx, dy);
		}
		return map == null || map.isWallBetween(x, y, plane, dx, dy);
	}

	/** The open rows that start on the packed tile. */
	int[] openRowsFrom(int tile)
	{
		if (table == null)
		{
			return NO_ROWS;
		}
		int[] rows = table.rowsFrom(tile);
		return rows.length == 0 ? NO_ROWS : Arrays.stream(rows).filter(rowOpen).toArray();
	}

	int rowDestination(int row)
	{
		return table.rowDestTile(row);
	}

	boolean isExitRow(int row)
	{
		return table.isExitRow(row);
	}

	Exit exit(int row)
	{
		return table.exit(table.rowOrigin(row), table.rowDest(row));
	}

	int rowCost(int row)
	{
		return table.rowCost(row);
	}

	private boolean inScene(int x, int y, int plane)
	{
		return scene != null && scene.contains(x, y, plane);
	}
}
