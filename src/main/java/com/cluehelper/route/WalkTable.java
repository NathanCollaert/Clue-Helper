package com.cluehelper.route;

import com.cluehelper.transport.Requirements;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.zip.GZIPInputStream;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable once loaded; any thread. Costs are tiles, which are half-ticks when running. Baked from the walk
 * generator's tables by the test-side {@code WalkLoader}: {@link #JSON} holds the fields Gson reads, {@link #COLUMNS}
 * the transient int columns.
 */
public final class WalkTable
{
	public static final int NO_NODE = -1;
	public static final int NO_PATH = -1;
	static final String DIRECTORY = "walk/";
	public static final String JSON = "walk.json";
	public static final String COLUMNS = "columns.bin";
	private static final int[] NO_ROWS = new int[0];

	WorldPoint[] tiles;
	WorldPoint[] placed;
	int[][] gateSets;
	/** Index = gate id − 1. */
	@Getter
	List<Requirements> gates;
	Map<Integer, List<Bank>> banks;
	Map<Integer, int[]> rowsByOrigin;
	boolean[] rowIsExit;
	Map<Long, Exit> exits;
	Map<Long, List<Exit>> rowObjects;
	/** Each row object's id by the packed tiles its rows start from. */
	Map<Integer, Set<Integer>> rowObjectsAt;
	/** The gate of the webs' rows: one passes it by carrying something that slashes. */
	@Getter
	int slashGate;
	/** The vars the gates read, so the player model keeps them current. */
	@Getter
	List<Integer> watchedVarbits;
	@Getter
	List<Integer> watchedVarps;

	final transient Map<WorldPoint, Integer> byTile = new HashMap<>();
	transient int[] entryStart;
	transient int[] entryTo;
	transient int[] entryFull;
	transient int[] entryGateSet;
	transient int[] entryOpen;
	transient int[] entryAlternative;
	transient int[] entryAlternativeGateSet;
	transient int[] reverseStart;
	transient int[] reverseEntry;
	transient int[] entryFrom;
	transient int[] rowOrigin;
	transient int[] rowDest;
	transient int[] rowCost;
	transient int[] rowGate;

	private WalkTable()
	{
	}

