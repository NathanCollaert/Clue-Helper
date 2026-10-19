package com.cluehelper.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Start legs (PLAN §5.4 Walk edges, B02): walking costs from the player's tile over the loaded scene's
 * collision flags plus the in-scene door, stair and shortcut rows, to every walk-table node and target in
 * the scene. No Chebyshev anywhere: a tile the BFS doesn't reach has no walk. Costs are tiles (half-ticks).
 */
public final class SceneBfs
{
	public static final int UNREACHED = Integer.MAX_VALUE;

	private static final int FULL = CollisionDataFlag.BLOCK_MOVEMENT_FULL;
	private static final int N = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
	private static final int E = CollisionDataFlag.BLOCK_MOVEMENT_EAST;
	private static final int S = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
	private static final int W = CollisionDataFlag.BLOCK_MOVEMENT_WEST;
	private static final int NE = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
	private static final int NW = CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
	private static final int SE = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
	private static final int SW = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
	private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
	private static final int[] DY = {0, 0, -1, 1, -1, -1, 1, 1};
	private static final int BUCKETS = 1024;
	private static final int BORDER_LOW = 1;
	private static final int BORDER_HIGH = 5;

	/** One top-level, non-instanced scene's collision flags, copied on the client thread. */
	@Value
	public static class Scene
	{
		int baseX;
		int baseY;
		int sizeX;
		int sizeY;
		/** [plane][x][y], as {@link CollisionData#getFlags()}; a null plane has no data. */
		int[][][] flags;

		/** Null for an instanced scene or one without collision data. Client thread. */
		public static Scene capture(WorldView view)
		{
			CollisionData[] maps = view == null || view.isInstance() ? null : view.getCollisionMaps();
			if (maps == null)
			{
				return null;
			}
			int[][][] flags = new int[maps.length][][];
			for (int plane = 0; plane < maps.length; plane++)
			{
				if (maps[plane] != null)
				{
					int[][] source = maps[plane].getFlags();
					flags[plane] = new int[source.length][];
					for (int x = 0; x < source.length; x++)
					{
						flags[plane][x] = source[x].clone();
					}
				}
			}
			return new Scene(view.getBaseX(), view.getBaseY(), view.getSizeX(), view.getSizeY(), flags);
		}

		/** Inside the walkable part: the client blocks the scene's first row and last five (seen live, B10). */
		public boolean contains(WorldPoint tile)
		{
			int x = tile.getX() - baseX;
			int y = tile.getY() - baseY;
			return x >= BORDER_LOW && y >= BORDER_LOW && x < sizeX - BORDER_HIGH && y < sizeY - BORDER_HIGH
				&& tile.getPlane() >= 0 && tile.getPlane() < flags.length && flags[tile.getPlane()] != null;
		}
	}

	/** Walk costs from one start tile over one scene. */
	public static final class Result
	{
		private final Scene scene;
		private final int[] cost;

		private Result(Scene scene, int[] cost)
		{
			this.scene = scene;
			this.cost = cost;
		}

		/** Tiles from the start, or {@link #UNREACHED} (also outside the scene). */
		public int cost(WorldPoint tile)
		{
			if (tile == null || !scene.contains(tile))
			{
				return UNREACHED;
			}
			return cost[index(scene, tile.getX() - scene.getBaseX(), tile.getY() - scene.getBaseY(), tile.getPlane())];
		}

		public Scene getScene()
		{
			return scene;
		}

		public int reachedTiles()
		{
			int reached = 0;
			for (int c : cost)
			{
				reached += c == UNREACHED ? 0 : 1;
			}
			return reached;
		}
	}

	private SceneBfs()
	{
	}

	/**
	 * @param rowOpen whether each walk-table row may be used (its gate passes); rows whose ends aren't both in
	 *                the scene are ignored
	 */
	public static Result run(Scene scene, WorldPoint start, WalkTable table, boolean[] rowOpen)
	{
		int planes = scene.getFlags().length;
		int[] cost = new int[planes * scene.getSizeX() * scene.getSizeY()];
		Arrays.fill(cost, UNREACHED);
		if (!scene.contains(start))
		{
			return new Result(scene, cost);
		}
		Map<Integer, List<int[]>> rows = sceneRows(scene, table, rowOpen);
		IntQueue[] buckets = new IntQueue[BUCKETS];
		for (int i = 0; i < BUCKETS; i++)
		{
			buckets[i] = new IntQueue();
		}
		int startIndex = index(scene, start.getX() - scene.getBaseX(), start.getY() - scene.getBaseY(), start.getPlane());
		cost[startIndex] = 0;
		buckets[0].add(startIndex);
		int pending = 1;
		for (int c = 0; pending > 0; c++)
		{
			IntQueue bucket = buckets[c % BUCKETS];
			for (int i = 0; i < bucket.size; i++)
			{
				int at = bucket.data[i];
				pending--;
				if (cost[at] != c)
				{
					continue;
				}
				pending += expand(scene, at, c, cost, buckets, rows);
			}
			bucket.size = 0;
		}
		return new Result(scene, cost);
	}

