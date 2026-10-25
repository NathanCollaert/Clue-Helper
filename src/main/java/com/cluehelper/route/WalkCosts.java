package com.cluehelper.route;

import net.runelite.api.coords.WorldPoint;

/**
 * The walk from one tile to every tile near it, as far as a table walk goes: what the planner's start legs and the
 * guide's arrival checks cost. An {@link Exit} (a cave, a dungeon's ladder) ends the walk at its landing, so a route
 * goes on from that landing's node and the guide can name the exit. Costs are tiles, half-ticks when running.
 * Immutable once built; any thread.
 */
public final class WalkCosts
{
	public static final int UNREACHED = WalkSearch.UNREACHED;
	/** As far as a walk of the table, so a start partway along one reaches the node it leads to. */
	private static final int MAX_TILES = 200;
	/** A node stands this close to an exit's landing. */
	private static final int BESIDE_TILES = 2;

	private final Ground ground;
	private final WorldPoint start;
	private final WalkSearch search;

	private WalkCosts(Ground ground, WorldPoint start, WalkSearch search)
	{
		this.ground = ground;
		this.start = start;
		this.search = search;
	}

	public static WalkCosts from(Ground ground, WorldPoint start)
	{
		WalkSearch search = new WalkSearch(ground, MAX_TILES, true);
		search.run(WalkTable.pack(start), (tile, cost) -> UNREACHED);
		return new WalkCosts(ground, start, search);
	}

	public WorldPoint getStart()
	{
		return start;
	}

	/** The loaded scene the walk was searched over, its doors open; null when none was. */
	public LoadedScene getScene()
	{
		return ground.getScene();
	}

	/** The walk to the tile, or to the landing of an exit within 2 tiles of it (the exit's node); else UNREACHED. */
	public int cost(WorldPoint tile)
	{
		if (tile == null)
		{
			return UNREACHED;
		}
		int cost = search.cost(WalkTable.pack(tile));
		if (cost != UNREACHED)
		{
			return cost;
		}
		int landing = exitLandingBy(tile);
		return landing == WalkSearch.NONE ? UNREACHED : search.cost(landing);
	}

	/** The exit a walk to {@code tile} leaves by, when the tile is its landing's node; else null. */
	public Exit exitTo(WorldPoint tile)
	{
		if (tile == null)
		{
			return null;
		}
		int packed = WalkTable.pack(tile);
		if (search.cost(packed) != UNREACHED)
		{
			int via = search.via(packed);
			return via != WalkSearch.NONE && ground.isExitRow(via) ? ground.exit(via) : null;
		}
		int landing = exitLandingBy(tile);
		return landing == WalkSearch.NONE ? null : ground.exit(search.via(landing));
	}

	/**
	 * The walk to a tile by {@code tile}: itself, else a straight neighbour with no wall between (a crate, a fairy
	 * ring), else the nearest within 2 tiles.
	 */
	public int costNear(WorldPoint tile)
	{
		if (cost(tile) != UNREACHED)
		{
			return cost(tile);
		}
		int best = UNREACHED;
		int bestDistance = Integer.MAX_VALUE;
		for (int dx = -BESIDE_TILES; dx <= BESIDE_TILES; dx++)
		{
			for (int dy = -BESIDE_TILES; dy <= BESIDE_TILES; dy++)
			{
				WorldPoint near = tile.dx(dx).dy(dy);
				int c = cost(near);
				int distance = opensOnto(near, tile) ? 0 : Math.max(Math.abs(dx), Math.abs(dy));
				if (c != UNREACHED && (distance < bestDistance || distance == bestDistance && c < best))
				{
					best = c;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/**
	 * The cheapest tile on a straight side of the object: the game walks to a side to use it, never a corner. A side
	 * with no wall between it and the object wins: stairs against a bank's outer wall face inwards. Null when none.
	 */
	public WorldPoint cheapestBeside(WorldPoint southWest, int sizeX, int sizeY)
	{
		WorldPoint best = null;
		int bestCost = UNREACHED;
		boolean bestOpen = false;
		for (int x = -1; x <= sizeX; x++)
		{
			for (int y = -1; y <= sizeY; y++)
			{
				boolean outsideX = x == -1 || x == sizeX;
				boolean outsideY = y == -1 || y == sizeY;
				WorldPoint tile = southWest.dx(x).dy(y);
				int c = outsideX != outsideY ? cost(tile) : UNREACHED;
				boolean open = c != UNREACHED
					&& opensOnto(tile, southWest.dx(Math.max(0, Math.min(sizeX - 1, x))).dy(Math.max(0, Math.min(sizeY - 1, y))));
				if (c != UNREACHED && (open && !bestOpen || open == bestOpen && c < bestCost))
				{
					best = tile;
					bestCost = c;
					bestOpen = open;
				}
			}
		}
		return best;
	}

	public int reachedTiles()
	{
		return search.reachedTiles();
	}

	private boolean opensOnto(WorldPoint from, WorldPoint to)
	{
		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		return from.getPlane() == to.getPlane() && Math.abs(dx) + Math.abs(dy) == 1
			&& !ground.isWallBetween(from.getX(), from.getY(), from.getPlane(), dx, dy);
	}

	/** The cheapest reached exit landing within 2 tiles of the tile. */
	private int exitLandingBy(WorldPoint tile)
	{
		int best = WalkSearch.NONE;
		int bestCost = UNREACHED;
		for (int landing : search.exitLandings())
		{
			boolean near = WalkSearch.plane(landing) == tile.getPlane()
				&& Math.max(Math.abs(WalkSearch.x(landing) - tile.getX()), Math.abs(WalkSearch.y(landing) - tile.getY())) <= BESIDE_TILES;
			if (near && search.cost(landing) < bestCost)
			{
				best = landing;
				bestCost = search.cost(landing);
			}
		}
		return best;
	}
}
