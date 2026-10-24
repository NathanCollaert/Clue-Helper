package com.cluehelper.route;

import com.cluehelper.transport.RequirementParser;
import com.cluehelper.transport.Requirements;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

/** Immutable once loaded; any thread. Costs are tiles, which are half-ticks when running. */
public final class WalkTable
{
	public static final int NO_NODE = -1;
	public static final int NO_PATH = -1;
	/** The spacing of each walk's waypoints, as the walk generator writes them. */
	public static final int WAYPOINT_TILES = 40;
	static final String DIRECTORY = "walk/";

	public static final int KIND_LANDING = 1;
	public static final int KIND_ORIGIN = 1 << 1;
	public static final int KIND_BANK = 1 << 2;
	public static final int KIND_TARGET = 1 << 3;
	public static final int KIND_POH = 1 << 4;
	public static final int KIND_CHANGE = 1 << 5;
	public static final int KIND_ENTRY = 1 << 6;
	/** Where a far walk row lands: only start legs walk from it, nothing walks to it. */
	public static final int KIND_EXIT = 1 << 7;
	private static final Map<String, Integer> KIND_BITS = Map.of("landing", KIND_LANDING, "origin", KIND_ORIGIN,
		"bank", KIND_BANK, "target", KIND_TARGET, "poh", KIND_POH, "change", KIND_CHANGE, "entry", KIND_ENTRY, "exit", KIND_EXIT);

	private WorldPoint[] tiles = new WorldPoint[0];
	private WorldPoint[] placed = new WorldPoint[0];
	private int[] kinds = new int[0];
	private final Map<WorldPoint, Integer> byTile = new HashMap<>();

	private int[] entryStart = {0};
	private int[] entryTo = new int[0];
	private int[] entryFull = new int[0];
	private int[] entryGateSet = new int[0];
	private int[] entryOpen = new int[0];
	private int[] reverseStart = {0};
	private int[] reverseEntry = new int[0];
	private int[] entryFrom = new int[0];
	private int[][] gateSets = new int[0][];
	private List<Requirements> gates = List.of();
	private Map<Integer, List<Bank>> banks = Map.of();

	private int[] rowOrigin = new int[0];
	private int[] rowDest = new int[0];
	private int[] rowCost = new int[0];
	private int[] rowGate = new int[0];
	/** Marks a point of {@link #entryWaypoints} as a row's origin or destination, above a packed tile's bits. */
	private static final int ROW_POINT = 1 << 30;
	/** Entry e's points are packed tiles waypoints[waypointStart[e]] up to waypoints[waypointStart[e + 1]]. */
	private int[] waypointStart = new int[1];
	private int[] waypoints = new int[0];
	private Map<Long, Exit> exits = Map.of();
	private Map<Long, Exit> rowObjects = Map.of();
	/** Each row object's id by the packed tiles its rows start from. */
	private Map<Integer, Set<Integer>> rowObjectsAt = Map.of();

	private WalkTable()
	{
	}

