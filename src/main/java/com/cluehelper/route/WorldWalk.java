package com.cluehelper.route;

import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * The walk between two tiles as the player's character takes it: the cheapest way over the {@link Ground}, laid tile
 * by tile as the game's own walking lays it when the player clicks along it. A row (stairs, a ladder) shows as a jump
 * from its origin to its destination. Any thread.
 */
public final class WorldWalk
{
	/** A blocked goal tile (a fairy ring) is used from a tile this close to it. */
	private static final int BESIDE_GOAL_TILES = 2;
	/** Once on a side of the goal with no wall between, a walk this much longer may still reach the goal itself. */
	private static final int BESIDE_GOAL_SLACK = 8;

	/**
	 * To the goal tile, or to beside it; for an object, to the first tile found that uses it, as the game walks. Empty
	 * when the goal is out of reach: a guess past the search would lead the wrong way (through a minigame portal).
	 */
	public List<WorldPoint> path(Ground ground, WorldPoint start, WalkGoal goal)
	{
		WalkSearch search = new WalkSearch(ground, WalkSearch.UNREACHED, false);
		End end = new End(ground, goal);
		search.run(WalkTable.pack(start), end);
		int tile = end.tile(search);
		return tile == WalkSearch.NONE ? List.of() : new GameWalk().along(ground, search.trace(tile));
	}

	/** Where the walk ends: the goal, the first tile found that uses its object, or the best tile beside it. */
	private static final class End implements WalkSearch.Stop
	{
		private final Ground ground;
		private final WalkGoal goal;
		private final WorldPoint goalTile;
		private final int target;
		private int usedFrom = WalkSearch.NONE;

		End(Ground ground, WalkGoal goal)
		{
			this.ground = ground;
			this.goal = goal;
			this.goalTile = goal.getSouthWest();
			this.target = WalkTable.pack(goalTile);
		}

		@Override
		public int after(int tile, int cost)
		{
			if (goal.isObject())
			{
				boolean uses = usedFrom == WalkSearch.NONE
					&& goal.isUsedFrom(ground, WalkSearch.x(tile), WalkSearch.y(tile), WalkSearch.plane(tile));
				usedFrom = uses ? tile : usedFrom;
				return uses ? cost : WalkSearch.UNREACHED;
			}
			if (tile == target)
			{
				return cost;
			}
			return opensOnto(tile) ? cost + BESIDE_GOAL_SLACK : WalkSearch.UNREACHED;
		}

		int tile(WalkSearch search)
		{
			return goal.isObject() ? usedFrom : besideGoal(search);
		}

		/** The goal when reached, else the reached tile beside it with no wall between, else the nearest. */
		private int besideGoal(WalkSearch search)
		{
			if (search.cost(target) != WalkSearch.UNREACHED)
			{
				return target;
			}
			int best = WalkSearch.NONE;
			int bestCost = WalkSearch.UNREACHED;
			int bestDistance = Integer.MAX_VALUE;
			for (int dx = -BESIDE_GOAL_TILES; dx <= BESIDE_GOAL_TILES; dx++)
			{
				for (int dy = -BESIDE_GOAL_TILES; dy <= BESIDE_GOAL_TILES; dy++)
				{
					int tile = WalkTable.pack(goalTile.getX() + dx, goalTile.getY() + dy, goalTile.getPlane());
					int cost = search.cost(tile);
					int distance = opensOnto(tile) ? 0 : Math.max(Math.abs(dx), Math.abs(dy));
					if (cost != WalkSearch.UNREACHED && (distance < bestDistance || distance == bestDistance && cost < bestCost))
					{
						best = tile;
						bestCost = cost;
						bestDistance = distance;
					}
				}
			}
			return best;
		}

		/** A straight neighbour of the goal with no wall between; a tile beside it through a wall is no way to it. */
		private boolean opensOnto(int tile)
		{
			int x = WalkSearch.x(tile);
			int y = WalkSearch.y(tile);
			int dx = x - goalTile.getX();
			int dy = y - goalTile.getY();
			return WalkSearch.plane(tile) == goalTile.getPlane() && Math.abs(dx) + Math.abs(dy) == 1
				&& !ground.isWallBetween(x, y, goalTile.getPlane(), -dx, -dy);
		}
	}
}
