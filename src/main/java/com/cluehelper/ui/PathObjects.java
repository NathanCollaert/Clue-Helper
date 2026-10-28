package com.cluehelper.ui;

import com.cluehelper.guide.*;
import com.cluehelper.route.*;
import java.awt.Rectangle;
import java.util.*;
import java.util.function.*;
import javax.inject.*;
import lombok.Value;
import net.runelite.api.*;
import net.runelite.api.coords.*;

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
	private static final String OPEN = "Open";

	@Inject
	private Client client;
	@Inject
	private SceneDoors doors;

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
				addRowObject(objects, view, rows(table, from, to), from);
			}
			else
			{
				addClosedDoor(objects, view, from, to);
				addRowObject(objects, view, rows(table, from, to), from);
				addWalkedOverRow(objects, view, table, path, i);
			}
		}
		return objects;
	}

	private static List<Exit> rows(WalkTable table, WorldPoint from, WorldPoint to)
	{
		return table == null ? List.of() : table.rowObjects(from, to);
	}

	/**
	 * A row across tiles the collision map leaves open (the Isafdar sticks, a trap stepped on otherwise): the line walks
	 * it tile by tile.
	 */
	private void addWalkedOverRow(Map<TileObject, String> objects, WorldView view, WalkTable table,
		List<WorldPoint> path, int origin)
	{
		for (int j = origin + 2; j < Math.min(path.size(), origin + 1 + ROW_OBJECT_TILES + 2); j++)
		{
			addRowObject(objects, view, rows(table, path.get(origin), path.get(j)), path.get(origin));
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
			TileObject near = row.getObjectId() < 0 ? null : rowObject(view, row, origin);
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
		TileObject closed = nearestAround(view, origin, object -> row.getTarget().equals(nameOf(object)) && hasOption(object, OPEN),
			(at, object) -> squaredDistance(object, origin));
		if (closed != null)
		{
			objects.putIfAbsent(closed, OPEN);
		}
	}

	private boolean hasOption(TileObject object, String option)
	{
		return Widgets.hasAction(LeftClicks.shownForm(client.getObjectDefinition(object.getId())).getActions(), option);
	}

	private static int squaredDistance(TileObject object, WorldPoint origin)
	{
		return squaredDistance(object.getWorldLocation(), origin);
	}

	static int squaredDistance(WorldPoint a, WorldPoint b)
	{
		int dx = a.getX() - b.getX();
		int dy = a.getY() - b.getY();
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
					SceneObjects.objectsOn(tile).stream().filter(object -> name != null && name.equals(nameOf(object))).forEach(found::add);
				}
			}
		}
		return found;
	}

	/**
	 * Client thread only. The object of every row from {@code from} to {@code to} on the tiles {@code around}: a climb's
	 * rope at its foot and its rocks at the top, which the walk data names as two rows. Each row is one object, the one
	 * nearest both its ends on one floor, else nearest {@code from}: Marim's two staircases share their ids and stand
	 * within reach of each other's rows, as the Kharazi Jungle's bushes do.
	 */
	List<TileObject> rowObjectsAround(WorldView view, WalkTable table, WorldPoint from, WorldPoint to, Rectangle around)
	{
		List<Exit> rows = rows(table, from, to);
		Map<Integer, TileObject> nearestByRow = new LinkedHashMap<>();
		for (int x = around.x; !rows.isEmpty() && x < around.x + around.width; x++)
		{
			for (int y = around.y; y < around.y + around.height; y++)
			{
				Tile tile = tile(view, new WorldPoint(x, y, from.getPlane()));
				if (tile == null)
				{
					continue;
				}
				for (TileObject object : SceneObjects.objectsOn(tile))
				{
					for (Exit row : rows)
					{
						if (row.getObjectId() >= 0 && isObject(object, row.getObjectId()))
						{
							nearestByRow.merge(row.getObjectId(), object, (kept, other) -> nearer(from, to, kept, other));
						}
					}
				}
			}
		}
		return List.copyOf(nearestByRow.values());
	}

	private static TileObject nearer(WorldPoint from, WorldPoint to, TileObject a, TileObject b)
	{
		return closeness(b, from, to) < closeness(a, from, to) ? b : a;
	}

	/** Nearness to both ends of a hop on one floor, else to its start alone. */
	private static int closeness(TileObject object, WorldPoint from, WorldPoint to)
	{
		boolean hop = to.getPlane() == from.getPlane() && to.distanceTo2D(from) <= ROW_OBJECT_TILES;
		return squaredDistance(object, from) + (hop ? squaredDistance(object, to) : 0);
	}

	private String nameOf(TileObject object)
	{
		return LeftClicks.shownForm(client.getObjectDefinition(object.getId())).getName();
	}

	/**
	 * The object a row crosses. Across a short hop on one floor it stands between the row's ends: the Kharazi Jungle's
	 * bushes share one id, and the one beside the origin is as near as the one in front of it.
	 */
	private TileObject rowObject(WorldView view, Exit row, WorldPoint origin)
	{
		WorldPoint end = row.getDestination();
		boolean hop = end.getPlane() == origin.getPlane() && end.distanceTo2D(origin) <= ROW_OBJECT_TILES;
		return nearestAround(view, origin, object -> isObject(object, row.getObjectId()),
			(at, object) -> squaredDistance(at, origin) + (hop ? squaredDistance(at, end) : 0));
	}

	private TileObject objectNear(WorldView view, int id, WorldPoint origin)
	{
		return nearestAround(view, origin, object -> isObject(object, id), (at, object) -> squaredDistance(at, origin));
	}

	/**
	 * Of the objects {@code wanted} within {@link #ROW_OBJECT_TILES} of the origin, the one with the lowest
	 * {@code distance} of its tile and itself; of several as near, the first met going west to east, south to north.
	 */
	private static TileObject nearestAround(WorldView view, WorldPoint origin, Predicate<TileObject> wanted,
		ToIntBiFunction<WorldPoint, TileObject> distance)
	{
		TileObject nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (int dx = -ROW_OBJECT_TILES; dx <= ROW_OBJECT_TILES; dx++)
		{
			for (int dy = -ROW_OBJECT_TILES; dy <= ROW_OBJECT_TILES; dy++)
			{
				WorldPoint at = origin.dx(dx).dy(dy);
				Tile tile = tile(view, at);
				for (TileObject object : tile == null ? List.<TileObject>of() : SceneObjects.objectsOn(tile))
				{
					int toObject = wanted.test(object) ? distance.applyAsInt(at, object) : Integer.MAX_VALUE;
					if (toObject < nearestDistance)
					{
						nearest = object;
						nearestDistance = toObject;
					}
				}
			}
		}
		return nearest;
	}

	/** Shortest Path names a multiloc by the form it shows (the Kharazi broken cart: 28931, placed as 2216). */
	private boolean isObject(TileObject object, int id)
	{
		if (object.getId() == id)
		{
			return true;
		}
		return LeftClicks.shownForm(client.getObjectDefinition(object.getId())).getId() == id;
	}

	private void addClosedDoor(Map<TileObject, String> objects, WorldView view, WorldPoint from, WorldPoint to)
	{
		int dx = Integer.signum(to.getX() - from.getX());
		int dy = Integer.signum(to.getY() - from.getY());
		int fromSides = (dx > 0 ? SceneDoors.EAST : dx < 0 ? SceneDoors.WEST : 0) | (dy > 0 ? SceneDoors.NORTH : dy < 0 ? SceneDoors.SOUTH : 0);
		int toSides = (dx > 0 ? SceneDoors.WEST : dx < 0 ? SceneDoors.EAST : 0) | (dy > 0 ? SceneDoors.SOUTH : dy < 0 ? SceneDoors.NORTH : 0);
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
		LocalPoint local = LocalPoint.fromWorld(view, at);
		return local == null ? null : view.getScene().getTiles()[at.getPlane()][local.getSceneX()][local.getSceneY()];
	}
}
