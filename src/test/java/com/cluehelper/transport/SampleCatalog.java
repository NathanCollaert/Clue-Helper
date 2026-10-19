package com.cluehelper.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;

/**
 * 27 real Shortest Path rows ({@code tools/catalog/b08_sample.py}) for the dev client's {@code ::ch-player}
 * until B09 ships the catalog and its loader.
 */
public final class SampleCatalog
{
	private static final String RESOURCE = "/b08/sample-catalog.tsv";

	private SampleCatalog()
	{
	}

	public static List<CatalogRow> load()
	{
		RequirementParser parser = new RequirementParser();
		List<CatalogRow> rows = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			SampleCatalog.class.getResourceAsStream(RESOURCE), StandardCharsets.UTF_8)))
		{
			String[] header = in.readLine().split("\t", -1);
			String line;
			while ((line = in.readLine()) != null)
			{
				String[] values = line.split("\t", -1);
				Map<String, String> cells = new HashMap<>();
				for (int i = 0; i < header.length && i < values.length; i++)
				{
					cells.put(header[i], values[i]);
				}
				rows.add(new CatalogRow(cells.get("Display info"), tile(cells.get("Destination")),
					CatalogRules.fairyRingStaff(parser.parse(cells))));
			}
		}
		catch (Exception e)
		{
			throw new IllegalStateException("can't read " + RESOURCE, e);
		}
		return rows;
	}

	private static WorldPoint tile(String cell)
	{
		if (cell == null || cell.isEmpty())
		{
			return null;
		}
		String[] xyz = cell.split(" ");
		return new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
	}
}
