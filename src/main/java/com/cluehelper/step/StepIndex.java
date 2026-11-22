package com.cluehelper.step;

import com.cluehelper.guide.SubStep;
import com.cluehelper.hotcold.Spot;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;

/**
 * Gson fills this from {@code steps.json}, baked by the test source set's {@code StepLoader} from the step files
 * Clue-Helper-Tools/tools/steps/generate.py writes: field names are its JSON keys. Keys iterate in sorted order, which
 * the golden routes' sample depends on.
 */
public final class StepIndex
{
	private static final String FILE = "steps.json";

	@Getter
	Map<String, Entry> steps = Map.of();
	/** Core's spots of both tiers, centred where the cache's rows put them. */
	@Getter
	List<Spot> hotColdSpots = List.of();

	public static StepIndex read(Gson gson)
	{
		try (InputStream in = StepIndex.class.getResourceAsStream(FILE))
		{
			if (in == null)
			{
				throw new IllegalStateException(FILE + " is missing");
			}
			return gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), StepIndex.class);
		}
		catch (IOException e)
		{
			throw new IllegalStateException("can't read " + FILE, e);
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

		String tier;
		/** The task as the box says it, when the clue's own wording is too long; null to use the clue's. */
		String act;
		/** The option the step's NPC is clicked with when it isn't Talk-to (the Monk of Entrana's Take-boat); null for a talk. */
		String npcOption;
		/** Which quest places the step's NPC for each player (Viggora); null for an NPC always at its targets. */
		LearnPlace learnPlace;
		List<CacheRow> rows = List.of();
		List<WorldPoint> tiles = List.of();
		List<Target> targets = List.of();
		List<Combat> combat = List.of();
		Challenge challenge;
		Music music;
		Stash stash;
		/** Hand data: where a step with no tile (a kill task) is done. */
		Area area;
		/** Hand data: a walkable tile beside a blocked target tile; null when the target is walkable. */
		WorldPoint stand;
		List<Requires> requires = List.of();
		/** Hand data: worn gear the talk needs, picked from what the player owns. */
		List<Gear> gear = List.of();
		/** Hand data: where the step's NPC wanders far from core's tile (Wingstone across the desert); null for most. */
		Wander wander;
		WayIn entry;
		/** Hand data: how to get to a target no route reaches (a Sailing island); null for the rest. */
		String reach;
		List<Place> places;
		/** Sherlock's items worn for the task (the lumberjack outfit), by core's names for them. */
		List<String> wear = List.of();
		/** Core's items the task can do without (a rake for a weeded patch), by core's names for them. */
		List<String> optional = List.of();
		/** Core's items the task does without from these skill levels on (a harpoon, fishing barehanded), by core's names. */
		Map<String, List<Level>> optionalFrom = Map.of();
		/** Core's items the task does without once one of these varbits is set (a rake after buying auto-weed), by core's names. */
		Map<String, List<Integer>> optionalWith = Map.of();
		/** What is done at the place before the task, in order: Alry's bird, then king worms for aerial fishing. */
		List<Before> before = List.of();
		/** Other places the key's NPCs are killed, beside the cache's key tile: any chicken drops the Seers' key. */
		List<WorldPoint> keyAlso = List.of();
		/** The task's line while something is carried, the first that matches: reanimate the head, then kill what rises. */
		List<Phase> phases = List.of();
		/** Once one of these is owned the task is done anywhere: a sacred eel needs only a knife, not Zul-Andra's spots. */
		List<Integer> anywhereWith = List.of();
		/** The task travels to one of the player's planted spirit trees: the route ends with the teleport to the first grown one. */
		boolean toPlantedTree;
		/** The task plants a seed in a farming patch, cleared first of weeds or a crop. */
		boolean plants;
		/** The step is in the dark, where core asks for no light source. */
		boolean light;
		/** Other items that do what one of core's items does (a vial of water and a cadantine for the unfinished potion). */
		List<Alternative> alternatives = List.of();
		/** How the task's item is made from what is carried, step by step (clean the herb, then make the potion). */
		List<Making> making = List.of();
		/** The spell the task casts (Lvl-5 Enchant), or null. */
		Cast cast;
		/** The spellbook the task is done on, or null for any. */
		String book;
		/** The option the task picks in an interface, or null. */
		Choose choose;
		String floor;
		String note;
		/** Quest steps: never guided. */
		boolean hidden;
		/** The skill levels the task itself needs; boosts count. */
		List<Level> levels = List.of();
		/** Baked: the places visited in turn, the last ({@link Stage.Kind#FINAL}) core's targets; empty for one place. */
		List<Stage> stages = List.of();
		/** Baked: what is done once at the step, {@link Step#NPC_NAME} for core's name of its NPC (see guide's Acts). */
		List<SubStep> acts = List.of();
		/** Baked: the step's one act, shown at a marker or once its stages are done. */
		SubStep mainAct;
		/** Baked: who a walk to an area step's targets ends on (the monsters killed there). */
		Set<Integer> arriveNpcs = Set.of();
		/** Baked: how many of an item core's requirements ask for, by item id, where it is more than one (3 mithril bars). */
		Map<Integer, Integer> amounts = Map.of();

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
			entry.requires = unsolved.stream().flatMap(part -> part.getRequires().stream()).collect(toUnmodifiableList());
			entry.gear = unsolved.stream().flatMap(part -> part.getGear().stream()).collect(toUnmodifiableList());
			return entry;
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
			return getRequires().stream().filter(Requires::isWear).collect(toList());
		}

		/** The requirements the player still has to meet: none a finished quest waives. */
		public List<Requires> requiresFor(Predicate<Quest> finished)
		{
			return getRequires().stream().filter(need -> !need.isWaivedBy(finished)).collect(toList());
		}

		/** The requirements to wear going in, but those the player meets by what they carry (a talisman). */
		public List<Requires> wornRequiresFor(Predicate<Quest> finished, Set<Integer> carriedIds)
		{
			return requiresFor(finished).stream().filter(need -> need.isWear() && !need.isCarriedInstead(carriedIds))
				.collect(toList());
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

		Kind kind;
		WorldPoint tile;
		List<Integer> npcs = List.of();
		/** The key's item id; -1 for a target with none. */
		int key = -1;
		/** Where the key's NPCs are killed. */
		WorldPoint keyTile;
		String text;
		/** An object's name when talking is all it does (the spirit tree); null otherwise. */
		String talkTo;
		/** A loc target's object ids: its own and the cache's fallback form. */
		Set<Integer> locs = Set.of();
	}

	@Getter
	public static final class Combat
	{
		String text;
		List<Integer> npcs = List.of();
	}

	/** The puzzle or light box a challenge hands over; {@code box} is null for a question, whose answer core shows. */
	@Getter
	public static final class Challenge
	{
		String box;
	}

	@Getter
	public static final class Music
	{
		String track;
		/** Where the track unlocks. */
		String unlock;
	}

	@Getter
	public static final class Stash
	{
		int object;
		WorldPoint tile;
	}

	@Getter
	public static final class Area
	{
		int radius;
		String text;
		/** The main spawn first, then the others the route may go to instead. */
		List<WorldPoint> tiles = List.of();
	}

	/**
	 * An NPC's wander range: the box it walks in, less a box inside it never enters,
	 * and tiles across it that routes may end on.
	 */
	@Getter
	public static final class Wander
	{
		Box area;
		/** The box it never enters; null when there is none. */
		Box exclude;
		List<WorldPoint> tiles = List.of();
		/** Where to look, shown under the walk. */
		String text;

		public boolean contains(WorldPoint at)
		{
			return Box.within(area, at) && !Box.within(exclude, at);
		}
	}

	/** West, south, east and north edges, inclusive, on one plane. */
	@Value
	public static class Box
	{
		int west;
		int south;
		int east;
		int north;
		int plane;

		static boolean within(Box box, WorldPoint at)
		{
			return box != null && at != null && at.getPlane() == box.plane && at.getX() >= box.west && at.getY() >= box.south
				&& at.getX() <= box.east && at.getY() <= box.north;
		}
	}

	/** An NPC's option or a ground item to pick up before the task, done once one of {@code held} is carried or worn. */
	@Getter
	public static final class Before
	{
		String text;
		List<Integer> npcs = List.of();
		String option;
		int groundItem = -1;
		List<Integer> held = List.of();
	}

	/** The task's line while any of {@code whileCarried} is carried. */
	@Getter
	public static final class Phase
	{
		String text;
		List<Integer> whileCarried = List.of();
	}

	@Getter
	public static final class Level
	{
		Skill skill;
		int level;
	}

	/** An interface's option, found by its text since its slot varies, and the button that confirms it. */
	@Getter
	public static final class Choose
	{
		int root;
		String text;
		int confirm;
	}

	/** A spell cast on an item: its spellbook buttons in click order (a group, then the spell), and the items. */
	@Getter
	public static final class Cast
	{
		List<Integer> widgets = List.of();
		List<Integer> on = List.of();
	}

	/** One step of making: its text and the items used, each group any of its ids; the first group is what is worked on. */
	@Getter
	public static final class Making
	{
		String text;
		List<List<Integer>> items = List.of();
		/** The product to pick in the "How many do you wish to make?" box; -1 when the step has none. */
		int make = -1;
	}

	/** Core's item {@code instead}, or one of each group of {@code all}. */
	@Getter
	public static final class Alternative
	{
		int instead;
		/** What the replacement is called where it is listed as missing; null for an alternative to core's item. */
		String name;
		List<List<Integer>> all = List.of();
	}

	/** Worn gear adding up to at least {@code atLeast} of its bonus (Ghommal's +100 melee strength). */
	@Getter
	public static final class Gear
	{
		String bonus;
		int atLeast;
		String name;

		public GearBonus getBonus()
		{
			return GearBonus.valueOf(bonus);
		}
	}

	/** The quest that places the step's NPC at one of its targets for each player. */
	@Getter
	public static final class LearnPlace
	{
		/** A {@link net.runelite.api.Quest} name. */
		String quest;
	}

	/** An NPC's option that puts a worn requirement on, where the item has no Wear of its own (Larry's Tuxedo-time). */
	@Getter
	public static final class PutOn
	{
		List<Integer> npcs = List.of();
		String option;
		String text;
		/** The slots the gear goes in, emptied before the NPC is asked (the suit's cape slot, and gloves). */
		List<EquipmentInventorySlot> free = List.of();
	}

	/** Items the way in needs; any one of {@link #getIds()} does. */
	@Getter
	public static final class Requires
	{
		/** Null when the item goes on by its own Wear. */
		PutOn putOn;
		List<Integer> ids = List.of();
		String name;
		/** Worn to go in (a diving helmet), not just carried (a spade). */
		boolean wear;
		/** Of a worn requirement, the items that do as well just carried (a talisman beside the tiara); empty for none. */
		@Getter(AccessLevel.NONE)
		List<Integer> carried = List.of();
		/** Who drops it for a player owning none (the Mercenary Captain's Metal key); null when it can't be got there. */
		From from;
		/** A quest whose finishing makes it unneeded (Monkey Madness II, for the Elder Guard's greegree); null for none. */
		@Getter(AccessLevel.NONE)
		String unlessQuest;
		/** {@code [varbit, value]} pairs that, all met, make it unneeded (God Wars' ropes, tied for good); empty for none. */
		@Getter(AccessLevel.NONE)
		List<int[]> unlessVarbits = List.of();

		public boolean isWaivedBy(Predicate<Quest> finished)
		{
			return unlessQuest != null && finished.test(Quest.valueOf(unlessQuest));
		}

		public boolean isWaivedByVarbits(IntUnaryOperator varbit)
		{
			return !unlessVarbits.isEmpty() && unlessVarbits.stream().allMatch(pair -> varbit.applyAsInt(pair[0]) == pair[1]);
		}

		public boolean isCarriedInstead(Set<Integer> carriedIds)
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
		int object = -1;
		String option;
		/** The object's closed form, a separate object opened first (the werewolf trapdoor); -1 when none. */
		int closed = -1;
		/** Items used on the object to go in, each time (a rope on the Genie's crevice); empty when it is clicked. */
		List<Integer> use = List.of();
		WorldPoint tile;
		/** Where the way in lands. */
		WorldPoint to;
		@Getter(AccessLevel.NONE)
		Box inside;
		/** The way's own floors on the way in (the pyramid's lower levels). */
		@Getter(AccessLevel.NONE)
		List<Box> partWay = List.of();
		/** Part way in, no line: the way can't be drawn (Pyramid Plunder's doors open in no fixed order). */
		boolean noLine;
		String text;
		/** The chat options to pick, in order, when the way in is a conversation (Shantay's arrest). */
		List<String> dialogue = List.of();
		/** Ground items to pick up part way in before going on (the Mogre Camp's rocks); null when none. */
		PickUp pickUp;

		public boolean isInside(WorldPoint at)
		{
			return Box.within(inside, at);
		}

		/** On the way in, not yet inside. */
		public boolean isPartWay(WorldPoint at)
		{
			return partWay.stream().anyMatch(box -> Box.within(box, at));
		}
	}

	/** {@code count} of ground item {@code id}, then {@code option} on one of {@code objects} to go on. */
	@Getter
	public static final class PickUp
	{
		int id;
		int count;
		String name;
		List<Integer> objects = List.of();
		String option;
		/** What to do once they are picked up: "enter the cavern". */
		String then;
		/** Where the game counts them when they don't go to the inventory (under water); -1 for none. */
		int varbit = -1;
		/** Where they lie: the game shows ground items only near the player, so these lead the way until they show. */
		List<WorldPoint> spawnTiles = List.of();
	}

	@Getter
	public static final class Place
	{
		WorldPoint tile;
		String text;
		/** What the task is done on there (an anvil), outlined; empty for a place with nothing to click. */
		List<Integer> objects = List.of();
		/** Who the task is done on there (a dust devil, a fishing spot), outlined. */
		List<Integer> npcs = List.of();
		@Getter(AccessLevel.NONE)
		List<String> click;
		/** Core's items this place takes others in place of (pure essence for fragments at the True Blood Altar). */
		List<Alternative> uses = List.of();
		/** A patch that must be grown for this place to count; null for a place always there. */
		String grown;
		/** The area the task works anywhere in (the Dark Altar's reanimation ground); null for a place that is a tile. */
		Box span;
		/** The chat options to pick, in order, after the click. */
		List<String> dialogue = List.of();

		public boolean spans(WorldPoint at)
		{
			return Box.within(span, at);
		}

		/** The menu option that does the task on the place's objects or NPCs ("Chop down"), or null. */
		public String getOption()
		{
			return click == null || click.size() < 2 ? null : click.get(0);
		}
	}

	/** An NPC killed for a way in's item: where it stands and what the stage says. */
	@Getter
	public static final class From
	{
		List<Integer> npcs = List.of();
		/** An object the item is had from (the Elemental Workshop's bookcase), instead of an NPC that drops it. */
		List<Integer> objects = List.of();
		/** The object's option; an NPC is attacked. */
		String option;
		WorldPoint tile;
		String text;
	}

}
