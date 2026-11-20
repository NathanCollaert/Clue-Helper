package com.cluehelper.transport;

import com.cluehelper.route.WalkLoader;
import com.cluehelper.step.StepLoader;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;
import static org.junit.Assert.*;

/** The shipped data must be what the source tables parse to today. */
public class BakedDataTest
{
	private static final String STALE = " is out of date with its source tables: run gradlew bakeData";
	private final Gson gson = RuneLiteAPI.GSON;

	@Test
	public void catalogIsBakedFromTheCurrentTables() throws IOException
	{
		assertEquals(DataBake.CATALOG + STALE, CatalogLoader.load(), Catalog.read(gson));
	}

	@Test
	public void walkDataIsBakedFromTheCurrentTables() throws IOException
	{
		WalkLoader.Baked walk = WalkLoader.load();
		assertEquals(WalkLoader.JSON + STALE, DataBake.walkJson(walk), Files.readString(WalkLoader.JSON, StandardCharsets.UTF_8));
		try (InputStream baked = new GZIPInputStream(Files.newInputStream(WalkLoader.COLUMNS)))
		{
			assertArrayEquals(WalkLoader.COLUMNS + STALE, walk.getColumns(), baked.readAllBytes());
		}
	}

	@Test
	public void stepDataIsBakedFromTheCurrentStepFiles() throws IOException
	{
		assertEquals(StepLoader.JSON + STALE, DataBake.stepsJson(), Files.readString(StepLoader.JSON, StandardCharsets.UTF_8));
	}

	@Test
	public void tableValuesMatchTheirGamevalNames() throws IOException
	{
		assertEquals(DataBake.TABLES + STALE, DataBake.tablesWithValues(), Files.readAllLines(DataBake.TABLES, StandardCharsets.UTF_8));
	}
}
