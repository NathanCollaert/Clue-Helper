package com.cluehelper.step;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Hand-written click targets and walk row objects against the cache's names and options. Copied ids went wrong
 * silently three times (an ivy that was a corpse, a ladder top that was its bottom). Needs the local cache dump, which
 * isn't committed: without it the test is skipped.
 */
public class StepDataCacheTest
{
	private static final Path DUMP = Path.of("tools/cache/dump/2026-10-18");
	private static final Path SKILL_STEPS = Path.of("src/main/resources/com/cluehelper/step/steps/skill.json");
	private static final Path ROW_OBJECTS = Path.of("src/main/resources/com/cluehelper/route/walk/row-objects.tsv");
	private static final String SHERLOCK = "skill:SHERLOCK:";
	/** An item used on the object ("Use Rope -> Rock") is no option of the object's own. */
	private static final String USE_ITEM_ON = "Use";

	private static Map<Integer, JsonObject> objects;
	private static Map<Integer, JsonObject> npcs;

	@BeforeClass
	public static void loadDump() throws IOException
	{
		assumeTrue("no cache dump at " + DUMP.toAbsolutePath(), Files.isDirectory(DUMP));
		objects = byId(DUMP.resolve("objects.json"));
		npcs = byId(DUMP.resolve("npcs.json"));
	}

	@Test
	public void sherlockPlacesNameTheirClickTargetsAsTheCacheDoes() throws IOException
	{
		List<String> failures = new ArrayList<>();
		JsonObject steps = read(SKILL_STEPS).getAsJsonObject().getAsJsonObject("steps");
		for (Map.Entry<String, JsonElement> step : steps.entrySet())
		{
			if (!step.getKey().startsWith(SHERLOCK))
			{
				continue;
			}
			JsonObject data = step.getValue().getAsJsonObject();
			if (!data.has("places"))
			{
				failures.add(step.getKey() + ": no places, and not marked anywhere (places [])");
				continue;
			}
			for (JsonElement place : data.getAsJsonArray("places"))
			{
				checkPlace(step.getKey(), place.getAsJsonObject(), failures);
			}
		}
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	@Test
	public void walkRowObjectsAreTheCachesObjects() throws IOException
	{
		List<String> failures = new ArrayList<>();
		List<String> lines = Files.readAllLines(ROW_OBJECTS, StandardCharsets.UTF_8);
		for (String line : lines.subList(1, lines.size()))
		{
			String[] cells = line.split("\t");
			String option = cells[2];
			String name = cells[3];
			int id = Integer.parseInt(cells[4]);
			boolean found = family(objects, id, "configChangeDest").stream().anyMatch(o -> named(o, name, option))
				|| family(npcs, id, "configs").stream().anyMatch(n -> named(n, name, option))
				|| USE_ITEM_ON.equals(option) && family(objects, id, "configChangeDest").stream().anyMatch(o -> name.equals(name(o)));
			if (!found)
			{
				failures.add(cells[0] + ": " + option + " " + name + " " + id);
			}
		}
		assertTrue(failures.size() + " row objects:\n" + String.join("\n", failures), failures.isEmpty());
	}

	private static void checkPlace(String key, JsonObject place, List<String> failures)
	{
		JsonArray objectIds = place.has("objects") ? place.getAsJsonArray("objects") : new JsonArray();
		JsonArray npcIds = place.has("npcs") ? place.getAsJsonArray("npcs") : new JsonArray();
		if (objectIds.size() + npcIds.size() == 0)
		{
			return;
		}
		String at = key + " at " + place.get("tile");
		if (!place.has("click"))
		{
			failures.add(at + ": click targets without click [option, name]");
			return;
		}
		JsonArray click = place.getAsJsonArray("click");
		String option = click.size() < 2 ? null : click.get(0).getAsString();
		String name = click.get(click.size() - 1).getAsString();
		objectIds.forEach(id -> checkId(at, "object", family(objects, id.getAsInt(), "configChangeDest"), id, option, name, failures));
		npcIds.forEach(id -> checkId(at, "npc", family(npcs, id.getAsInt(), "configs"), id, option, name, failures));
	}

	private static void checkId(String at, String kind, List<JsonObject> family, JsonElement id, String option, String name,
		List<String> failures)
	{
		if (family.stream().noneMatch(entry -> named(entry, name, option)))
		{
			failures.add(at + ": " + kind + " " + id + " is " + family.stream().map(StepDataCacheTest::name).collect(Collectors.toList())
				+ ", not " + name + " with " + option);
		}
	}

	private static boolean named(JsonObject entry, String name, String option)
	{
		return name.equals(name(entry)) && (option == null || ops(entry).contains(option));
	}

	private static String name(JsonObject entry)
	{
		return entry.get("name").isJsonNull() ? null : entry.get("name").getAsString();
	}

	private static List<String> ops(JsonObject entry)
	{
		List<String> ops = new ArrayList<>();
		if (entry.has("ops") && entry.get("ops").isJsonArray())
		{
			for (JsonElement op : entry.getAsJsonArray("ops"))
			{
				if (op.isJsonObject() && op.getAsJsonObject().has("text") && !op.getAsJsonObject().get("text").isJsonNull())
				{
					ops.add(op.getAsJsonObject().get("text").getAsString());
				}
			}
		}
		return ops;
	}

	/** The entry and its multiloc children: which one shows depends on a var. */
	private static List<JsonObject> family(Map<Integer, JsonObject> table, int id, String children)
	{
		List<JsonObject> family = new ArrayList<>();
		JsonObject parent = table.get(id);
		if (parent == null)
		{
			return family;
		}
		family.add(parent);
		if (parent.has(children) && parent.get(children).isJsonArray())
		{
			for (JsonElement child : parent.getAsJsonArray(children))
			{
				if (!child.isJsonNull() && table.containsKey(child.getAsInt()))
				{
					family.add(table.get(child.getAsInt()));
				}
			}
		}
		return family;
	}

	private static Map<Integer, JsonObject> byId(Path file) throws IOException
	{
		Map<Integer, JsonObject> byId = new HashMap<>();
		for (JsonElement entry : read(file).getAsJsonArray())
		{
			byId.put(entry.getAsJsonObject().get("id").getAsInt(), entry.getAsJsonObject());
		}
		return byId;
	}

	private static JsonElement read(Path file) throws IOException
	{
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			return new JsonParser().parse(reader);
		}
	}
}
