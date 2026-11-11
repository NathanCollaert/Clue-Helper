package com.cluehelper.transport;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import net.runelite.http.api.RuneLiteAPI;

/**
 * Turns the source tables into the data the plugin loads, so the parsing never ships: the teleport catalog
 * ({@code catalog.json}), the walk gates' and banks' requirements ({@code walk/requirements.json}) and the gameval
 * values in {@code tables.tsv}, each written after its constant's name. Run with
 * {@code gradlew bakeData} after Clue-Helper-Tools regenerates the catalog or the walk data; {@code BakedDataTest}
 * fails while the baked files are out of date.
 */
public final class DataBake
{
	static final Path CATALOG = Paths.get("src/main/resources/com/cluehelper/transport/catalog.json");
	static final Path WALK_REQUIREMENTS = Paths.get("src/main/resources/com/cluehelper/route/walk/requirements.json");
	private static final Path GATES = Paths.get("src/test/resources/com/cluehelper/route/walk/gates.tsv");
	private static final Path BANKS = Paths.get("src/main/resources/com/cluehelper/route/walk/banks.tsv");
	private static final List<String> GATE_COLUMNS = List.of(RequirementParser.SKILLS, RequirementParser.QUESTS,
		RequirementParser.VARBITS, RequirementParser.VARPS, RequirementParser.ITEMS);
	private static final int FIRST_GATE_COLUMN = 1;
	private static final List<String> BANK_COLUMNS = List.of(RequirementParser.MEMBERS, RequirementParser.SKILLS,
		RequirementParser.QUESTS, RequirementParser.VARBITS, RequirementParser.VARPS);
	private static final int FIRST_BANK_COLUMN = 3;
	static final Path TABLES = Paths.get("src/main/resources/com/cluehelper/tables.tsv");
	private static final Pattern GAMEVAL_CELL = Pattern.compile("((?:ItemID|VarbitID|VarPlayerID|InterfaceID|ObjectID|NpcID)(?:\\.\\w+)+?)(=-?\\d+)?");
	/** Sets iterate in a different order each run; sorted, a bake of unchanged tables writes the same file. */
	private static final JsonSerializer<Set<?>> SORTED_SETS = (set, type, context) ->
		context.serialize(set.stream().map(String::valueOf).sorted().toArray(String[]::new));

	private DataBake()
	{
	}

	public static void main(String[] args) throws IOException
	{
		Gson gson = RuneLiteAPI.GSON.newBuilder().registerTypeHierarchyAdapter(Set.class, SORTED_SETS).create();
		write(CATALOG, gson.toJson(CatalogLoader.load()));
		write(WALK_REQUIREMENTS, gson.toJson(walkRequirements()));
		Files.write(TABLES, tablesWithValues(), StandardCharsets.UTF_8);
		System.out.println("baked " + CATALOG + ", " + WALK_REQUIREMENTS + " and " + TABLES);
	}

	/** The tables file with every gameval cell's value written after its name. */
	static List<String> tablesWithValues() throws IOException
	{
		return Files.readAllLines(TABLES, StandardCharsets.UTF_8).stream()
			.map(line -> line.startsWith("#") ? line : Arrays.stream(line.split("\t", -1)).map(DataBake::withValue)
				.collect(Collectors.joining("\t")))
			.collect(Collectors.toList());
	}

	private static String withValue(String cell)
	{
		Matcher gameval = GAMEVAL_CELL.matcher(cell);
		return gameval.matches() ? gameval.group(1) + "=" + value(gameval.group(1)) : cell;
	}

	/**
	 * A gameval constant by its source name, such as InterfaceID.Toplevel.STONE3. ObjectID inherits half its constants
	 * from a package-private class, which only an accessible field can read.
	 */
	private static int value(String name)
	{
		int field = name.lastIndexOf('.');
		String owner = "net.runelite.api.gameval." + name.substring(0, field).replace('.', '$');
		try
		{
			Field constant = Class.forName(owner).getField(name.substring(field + 1));
			constant.setAccessible(true);
			return constant.getInt(null);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalArgumentException("no gameval constant " + name, e);
		}
	}

	/** In the shape of {@code WalkTable.BakedRequirements}. */
	static Map<String, List<Requirements>> walkRequirements() throws IOException
	{
		RequirementParser parser = new RequirementParser();
		Map<String, List<Requirements>> baked = new LinkedHashMap<>();
		baked.put("gates", requirements(parser, rows(GATES), FIRST_GATE_COLUMN, GATE_COLUMNS));
		baked.put("banks", requirements(parser, rows(BANKS), FIRST_BANK_COLUMN, BANK_COLUMNS));
		return baked;
	}

	/** The requirement columns from {@code first} on, one per key; a short row leaves the rest empty. */
	private static List<Requirements> requirements(RequirementParser parser, List<String[]> rows, int first, List<String> keys)
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

	/** The tab-separated rows after the header; blank and # lines left out, as the walk tables are read. */
	private static List<String[]> rows(Path table) throws IOException
	{
		List<String[]> rows = new ArrayList<>();
		boolean header = true;
		for (String line : Files.readAllLines(table, StandardCharsets.UTF_8))
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

	private static void write(Path file, String json) throws IOException
	{
		Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
	}
}
