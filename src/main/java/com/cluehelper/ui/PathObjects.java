package com.cluehelper.ui;

import com.cluehelper.route.Exit;
import com.cluehelper.route.SceneDoors;
import com.cluehelper.route.WalkTable;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
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
	private static final String OPEN = "Open";

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
				addRowObject(objects, view, table == null ? List.of() : table.rowObjects(from, to), from);
			}
			else
			{
				addClosedDoor(objects, view, from, to);
				addRowObject(objects, view, table == null ? List.of() : table.rowObjects(from, to), from);
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
			addRowObject(objects, view, table.rowObjects(path.get(origin), path.get(j)), path.get(origin));
		}
	}

	/** Two tiles of a walk that aren't neighbours on one floor: a row (stairs, a shortcut) joins them. */
	static boolean isJump(WorldPoint from, WorldPoint to)
	{
		return from.getPlane() != to.getPlane() || Math.max(Math.abs(from.getX() - to.getX()), Math.abs(from.getY() - to.getY())) > 1;
	}

	/** Of the rows joining the same tiles, the object nearest the origin is the one clicked (the rope below the rocks). */
	private void addRowObject(Map<TileObject, String> objects, WorldView view, List<Exit> rows, WorldPoint origin)
	{
		Exit clicked = null;
		TileObject object = null;
		for (Exit row : rows)
		{
			TileObject near = row.getObjectId() < 0 ? null : objectNear(view, row.getObjectId(), origin);
			if (near != null && (object == null || squaredDistance(near, origin) < squaredDistance(object, origin)))
			{
				clicked = row;
				object = near;
			}
		}
		if (object == null)
		{
			rows.stream().filter(row -> row.getObjectId() >= 0).findFirst().ifPresent(row -> addClosed(objects, view, row, origin));
			return;
		}
		Prerequisite first = FIRST.get(clicked.getObjectId());
		TileObject before = first == null ? null : objectNear(view, first.getObjectId(), origin);
		if (before != null)
		{
			objects.putIfAbsent(before, first.getOption());
		}
		objects.putIfAbsent(object, clicked.getOption());
	}

	/** A closed trapdoor is another object of the same name, opened first: the row names only its open form. */
	private void addClosed(Map<TileObject, String> objects, WorldView view, Exit row, WorldPoint origin)
	{
		TileObject closed = null;
		for (int dx = -ROW_OBJECT_TILES; dx <= ROW_OBJECT_TILES; dx++)
		{
			for (int dy = -ROW_OBJECT_TILES; dy <= ROW_OBJECT_TILES; dy++)
			{
				Tile tile = tile(view, origin.dx(dx).dy(dy));
				for (TileObject object : tile == null ? List.<TileObject>of() : objectsOn(tile))
				{
					boolean opens = row.getTarget().equals(nameOf(object)) && hasOption(object, OPEN);
					if (opens && (closed == null || squaredDistance(object, origin) < squaredDistance(closed, origin)))
					{
						closed = object;
					}
				}
			}
		}
		if (closed != null)
		{
			objects.putIfAbsent(closed, OPEN);
		}
	}

	private boolean hasOption(TileObject object, String option)
	{
		ObjectComposition placed = client.getObjectDefinition(object.getId());
		ObjectComposition shown = placed.getImpostorIds() != null ? placed.getImpostor() : placed;
		return shown != null && shown.getActions() != null && Arrays.asList(shown.getActions()).contains(option);
	}

	private static int squaredDistance(TileObject object, WorldPoint origin)
	{
		WorldPoint at = object.getWorldLocation();
		int dx = at.getX() - origin.getX();
		int dy = at.getY() - origin.getY();
		return dx * dx + dy * dy;
	}

	/**
	 * Client thread only. The objects named as {@code crossed} is on the tiles {@code around} a jump: every stepping stone
	 * of a crossing, both ends of a pipe, where the walk data names only one.
	 */
	List<TileObject> sameNamed(WorldView view, TileObject crossed, Rectangle around)
	{
		String name = nameOf(crossed);
		List<TileObject> found = new ArrayList<>();
		for (int x = around.x; x < around.x + around.width; x++)
		{
			for (int y = around.y; y < around.y + around.height; y++)
			{
				Tile tile = tile(view, new WorldPoint(x, y, crossed.getPlane()));
				if (tile != null)
				{
					objectsOn(tile).stream().filter(object -> name != null && name.equals(nameOf(object))).forEach(found::add);
				}
			}
		}
		return found;
	}

	/**
	 * Client thread only. The objects of every row from {@code from} to {@code to} on the tiles {@code around}: a climb's
	 * rope at its foot and its rocks at the top, which the walk data names as two rows.
	 */
	List<TileObject> rowObjectsAround(WorldView view, WalkTable table, WorldPoint from, WorldPoint to, Rectangle around)
	{
		List<Exit> rows = table == null ? List.of() : table.rowObjects(from, to);
		List<TileObject> found = new ArrayList<>();
		for (int x = around.x; !rows.isEmpty() && x < around.x + around.width; x++)
		{
			for (int y = around.y; y < around.y + around.height; y++)
			{
				Tile tile = tile(view, new WorldPoint(x, y, from.getPlane()));
				if (tile != null)
				{
					objectsOn(tile).stream()
						.filter(object -> rows.stream().anyMatch(row -> row.getObjectId() >= 0 && isObject(object, row.getObjectId())))
						.forEach(found::add);
				}
			}
		}
		return found;
	}

	private List<TileObject> objectsOn(Tile tile)
	{
		List<TileObject> objects = new ArrayList<>();
		for (GameObject object : tile.getGameObjects())
		{
			if (object != null)
			{
				objects.add(object);
			}
		}
		for (TileObject object : new TileObject[]{tile.getWallObject(), tile.getGroundObject(), tile.getDecorativeObject()})
		{
			if (object != null)
			{
				objects.add(object);
			}
		}
		return objects;
	}

	private String nameOf(TileObject object)
	{
		ObjectComposition placed = client.getObjectDefinition(object.getId());
		ObjectComposition shown = placed.getImpostorIds() != null ? placed.getImpostor() : placed;
		return shown == null ? null : shown.getName();
	}

	/** The one nearest the row's origin: a pipe's two ends share an id, and the one clicked is the near end. */
	private TileObject objectNear(WorldView view, int id, WorldPoint origin)
	{
		TileObject nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (int dx = -ROW_OBJECT_TILES; dx <= ROW_OBJECT_TILES; dx++)
		{
			for (int dy = -ROW_OBJECT_TILES; dy <= ROW_OBJECT_TILES; dy++)
			{
				TileObject object = objectOn(tile(view, origin.dx(dx).dy(dy)), id);
				int distance = dx * dx + dy * dy;
				if (object != null && distance < nearestDistance)
				{
					nearest = object;
					nearestDistance = distance;
				}
			}
		}
		return nearest;
	}

	private TileObject objectOn(Tile tile, int id)
	{
		return tile == null ? null : objectsOn(tile).stream().filter(object -> isObject(object, id)).findFirst().orElse(null);
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
