package com.cluehelper.ui;

import com.cluehelper.route.Exit;
import com.cluehelper.route.SceneBfs;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.WalkTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** The objects a path line leads through: each walk row it takes (a ladder, stairs, a shortcut) and each closed door. */
final class PathObjects
{
	/** A large staircase stands a few tiles from the row's origin tile. */
	private static final int ROW_OBJECT_TILES = 3;
	/** Row objects that open only after another is used each time, by id: Meiyerditch's barricade after its rocky surface. */
	private static final Map<Integer, Prerequisite> FIRST = Map.of(18054, new Prerequisite(18056, "Search"));

	static boolean isUsedFirst(int objectId)
	{
		return FIRST.values().stream().anyMatch(first -> first.getObjectId() == objectId);
	}

	@Value
	private static class Prerequisite
	{
		int objectId;
		String option;
	}
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
				addRowObject(objects, client, view, table == null ? null : table.rowObject(from, to), from);
			}
			else
			{
				addClosedDoor(objects, client, view, doors, from, to);
				addRowObject(objects, client, view, table == null ? null : table.rowObject(from, to), from);
				addWalkedOverRow(objects, client, view, table, path, i);
			}
		}
		if (!path.isEmpty() && table != null)
		{
			WorldPoint last = path.get(path.size() - 1);
			for (SceneBfs.ExitReach exit : scene.exitsFrom(last))
			{
				addRowObject(objects, client, view, table.rowObject(exit.getOrigin(), exit.getDestination()), last);
			}
		}
		return objects;
	}

	/**
	 * A row across tiles the collision map leaves open (the Isafdar sticks, a trap stepped on otherwise): the line walks
	 * it tile by tile.
	 */
	private static void addWalkedOverRow(Map<TileObject, String> objects, Client client, WorldView view, WalkTable table,
		List<WorldPoint> path, int origin)
	{
		for (int j = origin + 2; table != null && j < Math.min(path.size(), origin + 1 + ROW_OBJECT_TILES + 2); j++)
		{
			addRowObject(objects, client, view, table.rowObject(path.get(origin), path.get(j)), path.get(origin));
		}
	}

	private static boolean isJump(WorldPoint from, WorldPoint to)
	{
		return from.getPlane() != to.getPlane() || Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getY() - to.getY())) > 1;
	}

	private static void addRowObject(Map<TileObject, String> objects, Client client, WorldView view, Exit row, WorldPoint origin)
	{
		if (row == null || row.getObjectId() < 0)
		{
			return;
		}
		Prerequisite first = FIRST.get(row.getObjectId());
		TileObject before = first == null ? null : objectNear(client, view, first.getObjectId(), origin);
		if (before != null)
		{
			objects.putIfAbsent(before, first.getOption());
		}
		TileObject object = objectNear(client, view, row.getObjectId(), origin);
		if (object != null)
		{
			objects.putIfAbsent(object, row.getOption());
		}
	}

	private static TileObject objectNear(Client client, WorldView view, int id, WorldPoint origin)
	{
		for (int dx = -ROW_OBJECT_TILES; dx <= ROW_OBJECT_TILES; dx++)
		{
			for (int dy = -ROW_OBJECT_TILES; dy <= ROW_OBJECT_TILES; dy++)
			{
				TileObject object = objectOn(client, tile(view, origin.dx(dx).dy(dy)), id);
				if (object != null)
				{
					return object;
				}
			}
		}
		return null;
	}

	private static TileObject objectOn(Client client, Tile tile, int id)
	{
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && isObject(client, object, id))
			{
				return object;
			}
		}
		for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getGroundObject(), tile.getDecorativeObject()})
		{
			if (object != null && isObject(client, object, id))
			{
				return object;
			}
		}
		return null;
	}

	/** Shortest Path names a multiloc by the form it shows (the Kharazi broken cart: 28931, placed as 2216). */
	private static boolean isObject(Client client, TileObject object, int id)
	{
		if (object.getId() == id)
		{
			return true;
		}
		ObjectComposition placed = client.getObjectDefinition(object.getId());
		ObjectComposition shown = placed.getImpostorIds() != null ? placed.getImpostor() : null;
		return shown != null && shown.getId() == id;
	}

	private static void addClosedDoor(Map<TileObject, String> objects, Client client, WorldView view, SceneDoors doors, WorldPoint from,
		WorldPoint to)
	{
		int dx = Integer.signum(to.getX() - from.getX());
		int dy = Integer.signum(to.getY() - from.getY());
		int fromSides = (dx > 0 ? EAST : dx < 0 ? WEST : 0) | (dy > 0 ? NORTH : dy < 0 ? SOUTH : 0);
		int toSides = (dx > 0 ? WEST : dx < 0 ? EAST : 0) | (dy > 0 ? SOUTH : dy < 0 ? NORTH : 0);
		for (TileObject door : new TileObject[]{closedDoorFacing(view, doors, from, fromSides),
			closedDoorFacing(view, doors, to, toSides), doors.diagonalDoor(tile(view, to))})
		{
			if (door != null)
			{
				objects.putIfAbsent(door, doors.openOption(door.getId()));
			}
		}
	}

	private static WallObject closedDoorFacing(WorldView view, SceneDoors doors, WorldPoint at, int sides)
	{
		Tile tile = tile(view, at);
		WallObject wall = tile == null ? null : tile.getWallObject();
		return wall != null && (wall.getOrientationA() & sides) != 0 && doors.opensOnClick(wall) ? wall : null;
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
