package com.cluehelper.transport;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.Value;

@Value
public class Catalog
{
	public static final Catalog EMPTY = new Catalog(List.of(), List.of());
	/** Baked by the test source set's DataBake from the tables Clue-Helper-Tools/tools/catalog/seed.py writes. */
	private static final String FILE = "catalog.json";

	List<CatalogRow> teleports;
	List<SpellbookChange> spellbookChanges;

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
