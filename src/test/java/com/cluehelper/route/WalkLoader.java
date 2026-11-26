package com.cluehelper.route;

import com.cluehelper.transport.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Parses the walk generator's tables ({@code src/test/resources/com/cluehelper/route/walk}) into what
 * {@link WalkTable#load} reads: {@code walk.json}, by the table's field names, and {@code columns.bin}, its int columns
 * in the order the table reads them. {@code DataBake} writes them; {@code BakedDataTest} fails while they are stale.
 */
public final class WalkLoader
{
	public static final Path JSON = Paths.get("src/main/resources/com/cluehelper/route/walk", WalkTable.JSON);
	public static final Path COLUMNS = Paths.get("src/main/resources/com/cluehelper/route/walk", WalkTable.COLUMNS);
	private static final Path SOURCES = Paths.get("src/test/resources/com/cluehelper/route/walk");
	private static final List<String> GATE_COLUMNS = List.of(RequirementParser.SKILLS, RequirementParser.QUESTS,
		RequirementParser.VARBITS, RequirementParser.VARPS, RequirementParser.ITEMS);
	private static final int FIRST_GATE_COLUMN = 1;
	private static final List<String> BANK_COLUMNS = List.of(RequirementParser.MEMBERS, RequirementParser.SKILLS,
		RequirementParser.QUESTS, RequirementParser.VARBITS, RequirementParser.VARPS, RequirementParser.ITEMS);
	private static final int FIRST_BANK_COLUMN = 2;
	/** The option of the webs' rows: their gate is the one {@link SceneDoors} opens webs by. */
	private static final String SLASH = "Slash";

	private final RequirementParser parser = new RequirementParser();
	private WorldPoint[] tiles;
	private WorldPoint[] placed;
	private final Map<WorldPoint, Integer> byTile = new HashMap<>();
	private int[][] gateSets;
	private int[] entryStart;
	private int[] entryTo;
	private int[] entryFull;
	private int[] entryGateSet;
	private int[] entryOpen;
	private int[] entryAlternative;
	private int[] entryAlternativeGateSet;
	private int[] reverseStart;
	private int[] reverseEntry;
	private int[] entryFrom;
	private int[] rowOrigin;
	private int[] rowDest;
	private int[] rowCost;
	private int[] rowGate;

	@Value
	public static class Baked
	{
		Map<String, Object> json;
		/** Uncompressed: each column its length, then its values, as big-endian ints. */
		byte[] columns;
	}

	private WalkLoader()
	{
	}

	public static Baked load() throws IOException
	{
		return new WalkLoader().bake();
	}

	/** The walk gates, parsed as the bake parses them. */
	public static List<Requirements> gates() throws IOException
	{
		WalkLoader loader = new WalkLoader();
		return loader.requirements(rows("gates.tsv"), FIRST_GATE_COLUMN, GATE_COLUMNS);
	}

	private Baked bake() throws IOException
	{
		readNodes(rows("nodes.tsv"));
		readTable(new Varints(Files.readAllBytes(SOURCES.resolve("table.bin"))));
		readRows(new Varints(Files.readAllBytes(SOURCES.resolve("rows.bin"))));
		indexReverse();
		List<Requirements> gates = requirements(rows("gates.tsv"), FIRST_GATE_COLUMN, GATE_COLUMNS);
		Map<Long, Exit> exits = exits(rows("exits.tsv"));
		Map<Long, List<Exit>> rowObjects = rowObjects(rows("row-objects.tsv"));
		Map<Integer, List<Bank>> banks = banks(rows("banks.tsv"));
		List<Requirements> watchedRequirements = new ArrayList<>(gates);
		banks.values().forEach(atNode -> atNode.forEach(bank -> watchedRequirements.add(bank.getRequirements())));

		Map<String, Object> json = new LinkedHashMap<>();
		json.put("tiles", tiles);
		json.put("placed", placed);
		json.put("gateSets", gateSets);
		json.put("gates", gates);
		json.put("banks", banks);
		json.put("rowsByOrigin", rowsByOrigin());
		json.put("rowIsExit", rowIsExit(exits));
		json.put("exits", exits);
		json.put("rowObjects", rowObjects);
		json.put("rowObjectsAt", rowObjectsAt(rowObjects));
		json.put("slashGate", slashGate(rowObjects));
		Map<VarCheck.Kind, Set<Integer>> watched = watchedVars(watchedRequirements);
		json.put("watchedVarbits", watched.get(VarCheck.Kind.VARBIT));
		json.put("watchedVarps", watched.get(VarCheck.Kind.VARP));
		return new Baked(json, columns(entryStart, entryTo, entryFull, entryGateSet, entryOpen, entryAlternative,
			entryAlternativeGateSet, reverseStart, reverseEntry, entryFrom, rowOrigin, rowDest, rowCost, rowGate));
	}

	private static byte[] columns(int[]... columns) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			for (int[] column : columns)
			{
				out.writeInt(column.length);
				for (int value : column)
				{
					out.writeInt(value);
				}
			}
		}
		return bytes.toByteArray();
	}

	private void readNodes(List<String[]> rows)
	{
		int count = rows.size();
		tiles = new WorldPoint[count];
		placed = new WorldPoint[count];
		for (String[] c : rows)
		{
			int id = Integer.parseInt(c[0]);
			tiles[id] = new WorldPoint(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]));
			placed[id] = c[4].isEmpty() ? null : new WorldPoint(Integer.parseInt(c[4]), Integer.parseInt(c[5]), Integer.parseInt(c[6]));
			byTile.putIfAbsent(tiles[id], id);
		}
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
		IntList to = new IntList(), full = new IntList(), set = new IntList(), open = new IntList();
		IntList alternative = new IntList(), alternativeSet = new IntList();
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
				int fullCost = in.next();
				full.add(fullCost);
				int gateSet = in.next();
				set.add(gateSet);
				open.add(gateSet == 0 ? WalkTable.NO_PATH : in.next() - 1);
				int alternativeGateSet = gateSet == 0 ? 0 : in.next();
				alternativeSet.add(alternativeGateSet);
				alternative.add(alternativeGateSet == 0 ? WalkTable.NO_PATH : fullCost + in.next());
			}
		}
		entryStart[nodes] = to.size;
		entryTo = to.toArray();
		entryFull = full.toArray();
		entryGateSet = set.toArray();
		entryOpen = open.toArray();
		entryAlternative = alternative.toArray();
		entryAlternativeGateSet = alternativeSet.toArray();
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

	private Map<Integer, int[]> rowsByOrigin()
	{
		Map<Integer, IntList> byOrigin = new TreeMap<>();
		for (int row = 0; row < rowOrigin.length; row++)
		{
			byOrigin.computeIfAbsent(rowOrigin[row], tile -> new IntList()).add(row);
		}
		Map<Integer, int[]> rows = new TreeMap<>();
		byOrigin.forEach((tile, list) -> rows.put(tile, list.toArray()));
		return rows;
	}

	private boolean[] rowIsExit(Map<Long, Exit> exits)
	{
		boolean[] isExit = new boolean[rowOrigin.length];
		for (int row = 0; row < rowOrigin.length; row++)
		{
			isExit[row] = exits.containsKey(WalkTable.exitKey(rowOrigin[row], rowDest[row]));
		}
		return isExit;
	}

	private Map<Integer, List<Bank>> banks(List<String[]> rows)
	{
		List<Requirements> requirements = requirements(rows, FIRST_BANK_COLUMN, BANK_COLUMNS);
		Map<Integer, List<Bank>> byNode = new TreeMap<>();
		for (int i = 0; i < rows.size(); i++)
		{
			String[] c = rows.get(i);
			Integer node = byTile.get(parseTile(c[0]));
			if (node == null)
			{
				throw new IllegalStateException("banks.tsv: no node at " + c[0]);
			}
			byNode.computeIfAbsent(node, n -> new ArrayList<>()).add(new Bank(c[1], requirements.get(i)));
		}
		return byNode;
	}

	/** The first row's action stays the exit's; a later row's object is another form of it. */
	private static Map<Long, Exit> exits(List<String[]> rows)
	{
		Map<Long, Exit> byRow = new TreeMap<>();
		for (String[] c : rows)
		{
			Exit row = rowAction(c, Set.of());
			byRow.merge(key(row), row, (first, later) ->
			{
				if (later.getObjectId() < 0 || later.getObjectId() == first.getObjectId())
				{
					return first;
				}
				Set<Integer> forms = new TreeSet<>(first.getOtherForms());
				forms.add(later.getObjectId());
				return new Exit(first.getOrigin(), first.getDestination(), first.getOption(), first.getTarget(),
					first.getObjectId(), forms, first.getChat());
			});
		}
		return byRow;
	}

	private static Map<Long, List<Exit>> rowObjects(List<String[]> rows)
	{
		Map<Long, List<Exit>> byTiles = new TreeMap<>();
		for (String[] c : rows)
		{
			Exit row = rowAction(c, Set.of());
			byTiles.computeIfAbsent(key(row), key -> new ArrayList<>()).add(row);
		}
		return byTiles;
	}

	private static Map<Integer, Set<Integer>> rowObjectsAt(Map<Long, List<Exit>> rowObjects)
	{
		Map<Integer, Set<Integer>> at = new TreeMap<>();
		rowObjects.values().forEach(sameTiles -> sameTiles.forEach(row ->
			at.computeIfAbsent(WalkTable.pack(row.getOrigin()), tile -> new TreeSet<>()).add(row.getObjectId())));
		return at;
	}

	private int slashGate(Map<Long, List<Exit>> rowObjects)
	{
		Set<Integer> gates = new TreeSet<>();
		for (int row = 0; row < rowOrigin.length; row++)
		{
			boolean web = rowObjects.getOrDefault(WalkTable.exitKey(rowOrigin[row], rowDest[row]), List.of()).stream()
				.anyMatch(object -> SLASH.equals(object.getOption()));
			if (web)
			{
				gates.add(rowGate[row]);
			}
		}
		if (gates.size() != 1 || gates.contains(0))
		{
			throw new IllegalStateException("the webs' rows should share one gate, not " + gates);
		}
		return gates.iterator().next();
	}

	/** Every var a gate reads, directly or as an item clause's var branch, by kind. */
	private static Map<VarCheck.Kind, Set<Integer>> watchedVars(List<Requirements> requirements)
	{
		Map<VarCheck.Kind, Set<Integer>> watched = new EnumMap<>(VarCheck.Kind.class);
		for (VarCheck.Kind kind : VarCheck.Kind.values())
		{
			watched.put(kind, new TreeSet<>());
		}
		for (Requirements gate : requirements)
		{
			List<VarCheck> checks = new ArrayList<>(gate.getVars());
			gate.getItems().forEach(clause -> clause.getBranches().stream().filter(ItemClause.Branch::isVar)
				.forEach(branch -> checks.add(branch.getVar())));
			checks.forEach(check -> watched.get(check.getKind()).add(check.getId()));
		}
		return watched;
	}

	private static long key(Exit row)
	{
		return WalkTable.exitKey(WalkTable.pack(row.getOrigin()), WalkTable.pack(row.getDestination()));
	}

	private static Exit rowAction(String[] c, Set<Integer> otherForms)
	{
		int objectId = c[4].isEmpty() ? -1 : Integer.parseInt(c[4]);
		return new Exit(parseTile(c[0]), parseTile(c[1]), c[2], c[3], objectId, otherForms, c.length > 5 ? c[5] : "");
	}

	private static WorldPoint parseTile(String cell)
	{
		String[] parts = cell.split(" ");
		return new WorldPoint(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
	}

	/** The requirement columns from {@code first} on, one per key; a short row leaves the rest empty. */
	private List<Requirements> requirements(List<String[]> rows, int first, List<String> keys)
	{
		List<Requirements> parsed = new ArrayList<>();
		for (String[] cells : rows)
		{
			Map<String, String> byKey = new HashMap<>();
			for (int i = 0; i < keys.size(); i++)
			{
				byKey.put(keys.get(i), first + i < cells.length ? cells[first + i] : "");
			}
			parsed.add(parser.parse(byKey));
		}
		return parsed;
	}

	/** The tab-separated rows after the header; blank and # lines left out. */
	private static List<String[]> rows(String table) throws IOException
	{
		List<String[]> rows = new ArrayList<>();
		boolean header = true;
		for (String line : Files.readAllLines(SOURCES.resolve(table), StandardCharsets.UTF_8))
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
		return rows;
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
}
