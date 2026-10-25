package com.cluehelper.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Lays a walk on the tiles the game itself walks, keeping its length. The player clicks a tile and the game searches
 * a 128 by 128 window round them breadth first, trying neighbours in {@link Directions} order, and walks the path
 * back from the clicked tile along each tile's first finder. Many walks are equally short; this one is the game's.
 * Each stretch on foot is split into clicks a minimap click can reach, and each click's own path replaces the walk up
 * to it when it is exactly as long. A step no click walks (through a door, onto a ladder) stays as it was.
 * Adapted from Shortest Path's InGameWalkRewriter.
 */
final class GameWalk
{
	/** About a minimap click's reach. */
	private static final int CLICK_TILES = 15;
	/** The corners the game keeps for one click's walk; past them the player stops short. */
	private static final int MAX_CHECKPOINTS = 25;
	private static final int WINDOW = 128;
	private static final int HALF_WINDOW = WINDOW / 2;
	private static final int UNVISITED = -1;
	private static final int NO_STEP = Integer.MIN_VALUE;

	private final int[] distance = new int[WINDOW * WINDOW];
	private final int[] parent = new int[WINDOW * WINDOW];
	private final int[] queue = new int[WINDOW * WINDOW];

	List<WorldPoint> along(Ground ground, int[] path)
	{
		int[] walked = path.clone();
		int current = 0;
		while (current < path.length - 1)
		{
			int click = farthestClick(ground, path, current);
			if (click == current)
			{
				current++;
				continue;
			}
			int originX = WalkSearch.x(path[current]) - HALF_WINDOW;
			int originY = WalkSearch.y(path[current]) - HALF_WINDOW;
			int plane = WalkSearch.plane(path[current]);
			int at = local(originX, originY, path[click]);
			for (int i = click; i > current; i--, at = parent[at])
			{
				walked[i] = WalkTable.pack(originX + at % WINDOW, originY + at / WINDOW, plane);
			}
			current = click;
		}
		List<WorldPoint> tiles = new ArrayList<>(walked.length);
		for (int tile : walked)
		{
			tiles.add(WalkTable.unpack(tile));
		}
		return tiles;
	}

	/** The farthest tile on foot within reach whose clicked walk is as long as the path's, or {@code current} if none. */
	private int farthestClick(Ground ground, int[] path, int current)
	{
		int last = current;
		while (last + 1 < path.length && isStep(path[last], path[last + 1]) && withinReach(path[current], path[last + 1]))
		{
			last++;
		}
		if (last == current)
		{
			return current;
		}
		int originX = WalkSearch.x(path[current]) - HALF_WINDOW;
		int originY = WalkSearch.y(path[current]) - HALF_WINDOW;
		search(ground, originX, originY, WalkSearch.plane(path[current]), last - current);
		for (int i = last; i > current; i--)
		{
			int at = local(originX, originY, path[i]);
			if (distance[at] == i - current && fitsCheckpoints(at, i - current))
			{
				return i;
			}
		}
		return current;
	}

	/** The game's walking search from the window's centre, as deep as {@code depth}; doors stay shut as the game has them. */
	private void search(Ground ground, int originX, int originY, int plane, int depth)
	{
		Arrays.fill(distance, UNVISITED);
		int start = HALF_WINDOW * WINDOW + HALF_WINDOW;
		distance[start] = 0;
		parent[start] = start;
		int head = 0;
		int tail = 0;
		queue[tail++] = start;
		while (head < tail)
		{
			int at = queue[head++];
			if (distance[at] >= depth)
			{
				break;
			}
			int x = at % WINDOW;
			int y = at / WINDOW;
			for (int d = 0; d < Directions.COUNT; d++)
			{
				int nx = x + Directions.DX[d];
				int ny = y + Directions.DY[d];
				int next = ny * WINDOW + nx;
				if (nx < 0 || ny < 0 || nx >= WINDOW || ny >= WINDOW || distance[next] != UNVISITED
					|| !ground.canMove(originX + x, originY + y, plane, d) || ground.stepCost(originX + x, originY + y, plane, d) != 1)
				{
					continue;
				}
				distance[next] = distance[at] + 1;
				parent[next] = at;
				queue[tail++] = next;
			}
		}
	}

	private boolean fitsCheckpoints(int at, int steps)
	{
		int turns = 0;
		int previous = NO_STEP;
		for (int i = 0; i < steps; i++, at = parent[at])
		{
			int step = at - parent[at];
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

	private static int local(int originX, int originY, int tile)
	{
		return (WalkSearch.y(tile) - originY) * WINDOW + WalkSearch.x(tile) - originX;
	}
}
