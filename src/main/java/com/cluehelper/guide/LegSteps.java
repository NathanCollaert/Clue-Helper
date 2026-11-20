package com.cluehelper.guide;

import com.cluehelper.player.*;
import com.cluehelper.route.*;
import com.cluehelper.step.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.function.Function;
import net.runelite.api.coords.WorldPoint;
import static java.util.stream.Collectors.*;
import com.cluehelper.guide.SubStep.Kind;

/** The sub-step for each kind of route leg: what it says, where it leads and where it lands. */
final class LegSteps
{
	/** A feel's information depends on its exact tile, so the walk ends on the marker. */
	private static final int PROBE_ARRIVE_TILES = 0;
	private static final String CODE_SEPARATOR = "-";
	private static final int CODE_LENGTH = 3;
	private static final String TALK_TO = "Talk to";
	/** Teleports that land anywhere in a place, by name: how far from the catalog's landing tile a landing still counts. */
	private static final Map<String, Integer> SCATTERED_LANDING_TILES = Map.of("Dorgesh-kaan sphere", 120);

	private LegSteps()
	{
	}

	static SubStep walkToStage(Leg leg, Stage stage, StepIndex.Entry data)
	{
		int radius = stage.getRadius() > 0 ? stage.getRadius() : SubSteps.ARRIVE_TILES;
		if (!stage.isOurMarker())
		{
			return walkToClue(leg.getTo(), radius, data);
		}
		String text = !stage.getNpcIds().isEmpty() ? "Walk to the outlined NPC"
			: !stage.getObjectIds().isEmpty() ? "Walk to the outlined object" : "Walk to the marker";
		String place = stage.getKind() == Stage.Kind.TASK ? stage.atPlace(leg.getTo()).getDetail() : null;
		return walkTo(text, leg.getTo(), radius).detail(place)
			.npcIds(stage.getNpcIds()).objectIds(stage.getObjectIds()).option(stage.getOption()).spans(stage.getSpans()).build();
	}

	/** A walk that ends within {@code radius} of {@code to}, with the line leading there. */
	static SubStep.SubStepBuilder walkTo(String text, WorldPoint to, int radius)
	{
		return SubStep.of(Kind.WALK, text).waypoint(to).goal(to).radius(radius);
	}

	/**
	 * Monsters spread over an area (hellhounds) are walked to until one is close by, and outlined on landing; a wide
	 * wanderer is looked for, where it wanders said under the walk.
	 */
	static SubStep walkToClue(WorldPoint goal, int radius, StepIndex.Entry data)
	{
		StepIndex.Wander wander = data.getWander();
		return SubStep.of(Kind.WALK_TO_CLUE, "Walk to the clue").goal(goal).radius(radius).npcIds(data.getArriveNpcs())
			.detail(wander == null ? null : wander.getText()).build();
	}

	/** Core's *clue* tag items are named too, and outlined in the bank like ours, so the spade is easy to find. */
	static SubStep withdraw(Route route, Function<ItemUse, String> itemLines)
	{
		if (route.getWithdraw().isEmpty())
		{
			return SubStep.of(Kind.WITHDRAW, "Close the bank").build();
		}
		List<String> items = route.getWithdraw().stream().map(use -> itemLine(use, itemLines)).collect(toList());
		String text = route.isDepositFirst() ? "Deposit to free slots, then withdraw:" : "Withdraw from the bank:";
		return SubStep.of(Kind.WITHDRAW, text).items(List.copyOf(items))
			.bankItems(route.getWithdraw().stream().map(ItemUse::getItemId).collect(toUnmodifiableSet())).build();
	}

	static SubStep walk(Leg leg, Leg next, Step step, boolean takeFromStash)
	{
		WorldPoint stash = takeFromStash ? SubSteps.stashTile(step) : null;
		if (stash != null && step.getTargets().contains(leg.getTo()))
		{
			return stashWalk(stash);
		}
		if (step.getWaypointAct() != null && step.getTargets().contains(leg.getTo()))
		{
			return walkTo("Walk to the marker", leg.getTo(), step.isActAnywhere() ? SubSteps.ARRIVE_TILES : PROBE_ARRIVE_TILES).build();
		}
		if (step.getTargets().contains(leg.getTo()))
		{
			return walkToClue(leg.getTo(), step.getKind() == TargetKind.AREA ? SubSteps.AREA_ARRIVE_TILES : SubSteps.ARRIVE_TILES, step.getData());
		}
		return walkTo("Walk to " + walkGoal(next), leg.getTo(), SubSteps.ARRIVE_TILES).build();
	}

	static SubStep stashWalk(WorldPoint stash)
	{
		return walkTo("Walk to the STASH unit", stash, SubSteps.ARRIVE_TILES).build();
	}

	static SubStep enter(StepIndex.Entry data)
	{
		StepIndex.WayIn way = data.getEntry();
		return SubStep.of(Kind.ENTER, "Go in: " + way.getText()).waypoint(way.getTile()).wayIn(way).build();
	}

	static SubStep exit(Leg leg, Exit exit)
	{
		return SubStep.of(Kind.EXIT, exitText(exit))
			.waypoint(exit.getOrigin()).goal(leg.getTo()).radius(SubSteps.LANDING_TILES).exit(exit).build();
	}