	public static WalkTable load(Gson gson) throws IOException
	{
		WalkTable table;
		try (Reader in = new InputStreamReader(open(DIRECTORY + JSON), StandardCharsets.UTF_8))
		{
			table = gson.fromJson(in, WalkTable.class);
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(open(DIRECTORY + COLUMNS)))))
		{
			table.entryStart = ints(in);
			table.entryTo = ints(in);
			table.entryFull = ints(in);
			table.entryGateSet = ints(in);
			table.entryOpen = ints(in);
			table.entryAlternative = ints(in);
			table.entryAlternativeGateSet = ints(in);
			table.reverseStart = ints(in);
			table.reverseEntry = ints(in);
			table.entryFrom = ints(in);
			table.rowOrigin = ints(in);
			table.rowDest = ints(in);
			table.rowCost = ints(in);
			table.rowGate = ints(in);
		}
		for (int node = 0; node < table.tiles.length; node++)
		{
			table.byTile.putIfAbsent(table.tiles[node], node);
		}
		return table;
	}

	private static int[] ints(DataInputStream in) throws IOException
	{
		int[] column = new int[in.readInt()];
		for (int i = 0; i < column.length; i++)
		{
			column[i] = in.readInt();
		}
		return column;
	}

	public int nodeCount()
	{
		return tiles.length;
	}

	public WorldPoint tile(int node)
	{
		return tiles[node];
	}

	/** The walkable tile the node snaps to; null when none is within 2 tiles. */
	public WorldPoint placed(int node)
	{
		return placed[node];
	}

	public int node(WorldPoint tile)
	{
		return tile == null ? NO_NODE : byTile.getOrDefault(tile, NO_NODE);
	}

	public int entriesFrom(int node)
	{
		return entryStart[node];
	}

	public int entriesEnd(int node)
	{
		return entryStart[node + 1];
	}

	public int entryTo(int entry)
	{
		return entryTo[entry];
	}

	/** The cost when every gate on the path passes. */
	public int entryFull(int entry)
	{
		return entryFull[entry];
	}

	/** 0 when the path uses no gated row; else an index into {@link #gateSet(int)}. */
	public int entryGateSet(int entry)
	{
		return entryGateSet[entry];
	}

	/** The cost using always-open rows only, or {@link #NO_PATH}; meaningful only for a gated entry. */
	public int entryOpen(int entry)
	{
		return entryOpen[entry];
	}

	/**
	 * The cheapest cost passing none of {@link #entryGateSet}'s gates, or {@link #NO_PATH}; kept only when it uses gates
	 * of its own ({@link #entryAlternativeGateSet}) and beats {@link #entryOpen}. A place with two differently gated ways
	 * in stays reachable when the cheaper way's gates fail.
	 */
	public int entryAlternative(int entry)
	{
		return entryAlternative[entry];
	}

	/** 0 when the entry has no {@link #entryAlternative}; else an index into {@link #gateSet(int)}. */
	public int entryAlternativeGateSet(int entry)
	{
		return entryAlternativeGateSet[entry];
	}

	public int gateSetCount()
	{
		return gateSets.length;
	}

	/** Gate ids (1-based) used by entries with this gate set (1-based). */
	public int[] gateSet(int set)
	{
		return gateSets[set - 1];
	}

	public List<Bank> banks(int node)
	{
		return banks.getOrDefault(node, List.of());
	}

	public int rowCount()
	{
		return rowOrigin.length;
	}

	public WorldPoint rowOrigin(int row)
	{
		return unpack(rowOrigin[row]);
	}

	public WorldPoint rowDest(int row)
	{
		return unpack(rowDest[row]);
	}

	public int rowCost(int row)
	{
		return rowCost[row];
	}

	/** The row is an {@link Exit}: it leaves for a far landing (a cave, a dungeon's ladder). */
	boolean isExitRow(int row)
	{
		return rowIsExit[row];
	}

	/** The rows that start on the packed tile ({@link #pack}). */
	int[] rowsFrom(int tile)
	{
		return rowsByOrigin.getOrDefault(tile, NO_ROWS);
	}

	/** The packed tile the row lands on. */
	int rowDestTile(int row)
	{
		return rowDest[row];
	}

	/** 0 for an always-open row, else a gate id. */
	public int rowGate(int row)
	{
		return rowGate[row];
	}

	/**
	 * The exit a walk of {@code tiles} from {@code from} to {@code to} goes through, the one with the least walking either
	 * side of it (the whirlpool down to the Ancient Cavern); null when none fits.
	 */
	public Exit exitBetween(WorldPoint from, WorldPoint to, int tiles)
	{
		ToIntFunction<Exit> walked = exit -> from.distanceTo2D(exit.getOrigin()) + exit.getDestination().distanceTo2D(to);
		return exits.values().stream().filter(exit -> walked.applyAsInt(exit) <= tiles).min(Comparator.comparingInt(walked)).orElse(null);
	}

	/** Null unless the row from {@code origin} to {@code destination} is an {@link Exit}. */
	public Exit exit(WorldPoint origin, WorldPoint destination)
	{
		return exits.get(exitKey(origin, destination));
	}

	/**
	 * The objects a row from {@code origin} to {@code destination} is taken through (a ladder, a door); empty for none.
	 * Several rows can join the same tiles: the Observatory's climb is both its grapple's rocks and its rope.
	 */
	public List<Exit> rowObjects(WorldPoint origin, WorldPoint destination)
	{
		return rowObjects.getOrDefault(exitKey(origin, destination), List.of());
	}

	/** A row starts by {@code tile} (on it or next to it) through the object: the same id elsewhere says nothing. */
	public boolean isRowObjectAt(int objectId, WorldPoint tile)
	{
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (rowObjectsAt.getOrDefault(pack(tile.dx(dx).dy(dy)), Set.of()).contains(objectId))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Entries that walk into {@code node}: {@code reverseEntry(i)} for i in [reverseFrom, reverseEnd). */
	public int reverseFrom(int node)
	{
		return reverseStart[node];
	}

	public int reverseEnd(int node)
	{
		return reverseStart[node + 1];
	}

	public int reverseEntry(int index)
	{
		return reverseEntry[index];
	}

	public int entryFrom(int entry)
	{
		return entryFrom[entry];
	}

	private static long exitKey(WorldPoint origin, WorldPoint destination)
	{
		return exitKey(pack(origin), pack(destination));
	}

	static long exitKey(int origin, int destination)
	{
		return (long) origin << 32 | destination & 0xFFFFFFFFL;
	}

	static int pack(WorldPoint tile)
	{
		return pack(tile.getX(), tile.getY(), tile.getPlane());
	}

	static int pack(int x, int y, int plane)
	{
		return plane << 28 | y << 14 | x;
	}

	static WorldPoint unpack(int packed)
	{
		return new WorldPoint(packed & 0x3FFF, packed >>> 14 & 0x3FFF, packed >>> 28);
	}

	/** A resource of this package; missing is an error. */
	static InputStream open(String resource) throws IOException
	{
		InputStream in = WalkTable.class.getResourceAsStream(resource);
		if (in == null)
		{
			throw new IOException("missing resource " + resource);
		}
		return in;
	}
}
