package com.cluehelper.transport;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.Value;

@Value
public class Catalog
{
	public static final Catalog EMPTY = new Catalog(List.of(), List.of(), Set.of(), Map.of(), Set.of(), Set.of(), Set.of());
	/** Baked by the test source set's DataBake from the tables Clue-Helper-Tools/tools/catalog/seed.py writes. */
	private static final String FILE = "catalog.json";

	List<CatalogRow> teleports;
	List<SpellbookChange> spellbookChanges;
	/** The items the rows ask for: which banked ones count. */
	Set<Integer> items;
	/** The rows' and the walk gates' items by name, for the no-route notes. */
	Map<Integer, String> itemNames;
	/** The varbits and varps the rows check, their items' waivers too. */
	Set<Integer> watchedVarbits;
	Set<Integer> watchedVarps;
	/** The house's objects the rows use (a nexus, an altar, a jewellery box), looked for in the house. */
	Set<Integer> housePieces;

	public static Catalog read(Gson gson)
	{
		try (InputStream in = Catalog.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				throw new IllegalStateException(FILE + " is missing");
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Catalog.class);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read " + FILE, e);
		}
	}
}