	/** The Swap's book is its spell's, not the leg's: after the cast the player is back on Lunar. */
	static SubStep swap(Leg leg)
	{
		Book book = leg.getRow().getRequirements().getBook();
		return SubStep.of(Kind.SPELLBOOK_SWAP, "Spellbook Swap to " + book.label())
			.detail("Right-click Spellbook Swap, then pick the book").book(book).change(leg.getChange()).build();
	}

	static SubStep teleport(Leg leg, HouseState house)
	{
		CatalogRow row = leg.getRow();
		WorldPoint standsAt = row.isLocated() && !row.isInHouse() ? leg.getFrom() : null;
		return SubStep.of(Kind.TELEPORT, travelLine(row, key(row, house))).detail(row.getDetail())
			.waypoint(standsAt).goal(leg.getTo()).radius(SCATTERED_LANDING_TILES.getOrDefault(row.getName(), SubSteps.LANDING_TILES))
			.row(row).build();
	}

	/** A chained code (the Fairy Queen's hideout) is dialled one code at a time, each from the ring the last one landed at. */
	static List<SubStep> fairyRing(Leg leg)
	{
		CatalogRow landing = leg.getLanding();
		String code = landing.getCode();
		if (code == null || !code.contains(CODE_SEPARATOR))
		{
			return List.of(oneRing(leg));
		}
		String[] hops = code.split(CODE_SEPARATOR);
		List<SubStep> steps = new ArrayList<>();
		for (int i = 0; i < hops.length; i++)
		{
			boolean last = i == hops.length - 1;
			steps.add(SubStep.of(Kind.FAIRY_RING, travelLine(landing, hops[i]))
				.detail("Code " + (i + 1) + " of " + hops.length + (last ? "" : ", then dial the next where you land"))
				.waypoint(i == 0 ? leg.getFrom() : null).goal(last ? leg.getTo() : null).radius(SubSteps.LANDING_TILES)
				.row(landing.toBuilder().code(hops[i]).build()).build());
		}
		return List.copyOf(steps);
	}

	private static SubStep oneRing(Leg leg)
	{
		CatalogRow landing = leg.getLanding();
		boolean coded = landing.getCode() != null && landing.getCode().length() == CODE_LENGTH;
		return SubStep.of(Kind.FAIRY_RING, travelLine(landing, coded ? landing.getCode() : null))
			.waypoint(leg.getFrom()).goal(leg.getTo()).radius(SubSteps.LANDING_TILES).row(landing).build();
	}

	static SubStep change(Leg leg)
	{
		SpellbookChange change = leg.getChange();
		return SubStep.of(Kind.CHANGE_BOOK, change.getText() + " → " + leg.getBookAfter().label())
			.detail(change.getDetail()).waypoint(change.getTile()).book(leg.getBookAfter()).change(change).build();
	}

	/**
	 * Every teleport reads alike, ITEM: PLACE - KEY: the item or object in the text's white, what to click in it in the
	 * item lines' grey, and its hotkey or fairy ring code last in the menu's blue. A teleport with no item reads as
	 * VERB: THING instead.
	 *
	 * @param key null when none is known
	 */
	static String travelLine(CatalogRow row, String key)
	{
		if (row.getPick() == null)
		{
			return row.getLabel();
		}
		String place = row.getPick().isEmpty() ? "" : MenuLines.pickColoured(row.getPick());
		String keyed = key == null ? place : place.isEmpty() ? MenuLines.keyText(key) : place + " - " + MenuLines.keyText(key);
		return row.getLabel() + ": " + keyed;
	}

	private static String itemLine(ItemUse use, Function<ItemUse, String> itemLines)
	{
		String storage = use.getContainer() == Container.GROUP_STORAGE ? " (group storage)" : "";
		return itemLines.apply(use) + storage;
	}

	private static String exitText(Exit exit)
	{
		String target = exit.getTarget().isEmpty() ? "exit" : exit.getTarget().toLowerCase();
		String option = exit.getOption().replace('-', ' ');
		if (option.equals(TALK_TO) && !exit.getTarget().isEmpty())
		{
			return TALK_TO + " " + exit.getTarget();
		}
		return option.isEmpty() ? "Take the " + target : option + (option.equals("Travel") ? " with the " : " the ") + target;
	}

	private static String walkGoal(Leg next)
	{
		if (next == null)
		{
			return "the marker";
		}
		switch (next.getKind())
		{
			case FAIRY_RING:
				return "the fairy ring";
			case SPELLBOOK_CHANGE:
				return next.getChange().getPlace();
			case BANK:
				return "the bank";
			case TELEPORT:
				return next.getRow().getApproach();
			default:
				return "the marker";
		}
	}

	/** A nexus line's key is where the house's nexus lists it. */
	private static String key(CatalogRow row, HouseState house)
	{
		if (row.getLine() != MenuLine.NEXUS)
		{
			return row.getKey();
		}
		Integer line = house.getNexusLines().get(row.getLineId());
		return line == null || line < 1 || line > MenuLines.HOTKEYS.length() ? null : String.valueOf(MenuLines.HOTKEYS.charAt(line - 1));
	}
}
