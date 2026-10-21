package com.cluehelper.ui;

import com.cluehelper.route.Exit;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.WalkTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** The objects a path line leads through: each walk row it takes (a ladder, stairs, a shortcut) and each closed door. */
final class PathObjects
{
	private static final String OPEN = "Open";
	/** A large staircase stands a few tiles from the row's origin tile. */
	private static final int ROW_OBJECT_TILES = 3;
	private static final int WEST = 1;
	private static final int NORTH = 2;
	private static final int EAST = 4;
	private static final int SOUTH = 8;

	private PathObjects()
	{
	}

	/** Client thread only. Each object with the menu option that takes the path through it. */
	static Map<TileObject, String> along(Client client, WorldView view, WalkTable table, SceneBfs.Result scene, SceneDoors doors,
		List<WorldPoint> path)
	{
		Map<TileObject, String> objects = new LinkedHashMap<>();
		for (int i = 0; i + 1 < path.size(); i++)
		{
			WorldPoint from = path.get(i);
			WorldPoint to = path.get(i + 1);
			if (isJump(from, to))
			{
				addRowObject(objects, view, table == null ? null : table.rowObject(from, to), from);
			}
			else
			{
				addClosedDoor(objects, client, view, doors, from, to);
			}
		}
		if (!path.isEmpty() && table != null)
		{
			WorldPoint last = path.get(path.size() - 1);
			for (SceneBfs.ExitReach exit : scene.exitsFrom(last))
			{
				addRowObject(objects, view, table.rowObject(exit.getOrigin(), exit.getDestination()), last);
			}
		}
		return objects;
	}

	private static boolean isJump(WorldPoint from, WorldPoint to)
	{
		return from.getPlane() != to.getPlane() || Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getY() - to.getY())) > 1;
	}

	private static void addRowObject(Map<TileObject, String> objects, WorldView view, Exit row, WorldPoint origin)
	{
		if (row == null || row.getObjectId() < 0)
		{
			return;
		}
		TileObject object = objectNear(view, row.getObjectId(), origin);
		if (object != null)
		{
			objects.putIfAbsent(object, row.getOption());
		}
	}

	private static TileObject objectNear(WorldView view, int id, WorldPoint origin)
	{
		for (int dx = -ROW_OBJECT_TILES; dx <= ROW_OBJECT_TILES; dx++)
		{
			for (int dy = -ROW_OBJECT_TILES; dy <= ROW_OBJECT_TILES; dy++)
			{
				TileObject object = objectOn(tile(view, origin.dx(dx).dy(dy)), id);
				if (object != null)
				{
					return object;
				}
			}
		}
		return null;
	}

	private static TileObject objectOn(Tile tile, int id)
	{
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && object.getId() == id)
			{
				return object;
			}
		}
		for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getGroundObject(), tile.getDecorativeObject()})
		{
			if (object != null && object.getId() == id)
			{
				return object;
			}
		}
		return null;
	}

	private static void addClosedDoor(Map<TileObject, String> objects, Client client, WorldView view, SceneDoors doors, WorldPoint from,
		WorldPoint to)
	{
		int dx = Integer.signum(to.getX() - from.getX());
		int dy = Integer.signum(to.getY() - from.getY());
		int fromSides = (dx > 0 ? EAST : dx < 0 ? WEST : 0) | (dy > 0 ? NORTH : dy < 0 ? SOUTH : 0);
		int toSides = (dx > 0 ? WEST : dx < 0 ? EAST : 0) | (dy > 0 ? SOUTH : dy < 0 ? NORTH : 0);
		for (TileObject door : new TileObject[]{closedDoorFacing(client, view, doors, from, fromSides),
			closedDoorFacing(client, view, doors, to, toSides), doors.diagonalDoor(client, tile(view, to))})
		{
			if (door != null)
			{
				objects.putIfAbsent(door, OPEN);
			}
		}
	}

	private static WallObject closedDoorFacing(Client client, WorldView view, SceneDoors doors, WorldPoint at, int sides)
	{
		Tile tile = tile(view, at);
		WallObject wall = tile == null ? null : tile.getWallObject();
		return wall != null && (wall.getOrientationA() & sides) != 0 && doors.isClosed(client, wall) ? wall : null;
	}

	private static Tile tile(WorldView view, WorldPoint at)
	{
		int x = at.getX() - view.getBaseX();
		int y = at.getY() - view.getBaseY();
		Tile[][][] tiles = view.getScene().getTiles();
		if (at.getPlane() < 0 || at.getPlane() >= tiles.length || x < 0 || y < 0 || x >= tiles[at.getPlane()].length
			|| y >= tiles[at.getPlane()][x].length)
		{
			return null;
		}
		return tiles[at.getPlane()][x][y];
	}
}
