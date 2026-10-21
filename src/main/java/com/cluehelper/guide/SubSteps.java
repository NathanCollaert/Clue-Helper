package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.ItemUse;
import com.cluehelper.route.Exit;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Route;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.SpadeNeed;
import com.cluehelper.step.Stage;
import com.cluehelper.step.Step;
import com.cluehelper.step.StepIndex;
import com.cluehelper.step.TargetKind;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.SpellbookChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

public final class SubSteps
{
	static final int ARRIVE_TILES = 3;
	/** Some teleports land anywhere in a small area, beyond the catalog's 0–2 tile spread. */
	static final int LANDING_TILES = 10;
	/** A wandering NPC: being in its area is enough. */
	static final int AREA_ARRIVE_TILES = 15;
	/** A feel's information depends on its exact tile, so the walk ends on the marker. */
	static final int PROBE_ARRIVE_TILES = 0;
	private static final String FAIRY_RING_PREFIX = "Fairy ring";
	private static final String TALK_TO = "Talk to";
	private static final String MINIGAME_SUFFIX = " Minigame Teleport";
	private static final String QUETZAL_MENU = "quetzal ";
	private static final Pattern LIST_HOTKEY = Pattern.compile("^([0-9A-Z])[.:] ");
	private static final Pattern CLICK_NOTE = Pattern.compile(" \\((left|right)-click\\)$");
	private static final Map<String, String> CHANGE_WARNINGS = Map.of(
		"Ancient Pyramid altar", "Drains your Prayer, and the way in crosses desert heat",
		"Astral altar", "Aggressive suqahs nearby",
		"Tyss", "Right-click Tyss: Spellbook");

	private SubSteps()
	{
	}

	/** Client thread only, for the {@code itemNames} lookups. */
	public static List<SubStep> of(Route route, Step step, IntFunction<String> itemNames)
	{
		return of(route, step, itemNames, HouseState.NONE);
	}

	public static List<SubStep> of(Route route, Step step, IntFunction<String> itemNames, HouseState house)
	{
		return of(route, step, itemNames, house, leg -> null, false);
	}

	/**
	 * {@code house} gives the nexus hotkeys, which follow the player's own slot order; {@code exits} names the exit a
	 * start leg leaves the scene by, or null; {@code takeFromStash} when the step's items wait in its STASH unit.
	 */
	public static List<SubStep> of(Route route, Step step, IntFunction<String> itemNames, HouseState house,
		Function<Leg, Exit> exits, boolean takeFromStash)
	{
		List<SubStep> steps = new ArrayList<>();
		List<Leg> legs = route.getLegs();
		if (route.isOpenBankStart() && !route.getWithdraw().isEmpty())
		{
			steps.add(withdraw(route, itemNames));
		}
		List<Stage> stages = step.getStages().stream().filter(stage -> !stage.getTiles().isEmpty()).collect(Collectors.toList());
		int stage = 0;
		for (int i = 0; i < legs.size(); i++)
		{
			Leg leg = legs.get(i);
			Leg next = i + 1 < legs.size() ? legs.get(i + 1) : null;
			Exit exit = leg.getKind() == Leg.Kind.WALK ? exits.apply(leg) : null;
			Stage reached = stage < stages.size() - 1 && stages.get(stage).getTiles().contains(leg.getTo()) ? stages.get(stage) : null;
			if (reached != null && leg.getKind() == Leg.Kind.WALK && exit == null)
			{
				steps.add(walkToStage(leg, reached));
				steps.add(stageAct(reached));
				stage++;
				continue;
			}
			switch (leg.getKind())
			{
				case WALK:
					if (exit == null && entersStepArea(leg, step))
					{
						steps.add(enter(step.getData()));
					}
					steps.add(exit != null ? exit(leg, exit) : walk(leg, next, step));
					break;
				case WILDERNESS_EXIT:
					steps.add(SubStep.builder().kind(SubStep.Kind.WILDERNESS_EXIT).text("Walk south out of deep Wilderness")
						.detail("Teleports work from there").waypoint(leg.getTo()).goal(leg.getTo()).build());
					break;
				case SWAP_TELEPORT:
					steps.add(swap(leg));
					steps.add(teleport(leg, house));
					break;
				case TELEPORT:
					steps.add(teleport(leg, house));
					break;
				case FAIRY_RING:
					steps.add(fairyRing(leg));
					break;
				case SPELLBOOK_CHANGE:
					steps.add(change(leg));
					break;
				default:
					steps.add(SubStep.builder().kind(SubStep.Kind.BANK).text("Open the bank")
						.detail(route.isPinIncluded() ? "Your PIN comes first" : null).waypoint(leg.getTo()).build());
					steps.add(withdraw(route, itemNames));
					break;
			}
			if (reached != null)
			{
				steps.add(stageAct(reached));
				stage++;
			}
		}
		Stage last = stages.isEmpty() ? null : stages.get(stages.size() - 1);
		if (last != null && last.getKind() != Stage.Kind.FINAL)
		{
			steps.add(stageAct(last));
		}
		else
		{
			steps.addAll(Acts.of(step, takeFromStash));
		}
		addActsDoneAnywhere(steps, step);
		return steps;
	}

