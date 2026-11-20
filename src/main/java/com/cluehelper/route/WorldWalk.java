package com.cluehelper.route;

import java.util.*;
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
		return tile == WalkSearch.NONE ? List.of() : GameWalk.along(ground, search.trace(tile));
	}

	/** Where the walk ends: the goal's first tile found, the first tile found that uses its object, or the best tile beside it. */
	private static final class End implements WalkSearch.Stop
	{
		final Ground ground;
		final WalkGoal goal;
		final WorldPoint goalTile;
		final int target;
		int usedFrom = WalkSearch.NONE;
		int reached = WalkSearch.NONE;

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
			if (goal.contains(WalkSearch.x(tile), WalkSearch.y(tile), WalkSearch.plane(tile)))
			{
				reached = reached == WalkSearch.NONE ? tile : reached;
				return cost;
			}
			boolean opensOnto = ground.opensOnto(WalkSearch.x(tile), WalkSearch.y(tile), WalkSearch.plane(tile), goalTile);
			return opensOnto ? cost + BESIDE_GOAL_SLACK : WalkSearch.UNREACHED;
		}

		int tile(WalkSearch search)
		{
			return goal.isObject() ? usedFrom : reached != WalkSearch.NONE ? reached : besideGoal(search);
		}

		/** The goal when reached, else the reached tile beside it with no wall between, else the nearest. */
		private int besideGoal(WalkSearch search)
		{
			return search.cost(target) != WalkSearch.UNREACHED ? target : ground.nearestBeside(goalTile, BESIDE_GOAL_TILES, search::cost);
		}
	}

	/**
	 * Lays a walk on the tiles the game itself walks, keeping its length. The player clicks a tile and the game searches
	 * round them breadth first, trying neighbours in {@link Directions} order, and walks the path back from the clicked
	 * tile along each tile's first finder. Many walks are equally short; this one is the game's.
	 * Each stretch on foot is split into clicks a minimap click can reach, and each click's own path replaces the walk up
	 * to it when it is exactly as long. A step no click walks (through a door, onto a ladder) stays as it was.
	 * Adapted from Shortest Path's InGameWalkRewriter.
	 */
	private static final class GameWalk
	{
		/** About a minimap click's reach. */
		private static final int CLICK_TILES = 15;
		/** The corners the game keeps for one click's walk; past them the player stops short. */
		private static final int MAX_CHECKPOINTS = 25;
		private static final int NO_STEP = Integer.MIN_VALUE;

		static List<WorldPoint> along(Ground ground, int[] path)
		{
			int[] walked = path.clone();
			int current = 0;
			while (current < path.length - 1)
			{
				current = layClick(ground, path, current, walked);
			}
			List<WorldPoint> tiles = new ArrayList<>(walked.length);
			for (int tile : walked)
			{
				tiles.add(WalkTable.unpack(tile));
			}
			return tiles;
		}

		/**
		 * Lays the clicked walk to the farthest tile on foot within reach whose walk is as long as the path's onto
		 * {@code walked}: the tile it ends on, or the next tile when there is none.
		 */
		private static int layClick(Ground ground, int[] path, int current, int[] walked)
		{
			int last = current;
			while (last + 1 < path.length && isStep(path[last], path[last + 1]) && withinReach(path[current], path[last + 1]))
			{
				last++;
			}
			if (last == current)
			{
				return current + 1;
			}
			WalkSearch clicked = WalkSearch.clicked(ground, last - current);
			clicked.run(path[current], (tile, cost) -> WalkSearch.UNREACHED);
			for (int i = last; i > current; i--)
			{
				if (clicked.cost(path[i]) == i - current && fitsCheckpoints(clicked, path[i], i - current))
				{
					for (int j = i, at = path[i]; j > current; j--, at = clicked.parent(at))
					{
						walked[j] = at;
					}
					return i;
				}
			}
			return current + 1;
		}

		private static boolean fitsCheckpoints(WalkSearch clicked, int at, int steps)
		{
			int turns = 0;
			int previous = NO_STEP;
			for (int i = 0; i < steps; i++, at = clicked.parent(at))
			{
				int step = at - clicked.parent(at);
				turns += previous != NO_STEP && step != previous ? 1 : 0;
				previous = step;
			}
			return turns + 1 <= MAX_CHECKPOINTS;
		}

		private static boolean isStep(int from, int to)
		{
			return WalkSearch.plane(from) == WalkSearch.plane(to)
				&& Math.max(Math.abs(WalkSearch.x(from) - WalkSearch.x(to)), Math.abs(WalkSearch.y(from) - WalkSearch.y(to))) == 1;
		}

		private static boolean withinReach(int from, int to)
		{
			int dx = WalkSearch.x(to) - WalkSearch.x(from);
			int dy = WalkSearch.y(to) - WalkSearch.y(from);
			return dx * dx + dy * dy <= CLICK_TILES * CLICK_TILES;
		}
	}
}
