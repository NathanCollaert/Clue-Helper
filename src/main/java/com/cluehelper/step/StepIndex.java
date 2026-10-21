package com.cluehelper.step;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Gson fills this from step-index.json (tools/cache/c5_step_index.py): field names are its JSON keys. */
@Slf4j
public final class StepIndex
{
	static final String RESOURCE = "step-index.json";

	private String dump;
	private Map<String, Entry> steps = Map.of();
	private Map<String, Spot> hotColdSpots = Map.of();

	public static StepIndex read(Gson gson)
	{
		try (InputStream in = StepIndex.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} is missing: every step resolves as unknown", RESOURCE);
				return empty();
			}
			return read(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		catch (IOException | JsonParseException e)
		{
			log.warn("{} is unreadable: every step resolves as unknown", RESOURCE, e);
			return empty();
		}
	}

	static StepIndex read(Gson gson, Reader reader)
	{
		StepIndex index = gson.fromJson(reader, StepIndex.class);
		return index == null ? empty() : index;
	}

	public static StepIndex empty()
	{
		return new StepIndex();
	}

	public Entry entry(String key)
	{
		return steps == null ? null : steps.get(key);
	}

	public Map<String, Entry> getSteps()
	{
		return steps == null ? Map.of() : steps;
	}

	public Map<String, Spot> getHotColdSpots()
	{
		return hotColdSpots == null ? Map.of() : hotColdSpots;
	}

	/** The cache dump the data was generated from, e.g. "2026-10-18". */
	public String getDump()
	{
		return dump;
	}

	public static final class Entry
	{
		private String tier;
		private List<CacheRow> rows;
		private List<int[]> tiles;

		static Entry of(String tier, List<CacheRow> rows)
		{
			Entry entry = new Entry();
			entry.tier = tier;
			entry.rows = List.copyOf(rows);
			entry.tiles = List.of();
			return entry;
		}

		public String getTier()
		{
			return tier;
		}

		public List<CacheRow> getRows()
		{
			return rows == null ? List.of() : rows;
		}

		/** Each tile as x, y, plane. */
		public List<int[]> getTiles()
		{
			return tiles == null ? List.of() : tiles;
		}
	}

	public static final class Spot
	{
		private int row;
		private String name;
		private int[] tile;

		/** The row's centre as x, y, plane; null in an index generated before B19. */
		public int[] getTile()
		{
			return tile;
		}

		public int getRow()
		{
			return row;
		}

		public String getName()
		{
			return name;
		}
	}
}
