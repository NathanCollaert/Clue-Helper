package com.cluehelper.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * No Chebyshev fallback: a tile the BFS doesn't reach has no walk. Costs are tiles, which are half-ticks when
 * running.
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
	private static final int MAX_TILES_BESIDE_WAYPOINT = 2;
	private static final int NO_DIRECTION = -1;

	@Value
	public static class Scene
	{
		int baseX;
		int baseY;
		int sizeX;
		int sizeY;
		/** [plane][x][y]; a null plane has no data. */
		int[][][] flags;

		/** Client thread only. Null for an instanced scene or one without collision data. */
		public static Scene capture(WorldView view)
		{
			return view == null || view.isInstance() ? null : captureLoaded(view);
		}

		/** Client thread only. In an instance its tiles are the instance's own and match nothing in the walk table. */
		public static Scene captureLoaded(WorldView view)
		{
			CollisionData[] maps = view == null ? null : view.getCollisionMaps();
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

		/** The client blocks the scene's first row and last five. */
		public boolean contains(WorldPoint tile)
		{
			int x = tile.getX() - baseX;
			int y = tile.getY() - baseY;
			return x >= BORDER_LOW && y >= BORDER_LOW && x < sizeX - BORDER_HIGH && y < sizeY - BORDER_HIGH
				&& tile.getPlane() >= 0 && tile.getPlane() < flags.length && flags[tile.getPlane()] != null;
		}
	}

	/** A walk row out of the scene, reached by the BFS: where it starts and lands, and the cost to its landing. */
	@Value
	public static class ExitReach
	{
		WorldPoint origin;
		WorldPoint destination;
		int cost;
	}

	public static final class Result
	{
		private final Scene scene;
		private final int[] cost;
		private final List<ExitReach> exits;

		/** Each in-scene row by its destination index: {origin index, cost}. */
		private final Map<Integer, List<int[]>> rowsInto;

		private Result(Scene scene, int[] cost, List<ExitReach> exits, Map<Integer, List<int[]>> rowsInto)
		{
			this.scene = scene;
			this.cost = cost;
			this.exits = exits;
			this.rowsInto = rowsInto;
		}

		/** A tile outside the scene costs its exit's landing when one lands within 2 tiles of it (a node's snap). */
		public int cost(WorldPoint tile)
		{
			if (tile == null)
			{
				return UNREACHED;
			}
			if (!scene.contains(tile))
			{
				ExitReach exit = exitTo(tile);
				return exit == null ? UNREACHED : exit.getCost();
			}
			return cost[index(scene, tile.getX() - scene.getBaseX(), tile.getY() - scene.getBaseY(), tile.getPlane())];
		}

		/** The cheapest reached exit landing within 2 tiles of {@code tile}, or null. */
		public ExitReach exitTo(WorldPoint tile)
		{
			ExitReach best = null;
			for (ExitReach exit : exits)
			{
				WorldPoint landing = exit.getDestination();
				boolean near = landing.getPlane() == tile.getPlane()
					&& Math.max(Math.abs(landing.getX() - tile.getX()), Math.abs(landing.getY() - tile.getY())) <= MAX_TILES_BESIDE_WAYPOINT;
				if (near && (best == null || exit.getCost() < best.getCost()))
				{
					best = exit;
				}
			}
			return best;
		}

		/** The reached exit landing within {@code tiles} of {@code tile} that is cheapest counting the rest on foot. */
		public ExitReach exitNear(WorldPoint tile, int tiles)
		{
			ExitReach best = null;
			int bestCost = UNREACHED;
			for (ExitReach exit : exits)
			{
				WorldPoint landing = exit.getDestination();
				int distance = Math.max(Math.abs(landing.getX() - tile.getX()), Math.abs(landing.getY() - tile.getY()));
				if (landing.getPlane() == tile.getPlane() && distance <= tiles && exit.getCost() + distance < bestCost)
				{
					best = exit;
					bestCost = exit.getCost() + distance;
				}
			}
			return best;
		}

		/** The reached exits that start on {@code origin}. */
		public List<ExitReach> exitsFrom(WorldPoint origin)
		{
			List<ExitReach> from = new ArrayList<>();
			for (ExitReach exit : exits)
			{
				if (exit.getOrigin().equals(origin))
				{
					from.add(exit);
				}
			}
			return from;
		}

		public Scene getScene()
		{
			return scene;
		}

		/**
		 * A waypoint on a blocked tile (a fairy ring, an altar) is reached at the reachable tile closest to it. A door or
		 * stairs row shows as a jump from its origin to its destination.
		 */
		public List<WorldPoint> pathTo(WorldPoint waypoint)
		{
			List<WorldPoint> path = new ArrayList<>();
			WorldPoint goal = nearestReached(waypoint);
			if (!scene.contains(goal))
			{
				ExitReach exit = exitTo(goal);
				if (exit == null)
				{
					return path;
				}
				goal = nearestReached(exit.getOrigin());
			}
			int c = cost(goal);
			if (c == UNREACHED)
			{
				return path;
			}
			int plane = goal.getPlane();
			int x = goal.getX() - scene.getBaseX();
			int y = goal.getY() - scene.getBaseY();
			path.add(goal);
			boolean onBlocked = true;
			while (c > 0)
			{
				int[][] flags = scene.getFlags()[plane];
				int step = arrivalDirection(flags, x, y, plane, c);
				if (step == NO_DIRECTION && onBlocked)
				{
					step = ontoBlockedOrigin(x, y, plane, c);
				}
				if (step == NO_DIRECTION)
				{
					int origin = rowOriginInto(index(scene, x, y, plane), c);
					if (origin < 0)
					{
						break;
					}
					c = cost[origin];
					plane = origin / (scene.getSizeX() * scene.getSizeY());
					x = origin % (scene.getSizeX() * scene.getSizeY()) / scene.getSizeY();
					y = origin % scene.getSizeY();
					path.add(new WorldPoint(scene.getBaseX() + x, scene.getBaseY() + y, plane));
					onBlocked = true;
					continue;
				}
				x -= DX[step];
				y -= DY[step];
				c--;
				onBlocked = false;
				path.add(new WorldPoint(scene.getBaseX() + x, scene.getBaseY() + y, plane));
			}
			Collections.reverse(path);
			return path;
		}

		/** The start of a walk row (a shortcut, stairs, a door) the BFS reached {@code into} by, at cost {@code c}; -1 if none. */
		private int rowOriginInto(int into, int c)
		{
			for (int[] row : rowsInto.getOrDefault(into, List.of()))
			{
				if (cost[row[0]] != UNREACHED && cost[row[0]] + row[1] == c)
				{
					return row[0];
				}
			}
			return -1;
		}

		/**
		 * A goal outside the scene, or unreached in it, is drawn toward: to the reached tile nearest it, weighted by the walk
		 * there. A guess past the scene's edge, redrawn as the scene moves.
		 */
		public List<WorldPoint> pathToward(WorldPoint goal)
		{
			if (costNear(goal) != UNREACHED)
			{
				return pathTo(goal);
			}
			WorldPoint best = null;
			long bestScore = Long.MAX_VALUE;
			int plane = goal.getPlane();
			if (plane < 0 || plane >= scene.getFlags().length || scene.getFlags()[plane] == null)
			{
				return new ArrayList<>();
			}
			for (int x = 0; x < scene.getSizeX(); x++)
			{
				for (int y = 0; y < scene.getSizeY(); y++)
				{
					int c = cost[index(scene, x, y, plane)];
					if (c == UNREACHED)
					{
						continue;
					}
					int wx = scene.getBaseX() + x;
					int wy = scene.getBaseY() + y;
					long score = (long) c + 2L * Math.max(Math.abs(wx - goal.getX()), Math.abs(wy - goal.getY()));
					if (score < bestScore)
					{
						bestScore = score;
						best = new WorldPoint(wx, wy, plane);
					}
				}
			}
			return best == null ? new ArrayList<>() : pathTo(best);
		}

		/** Corners don't count: the game walks to a straight side to use an object. Null when no side is reached. */
		public WorldPoint cheapestBeside(WorldPoint southWest, int sizeX, int sizeY)
		{
			WorldPoint best = null;
			int bestCost = UNREACHED;
			for (int x = -1; x <= sizeX; x++)
			{
				for (int y = -1; y <= sizeY; y++)
				{
					boolean outsideX = x == -1 || x == sizeX;
					boolean outsideY = y == -1 || y == sizeY;
					WorldPoint tile = southWest.dx(x).dy(y);
					int c = outsideX != outsideY ? cost(tile) : UNREACHED;
					if (c < bestCost)
					{
						best = tile;
						bestCost = c;
					}
				}
			}
			return best;
		}

		public int costNear(WorldPoint tile)
		{
			return cost(nearestReached(tile));
		}

		/**
		 * A blocked waypoint (a crate, a fairy ring) is used from a side with no wall between, so such a side wins over
		 * a nearer or cheaper tile behind a wall.
		 */
		private WorldPoint nearestReached(WorldPoint waypoint)
		{
			if (cost(waypoint) != UNREACHED)
			{
				return waypoint;
			}
			WorldPoint best = waypoint;
			int bestCost = UNREACHED;
			int bestDistance = Integer.MAX_VALUE;
			for (int dx = -MAX_TILES_BESIDE_WAYPOINT; dx <= MAX_TILES_BESIDE_WAYPOINT; dx++)
			{
				for (int dy = -MAX_TILES_BESIDE_WAYPOINT; dy <= MAX_TILES_BESIDE_WAYPOINT; dy++)
				{
					WorldPoint tile = waypoint.dx(dx).dy(dy);
					int c = cost(tile);
					int distance = opensOnto(tile, waypoint) ? 0 : Math.max(Math.abs(dx), Math.abs(dy));
					if (c != UNREACHED && (distance < bestDistance || distance == bestDistance && c < bestCost))
					{
						best = tile;
						bestCost = c;
						bestDistance = distance;
					}
				}
			}
			return best;
		}

		/** A straight neighbour with no wall between it and {@code to}. */
		private boolean opensOnto(WorldPoint from, WorldPoint to)
		{
			int dx = to.getX() - from.getX();
			int dy = to.getY() - from.getY();
			if (from.getPlane() != to.getPlane() || Math.abs(dx) + Math.abs(dy) != 1 || !scene.contains(from) || !scene.contains(to))
			{
				return false;
			}
			int[][] flags = scene.getFlags()[from.getPlane()];
			if (flags == null)
			{
				return false;
			}
			int fromWall = dx > 0 ? E : dx < 0 ? W : dy > 0 ? N : S;
			int toWall = dx > 0 ? W : dx < 0 ? E : dy > 0 ? S : N;
			int fx = from.getX() - scene.getBaseX();
			int fy = from.getY() - scene.getBaseY();
			return (flags[fx][fy] & fromWall) == 0 && (flags[fx + dx][fy + dy] & toWall) == 0;
		}

		/** Stairs and ladders block their own tile, which the BFS enters from a straight side only. */
		private int ontoBlockedOrigin(int x, int y, int plane, int c)
		{
			for (int d = 0; d < 4; d++)
			{
				int px = x - DX[d];
				int py = y - DY[d];
				if (px >= 0 && py >= 0 && px < scene.getSizeX() && py < scene.getSizeY() && cost[index(scene, px, py, plane)] == c - 1)
				{
					return d;
				}
			}
			return NO_DIRECTION;
		}

		private int arrivalDirection(int[][] flags, int x, int y, int plane, int c)
		{
			for (int d = 0; d < DX.length; d++)
			{
				int px = x - DX[d];
				int py = y - DY[d];
				if (px >= 0 && py >= 0 && px < scene.getSizeX() && py < scene.getSizeY()
					&& cost[index(scene, px, py, plane)] == c - 1 && canMove(flags, px, py, d))
				{
					return d;
				}
			}
			return NO_DIRECTION;
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

	public static Result run(Scene scene, WorldPoint start, WalkTable table, boolean[] rowOpen)
	{
		int planes = scene.getFlags().length;
		int[] cost = new int[planes * scene.getSizeX() * scene.getSizeY()];
		Arrays.fill(cost, UNREACHED);
		if (!scene.contains(start))
		{
			return new Result(scene, cost, List.of(), Map.of());
		}
		Map<Integer, List<int[]>> rows = sceneRows(scene, table, rowOpen);
		Map<Integer, List<Integer>> leaving = leavingRows(scene, table, rowOpen);
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
		return new Result(scene, cost, exits(table, leaving, cost), reversed(rows));
	}

	private static List<ExitReach> exits(WalkTable table, Map<Integer, List<Integer>> leaving, int[] cost)
	{
		List<ExitReach> exits = new ArrayList<>();
		leaving.forEach((origin, rows) ->
		{
			if (cost[origin] == UNREACHED)
			{
				return;
			}
			for (int r : rows)
			{
				exits.add(new ExitReach(table.rowOrigin(r), table.rowDest(r), cost[origin] + table.rowCost(r)));
			}
		});
		return List.copyOf(exits);
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

	/** The game's movement rule; {@code d} indexes DX and DY. */
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
		if (table == null)
		{
			return rows;
		}
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

	private static Map<Integer, List<int[]>> reversed(Map<Integer, List<int[]>> rows)
	{
		Map<Integer, List<int[]>> into = new HashMap<>();
		rows.forEach((origin, out) -> out.forEach(row -> into.computeIfAbsent(row[0], k -> new ArrayList<>()).add(new int[]{origin, row[1]})));
		return into;
	}

	/** Rows from the scene to a far landing outside it, by origin index: the row numbers. */
	private static Map<Integer, List<Integer>> leavingRows(Scene scene, WalkTable table, boolean[] rowOpen)
	{
		Map<Integer, List<Integer>> rows = new HashMap<>();
		if (table == null)
		{
			return rows;
		}
		for (int r = 0; r < table.rowCount(); r++)
		{
			WorldPoint origin = table.rowOrigin(r);
			WorldPoint dest = table.rowDest(r);
			if (rowOpen[r] && scene.contains(origin) && !scene.contains(dest) && table.exit(origin, dest) != null)
			{
				int from = index(scene, origin.getX() - scene.getBaseX(), origin.getY() - scene.getBaseY(), origin.getPlane());
				rows.computeIfAbsent(from, k -> new ArrayList<>()).add(r);
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
