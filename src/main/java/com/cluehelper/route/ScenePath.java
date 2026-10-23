package com.cluehelper.route;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * The path line toward a goal the loaded scene can't walk to: the scene's walk to the farthest point it reaches on the
 * table's walk there, its waypoints 40 tiles apart. One walk is kept per goal and followed only forward, so the line
 * doesn't swap ways as the plan's start leg moves with the player.
 */
public final class ScenePath
{
	/** A ladder or cave entrance landing this close to a goal off the scene (a basement NPC) is the way there. */
	private static final int EXIT_NEAR_GOAL_TILES = 20;
	/** Standing on a waypoint, the line leads on to the next one. */
	private static final int AT_POINT_TILES = 3;
	private static final int BESIDE_POINT_TILES = 8;
	private static final int GOAL_NODE_TILES = 8;

	private WorldPoint goal;
	/** The kept walk: its start node, its waypoints, its end node. */
	private List<WorldPoint> walk = List.of();
	/** The point of {@code walk} the line leads to: the farthest the scene reaches, or the one after it once passed; never goes back. */
	private int reached;

	/**
	 * A line from a fresh walk choice. {@code via} and {@code joinedFrom}: the planned route's start leg
	 * ({@link Route#getStartVia()}, {@link Route#getStartJoinedFrom()}).
	 */
	public static List<WorldPoint> toward(SceneBfs.Result scene, WalkTable table, WorldPoint goal, WorldPoint via, WorldPoint joinedFrom)
	{
		return new ScenePath().line(scene, table, goal, via, joinedFrom);
	}

	public static List<WorldPoint> toward(SceneBfs.Result scene, WalkTable table, WorldPoint goal)
	{
		return toward(scene, table, goal, null, null);
	}

	/** The next line chooses its walk again, as for a new goal. */
	public void forget()
	{
		goal = null;
	}

	/** The line from the player, the start of {@code scene}; the walk is chosen again for a new goal or once left. */
	public List<WorldPoint> line(SceneBfs.Result scene, WalkTable table, WorldPoint goal, WorldPoint via, WorldPoint joinedFrom)
	{
		if (scene.costNear(goal) != SceneBfs.UNREACHED || table == null)
		{
			return scene.pathToward(goal);
		}
		SceneBfs.ExitReach wayDown = scene.exitNear(goal, EXIT_NEAR_GOAL_TILES);
		if (wayDown != null)
		{
			return scene.pathTo(wayDown.getDestination());
		}
		if (!goal.equals(this.goal) || !isOnWalk(scene))
		{
			this.goal = goal;
			walk = walkTo(scene, table, goal, via, joinedFrom);
			reached = 0;
		}
		if (walk.isEmpty())
		{
			return scene.pathToward(goal);
		}
		reached = farthestReached(scene);
		while (reached + 1 < walk.size() && isPassed(scene, reached))
		{
			reached++;
		}
		return lineTo(scene, walk.get(reached));
	}

	private static List<WorldPoint> walkTo(SceneBfs.Result scene, WalkTable table, WorldPoint goal, WorldPoint via, WorldPoint joinedFrom)
	{
		int target = nodeAt(table, goal);
		int start = startNode(scene, table, via, joinedFrom);
		int e = target == WalkTable.NO_NODE || start == WalkTable.NO_NODE ? -1 : entry(table, start, target);
		if (e < 0)
		{
			return List.of();
		}
		List<WorldPoint> points = new ArrayList<>();
		points.add(table.placed(start));
		points.addAll(table.entryWaypoints(e));
		points.add(table.placed(target));
		if (!goal.equals(table.placed(target)))
		{
			points.add(goal);
		}
		return List.copyOf(points);
	}

	/** A goal that isn't a node (a STASH unit beside its clue) follows the walk to the nearest node by it. */
	private static int nodeAt(WalkTable table, WorldPoint goal)
	{
		int exact = table.node(goal);
		if (exact != WalkTable.NO_NODE)
		{
			return exact;
		}
		int best = WalkTable.NO_NODE;
		int bestDistance = GOAL_NODE_TILES + 1;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			WorldPoint at = table.placed(node);
			int distance = at == null || at.getPlane() != goal.getPlane() ? Integer.MAX_VALUE : at.distanceTo2D(goal);
			if (distance < bestDistance)
			{
				best = node;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static int startNode(SceneBfs.Result scene, WalkTable table, WorldPoint via, WorldPoint joinedFrom)
	{
		int joined = joinedFrom == null ? WalkTable.NO_NODE : table.node(joinedFrom);
		if (joined != WalkTable.NO_NODE)
		{
			return joined;
		}
		int viaNode = via == null ? WalkTable.NO_NODE : table.node(via);
		return viaNode != WalkTable.NO_NODE ? viaNode : nearestNode(scene, table);
	}

	/** The kept walk still leads on from the scene, or runs past its edge. */
	private boolean isOnWalk(SceneBfs.Result scene)
	{
		if (walk.isEmpty())
		{
			return false;
		}
		for (int i = reached; i < walk.size(); i++)
		{
			if (isReached(scene, walk.get(i)))
			{
				return true;
			}
		}
		return !scene.getScene().contains(walk.get(reached));
	}

	/**
	 * Standing on the point, or beside it with the next one no farther without it. Only beside it: past the scene's
	 * edge the walk to the next one is a guess that knows no cliffs.
	 */
	private boolean isPassed(SceneBfs.Result scene, int point)
	{
		WorldPoint at = walk.get(point);
		WorldPoint next = walk.get(point + 1);
		int toPoint = scene.costNear(at);
		if (toPoint <= AT_POINT_TILES)
		{
			return true;
		}
		if (toPoint > BESIDE_POINT_TILES || at.getPlane() != next.getPlane())
		{
			return false;
		}
		int toNext = scene.walkAtLeast(next);
		return toNext != SceneBfs.UNREACHED && toNext <= toPoint + at.distanceTo2D(next);
	}

	private int farthestReached(SceneBfs.Result scene)
	{
		for (int i = walk.size() - 1; i > reached; i--)
		{
			if (isReached(scene, walk.get(i)))
			{
				return i;
			}
		}
		return reached;
	}

	/** A point past the scene, or beyond a ladder or cave entrance, is drawn toward. */
	private static List<WorldPoint> lineTo(SceneBfs.Result scene, WorldPoint point)
	{
		if (isReached(scene, point))
		{
			return scene.pathTo(point);
		}
		SceneBfs.ExitReach exit = scene.exitNear(point, WalkTable.WAYPOINT_TILES);
		return exit != null ? scene.pathTo(exit.getDestination()) : scene.pathToward(point);
	}

	private static boolean isReached(SceneBfs.Result scene, WorldPoint point)
	{
		return point != null && scene.getScene().contains(point) && scene.costNear(point) != SceneBfs.UNREACHED;
	}

	private static int entry(WalkTable table, int from, int to)
	{
		for (int e = table.entriesFrom(from); e < table.entriesEnd(from); e++)
		{
			if (table.entryTo(e) == to)
			{
				return e;
			}
		}
		return -1;
	}

	private static int nearestNode(SceneBfs.Result scene, WalkTable table)
	{
		int best = WalkTable.NO_NODE;
		int bestCost = SceneBfs.UNREACHED;
		for (int node = 0; node < table.nodeCount(); node++)
		{
			WorldPoint at = table.placed(node);
			int cost = at == null || !scene.getScene().contains(at) ? SceneBfs.UNREACHED : scene.cost(at);
			if (cost < bestCost)
			{
				bestCost = cost;
				best = node;
			}
		}
		return best;
	}
}