	private static int expand(Scene scene, int at, int c, int[] cost, IntQueue[] buckets, Map<Integer, List<int[]>> rows)
	{
		int plane = at / (scene.getSizeX() * scene.getSizeY());
		int rest = at % (scene.getSizeX() * scene.getSizeY());
		int x = rest / scene.getSizeY();
		int y = rest % scene.getSizeY();
		int[][] flags = scene.getFlags()[plane];
		int pushed = 0;
		for (int d = 0; d < 8; d++)
		{
			int nx = x + DX[d];
			int ny = y + DY[d];
			if (nx < 0 || ny < 0 || nx >= scene.getSizeX() || ny >= scene.getSizeY())
			{
				continue;
			}
			int next = index(scene, nx, ny, plane);
			boolean open = canMove(flags, x, y, d);
			boolean ontoRowOrigin = !open && d < 4 && (flags[nx][ny] & FULL) != 0 && rows.containsKey(next);
			if ((open || ontoRowOrigin) && push(next, c + 1, cost, buckets))
			{
				pushed++;
			}
		}
		for (int[] row : rows.getOrDefault(at, List.of()))
		{
			if (push(row[0], c + row[1], cost, buckets))
			{
				pushed++;
			}
		}
		return pushed;
	}

	private static boolean push(int index, int c, int[] cost, IntQueue[] buckets)
	{
		if (c >= cost[index])
		{
			return false;
		}
		cost[index] = c;
		buckets[c % BUCKETS].add(index);
		return true;
	}

	/** The game's movement rule for one step in direction {@code d} (DX/DY order). */
	static boolean canMove(int[][] f, int x, int y, int d)
	{
		switch (d)
		{
			case 0:
				return free(f, x - 1, y, E);
			case 1:
				return free(f, x + 1, y, W);
			case 2:
				return free(f, x, y - 1, N);
			case 3:
				return free(f, x, y + 1, S);
			case 4:
				return free(f, x - 1, y - 1, N | E | NE) && free(f, x - 1, y, E) && free(f, x, y - 1, N);
			case 5:
				return free(f, x + 1, y - 1, N | W | NW) && free(f, x + 1, y, W) && free(f, x, y - 1, N);
			case 6:
				return free(f, x - 1, y + 1, S | E | SE) && free(f, x - 1, y, E) && free(f, x, y + 1, S);
			default:
				return free(f, x + 1, y + 1, S | W | SW) && free(f, x + 1, y, W) && free(f, x, y + 1, S);
		}
	}

	private static boolean free(int[][] f, int x, int y, int sides)
	{
		return x >= 0 && y >= 0 && x < f.length && y < f[x].length && (f[x][y] & (FULL | sides)) == 0;
	}

	/** In-scene rows by origin index: {destination index, cost}. */
	private static Map<Integer, List<int[]>> sceneRows(Scene scene, WalkTable table, boolean[] rowOpen)
	{
		Map<Integer, List<int[]>> rows = new HashMap<>();
		for (int r = 0; r < table.rowCount(); r++)
		{
			if (!rowOpen[r])
			{
				continue;
			}
			WorldPoint origin = table.rowOrigin(r);
			WorldPoint dest = table.rowDest(r);
			if (scene.contains(origin) && scene.contains(dest))
			{
				int from = index(scene, origin.getX() - scene.getBaseX(), origin.getY() - scene.getBaseY(), origin.getPlane());
				int to = index(scene, dest.getX() - scene.getBaseX(), dest.getY() - scene.getBaseY(), dest.getPlane());
				rows.computeIfAbsent(from, k -> new ArrayList<>()).add(new int[]{to, table.rowCost(r)});
			}
		}
		return rows;
	}

	private static int index(Scene scene, int x, int y, int plane)
	{
		return (plane * scene.getSizeX() + x) * scene.getSizeY() + y;
	}

	private static final class IntQueue
	{
		int[] data = new int[256];
		int size;

		void add(int value)
		{
			if (size == data.length)
			{
				data = Arrays.copyOf(data, size * 2);
			}
			data[size++] = value;
		}
	}
}
