package com.cluehelper.step;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import java.util.function.Predicate;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * Gson fills this from the generated step files (Clue-Helper-Tools/tools/steps/generate.py), one per clue type: field
 * names are their JSON keys. Fields the plugin doesn't use yet (outfits, requirement groups) stay in the files only.
 * Keys iterate in sorted order, which the golden routes' sample depends on.
 */
@Slf4j
public final class StepIndex
{
	static final String DIRECTORY = "steps/";
	private static final List<String> FILES = List.of("anagram", "cipher", "coordinate", "cryptic", "emote", "fairyring", "falo",
		"hotcold", "map", "music", "skill");

	@Getter
	private Map<String, Entry> steps = Map.of();
	@Getter
	private Map<String, Spot> hotColdSpots = Map.of();

	public static StepIndex read(Gson gson)
	{
		StepIndex all = new StepIndex();
		Map<String, Entry> steps = new HashMap<>();
		Map<String, Spot> spots = new HashMap<>();
		for (String file : FILES)
		{
			String resource = DIRECTORY + file + ".json";
			try (InputStream in = StepIndex.class.getResourceAsStream(resource))
			{
				if (in == null)
				{
					log.warn("{} is missing: its steps resolve as unknown", resource);
					continue;
				}
				StepIndex part = read(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
				steps.putAll(part.getSteps());
				spots.putAll(part.getHotColdSpots());
			}
			catch (IOException | JsonParseException e)
			{
				log.warn("{} is unreadable: its steps resolve as unknown", resource, e);
			}
		}
		all.steps = Collections.unmodifiableMap(new TreeMap<>(steps));
		all.hotColdSpots = Collections.unmodifiableMap(new TreeMap<>(spots));
		return all;
	}

	static StepIndex read(Gson gson, Reader reader)
	{
		Gson withSkills = gson.newBuilder()
			.registerTypeAdapter(Skill.class, (JsonDeserializer<Skill>) (json, type, context) -> skill(json.getAsString()))
			.create();
		StepIndex index = withSkills.fromJson(reader, StepIndex.class);
		return index == null ? empty() : index;
	}

	/** The cache spells skills "Fishing", the hand data "FISHING". */
	private static Skill skill(String name)
	{
		try
		{
			return Skill.valueOf(name.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new JsonParseException("unknown skill " + name, e);
		}
	}

	public static StepIndex empty()
	{
		return new StepIndex();
	}

	public Entry entry(String key)
	{
		return steps.get(key);
	}

	@Getter
	public static final class Entry
	{
		/** A key the data lacks: every getter answers empty. */
		public static final Entry NONE = new Entry();

		private String tier;
		private List<CacheRow> rows = List.of();
		/** Each tile as x, y, plane. */
		private List<int[]> tiles = List.of();
		private List<Target> targets = List.of();
		private List<Combat> combat = List.of();
		private Challenge challenge;
		private Music music;
		private Stash stash;
		/** Hand data: where a step with no tile (a kill task) is done. */
		private Area area;
		private int[] stand;
		private List<Requires> requires = List.of();
		/** Hand data: worn gear the talk needs, picked from what the player owns. */
		private List<Gear> gear = List.of();
		/** Hand data: where the step's NPC wanders far from core's tile (Wingstone across the desert); null for most. */
		private Wander wander;
		private WayIn entry;
		private Reach reach;
		private List<Place> places;
		/** Sherlock's items worn for the task (the lumberjack outfit), by core's names for them. */
		private List<String> wear = List.of();
		/** Core's items the task can do without (a rake for a weeded patch), by core's names for them. */
		private List<String> optional = List.of();
		/** Core's items the task does without from these skill levels on (a harpoon, fishing barehanded), by core's names. */
		private Map<String, List<Level>> optionalFrom = Map.of();
		/** Core's items the task does without once one of these varbits is set (a rake after buying auto-weed), by core's names. */
		private Map<String, List<Integer>> optionalWith = Map.of();
		/** What is done at the place before the task, in order: Alry's bird, then king worms for aerial fishing. */
		private List<Before> before = List.of();
		/** Once one of these is owned the task is done anywhere: a sacred eel needs only a knife, not Zul-Andra's spots. */
		private List<Integer> anywhereWith = List.of();
		/** The task travels to one of the player's planted spirit trees, picked in the tree's list. */
		private boolean toPlantedTree;
		/** The task plants a seed in a farming patch, cleared first of weeds or a crop. */
		private boolean plants;
		/** The step is in the dark, where core asks for no light source: the Genie's cave below Nardah. */
		private boolean light;
		/** Other items that do what one of core's items does (a vial of water and a cadantine for the unfinished potion). */
		private List<Alternative> alternatives = List.of();
		/** How the task's item is made from what is carried, step by step (clean the herb, then make the potion). */
		private List<Making> making = List.of();
		/** The spell the task casts (Lvl-5 Enchant), or null. */
		private Cast cast;
		/** The spellbook the task is done on (ARCEUUS for the Barrows tablet), or null for any. */
		private String book;
		/** The option the task picks in an interface (Barrows at the lectern), or null. */
		private Choose choose;
		private String floor;
		private String note;
		/** Quest steps: never guided. */
		private boolean hidden;
		@Getter(AccessLevel.NONE)
		private Prerequisites requirements;

		static Entry of(String tier, List<CacheRow> rows)
		{
			Entry entry = new Entry();
			entry.tier = tier;
			entry.rows = List.copyOf(rows);
			return entry;
		}

		/** A three-step: every part's rows, and what its unsolved parts need. */
		static Entry ofParts(String tier, List<CacheRow> rows, List<Entry> unsolved)
		{
			Entry entry = of(tier, rows);
			entry.requires = unsolved.stream().flatMap(part -> part.getRequires().stream()).collect(Collectors.toUnmodifiableList());
			entry.gear = unsolved.stream().flatMap(part -> part.getGear().stream()).collect(Collectors.toUnmodifiableList());
			return entry;
		}

		/** Hand data: a walkable tile beside a blocked target tile; null when the target is walkable. */
		public WorldPoint getStand()
		{
			return point(stand);
		}

		/** The needs the act wants worn, by name; gear for a way in is put on before going in instead. */
		public List<String> getWornNeeds()
		{
			List<String> names = new ArrayList<>();
			getGear().forEach(item -> names.add(item.getName()));
			if (entry == null)
			{
				wornRequires().forEach(need -> names.add(need.getName()));
			}
			return List.copyOf(names);
		}

		/** The requirements that are gear to wear. */
		public List<Requires> wornRequires()
		{
			return getRequires().stream().filter(Requires::isWear).collect(Collectors.toList());
		}

		/** The requirements the player still has to meet: none a finished quest waives. */
		public List<Requires> requiresFor(Predicate<Quest> finished)
		{
			return getRequires().stream().filter(need -> !need.isWaivedBy(finished)).collect(Collectors.toList());
		}

		/** The gear to wear the player still has to meet. */
		/** The requirements to wear going in, but those the player meets by what they carry (a talisman). */
		public List<Requires> wornRequiresFor(Predicate<Quest> finished, Set<Integer> carriedIds)
		{
			return requiresFor(finished).stream().filter(need -> need.isWear() && !need.isCarriedInstead(carriedIds))
				.collect(Collectors.toList());
		}

		/** The skill levels the task itself needs (Sherlock's 82 Fishing); boosts count. */
		public List<Level> getLevels()
		{
			return requirements == null ? List.of() : requirements.stats;
		}

		/** Hand data: how to get to a target no route reaches (a Sailing island); null for the rest. */
		public String getReach()
		{
			return reach == null ? null : reach.text;
		}

		public List<Place> getPlaces()
		{
			return places == null ? List.of() : places;
		}

		/** True when hand data says where the task is done; an empty list then means anywhere. */
		public boolean hasPlaces()
		{
			return places != null;
		}
	}

	/** A cache target: a tile, an NPC, an object, an NPC killed for a key before a search, or a kill. */
	@Getter
	public static final class Target
	{
		public enum Kind
		{
			@SerializedName("coord")
			COORD,
			@SerializedName("npc")
			NPC,
			@SerializedName("loc")
			LOC,
			/** Kill an NPC for a key, then search. */
			@SerializedName("key")
			KEY,
			@SerializedName("kill")
			KILL
		}

		private Kind kind;
		private int[] tile;
		private List<Integer> npcs = List.of();
		private Integer key;
		private int[] keyTile;
		private String text;
		/** An object's name when talking is all it does (the spirit tree); null otherwise. */
		private String talkTo;

		public WorldPoint getTile()
		{
			return point(tile);
		}

		public int getKey()
		{
			return key == null ? -1 : key;
		}

		/** Where the key's NPCs are killed. */
		public WorldPoint getKeyTile()
		{
			return point(keyTile);
		}
	}

	@Getter
	public static final class Combat
	{
		private String text;
		private List<Integer> npcs = List.of();
	}

	/** The puzzle or light box a challenge hands over; {@code box} is null for a question, whose answer core shows. */
	@Getter
	public static final class Challenge
	{
		private String box;
	}

	@Getter
	public static final class Music
	{
		private String track;
		/** Where the track unlocks. */
		private String unlock;
	}

	@Getter
	public static final class Stash
	{
		private int object;
		private int[] tile;

		public WorldPoint getTile()
		{
			return point(tile);
		}
	}

	@Getter
	public static final class Area
	{
		private int[] tile;
		private int radius;
		private String text;
		/** Other spawns the route may go to instead (green dragons outside the Wilderness), each as x, y, plane. */
		@Getter(AccessLevel.NONE)
		private List<int[]> also = List.of();

		public WorldPoint getTile()
		{
			return point(tile);
		}

		/** The main spawn first, then the others. */
		public List<WorldPoint> getTiles()
		{
			List<WorldPoint> tiles = new ArrayList<>();
			tiles.add(getTile());
			also.forEach(other -> tiles.add(point(other)));
			return List.copyOf(tiles);
		}
	}

	/**
	 * An NPC's wander range: the box it walks in, less a box inside it never enters (Lumbridge Castle's keep for Hans),
	 * and tiles across it that routes may end on.
	 */
	public static final class Wander
	{
		private int[] area;
		private int[] exclude;
		private List<int[]> tiles;
		/** Where to look, shown under the walk: "He wanders the desert between Nardah and the Agility Pyramid". */
		@Getter
		private String text;

		public boolean contains(WorldPoint at)
		{
			return WayIn.within(area, at) && (exclude == null || !WayIn.within(exclude, at));
		}

		/** West, south, east, north and plane of the box it never enters; null when there is none. */
		public int[] getExclude()
		{
			return exclude == null ? null : exclude.clone();
		}

		public int getWest()
		{
			return area[0];
		}

		public int getSouth()
		{
			return area[1];
		}

		public int getEast()
		{
			return area[2];
		}

		public int getNorth()
		{
			return area[3];
		}

		public int getPlane()
		{
			return area[4];
		}

		public List<WorldPoint> getTiles()
		{
			return tiles == null ? List.of() : tiles.stream().map(StepIndex::point).collect(Collectors.toUnmodifiableList());
		}
	}

	/** An NPC's option or a ground item to pick up before the task, done once one of {@code held} is carried or worn. */
	@Getter
	public static final class Before
	{
		private String text;
		private List<Integer> npcs = List.of();
		private String option;
		private int groundItem = -1;
		private List<Integer> held = List.of();
	}

	@Getter
	public static final class Level
	{
		private Skill skill;
		private int level;
	}

	/** The cache's requirements of the task; its items come from core instead. */
	private static final class Prerequisites
	{
		private List<Level> stats = List.of();
	}

	/** An interface's option, found by its text since its slot varies, and the button that confirms it. */
	@Getter
	public static final class Choose
	{
		private int root;
		private String text;
		private int confirm;
	}

	/** A spell cast on an item: its spellbook buttons in click order (a group, then the spell), and the items. */
	@Getter
	public static final class Cast
	{
		private List<Integer> widgets = List.of();
		private List<Integer> on = List.of();
	}

	/** One step of making: its text and the items used, each group any of its ids; the first group is what is worked on. */
	@Getter
	public static final class Making
	{
		private String text;
		private List<List<Integer>> items = List.of();
		/** The product to pick in the "How many do you wish to make?" box; -1 when the step has none. */
		private int make = -1;
	}

	/** Core's item {@code instead}, or one of each group of {@code all}. */
	@Getter
	public static final class Alternative
	{
		private int instead;
		/** What the replacement is called where it is listed as missing; null for an alternative to core's item. */
		private String name;
		private List<List<Integer>> all = List.of();
	}

	/** Worn gear adding up to at least {@code atLeast} of its bonus (Ghommal's +100 melee strength). */
	@Getter
	public static final class Gear
	{
		private String bonus;
		private int atLeast;
		private String name;

		public GearBonus getBonus()
		{
			return GearBonus.valueOf(bonus);
		}
	}

	/** Items the way in needs; any one of {@link #getIds()} does. */
	@Getter
	public static final class Requires
	{
		private List<Integer> ids = List.of();
		private String name;
		/** Worn to go in (a diving helmet), not just carried (a spade). */
		private boolean wear;
		/** Of a worn requirement, the items that do as well just carried (a talisman beside the tiara); empty for none. */
		@Getter(AccessLevel.NONE)
		private List<Integer> carried = List.of();
		/** Who drops it for a player owning none (the Mercenary Captain's Metal key); null when it can't be got there. */
		private From from;
		/** A quest whose finishing makes it unneeded (Monkey Madness II, for the Elder Guard's greegree); null for none. */
		@Getter(AccessLevel.NONE)
		private String unlessQuest;

		public boolean isWaivedBy(Predicate<Quest> finished)
		{
			return unlessQuest != null && finished.test(Quest.valueOf(unlessQuest));
		}

		private boolean isCarriedInstead(Set<Integer> carriedIds)
		{
			return carried.stream().anyMatch(carriedIds::contains);
		}
	}

	/**
	 * The way into a target's area: the object to use (-1 for none) and the box that counts as inside. The walk data
	 * holds it as a walk row (Clue-Helper-Tools/tools/walk), so routes already pass through it.
	 */
	@Getter
	public static final class WayIn
	{
		private int object = -1;
		private String option;
		/** The object's closed form, a separate object opened first (the werewolf trapdoor); -1 when none. */
		private int closed = -1;
		/** Items used on the object to go in, each time (a rope on the Genie's crevice); empty when it is clicked. */
		private List<Integer> use = List.of();
		private int[] tile;
		private int[] to;
		@Getter(AccessLevel.NONE)
		private int[] inside;
		/** The way's own floors on the way in (the pyramid's lower levels): areas as {@code inside}. */
		@Getter(AccessLevel.NONE)
		private int[][] partWay;
		/** Part way in, no line: the way can't be drawn (Pyramid Plunder's doors open in no fixed order). */
		private boolean noLine;
		private String text;
		/** The chat options to pick, in order, when the way in is a conversation (Shantay's arrest). */
		private List<String> dialogue = List.of();
		/** Ground items to pick up part way in before going on (the Mogre Camp's rocks); null when none. */
		private PickUp pickUp;

		/** Where the way in lands. */
		public WorldPoint getTo()
		{
			return point(to);
		}

		public WorldPoint getTile()
		{
			return point(tile);
		}

		public boolean isInside(WorldPoint at)
		{
			return within(inside, at);
		}

		/** On the way in, not yet inside. */
		public boolean isPartWay(WorldPoint at)
		{
			for (int[] area : partWay == null ? new int[0][] : partWay)
			{
				if (within(area, at))
				{
					return true;
				}
			}
			return false;
		}

		private static boolean within(int[] area, WorldPoint at)
		{
			return area != null && at != null && at.getPlane() == area[4] && at.getX() >= area[0] && at.getY() >= area[1]
				&& at.getX() <= area[2] && at.getY() <= area[3];
		}
	}

	/** {@code count} of ground item {@code id}, then {@code option} on one of {@code objects} to go on. */
	@Getter
	public static final class PickUp
	{
		private int id;
		private int count;
		private String name;
		private List<Integer> objects = List.of();
		private String option;
		/** What to do once they are picked up: "enter the cavern". */
		private String then;
		/** Where the game counts them when they don't go to the inventory (under water); -1 for none. */
		private int varbit = -1;
		/** Where they lie: the game shows ground items only near the player, so these lead the way until they show. */
		private List<int[]> spawns = List.of();

		public List<WorldPoint> spawnTiles()
		{
			return spawns.stream().map(StepIndex::point).collect(Collectors.toList());
		}
	}

	private static final class Reach
	{
		private String text;
	}

	@Getter
	public static final class Place
	{
		private int[] tile;
		private String text;
		/** What the task is done on there (an anvil), outlined; empty for a place with nothing to click. */
		private List<Integer> objects = List.of();
		/** Who the task is done on there (a dust devil, a fishing spot), outlined. */
		private List<Integer> npcs = List.of();
		@Getter(AccessLevel.NONE)
		private List<String> click;
		/** Core's items this place takes others in place of (pure essence for fragments at the True Blood Altar). */
		private List<Alternative> uses = List.of();
		/** A patch that must be grown for this place to count (a Farming Guild redwood); null for a place always there. */
		private String grown;
		/** The area the task works anywhere in, {west, south, east, north, plane} (the Dark Altar's reanimation ground). */
		private int[] span;
		/** The chat options to pick, in order, after the click (the Skullball Boss's "I would like to do the course"). */
		private List<String> dialogue = List.of();

		public boolean hasSpan()
		{
			return span != null;
		}

		public boolean spans(WorldPoint at)
		{
			return WayIn.within(span, at);
		}

		/** {west, south, east, north, plane}; only for a place that {@link #hasSpan()}. */
		public int[] getSpan()
		{
			return span.clone();
		}

		public WorldPoint getTile()
		{
			return point(tile);
		}

		/** The menu option that does the task on the place's objects or NPCs ("Chop down"), or null. */
		public String getOption()
		{
			return click == null || click.size() < 2 ? null : click.get(0);
		}
	}

	@Getter
	public static final class Spot
	{
		private int row;
		private String name;
		/** The row's centre as x, y, plane; null in older generated data. */
		private int[] tile;
	}

	/** An NPC killed for a way in's item: where it stands and what the stage says. */
	@Getter
	public static final class From
	{
		private List<Integer> npcs = List.of();
		/** An object the item is had from (the Elemental Workshop's bookcase), instead of an NPC that drops it. */
		private List<Integer> objects = List.of();
		/** The object's option; an NPC is attacked. */
		private String option;
		private int[] tile;
		private String text;

		public WorldPoint getTile()
		{
			return point(tile);
		}
	}

	private static WorldPoint point(int[] tile)
	{
		return tile == null || tile.length < 3 ? null : new WorldPoint(tile[0], tile[1], tile[2]);
	}
}
