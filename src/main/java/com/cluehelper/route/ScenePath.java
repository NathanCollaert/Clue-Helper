package com.cluehelper.route;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** The path line toward a goal the loaded scene can't walk to: the scene part of the table's shortest walk. */
public final class ScenePath
{
	/** Ties in the table's walk (diagonal steps, snapped nodes) differ by a tile or two. */
	private static final int SLACK_TILES = 2;
	/** A node only a few tiles ahead says nothing about the way on. */
	private static final int MIN_AHEAD_TILES = 8;
	/** A ladder or cave entrance landing this close to a goal off the scene (a basement NPC) is the way there. */
	private static final int EXIT_NEAR_GOAL_TILES = 20;
	/** Where tools/walk/WalkGen.java puts each walk's waypoint. */
	private static final int WAYPOINT_TILES = 40;

	private ScenePath()
	{
	}

	/**
	 * The table's walk is known only between its nodes plus one waypoint per walk, so the line follows the shortest walk
	 * from the node nearest the player: to the farthest point on it the scene can reach (a node, or the walk's waypoint
	 * 40 tiles along), else toward the first such point past the scene. A point counts only when it is ahead of the
	 * player, judged by the walk left from there against the player's own.
	 */
	public static List<WorldPoint> toward(SceneBfs.Result scene, WalkTable table, WorldPoint goal)
	{
		int target = table == null ? WalkTable.NO_NODE : table.node(goal);
		int start = table == null ? WalkTable.NO_NODE : nearestNode(scene, table);
		if (scene.costNear(goal) != SceneBfs.UNREACHED)
		{
			return scene.pathToward(goal);
		}
		SceneBfs.ExitReach wayDown = scene.exitNear(goal, EXIT_NEAR_GOAL_TILES);
		if (wayDown != null)
		{
			return scene.pathTo(wayDown.getDestination());
		}
		if (target == WalkTable.NO_NODE || start == WalkTable.NO_NODE)
		{
			return scene.pathToward(goal);
		}
		int directEntry = entry(table, start, target);
		int direct = start == target ? 0 : directEntry < 0 ? WalkTable.NO_PATH : table.entryFull(directEntry);
		if (direct == WalkTable.NO_PATH)
		{
			return scene.pathToward(goal);
		}
		int playerLeft = direct - scene.cost(table.placed(start));
		WorldPoint waypoint = directEntry < 0 ? null : table.entryWaypoint(directEntry);
		int waypointLeft = direct - WAYPOINT_TILES;
		WorldPoint drawable = null;
		int drawableLeft = Integer.MAX_VALUE;
		WorldPoint beyond = null;
		int beyondLeft = Integer.MIN_VALUE;
		for (int e = table.entriesFrom(start); e < table.entriesEnd(start); e++)
		{
			int middle = table.entryTo(e);
			int left = middle == target ? 0 : walk(table, middle, target);
			if (left == WalkTable.NO_PATH || table.entryFull(e) + left > direct + SLACK_TILES || left > playerLeft - MIN_AHEAD_TILES)
			{
				continue;
			}
			WorldPoint at = table.placed(middle);
			boolean reached = at != null && scene.getScene().contains(at) && scene.cost(at) != SceneBfs.UNREACHED;
			if (reached && left < drawableLeft)
			{
				drawable = at;
				drawableLeft = left;
			}
			else if (!reached && at != null && left > beyondLeft)
			{
				beyond = at;
				beyondLeft = left;
			}
		}
		boolean waypointAhead = waypoint != null && waypointLeft <= playerLeft - MIN_AHEAD_TILES;
		if (drawable != null && (!waypointAhead || drawableLeft < waypointLeft))
		{
			return scene.pathTo(drawable);
		}
		if (waypointAhead)
		{
			return scene.costNear(waypoint) != SceneBfs.UNREACHED ? scene.pathTo(waypoint) : scene.pathToward(waypoint);
		}
		return scene.pathToward(beyond != null ? beyond : goal);
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

	private static int walk(WalkTable table, int from, int to)
	{
		if (from == to)
		{
			return 0;
		}
		for (int e = table.entriesFrom(from); e < table.entriesEnd(from); e++)
		{
			if (table.entryTo(e) == to)
			{
				return table.entryFull(e);
			}
		}
		return WalkTable.NO_PATH;
	}
}
