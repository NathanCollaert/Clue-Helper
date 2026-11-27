package com.cluehelper.step;

import java.util.List;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/**
 * A step file entry's fields only the bake reads: {@link StageParts} and {@code ActParts} turn them into the stages and
 * acts {@code steps.json} holds, so the plugin's {@link StepIndex} never carries them. Gson fills it from the same entry
 * {@link StepIndex.Entry} is read from.
 */
public final class StepSource
{
	private StepSource()
	{
	}

	@Getter
	public static final class Entry
	{
		/** An entry for a key the step files lack: every getter answers empty. */
		public static final Entry NONE = new Entry();

		/** The task as the box says it, when the clue's own wording is too long; null to use the clue's. */
		private String act;
		/** The option the step's NPC is clicked with when it isn't Talk-to (the Monk of Entrana's Take-boat); null for a talk. */
		private String npcOption;
		private Challenge challenge;
		private Music music;
		/** Sherlock's items worn for the task (the lumberjack outfit), by core's names for them. */
		private List<String> wear = List.of();
		/** Other places the key's NPCs are killed, beside the cache's key tile: any chicken drops the Seers' key. */
		private List<WorldPoint> keyAlso = List.of();
		private String floor;
		private String note;
		@Getter(AccessLevel.NONE)
		private List<Place> places;
		/** In the order of {@link StepIndex.Entry#getTargets()}. */
		private List<Target> targets = List.of();
		private List<Combat> combat = List.of();
		/** In the order of {@link StepIndex.Entry#getRequires()}. */
		private List<Requires> requires = List.of();
		/** What is done at the place before the task, in order: Alry's bird, then king worms for aerial fishing. */
		private List<Before> before = List.of();
		/** Whatever form the task's objects are in, their left click does the task (a funeral pyre). */
		private boolean leftClickDoesIt;

		/** In the order of {@link StepIndex.Entry#getPlaces()}. */
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
	}

	@Getter
	public static final class Target
	{
		/** The cache's how-to: "Kill any Chicken to obtain a key". */
		private String text;
	}

	@Getter
	public static final class Combat
	{
		private List<Integer> npcs = List.of();
	}

	@Getter
	public static final class Requires
	{
		/** Who drops it for a player owning none (the Mercenary Captain's Metal key); null when it can't be got there. */
		private From from;
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
		private WorldPoint tile;
		private String text;
	}

	@Getter
	public static final class Place
	{
		private WorldPoint tile;
		private String text;
		private List<Integer> objects = List.of();
		/** Who the task is done on there (a dust devil, a fishing spot), outlined. */
		private List<Integer> npcs = List.of();
		@Getter(AccessLevel.NONE)
		private List<String> click;
		/** Core's items this place takes others in place of (pure essence for fragments at the True Blood Altar). */
		private List<StepIndex.Alternative> uses = List.of();
		/** A patch that must be grown for this place to count; null for a place always there. */
		private String grown;
		/** The chat options to pick, in order, after the click. */
		private List<String> dialogue = List.of();

		/** The menu option that does the task on the place's objects or NPCs ("Chop down"), or null. */
		public String getOption()
		{
			return click == null || click.size() < 2 ? null : click.get(0);
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
		/** Items it is done with in the inventory (sacred oil on the logs), highlighted there. */
		private List<Integer> items = List.of();
		/** Done anywhere, so before the walk to the place rather than at it. */
		private boolean anywhere;
		/** Objects it is done on with {@code option} (the pyre site's Construct). */
		private List<Integer> objects = List.of();
		/** Done once none of these is carried any more (the mangled bones, built into the pyre ship); else by {@code held}. */
		private List<Integer> gone = List.of();
	}
}
