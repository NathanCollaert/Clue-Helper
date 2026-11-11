package com.cluehelper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.*;
import lombok.Value;
import static java.util.stream.Collectors.*;

/**
 * Small lookup tables kept as data in {@code tables.tsv}, one row per entry: table, key, then cells. A cell that names a
 * gameval constant carries its value after {@code =}, written by {@code gradlew bakeData}; {@code BakedDataTest} fails
 * when one no longer matches its name. A table that is missing or misspelled fails the class's loading.
 */
public final class Tables
{
	private static final String FILE = "tables.tsv";
	private static final Map<String, List<Row>> TABLES = load();

	@Value
	public static class Row
	{
		String key;
		List<String> cells;

		public int id(int cell)
		{
			return Tables.id(cells.get(cell));
		}

		public int[] ids()
		{
			return cells.stream().mapToInt(Tables::id).toArray();
		}

		public String text()
		{
			return cells.get(0);
		}
	}

	private Tables()
	{
	}

	public static List<Row> rows(String table)
	{
		List<Row> rows = TABLES.get(table);
		if (rows == null)
		{
			throw new IllegalArgumentException(FILE + " has no table " + table);
		}
		return rows;
	}

	/** Every id of the table, row by row. */
	public static int[] ids(String table)
	{
		return rows(table).stream().flatMapToInt(row -> IntStream.of(row.ids())).toArray();
	}

	public static Set<Integer> idSet(String table)
	{
		return Arrays.stream(ids(table)).boxed().collect(toUnmodifiableSet());
	}

	/** The rows' keys, in file order. */
	public static List<String> keys(String table)
	{
		return rows(table).stream().map(Row::getKey).collect(toUnmodifiableList());
	}

	public static <V> Map<String, V> byKey(String table, Function<Row, V> value)
	{
		return byKey(table, Function.identity(), value);
	}

	/** Each row's key, read as a {@code K} (an enum's {@code valueOf}), to its value. */
	public static <K, V> Map<K, V> byKey(String table, Function<String, K> key, Function<Row, V> value)
	{
		return rows(table).stream().collect(toUnmodifiableMap(row -> key.apply(row.getKey()), value));
	}

	/** A gameval cell's value after its name, or a plain number. */
	private static int id(String cell)
	{
		return Integer.parseInt(cell.substring(cell.indexOf('=') + 1));
	}

	private static Map<String, List<Row>> load()
	{
		try (InputStream in = Tables.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				throw new IllegalStateException(FILE + " is missing");
			}
			return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.map(line -> line.split("\t", -1))
				.collect(groupingBy(cells -> cells[0], LinkedHashMap::new, mapping(
					cells -> new Row(cells[1], List.of(Arrays.copyOfRange(cells, 2, cells.length))), toUnmodifiableList())));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("can't read " + FILE, e);
		}
	}
}