	public static WalkTable load() throws IOException
	{
		WalkTable table = new WalkTable();
		table.readNodes(lines("nodes.tsv"));
		table.readGates(lines("gates.tsv"));
		table.readTable(new Varints(bytes("table.bin")));
		table.readRows(new Varints(bytes("rows.bin")));
		table.readWaypoints(new Varints(bytes("waypoints.bin")));
		table.readBanks(lines("banks.tsv"));
		table.exits = rowActions(lines("exits.tsv"));
		table.rowObjects = rowActions(lines("row-objects.tsv"));
		table.rowObjectsAt = table.rowObjects.values().stream().collect(Collectors.groupingBy(exit -> pack(exit.getOrigin()),
			Collectors.mapping(Exit::getObjectId, Collectors.toUnmodifiableSet())));
		table.indexReverse();
		return table;
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

	public boolean is(int node, int kind)
	{
		return (kinds[node] & kind) != 0;
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

	/** The tile 40 tiles along the entry's walk (gates passing), or null for a shorter walk. */
	public WorldPoint entryWaypoint(int entry)
	{
		for (int i = waypointStart[entry]; i < waypointStart[entry + 1]; i++)
		{
			if ((waypoints[i] & ROW_POINT) == 0)
			{
				return unpack(waypoints[i]);
			}
		}
		return null;
	}

	/** The entry's walk in order, short of its end: a tile every 40 tiles, and each ladder, door or cave it takes at both ends. */
	public List<WorldPoint> entryWaypoints(int entry)
	{
		List<WorldPoint> along = new ArrayList<>();
		for (int i = waypointStart[entry]; i < waypointStart[entry + 1]; i++)
		{
			along.add(unpack(waypoints[i] & ~ROW_POINT));
		}
		return along;
	}

	/** The cost using always-open rows only, or {@link #NO_PATH}; meaningful only for a gated entry. */
	public int entryOpen(int entry)
	{
		return entryOpen[entry];
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

	/** Index = gate id − 1. */
	public List<Requirements> getGates()
	{
		return gates;
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

	/** 0 for an always-open row, else a gate id. */
	public int rowGate(int row)
	{
		return rowGate[row];
	}

	/** Null unless the row from {@code origin} to {@code destination} is an {@link Exit}. */
	public Exit exit(WorldPoint origin, WorldPoint destination)
	{
		return exits.get(exitKey(origin, destination));
	}

	/** The object clicked to take the row from {@code origin} to {@code destination} (a ladder, a door), or null. */
	public Exit rowObject(WorldPoint origin, WorldPoint destination)
	{
		return rowObjects.get(exitKey(origin, destination));
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

	public int entryCount()
	{
		return entryTo.length;
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

	private void indexReverse()
	{
		int nodes = tiles.length;
		entryFrom = new int[entryTo.length];
		reverseStart = new int[nodes + 1];
		for (int node = 0; node < nodes; node++)
		{
			for (int e = entryStart[node]; e < entryStart[node + 1]; e++)
			{
				entryFrom[e] = node;
				reverseStart[entryTo[e] + 1]++;
			}
		}
		for (int node = 0; node < nodes; node++)
		{
			reverseStart[node + 1] += reverseStart[node];
		}
		reverseEntry = new int[entryTo.length];
		int[] fill = Arrays.copyOf(reverseStart, nodes);
		for (int e = 0; e < entryTo.length; e++)
		{
			reverseEntry[fill[entryTo[e]]++] = e;
		}
	}

	private static Map<Long, Exit> rowActions(List<String[]> rows)
	{
		Map<Long, Exit> byRow = new HashMap<>();
		for (String[] c : rows)
		{
			WorldPoint origin = parseTile(c[0]);
			WorldPoint destination = parseTile(c[1]);
			int objectId = c[4].isEmpty() ? -1 : Integer.parseInt(c[4]);
			byRow.putIfAbsent(exitKey(origin, destination), new Exit(origin, destination, c[2], c[3], objectId));
		}
		return byRow;
	}

	private static long exitKey(WorldPoint origin, WorldPoint destination)
	{
		return (long) pack(origin) << 32 | pack(destination) & 0xFFFFFFFFL;
	}

	private static int pack(WorldPoint tile)
	{
		return tile.getPlane() << 28 | tile.getY() << 14 | tile.getX();
	}

	private static WorldPoint parseTile(String cell)
	{
		String[] parts = cell.split(" ");
		return new WorldPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
	}

	static WorldPoint unpack(int packed)
	{
		return new WorldPoint(packed & 0x3FFF, packed >>> 14 & 0x3FFF, packed >>> 28);
	}

	private void readNodes(List<String[]> rows)
	{
		int count = rows.size();
		tiles = new WorldPoint[count];
		placed = new WorldPoint[count];
		kinds = new int[count];
		for (String[] c : rows)
		{
			int id = Integer.parseInt(c[0]);
			tiles[id] = new WorldPoint(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]));
			placed[id] = c[4].isEmpty() ? null : new WorldPoint(Integer.parseInt(c[4]), Integer.parseInt(c[5]), Integer.parseInt(c[6]));
			for (String kind : c[7].split(","))
			{
				kinds[id] |= KIND_BITS.getOrDefault(kind, 0);
			}
			byTile.putIfAbsent(tiles[id], id);
		}
	}

	private void readGates(List<String[]> rows)
	{
		RequirementParser parser = new RequirementParser();
		List<Requirements> parsed = new ArrayList<>();
		for (String[] c : rows)
		{
			Map<String, String> cells = new HashMap<>();
			cells.put(RequirementParser.SKILLS, cell(c, 1));
			cells.put(RequirementParser.QUESTS, cell(c, 2));
			cells.put(RequirementParser.VARBITS, cell(c, 3));
			cells.put(RequirementParser.VARPS, cell(c, 4));
			cells.put(RequirementParser.ITEMS, cell(c, 5));
			parsed.add(parser.parse(cells));
		}
		gates = List.copyOf(parsed);
	}

	private void readBanks(List<String[]> rows)
	{
		RequirementParser parser = new RequirementParser();
		Map<Integer, List<Bank>> byNode = new HashMap<>();
		for (String[] c : rows)
		{
			String[] xyz = c[0].split(" ");
			int node = node(new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
			if (node == NO_NODE)
			{
				throw new IllegalStateException("banks.tsv: no node at " + c[0]);
			}
			Map<String, String> cells = new HashMap<>();
			cells.put(RequirementParser.MEMBERS, cell(c, 3));
			cells.put(RequirementParser.SKILLS, cell(c, 4));
			cells.put(RequirementParser.QUESTS, cell(c, 5));
			cells.put(RequirementParser.VARBITS, cell(c, 6));
			cells.put(RequirementParser.VARPS, cell(c, 7));
			Bank bank = new Bank(c[1], c[2], parser.parse(cells));
			byNode.computeIfAbsent(node, n -> new ArrayList<>()).add(bank);
		}
		banks = byNode;
	}

	private void readTable(Varints in)
	{
		int nodes = in.next();
		if (nodes != tiles.length)
		{
			throw new IllegalStateException("table.bin has " + nodes + " nodes, nodes.tsv " + tiles.length);
		}
		gateSets = new int[in.next()][];
		for (int s = 0; s < gateSets.length; s++)
		{
			gateSets[s] = new int[in.next()];
			for (int g = 0; g < gateSets[s].length; g++)
			{
				gateSets[s][g] = in.next();
			}
		}
		IntBuffer to = new IntBuffer(), full = new IntBuffer(), set = new IntBuffer(), open = new IntBuffer();
		entryStart = new int[nodes + 1];
		for (int node = 0; node < nodes; node++)
		{
			entryStart[node] = to.size;
			int count = in.next();
			int previous = 0;
			for (int i = 0; i < count; i++)
			{
				previous += in.next();
				to.add(previous);
				full.add(in.next());
				int gateSet = in.next();
				set.add(gateSet);
				open.add(gateSet == 0 ? NO_PATH : in.next() - 1);
			}
		}
		entryStart[nodes] = to.size;
		entryTo = to.toArray();
		entryFull = full.toArray();
		entryGateSet = set.toArray();
		entryOpen = open.toArray();
	}

	/** Stored relative to the point before, the first to the entry's start node; kept here as absolute packed tiles. */
	private void readWaypoints(Varints in)
	{
		waypointStart = new int[entryTo.length + 1];
		IntBuffer points = new IntBuffer();
		int entry = 0;
		for (int node = 0; node < tiles.length; node++)
		{
			WorldPoint from = placed[node];
			for (; entry < entryStart[node + 1]; entry++)
			{
				waypointStart[entry] = points.size;
				int count = in.next();
				int x = from == null ? 0 : from.getX();
				int y = from == null ? 0 : from.getY();
				for (int k = 0; k < count; k++)
				{
					x += unzigzag(in.next());
					int value = in.next();
					y += unzigzag(value >>> 3);
					points.add((value & 1) * ROW_POINT | (value >> 1 & 3) << 28 | y << 14 | x);
				}
			}
		}
		waypointStart[entry] = points.size;
		waypoints = points.toArray();
	}

	private static int unzigzag(int value)
	{
		return value >>> 1 ^ -(value & 1);
	}

	private void readRows(Varints in)
	{
		int count = in.next();
		rowOrigin = new int[count];
		rowDest = new int[count];
		rowCost = new int[count];
		rowGate = new int[count];
		for (int i = 0; i < count; i++)
		{
			rowOrigin[i] = in.next();
			rowDest[i] = in.next();
			rowCost[i] = in.next();
			rowGate[i] = in.next();
		}
	}

	private static String cell(String[] cells, int index)
	{
		return index < cells.length ? cells[index] : "";
	}

	private static InputStream open(String name) throws IOException
	{
		InputStream in = WalkTable.class.getResourceAsStream(DIRECTORY + name);
		if (in == null)
		{
			throw new IOException("missing resource " + DIRECTORY + name);
		}
		return in;
	}

	private static List<String[]> lines(String name) throws IOException
	{
		List<String[]> rows = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(open(name), StandardCharsets.UTF_8)))
		{
			boolean header = true;
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				if (header)
				{
					header = false;
					continue;
				}
				rows.add(line.split("\t", -1));
			}
		}
		return rows;
	}

	private static byte[] bytes(String name) throws IOException
	{
		try (InputStream in = open(name))
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	private static final class Varints
	{
		private final byte[] data;
		private int at;

		Varints(byte[] data)
		{
			this.data = data;
		}

		int next()
		{
			int value = 0;
			for (int shift = 0; ; shift += 7)
			{
				if (at >= data.length)
				{
					throw new IllegalStateException("walk data ends early");
				}
				int b = data[at++];
				value |= (b & 0x7F) << shift;
				if ((b & 0x80) == 0)
				{
					return value;
				}
			}
		}
	}

	private static final class IntBuffer
	{
		int[] data = new int[1024];
		int size;

		void add(int value)
		{
			if (size == data.length)
			{
				data = Arrays.copyOf(data, size * 2);
			}
			data[size++] = value;
		}

		int[] toArray()
		{
			return Arrays.copyOf(data, size);
		}
	}
}
