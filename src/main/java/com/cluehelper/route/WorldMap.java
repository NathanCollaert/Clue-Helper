package com.cluehelper.route;

import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shortest Path's collision map of the whole game, doors open: four bits per tile, whether its north and east edges
 * can be walked and whether a wall or door stands on them. Immutable once loaded; any thread.
 */
public final class WorldMap
{
	private static final String RESOURCE = WalkTable.DIRECTORY + "collision-map.zip";
	private static final int REGION_BITS = 6;
	private static final int REGION_MASK = (1 << REGION_BITS) - 1;
	private static final int TILES_PER_PLANE = 1 << 2 * REGION_BITS;
	private static final int FLAGS_PER_TILE = 4;
	private static final int OPEN_NORTH = 0;
	private static final int OPEN_EAST = 1;
	private static final int WALL_NORTH = 2;
	private static final int WALL_EAST = 3;
	private static final int MAX_PLANE = 3;
	private static final int MAX_COORDINATE = 1 << 14;

	/** Each region's flag words by region x << 8 | region y; null where the map has none. */
	private final long[][] regions = new long[1 << 16][];

	private WorldMap()
	{
	}

	public static WorldMap load() throws IOException
	{
		WorldMap map = new WorldMap();
		try (InputStream resource = WorldMap.class.getResourceAsStream(RESOURCE))
		{
			if (resource == null)
			{
				throw new IOException("missing resource " + RESOURCE);
			}
			ZipInputStream in = new ZipInputStream(resource);
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null)
			{
				String[] region = entry.getName().split("_");
				map.regions[Integer.parseInt(region[0]) << 8 | Integer.parseInt(region[1])] = BitSet.valueOf(in.readAllBytes()).toLongArray();
			}
		}
		return map;
	}

	/** Shortest Path's walking rule: a diagonal needs both straight steps beside it; off a blocked tile, any open neighbour. */
	boolean canMove(int x, int y, int plane, int direction)
	{
		int dx = Directions.DX[direction];
		int dy = Directions.DY[direction];
		if (isBlocked(x, y, plane))
		{
			boolean straight = dx == 0 || dy == 0;
			return !isBlocked(x + dx, y + dy, plane)
				&& (straight || !isBlocked(x + dx, y, plane) && !isBlocked(x, y + dy, plane));
		}
		if (dx == 0 || dy == 0)
		{
			return canStep(x, y, plane, dx, dy);
		}
		return canStep(x, y, plane, 0, dy) && canStep(x, y + dy, plane, dx, 0)
			&& canStep(x, y, plane, dx, 0) && canStep(x + dx, y, plane, 0, dy);
	}

	/** No edge of the tile can be walked: an object stands on it. */
	boolean isBlocked(int x, int y, int plane)
	{
		return !canStep(x, y, plane, 0, 1) && !canStep(x, y, plane, 0, -1) && !canStep(x, y, plane, 1, 0)
			&& !canStep(x, y, plane, -1, 0);
	}

	/** A wall or door stands on the edge between straight neighbours; an object blocking a tile is no wall. */
	boolean isWallBetween(int x, int y, int plane, int dx, int dy)
	{
		if (dx > 0 || dy > 0)
		{
			return flag(x, y, plane, dx > 0 ? WALL_EAST : WALL_NORTH);
		}
		return flag(x + dx, y + dy, plane, dx < 0 ? WALL_EAST : WALL_NORTH);
	}

	boolean hasRegion(int x, int y)
	{
		return x >= 0 && y >= 0 && x < MAX_COORDINATE && y < MAX_COORDINATE && regions[region(x, y)] != null;
	}

	private boolean canStep(int x, int y, int plane, int dx, int dy)
	{
		if (dx > 0 || dy > 0)
		{
			return flag(x, y, plane, dx > 0 ? OPEN_EAST : OPEN_NORTH);
		}
		return flag(x + dx, y + dy, plane, dx < 0 ? OPEN_EAST : OPEN_NORTH);
	}

	private boolean flag(int x, int y, int plane, int flag)
	{
		if (plane < 0 || plane > MAX_PLANE || !hasRegion(x, y))
		{
			return false;
		}
		long[] words = regions[region(x, y)];
		int bit = (plane * TILES_PER_PLANE + (y & REGION_MASK) * (REGION_MASK + 1) + (x & REGION_MASK)) * FLAGS_PER_TILE + flag;
		int word = bit >>> 6;
		return word < words.length && (words[word] >>> (bit & 63) & 1L) != 0;
	}

	private static int region(int x, int y)
	{
		return (x >> REGION_BITS) << 8 | y >> REGION_BITS;
	}
}
