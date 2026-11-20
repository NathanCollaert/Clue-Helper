package com.cluehelper.step;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * Reads the step files Clue-Helper-Tools/tools/steps/generate.py writes ({@code src/test/resources/com/cluehelper/step/steps},
 * one per clue type, tiles as {@code [x, y, plane]}, boxes as {@code [west, south, east, north, plane]}) into the one
 * {@code steps.json} {@link StepIndex#read} takes as it is. {@code DataBake} writes it; {@code BakedDataTest} fails
 * while it is stale.
 */
public final class StepLoader
{
	public static final Path JSON = Paths.get("src/main/resources/com/cluehelper/step/steps.json");
	private static final Path SOURCES = Paths.get("src/test/resources/com/cluehelper/step/steps");
	private static final List<String> FILES = List.of("anagram", "cipher", "coordinate", "cryptic", "emote", "fairyring", "falo",
		"hotcold", "map", "music", "skill");
	private static final int BOX_SIDES = 5;
	private static final Gson SOURCE_GSON = new GsonBuilder()
		.registerTypeAdapter(WorldPoint.class, (JsonDeserializer<WorldPoint>) (json, type, context) -> point(json.getAsJsonArray()))
		.registerTypeAdapter(StepIndex.Box.class, (JsonDeserializer<StepIndex.Box>) (json, type, context) -> box(json.getAsJsonArray()))
		.registerTypeAdapter(Skill.class, (JsonDeserializer<Skill>) (json, type, context) -> Skill.valueOf(json.getAsString().toUpperCase(Locale.ROOT)))
		.create();

	private StepLoader()
	{
	}

	/** The source files as the plugin reads them, with the entries' fields in the shape {@link StepIndex} holds them. */
	public static StepIndex load() throws IOException
	{
		JsonObject steps = new JsonObject();
		JsonObject spots = new JsonObject();
		for (String file : FILES)
		{
			JsonObject source;
			try (Reader in = Files.newBufferedReader(SOURCES.resolve(file + ".json"), StandardCharsets.UTF_8))
			{
				source = new JsonParser().parse(in).getAsJsonObject();
			}
			source.getAsJsonObject("steps").entrySet().forEach(step -> steps.add(step.getKey(), flattened(step.getValue().getAsJsonObject())));
			if (source.has("hotColdSpots"))
			{
				source.getAsJsonObject("hotColdSpots").entrySet().forEach(spot -> spots.add(spot.getKey(), spot.getValue()));
			}
		}
		JsonObject index = new JsonObject();
		index.add("steps", sorted(steps));
		index.add("hotColdSpots", sorted(spots));
		return SOURCE_GSON.fromJson(index, StepIndex.class);
	}

	/** The JSON {@link StepIndex#read} takes. */
	public static String json(Gson gson, StepIndex index)
	{
		return gson.toJson(index) + "\n";
	}

	/** Getters that unwrapped a nested source field read a flat one. */
	private static JsonObject flattened(JsonObject entry)
	{
		JsonObject requirements = entry.getAsJsonObject("requirements");
		if (requirements != null && requirements.has("stats"))
		{
			entry.add("levels", requirements.get("stats"));
		}
		JsonObject reach = entry.getAsJsonObject("reach");
		if (reach != null)
		{
			entry.add("reach", reach.get("text"));
		}
		JsonObject area = entry.has("area") && entry.get("area").isJsonObject() ? entry.getAsJsonObject("area") : null;
		if (area != null)
		{
			JsonArray tiles = new JsonArray();
			tiles.add(area.get("tile"));
			if (area.has("also"))
			{
				tiles.addAll(area.getAsJsonArray("also"));
			}
			area.add("tiles", tiles);
		}
		for (JsonElement target : entry.has("targets") ? entry.getAsJsonArray("targets") : new JsonArray())
		{
			JsonObject object = target.getAsJsonObject();
			JsonArray locs = new JsonArray();
			for (String loc : List.of("loc", "fallbackLoc"))
			{
				if (object.has(loc))
				{
					locs.add(object.get(loc));
				}
			}
			object.add("locs", locs);
		}
		JsonObject entryWay = entry.getAsJsonObject("entry");
		JsonObject pickUp = entryWay == null ? null : entryWay.getAsJsonObject("pickUp");
		if (pickUp != null && pickUp.has("spawns"))
		{
			pickUp.add("spawnTiles", pickUp.get("spawns"));
		}
		return entry;
	}

	private static JsonObject sorted(JsonObject object)
	{
		JsonObject sorted = new JsonObject();
		object.keySet().stream().sorted().forEach(key -> sorted.add(key, object.get(key)));
		return sorted;
	}

	private static WorldPoint point(JsonArray xyz)
	{
		return new WorldPoint(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
	}

	private static StepIndex.Box box(JsonArray sides)
	{
		if (sides.size() != BOX_SIDES)
		{
			throw new JsonParseException("a box is west, south, east, north, plane: " + sides);
		}
		return new StepIndex.Box(sides.get(0).getAsInt(), sides.get(1).getAsInt(), sides.get(2).getAsInt(), sides.get(3).getAsInt(),
			sides.get(4).getAsInt());
	}
}
