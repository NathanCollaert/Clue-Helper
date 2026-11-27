package com.cluehelper.step;

import com.cluehelper.guide.*;
import com.cluehelper.hotcold.SpotParts;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.*;
import net.runelite.client.plugins.cluescrolls.clues.item.*;
import net.runelite.http.api.RuneLiteAPI;
import static java.util.stream.Collectors.*;

/**
 * Reads the step files Clue-Helper-Tools/tools/steps/generate.py writes ({@code src/test/resources/com/cluehelper/step/steps},
 * one per clue type, tiles as {@code [x, y, plane]}, boxes as {@code [west, south, east, north, plane]}) into the one
 * {@code steps.json} {@link StepIndex#read} takes as it is, each core clue's entry with the stages ({@link StageParts}) and
 * acts ({@link ActParts}) the plugin built from it at play before, and the other facts it derived from the entry or core's
 * clue. The fields only these read ({@link StepSource}) stay out of it. {@code DataBake} writes it; {@code BakedDataTest}
 * fails while it is stale.
 */
public final class StepLoader
{
	public static final Path JSON = Paths.get("src/main/resources/com/cluehelper/step/steps.json");
	private static final Path SOURCES = Paths.get("src/test/resources/com/cluehelper/step/steps");
	private static final List<String> FILES = List.of("anagram", "cipher", "coordinate", "cryptic", "emote", "fairyring", "falo",
		"hotcold", "map", "music", "skill");
	private static final int BOX_SIDES = 5;
	/** The tools a planting task's seed is listed beside: the seed goes in first. */
	private static final Set<Integer> PATCH_TOOLS = Set.of(ItemID.RAKE, ItemID.SPADE, ItemID.DIBBER);
	/** NPCs whose name changes with a quest (the doorman is Ghommal, then Laidee Gnonock): named at play. */
	private static final Set<Integer> NAMED_BY_QUEST = Set.of(NpcID.WARGUILD_GHOMMAL);
	/** Reads the source files' arrays, and the objects the baked parts are written as. */
	private static final Gson SOURCE_GSON = new GsonBuilder()
		.registerTypeAdapter(WorldPoint.class, (JsonDeserializer<WorldPoint>) (json, type, context) -> point(json))
		.registerTypeAdapter(StepIndex.Box.class, (JsonDeserializer<StepIndex.Box>) (json, type, context) -> box(json))
		.registerTypeAdapter(Skill.class, (JsonDeserializer<Skill>) (json, type, context) -> Skill.valueOf(json.getAsString().toUpperCase(Locale.ROOT)))
		.create();
	/**
	 * Sets and maps iterate in a different order each run; sorted, a bake of unchanged files writes the same file. A map
	 * keyed by tile is written as {@code [key, value]} pairs, which Gson reads back into a map.
	 */
	private static final JsonSerializer<Set<?>> SORTED_SETS = (set, type, context) -> context.serialize(set.stream().sorted().toArray());
	private static final JsonSerializer<Map<?, ?>> SORTED_MAPS = (map, type, context) ->
	{
		List<Map.Entry<?, ?>> entries = map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
			.collect(toList());
		if (entries.stream().allMatch(entry -> entry.getKey() instanceof String))
		{
			JsonObject object = new JsonObject();
			entries.forEach(entry -> object.add((String) entry.getKey(), context.serialize(entry.getValue())));
			return object;
		}
		JsonArray pairs = new JsonArray();
		for (Map.Entry<?, ?> entry : entries)
		{
			JsonArray pair = new JsonArray();
			pair.add(context.serialize(entry.getKey()));
			pair.add(context.serialize(entry.getValue()));
			pairs.add(pair);
		}
		return pairs;
	};
	private static final Gson GSON = RuneLiteAPI.GSON.newBuilder().registerTypeHierarchyAdapter(Set.class, SORTED_SETS)
		.registerTypeHierarchyAdapter(Map.class, SORTED_MAPS).create();

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
		addSpotEnemies(steps, spots);
		Map<String, StepSource.Entry> sources = new TreeMap<>();
		steps.entrySet().forEach(step -> sources.put(step.getKey(), SOURCE_GSON.fromJson(step.getValue(), StepSource.Entry.class)));
		JsonObject index = new JsonObject();
		index.add("steps", sorted(steps));
		index.add("hotColdSpots", GSON.toJsonTree(SpotParts.of(cacheSpots(spots))));
		Map<String, ClueScroll> clues = coreClues();
		StepIndex shaped = SOURCE_GSON.fromJson(index, StepIndex.class);
		clues.forEach((key, clue) -> entryOf(index, key).add("stages", GSON.toJsonTree(StageParts.of(shaped.entry(key), sources.get(key), clue))));
		StepIndex staged = SOURCE_GSON.fromJson(index, StepIndex.class);
		StepResolver resolver = new StepResolver(() -> staged);
		clues.forEach((key, clue) -> addActs(entryOf(index, key), resolver.resolve(clue, plugin()), sources.get(key)));
		clues.forEach((key, clue) -> entryOf(index, key).add("amounts", GSON.toJsonTree(withHandAmounts(amounts(clue), entryOf(index, key)))));
		StepIndex acted = SOURCE_GSON.fromJson(index, StepIndex.class);
		clues.forEach((key, clue) -> addPart(entryOf(index, key), acted.entry(key), clue));
		sources.forEach((key, source) -> addEntryFacts(entryOf(index, key), source));
		clues.forEach((key, clue) -> entryOf(index, key).add("plantItems", GSON.toJsonTree(plantItems(shaped.entry(key), clue))));
		return SOURCE_GSON.fromJson(index, StepIndex.class);
	}

	/** A master cryptic's stage as one part of a three-step, its NPC named as core names it unless a quest renames it. */
	private static void addPart(JsonObject json, StepIndex.Entry entry, ClueScroll clue)
	{
		if (clue instanceof CrypticClue && "master".equals(entry.getTier()) && !entry.getTargets().isEmpty())
		{
			List<Integer> npcs = entry.getTargets().get(0).getNpcs();
			boolean nameIsLive = !npcs.isEmpty() && NAMED_BY_QUEST.contains(npcs.get(0));
			json.add("part", GSON.toJsonTree(StageParts.part(entry, ((CrypticClue) clue).getNpc(plugin()), nameIsLive)));
		}
	}

	/** What the entry's own fields give: the acts before the task and the monsters met. */
	private static void addEntryFacts(JsonObject json, StepSource.Entry source)
	{
		json.add("beforeActs", GSON.toJsonTree(source.getBefore().stream().map(ActParts::beforeAct).collect(toList())));
		json.add("enemies", GSON.toJsonTree(source.getCombat().stream().flatMap(combat -> combat.getNpcs().stream()).collect(toSet())));
	}

	/** A planting task's items but the rake, which only clears the patch: the seed first, then the tools it goes in with. */
	private static List<Integer> plantItems(StepIndex.Entry entry, ClueScroll clue)
	{
		if (!entry.isPlants())
		{
			return List.of();
		}
		return CoreClueReader.itemRequirements(clue).stream().map(StepLoader::itemId).filter(id -> id != ItemID.RAKE)
			.sorted(Comparator.comparing((Integer id) -> PATCH_TOOLS.contains(id)).thenComparing(Comparator.naturalOrder())).collect(toList());
	}

	private static int itemId(ItemRequirement requirement)
	{
		if (!(requirement instanceof SingleItemRequirement || requirement instanceof MultipleOfItemRequirement))
		{
			throw new IllegalStateException("a planting task's item is one item: " + requirement);
		}
		return field(requirement, "itemId");
	}

	/**
	 * A master hot/cold step fights whatever its dig spot holds, and which spot is only known once dug: every spot's enemy is
	 * the step's.
	 */
	private static void addSpotEnemies(JsonObject steps, JsonObject spots)
	{
		JsonArray enemies = new JsonArray();
		spots.entrySet().stream().map(spot -> spot.getValue().getAsJsonObject().getAsJsonArray("combat")).filter(Objects::nonNull)
			.forEach(combat -> combat.forEach(enemy -> {
				if (!enemies.contains(enemy))
				{
					enemies.add(enemy);
				}
			}));
		steps.entrySet().stream().filter(step -> step.getKey().startsWith("hotcold:master:"))
			.forEach(step -> step.getValue().getAsJsonObject().add("combat", enemies));
	}

	/** Each step key's cache rows, as the step files hold them: the plugin itself never reads them. */
	public static Map<String, List<CacheRow>> cacheRows() throws IOException
	{
		Map<String, List<CacheRow>> byKey = new TreeMap<>();
		for (String file : FILES)
		{
			try (Reader in = Files.newBufferedReader(SOURCES.resolve(file + ".json"), StandardCharsets.UTF_8))
			{
				new JsonParser().parse(in).getAsJsonObject().getAsJsonObject("steps").entrySet().forEach(step ->
				{
					JsonElement rows = step.getValue().getAsJsonObject().get("rows");
					byKey.put(step.getKey(), rows == null ? List.of() : List.of(SOURCE_GSON.fromJson(rows, CacheRow[].class)));
				});
			}
		}
		return byKey;
	}

	/** The cache's hot/cold spots by core's name, as the hot/cold step file holds them. */
	public static Map<String, CacheSpot> cacheSpots() throws IOException
	{
		try (Reader in = Files.newBufferedReader(SOURCES.resolve("hotcold.json"), StandardCharsets.UTF_8))
		{
			return cacheSpots(new JsonParser().parse(in).getAsJsonObject().getAsJsonObject("hotColdSpots"));
		}
	}

	private static Map<String, CacheSpot> cacheSpots(JsonObject spots)
	{
		Map<String, CacheSpot> byName = new TreeMap<>();
		spots.entrySet().forEach(spot -> byName.put(spot.getKey(), SOURCE_GSON.fromJson(spot.getValue(), CacheSpot.class)));
		return byName;
	}

	/** A hot/cold spot's cache row. */
	@lombok.Getter
	public static final class CacheSpot
	{
		private int row;
		private String name;
		/** The row's centre as x, y, plane; null in older generated data. */
		private int[] tile;
	}

	/** The JSON {@link StepIndex#read} takes. */
	public static String json(StepIndex index)
	{
		return GSON.toJson(index) + "\n";
	}

	private static JsonObject entryOf(JsonObject index, String key)
	{
		return index.getAsJsonObject("steps").getAsJsonObject(key);
	}

	private static void addActs(JsonObject entry, Step step, StepSource.Entry source)
	{
		List<SubStep> acts = ActParts.baked(step, source);
		SubStep main = ActParts.bakedMain(step, source);
		List<SubStep> all = new ArrayList<>(acts);
		all.add(main);
		all.forEach(act -> requireBakeable(step, act));
		entry.add("acts", GSON.toJsonTree(acts));
		entry.add("mainAct", GSON.toJsonTree(main));
		entry.add("arriveNpcs", GSON.toJsonTree(ActParts.arriveNpcs(step, source)));
		entry.addProperty("keyFromNpc", ActParts.isKeyFromNpc(step));
		entry.add("prayer", GSON.toJsonTree(ActParts.prayerToActivate(step)));
	}

	/** An act holding a part of the game Gson can't write (core's item requirements, a teleport) must stay built at play. */
	private static void requireBakeable(Step step, SubStep act)
	{
		boolean bakeable = act.getEmptied().isEmpty() && act.getStage() == null && act.getRow() == null && act.getChange() == null
			&& act.getExit() == null && act.getWayIn() == null && act.getBook() == null;
		if (!bakeable)
		{
			throw new IllegalStateException(step.getKey() + "'s act holds what only the game has: " + act);
		}
	}

	/** The hand data's own amounts win: the parts an item is made from (6 black dragon leather for a d'hide set). */
	private static Map<Integer, Integer> withHandAmounts(Map<Integer, Integer> core, JsonObject entry)
	{
		Map<Integer, Integer> amounts = new TreeMap<>(core);
		if (entry.has("amounts"))
		{
			entry.getAsJsonObject("amounts").entrySet().forEach(hand -> amounts.put(Integer.parseInt(hand.getKey()), hand.getValue().getAsInt()));
		}
		return amounts;
	}

	/** The item ids core asks for more than one of, and how many: a MultipleOfItemRequirement, alone or as one of a choice. */
	private static Map<Integer, Integer> amounts(ClueScroll clue)
	{
		Map<Integer, Integer> amounts = new TreeMap<>();
		for (ItemRequirement requirement : CoreClueReader.itemRequirements(clue))
		{
			for (ItemRequirement single : requirement instanceof AnyRequirementCollection ? children((AnyRequirementCollection) requirement)
				: List.of(requirement))
			{
				if (single instanceof MultipleOfItemRequirement && quantity(single) > 1)
				{
					amounts.merge(field(single, "itemId"), quantity(single), Math::max);
				}
			}
		}
		return amounts;
	}

	private static int quantity(ItemRequirement multiple)
	{
		return field(multiple, "quantity");
	}

	@SuppressWarnings("unchecked")
	private static List<ItemRequirement> children(AnyRequirementCollection any)
	{
		return Arrays.asList((ItemRequirement[]) fieldValue(any, "requirements"));
	}

	private static int field(ItemRequirement requirement, String name)
	{
		return (int) fieldValue(requirement, name);
	}

	/** Core's requirements keep their parts private. */
	private static Object fieldValue(Object owner, String name)
	{
		try
		{
			java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(owner);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("core's " + owner.getClass().getSimpleName() + " has no " + name, e);
		}
	}

	/** Core's clues by step key, as the resolver keys them. */
	private static Map<String, ClueScroll> coreClues()
	{
		Map<String, ClueScroll> clues = new TreeMap<>();
		try
		{
			for (ClueScroll clue : CoreClues.steps().values())
			{
				clues.put(CoreClueReader.facts(clue, plugin()).getKey(), clue);
			}
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't read core's clues", e);
		}
		return clues;
	}

	private static ClueScrollPlugin plugin()
	{
		try
		{
			return CoreClues.plugin();
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException("can't make core's clue plugin", e);
		}
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

	private static WorldPoint point(JsonElement json)
	{
		if (json.isJsonObject())
		{
			JsonObject point = json.getAsJsonObject();
			return new WorldPoint(point.get("x").getAsInt(), point.get("y").getAsInt(), point.get("plane").getAsInt());
		}
		JsonArray xyz = json.getAsJsonArray();
		return new WorldPoint(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
	}

	private static StepIndex.Box box(JsonElement json)
	{
		if (json.isJsonObject())
		{
			return new Gson().fromJson(json, StepIndex.Box.class);
		}
		JsonArray sides = json.getAsJsonArray();
		if (sides.size() != BOX_SIDES)
		{
			throw new JsonParseException("a box is west, south, east, north, plane: " + sides);
		}
		return new StepIndex.Box(sides.get(0).getAsInt(), sides.get(1).getAsInt(), sides.get(2).getAsInt(), sides.get(3).getAsInt(),
			sides.get(4).getAsInt());
	}
}