	/** After the last bank stop, which may be what brings the task's items. */
	private static void addActsDoneAnywhere(List<SubStep> steps, Step step)
	{
		int at = 0;
		for (int i = 0; i < steps.size(); i++)
		{
			if (steps.get(i).getKind() == SubStep.Kind.WITHDRAW)
			{
				at = i + 1;
			}
		}
		for (Stage stage : step.getStages())
		{
			if (stage.getTiles().isEmpty())
			{
				steps.add(at++, stageAct(stage));
			}
		}
	}

	private static SubStep walkToStage(Leg leg, Stage stage)
	{
		int radius = stage.getRadius() > 0 ? stage.getRadius() : ARRIVE_TILES;
		if (!stage.isOurMarker())
		{
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue").detail("Follow the Clue Scroll marker")
				.goal(leg.getTo()).radius(radius).build();
		}
		String text = stage.getNpcIds().isEmpty() ? "Walk to the marker" : "Walk to the outlined NPC";
		return SubStep.builder().kind(SubStep.Kind.WALK).text(text)
			.waypoint(leg.getTo()).goal(leg.getTo()).radius(radius).npcIds(stage.getNpcIds()).build();
	}

	/** Ends when the stage is dropped from the step, which brings a new plan. */
	static SubStep stageAct(Stage stage)
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text(stage.getAct()).detail(stage.getDetail()).stage(stage)
			.npcIds(stage.getNpcIds()).build();
	}

	/** Core's *clue* tag items are named too, and outlined in the bank like ours, so the spade is easy to find. */
	static SubStep withdraw(Route route, IntFunction<String> itemNames)
	{
		if (route.getWithdraw().isEmpty())
		{
			return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text("Close the bank").build();
		}
		List<String> items = route.getWithdraw().stream().map(use -> itemLine(use, itemNames)).collect(Collectors.toList());
		String text = route.isDepositFirst() ? "Deposit to free slots, then withdraw:" : "Withdraw from the bank:";
		return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text(text).items(List.copyOf(items))
			.bankItems(route.getWithdraw().stream().map(ItemUse::getItemId).collect(Collectors.toUnmodifiableSet())).build();
	}

	/** No amount: a required item's use carries the whole banked stack, not what the step needs. */
	private static String itemLine(ItemUse use, IntFunction<String> itemNames)
	{
		String storage = use.getContainer() == Container.GROUP_STORAGE ? " (group storage)" : "";
		return itemNames.apply(use.getItemId()) + storage;
	}

	private static SubStep walk(Leg leg, Leg next, Step step)
	{
		if (step.getWaypointAct() != null && step.getTargets().contains(leg.getTo()))
		{
			return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to the marker")
				.waypoint(leg.getTo()).goal(leg.getTo()).radius(step.isActAnywhere() ? ARRIVE_TILES : PROBE_ARRIVE_TILES).build();
		}
		if (step.getTargets().contains(leg.getTo()))
		{
			int radius = step.getKind() == TargetKind.AREA ? AREA_ARRIVE_TILES : ARRIVE_TILES;
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue").detail("Follow the Clue Scroll marker")
				.goal(leg.getTo()).radius(radius).build();
		}
		return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to " + walkGoal(next))
			.waypoint(leg.getTo()).goal(leg.getTo()).radius(ARRIVE_TILES).build();
	}

	/** The last walk to the step starts outside its hand-written way in. */
	private static boolean entersStepArea(Leg leg, Step step)
	{
		StepIndex.WayIn way = step.getData() == null ? null : step.getData().getEntry();
		return way != null && step.getTargets().contains(leg.getTo()) && !way.isInside(leg.getFrom());
	}

	private static SubStep enter(StepIndex.Entry data)
	{
		StepIndex.WayIn way = data.getEntry();
		String needs = data.getRequires().stream().map(StepIndex.Requires::getName).collect(Collectors.joining(", "));
		String option = way.getOption() == null || way.getOption().isEmpty() ? null : way.getOption();
		String detail = option == null ? needs : needs.isEmpty() ? option : option + "; needs " + needs;
		return SubStep.builder().kind(SubStep.Kind.ENTER).text("Go in: " + way.getText()).detail(detail.isEmpty() ? null : detail)
			.waypoint(way.getTile()).wayIn(way).build();
	}

	private static SubStep exit(Leg leg, Exit exit)
	{
		return SubStep.builder().kind(SubStep.Kind.EXIT).text(exitText(exit))
			.waypoint(exit.getOrigin()).goal(leg.getTo()).radius(LANDING_TILES).exit(exit).build();
	}

	static String exitText(Exit exit)
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
				return place(next.getChange());
			case BANK:
				return "the bank";
			case TELEPORT:
				return located(next.getRow());
			default:
				return "the marker";
		}
	}

	private static String located(CatalogRow row)
	{
		if (row.getCategory() == Category.SPIRIT_TREE && !row.isInHouse())
		{
			return "the spirit tree";
		}
		return row.getCategory() == Category.QUETZAL ? "Renu" : "the marker";
	}

	/** The Swap's book is its spell's, not the leg's: after the cast the player is back on Lunar. */
	private static SubStep swap(Leg leg)
	{
		Book book = leg.getRow().getRequirements().getBook();
		return SubStep.builder().kind(SubStep.Kind.SPELLBOOK_SWAP).text("Spellbook Swap to " + bookName(book))
			.detail("Right-click Spellbook Swap, then pick the book").book(book).change(leg.getChange()).build();
	}

	private static SubStep teleport(Leg leg, HouseState house)
	{
		CatalogRow row = leg.getRow();
		WorldPoint standsAt = row.isLocated() && !row.isInHouse() ? leg.getFrom() : null;
		return SubStep.builder().kind(SubStep.Kind.TELEPORT).text(teleportText(row)).detail(withHotkey(teleportDetail(row), hotkey(row, house)))
			.waypoint(standsAt).goal(leg.getTo()).radius(LANDING_TILES).row(row).build();
	}

	static String teleportText(CatalogRow row)
	{
		String name = row.getName();
		if (row.isLocated() || isQuetzalMap(row))
		{
			return houseText(row);
		}
		switch (row.getCategory())
		{
			case SPELL:
			case HOME:
				return "Cast " + name;
			case MINIGAME:
				return "Minigame teleport: " + minigameName(row);
			case TABLET:
				return "Break the " + name;
			case SCROLL:
				return name.contains(":") ? "Use " + name : "Read the " + name + " scroll";
			default:
				return "Use " + name;
		}
	}

	private static String teleportDetail(CatalogRow row)
	{
		if (row.isInHouse())
		{
			return houseDetail(row);
		}
		if (row.getCategory() == Category.SPIRIT_TREE)
		{
			return "Travel";
		}
		if (row.getCategory() == Category.QUETZAL)
		{
			return "Renu: Travel, then pick it on the map";
		}
		if (isQuetzalMap(row))
		{
			return "Signal, then pick it on the map";
		}
		switch (row.getCategory())
		{
			case SPELL:
			case HOME:
				return "Magic tab";
			case MINIGAME:
				return "Magic tab: Minigame Teleport";
			default:
				return null;
		}
	}

	static boolean isQuetzalMap(CatalogRow row)
	{
		return row.getMenu() != null && row.getMenu().startsWith(QUETZAL_MENU);
	}

	static int quetzalTableId(CatalogRow row)
	{
		return isQuetzalMap(row) ? Integer.parseInt(row.getMenu().substring(QUETZAL_MENU.length())) : -1;
	}

	static String minigameName(CatalogRow row)
	{
		return row.getName().replace(MINIGAME_SUFFIX, "");
	}

	static char hotkey(CatalogRow row, HouseState house)
	{
		String menu = row.getMenu();
		if (menu != null && menu.startsWith("nexus "))
		{
			int struct = Integer.parseInt(menu.split(" ")[1]);
			Integer line = house.getNexusLines().get(struct);
			return line == null || line < 1 || line > MenuLines.HOTKEYS.length() ? 0 : MenuLines.HOTKEYS.charAt(line - 1);
		}
		if (menu != null && menu.startsWith("box "))
		{
			int line = Integer.parseInt(menu.substring("box ".length()));
			return line < MenuLines.HOTKEYS.length() ? MenuLines.HOTKEYS.charAt(line) : 0;
		}
		String label = menu != null && menu.startsWith("list ") ? menu.substring("list ".length())
			: row.getName().substring(row.getName().lastIndexOf(": ") + 2);
		Matcher key = LIST_HOTKEY.matcher(label);
		return key.find() ? key.group(1).charAt(0) : 0;
	}

	private static String withHotkey(String detail, char key)
	{
		if (key == 0)
		{
			return detail;
		}
		String press = "press " + key;
		return detail == null ? "In the list, " + press : detail + ", then " + press;
	}

	static String houseText(CatalogRow row)
	{
		String name = CLICK_NOTE.matcher(row.getName()).replaceFirst("");
		int colon = name.indexOf(": ");
		if (row.getCategory() == Category.PORTAL)
		{
			return "Enter the " + name.substring(colon + 2) + " portal";
		}
		return colon < 0 ? "Use the " + name.toLowerCase() : name.substring(0, colon) + " → " + name.substring(colon + 2);
	}

	private static String houseDetail(CatalogRow row)
	{
		String menu = row.getMenu();
		if (menu == null)
		{
			return null;
		}
		String[] parts = menu.split(" ", 2);
		switch (parts[0])
		{
			case "nexus":
				return "Teleport Menu (map: " + parts[1].split(" ", 2)[1] + ")";
			case "box":
				return "Teleport Menu";
			case "op":
				return row.getMenuTicks() == 0 ? "Left-click" : "Right-click: " + parts[1];
			case "list":
				return "Right-click: Teleport menu";
			default:
				return null;
		}
	}

	static String nexusPlace(String rowName)
	{
		String place = rowName.substring(rowName.indexOf(": ") + 2);
		int bracket = place.indexOf(" (");
		return bracket < 0 ? place : place.substring(0, bracket);
	}

	private static SubStep fairyRing(Leg leg)
	{
		CatalogRow landing = leg.getLanding();
		String place = landing.getName();
		int colon = place.indexOf(": ");
		String where = place.startsWith(FAIRY_RING_PREFIX) && colon >= 0 ? place.substring(colon + 2) : place;
		boolean coded = landing.getCode() != null && landing.getCode().length() == 3;
		return SubStep.builder().kind(SubStep.Kind.FAIRY_RING)
			.text(coded ? "Fairy ring " + landing.getCode() : "Fairy ring to " + where)
			.waypoint(leg.getFrom()).goal(leg.getTo()).radius(LANDING_TILES).row(landing).build();
	}

	private static SubStep change(Leg leg)
	{
		SpellbookChange change = leg.getChange();
		String book = bookName(leg);
		if (change.isAnywhere())
		{
			return SubStep.builder().kind(SubStep.Kind.CHANGE_BOOK).text(capeName(change) + ": Spellbook → " + book)
				.detail(change.getName().contains("(worn)") ? "Right-click the worn cape in the equipment tab" : "Right-click the cape: Spellbook")
				.book(leg.getBookAfter()).change(change).build();
		}
		return SubStep.builder().kind(SubStep.Kind.CHANGE_BOOK).text(change.getName() + " → " + book)
			.detail(warning(change)).waypoint(change.getTile()).book(leg.getBookAfter()).change(change).build();
	}

	private static String capeName(SpellbookChange change)
	{
		String name = change.getName();
		int colon = name.indexOf(':');
		return (colon < 0 ? name : name.substring(0, colon)).replace(" (worn)", "");
	}

	private static String place(SpellbookChange change)
	{
		String name = change.getName();
		int colon = name.indexOf(':');
		String where = colon < 0 ? name : name.substring(0, colon);
		return where.startsWith("Tyss") ? "Tyss" : "the " + where;
	}

	private static String warning(SpellbookChange change)
	{
		return CHANGE_WARNINGS.entrySet().stream().filter(e -> change.getName().startsWith(e.getKey()))
			.map(Map.Entry::getValue).findFirst().orElse(null);
	}

	private static String bookName(Leg leg)
	{
		return bookName(leg.getBookAfter());
	}

	private static String bookName(Book book)
	{
		String name = book.name();
		return name.charAt(0) + name.substring(1).toLowerCase();
	}

	static SubStep act(Step step)
	{
		if (step.getWaypointAct() != null)
		{
			return SubStep.builder().kind(SubStep.Kind.ACT).text(step.getWaypointAct())
				.waypoint(step.isActAnywhere() ? null : step.getTargets().get(0))
				.actItems(step.getActItems()).build();
		}
		if (needsItemFromNpc(step))
		{
			String what = hasAny(step.getObjectNames()) ? "the " + step.getObjectNames().get(0).toLowerCase() : "it";
			return SubStep.builder().kind(SubStep.Kind.ACT).text("Search " + what + " with the key")
				.detail("The key comes from " + step.getNpcs().get(0)).build();
		}
		return SubStep.builder().kind(SubStep.Kind.ACT).text(actText(step)).detail("The Clue Scroll plugin shows the details").build();
	}

	/**
	 * In core's cryptics naming both an NPC and an object, the NPC holds the key the object needs, except Juna's "speak to
	 * the guardian".
	 */
	private static boolean needsItemFromNpc(Step step)
	{
		boolean talk = step.getText() != null && step.getText().toLowerCase().contains("speak");
		return ClueTypes.CRYPTIC.equals(step.getType()) && !talk && hasAny(step.getNpcs())
			&& (hasAny(step.getObjectNames()) || hasAny(step.getObjectIds()));
	}

	private static boolean hasAny(List<?> list)
	{
		return list != null && !list.isEmpty();
	}

	private static String actText(Step step)
	{
		boolean dig = step.getRequirements() != null && step.getRequirements().getSpade() != SpadeNeed.NONE;
		String type = step.getType() == null ? "" : step.getType();
		switch (type)
		{
			case ClueTypes.EMOTE:
				return "Perform the emote";
			case ClueTypes.MUSIC:
				return "Talk to Cecilia";
			case ClueTypes.HOT_COLD:
				return "Feel the device, then dig";
			case ClueTypes.SKILL:
				return "Do the challenge";
			default:
				break;
		}
		if (dig)
		{
			return "Dig";
		}
		if (step.getNpcs() != null && !step.getNpcs().isEmpty())
		{
			return "Talk to " + step.getNpcs().get(0);
		}
		if (step.getObjectNames() != null && !step.getObjectNames().isEmpty())
		{
			return "Search the " + step.getObjectNames().get(0).toLowerCase();
		}
		if (step.getObjectIds() != null && !step.getObjectIds().isEmpty())
		{
			return "Search it";
		}
		return "Do the clue step";
	}

	static boolean near(WorldPoint tile, WorldPoint goal, int radius)
	{
		return tile != null && goal != null && tile.getPlane() == goal.getPlane() && tile.distanceTo2D(goal) <= radius;
	}
}
