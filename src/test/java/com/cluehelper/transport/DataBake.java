package com.cluehelper.transport;

import com.cluehelper.memory.AllotmentStages;
import com.cluehelper.player.RuneLoader;
import com.cluehelper.route.WalkLoader;
import com.cluehelper.step.StepLoader;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import net.runelite.http.api.RuneLiteAPI;

/**
 * Turns the source tables into the data the plugin loads, so the parsing never ships: the teleport catalog
 * ({@code catalog.json}), the walk data ({@code walk/walk.json} and {@code walk/columns.bin}, see {@link WalkLoader}),
 * the step data ({@code step/steps.json}, see {@link StepLoader}), the rune model ({@code player/runes.json}, see
 * {@link RuneLoader}), and in {@code tables.tsv} the gameval values, each written after its constant's name, and the
 * allotment growth table (see {@link AllotmentStages}). Run with {@code gradlew bakeData} after Clue-Helper-Tools regenerates the catalog,
 * the walk data or the step data; {@code BakedDataTest}
 * fails while the baked files are out of date.
 */
public final class DataBake
{
	static final Path CATALOG = Paths.get("src/main/resources/com/cluehelper/transport/catalog.json");
	static final Path TABLES = Paths.get("src/main/resources/com/cluehelper/tables.tsv");
	private static final Pattern GAMEVAL_CELL = Pattern.compile("((?:ItemID|VarbitID|VarPlayerID|InterfaceID|ObjectID|NpcID)(?:\\.\\w+)+?)(=-?\\d+)?");
	/** Sets iterate in a different order each run; sorted, a bake of unchanged tables writes the same file. */
	private static final JsonSerializer<Set<?>> SORTED_SETS = (set, type, context) ->
		context.serialize(set.stream().map(String::valueOf).sorted().toArray(String[]::new));
	static final Gson GSON = RuneLiteAPI.GSON.newBuilder().registerTypeHierarchyAdapter(Set.class, SORTED_SETS).create();

	private DataBake()
	{
	}

	public static void main(String[] args) throws IOException
	{
		Files.writeString(CATALOG, GSON.toJson(CatalogLoader.load()) + "\n", StandardCharsets.UTF_8);
		WalkLoader.Baked walk = WalkLoader.load();
		Files.writeString(WalkLoader.JSON, walkJson(walk), StandardCharsets.UTF_8);
		try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(WalkLoader.COLUMNS)))
		{
			out.write(walk.getColumns());
		}
		Files.writeString(StepLoader.JSON, stepsJson(), StandardCharsets.UTF_8);
		Files.writeString(RuneLoader.JSON, RuneLoader.json(GSON), StandardCharsets.UTF_8);
		Files.write(TABLES, tablesWithValues(), StandardCharsets.UTF_8);
		System.out.println("baked " + CATALOG + ", " + WalkLoader.JSON + ", " + WalkLoader.COLUMNS + ", " + StepLoader.JSON + ", " + RuneLoader.JSON + " and " + TABLES);
	}

	static String walkJson(WalkLoader.Baked walk)
	{
		return GSON.toJson(walk.getJson()) + "\n";
	}

	static String stepsJson() throws IOException
	{
		return StepLoader.json(StepLoader.load());
	}

	/** The tables file with every gameval cell's value written after its name, and the allotment table from core. */
	static List<String> tablesWithValues() throws IOException
	{
		List<String> allotmentRows = AllotmentStages.rows();
		List<String> lines = new ArrayList<>();
		for (String line : Files.readAllLines(TABLES, StandardCharsets.UTF_8))
		{
			if (!line.startsWith(AllotmentStages.TABLE + "\t"))
			{
				lines.add(line.startsWith("#") ? line : Arrays.stream(line.split("\t", -1)).map(DataBake::withValue)
					.collect(Collectors.joining("\t")));
			}
			else
			{
				lines.addAll(allotmentRows);
				allotmentRows = List.of();
			}
		}
		return lines;
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
}
