package com.cluehelper.transport;

import com.cluehelper.route.WalkTable;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** The shipped JSON must be what the source tables parse to today, and must read back to the same values. */
public class BakedDataTest
{
	private static final String STALE = " is out of date with its source tables: run gradlew bakeData";
	private final Gson gson = RuneLiteAPI.GSON;

	@Test
	public void catalogIsBakedFromTheCurrentTables() throws IOException
	{
		assertEquals(DataBake.CATALOG + STALE, CatalogLoader.load(), CatalogData.read(gson));
	}

	@Test
	public void walkRequirementsAreBakedFromTheCurrentTables() throws IOException
	{
		assertEquals(DataBake.WALK_REQUIREMENTS + STALE, DataBake.walkRequirements().get("gates"), WalkTable.load(gson).getGates());
	}

	@Test
	public void tableValuesMatchTheirGamevalNames() throws IOException
	{
		assertEquals(DataBake.TABLES + STALE, DataBake.tablesWithValues(), Files.readAllLines(DataBake.TABLES, StandardCharsets.UTF_8));
	}
}
