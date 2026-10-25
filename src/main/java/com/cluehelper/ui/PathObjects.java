package com.cluehelper.ui;

import com.cluehelper.route.Exit;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.WalkTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
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
@Singleton
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

	private final Client client;
	private final SceneDoors doors;

	@Inject
	PathObjects(Client client, SceneDoors doors)
	{
		this.client = client;
		this.doors = doors;
	}

	/** Client thread only. Each object with the menu option that takes the path through it. */
	Map<TileObject, String> along(WorldView view, WalkTable table, List<WorldPoint> path)
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
				addClosedDoor(objects, view, from, to);
				addRowObject(objects, view, table == null ? null : table.rowObject(from, to), from);
				addWalkedOverRow(objects, view, table, path, i);
			}
		}
		return objects;
	}

	/**
	 * A row across tiles the collision map leaves open (the Isafdar sticks, a trap stepped on otherwise): the line walks
	 * it tile by tile.
	 */
	private void addWalkedOverRow(Map<TileObject, String> objects, WorldView view, WalkTable table,
		List<WorldPoint> path, int origin)
	{
		for (int j = origin + 2; table != null && j < Math.min(path.size(), origin + 1 + ROW_OBJECT_TILES + 2); j++)
		{
			addRowObject(objects, view, table.rowObject(path.get(origin), path.get(j)), path.get(origin));
		}
	}

	private static boolean isJump(WorldPoint from, WorldPoint to)
	{
		return from.getPlane() != to.getPlane() || Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getY() - to.getY())) > 1;
	}

	private void addRowObject(Map<TileObject, String> objects, WorldView view, Exit row, WorldPoint origin)
	{
		if (row == null || row.getObjectId() < 0)
		{
			return;
		}
		Prerequisite first = FIRST.get(row.getObjectId());
		TileObject before = first == null ? null : objectNear(view, first.getObjectId(), origin);
		if (before != null)
		{
			objects.putIfAbsent(before, first.getOption());
		}
		TileObject object = objectNear(view, row.getObjectId(), origin);
		if (object != null)
		{
			objects.putIfAbsent(object, row.getOption());
		}
	}

	private TileObject objectNear(WorldView view, int id, WorldPoint origin)
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

	private TileObject objectOn(Tile tile, int id)
	{
		if (tile == null)
		{
			return null;
		}
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null && isObject(object, id))
			{
				return object;
			}
		}
		for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getGroundObject(), tile.getDecorativeObject()})
		{
			if (object != null && isObject(object, id))
			{
				return object;
			}
		}
		return null;
	}

	/** Shortest Path names a multiloc by the form it shows (the Kharazi broken cart: 28931, placed as 2216). */
	private boolean isObject(TileObject object, int id)
	{
		if (object.getId() == id)
		{
			return true;
		}
		ObjectComposition placed = client.getObjectDefinition(object.getId());
		ObjectComposition shown = placed.getImpostorIds() != null ? placed.getImpostor() : null;
		return shown != null && shown.getId() == id;
	}

	private void addClosedDoor(Map<TileObject, String> objects, WorldView view, WorldPoint from, WorldPoint to)
	{
		int dx = Integer.signum(to.getX() - from.getX());
		int dy = Integer.signum(to.getY() - from.getY());
		int fromSides = (dx > 0 ? EAST : dx < 0 ? WEST : 0) | (dy > 0 ? NORTH : dy < 0 ? SOUTH : 0);
		int toSides = (dx > 0 ? WEST : dx < 0 ? EAST : 0) | (dy > 0 ? SOUTH : dy < 0 ? NORTH : 0);
		for (TileObject door : new TileObject[]{closedDoorFacing(view, from, fromSides),
			closedDoorFacing(view, to, toSides), doors.diagonalDoor(tile(view, to))})
		{
			if (door != null)
			{
				objects.putIfAbsent(door, doors.openOption(door.getId()));
			}
		}
	}

	private WallObject closedDoorFacing(WorldView view, WorldPoint at, int sides)
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
