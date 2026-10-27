package com.cluehelper.route;

import java.util.Arrays;

/**
 * The cheapest walk from one tile over a {@link Ground}, tile by tile in {@link Directions} order: each step one tile,
 * a closed door a few more, a walk row its own cost. One search per instance; once run, read from any thread.
 */
final class WalkSearch
{
	static final int UNREACHED = Integer.MAX_VALUE;
	static final int NONE = -1;
	private static final int BUCKETS = 1024;

	/** Told of each tile as its cost settles: the cost after which the search may stop, or {@link #UNREACHED}. */
	interface Stop
	{
		int after(int tile, int cost);
	}

	private final Ground ground;
	private final int maxCost;
	private final boolean exitsEnd;
	private final int slotMask;
	private final int slotShift;
	private final int[] keys;
	private final int[] costs;
	private final int[] parents;
	private final int[] vias;
	private final boolean[] used;
	private final IntList exitLandings = new IntList();
	private int reached;
	private int settledBelow;

	/**
	 * @param maxCost no tile past this cost is reached
	 * @param exitsEnd an {@link Exit} row's landing is reached but not walked on from: a route goes on from its node
	 */
	WalkSearch(Ground ground, int maxCost, boolean exitsEnd)
	{
		this.ground = ground;
		this.maxCost = maxCost;
		this.exitsEnd = exitsEnd;
		int slots = Integer.highestOneBit(ground.tileLimit()) << 2;
		slotMask = slots - 1;
		slotShift = Integer.SIZE - Integer.numberOfTrailingZeros(slots);
		keys = new int[slots];
		costs = new int[slots];
		parents = new int[slots];
		vias = new int[slots];
		used = new boolean[slots];
	}

	void run(int start, Stop stop)
	{
		IntList[] buckets = new IntList[BUCKETS];
		for (int i = 0; i < BUCKETS; i++)
		{
			buckets[i] = new IntList();
		}
		improve(start, 0, NONE, NONE);
		buckets[0].add(start);
		int pending = 1;
		int stopAfter = UNREACHED;
		int cost = 0;
		for (; pending > 0 && cost <= stopAfter && reached < ground.tileLimit(); cost++)
		{
			IntList bucket = buckets[cost % BUCKETS];
			for (int i = 0; i < bucket.size; i++)
			{
				int tile = bucket.data[i];
				pending--;
				if (costOf(tile) != cost)
				{
					continue;
				}
				reached++;
				stopAfter = Math.min(stopAfter, stop.after(tile, cost));
				int via = vias[slot(tile)];
				if (via != NONE && ground.isExitRow(via))
				{
					exitLandings.add(tile);
					if (exitsEnd)
					{
						continue;
					}
				}
				pending += expand(buckets, tile, cost);
			}
			bucket.size = 0;
		}
		settledBelow = cost;
	}

	/** The tile's cost once settled, else {@link #UNREACHED}. */
	int cost(int tile)
	{
		int cost = costOf(tile);
		return cost < settledBelow ? cost : UNREACHED;
	}

	/** The tile the walk to {@code tile} came from, or {@link #NONE} at the start. */
	int parent(int tile)
	{
		return parents[slot(tile)];
	}

	/** The row the walk took into {@code tile}, or {@link #NONE} for a step. */
	int via(int tile)
	{
		return vias[slot(tile)];
	}

	int reachedTiles()
	{
		return reached;
	}

	/** The landings of the {@link Exit} rows reached, packed. */
	int[] exitLandings()
	{
		return Arrays.copyOf(exitLandings.data, exitLandings.size);
	}

	/** The walk to {@code end}, packed tiles from the start on. */
	int[] trace(int end)
	{
		IntList back = new IntList();
		for (int tile = end; tile != NONE; tile = parent(tile))
		{
			back.add(tile);
		}
		int[] path = new int[back.size];
		for (int i = 0; i < back.size; i++)
		{
			path[i] = back.data[back.size - 1 - i];
		}
		return path;
	}

	private int expand(IntList[] buckets, int tile, int cost)
	{
		int x = x(tile);
		int y = y(tile);
		int plane = plane(tile);
		int pushed = 0;
		for (int row : ground.openRowsFrom(tile))
		{
			pushed += push(buckets, ground.rowDestination(row), cost + ground.rowCost(row), tile, row);
		}
		if (isSteppedOntoObstacle(tile, x, y, plane))
		{
			return pushed;
		}
		for (int d = 0; d < Directions.COUNT; d++)
		{
			int nx = x + Directions.DX[d];
			int ny = y + Directions.DY[d];
			int next = WalkTable.pack(nx, ny, plane);
			if (ground.canMove(x, y, plane, d))
			{
				pushed += push(buckets, next, cost + ground.stepCost(x, y, plane, d), tile, NONE);
			}
			else if (d < Directions.STRAIGHT && ground.isBlocked(nx, ny, plane) && ground.openRowsFrom(next).length > 0
				&& !ground.isWallBetween(x, y, plane, Directions.DX[d], Directions.DY[d]))
			{
				pushed += push(buckets, next, cost + 1, tile, NONE);
			}
		}
		return pushed;
	}

	/**
	 * A blocked tile walked onto (the Strange floor) is left only by its rows, as the walk generator's table is: walking
	 * off its far side would cross without the row's time or gate. The start and a row's landing walk off as usual.
	 */
	private boolean isSteppedOntoObstacle(int tile, int x, int y, int plane)
	{
		return parent(tile) != NONE && via(tile) == NONE && ground.isBlocked(x, y, plane);
	}

	private int push(IntList[] buckets, int tile, int cost, int from, int via)
	{
		if (cost > maxCost || !improve(tile, cost, from, via))
		{
			return 0;
		}
		buckets[cost % BUCKETS].add(tile);
		return 1;
	}

	private int costOf(int tile)
	{
		int h = slot(tile);
		return used[h] ? costs[h] : UNREACHED;
	}

	private boolean improve(int tile, int cost, int from, int via)
	{
		int h = slot(tile);
		if (used[h] && costs[h] <= cost)
		{
			return false;
		}
		used[h] = true;
		keys[h] = tile;
		costs[h] = cost;
		parents[h] = from;
		vias[h] = via;
		return true;
	}

	private int slot(int tile)
	{
		int h = tile * 0x9E3779B1 >>> slotShift;
		while (used[h] && keys[h] != tile)
		{
			h = h + 1 & slotMask;
		}
		return h;
	}

	static int x(int tile)
	{
		return tile & 0x3FFF;
	}

	static int y(int tile)
	{
		return tile >>> 14 & 0x3FFF;
	}

	static int plane(int tile)
	{
		return tile >>> 28;
	}

	private static final class IntList
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
