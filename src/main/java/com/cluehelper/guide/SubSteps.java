package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.ItemUse;
import com.cluehelper.route.Exit;
import com.cluehelper.player.PositionRules;
import com.cluehelper.route.BookScrolls;
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
	public static final int ARRIVE_TILES = 3;
	/** The Agility Pyramid's course spans its foot; a cave's way in is a tile. */
	private static final int ON_WAY_IN_TILES = 40;
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
	private static final String NPC_MENU = "npc ";
	/** Between a menu cell's option and the list line it opens. */
	private static final String MENU_STEP = " > ";
	private static final String SCROLL_BOOK = "master scroll book";
	private static final String OPEN_OPTION = "Open";
	private static final String READ_OPTION = "Read";
	static final String REMOVE_OPTION = "Remove";
	private static final Map<Category, String> RIDE_PLACES = Map.of(Category.BOAT, "the boat", Category.CANOE,
		"the canoe station", Category.MINECART, "the minecart", Category.LEVER, "the lever", Category.BALLOON,
		"the balloon basket", Category.MUSHTREE, "the mushtree", Category.WILDERNESS_OBELISK, "the obelisk");
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
		Function<Leg, Exit> exits, boolean takeFromStash, boolean bookStaysOut, boolean carriesBook)
	{
		List<SubStep> steps = withWanderNote(withWayInEquipped(of(route, step, itemNames, house, exits, takeFromStash), step.getData()),
			step.getData());
		boolean wildernessTarget = step.getTargets().stream().anyMatch(tile -> PositionRules.wildernessLevel(tile) > 0);
		return bookStaysOut ? withBookBanked(steps, carriesBook, wildernessTarget) : steps;
	}

	private static List<SubStep> of(Route route, Step step, IntFunction<String> itemNames, HouseState house,
		Function<Leg, Exit> exits, boolean takeFromStash)
	{
		List<SubStep> steps = new ArrayList<>();
		List<Leg> legs = route.getLegs();
		boolean stashFirst = takeFromStash && stashOutsideWayIn(step);
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
				addStageAct(steps, reached);
				stage++;
				continue;
			}
			switch (leg.getKind())
			{
				case WALK:
				{
					boolean wayIn = exit != null && isWayIn(exit, step);
					if ((exit == null || wayIn) && entersStepArea(leg, step))
					{
						if (stashFirst)
						{
							steps.add(stashWalk(stashTile(step)));
							steps.addAll(Acts.fromStash(step));
						}
						steps.add(enter(step.getData()));
					}
					if (exit != null || wayIn || !boardedThere(next))
					{
						steps.add(exit != null && !wayIn ? exit(leg, exit) : walk(leg, next, step, takeFromStash && !stashFirst));
					}
					break;
				}
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
				addStageAct(steps, reached);
				stage++;
			}
		}
		Stage last = stages.isEmpty() ? null : stages.get(stages.size() - 1);
		if (last != null && last.getKind() != Stage.Kind.FINAL)
		{
			addStageAct(steps, last);
		}
		else
		{
			steps.addAll(Acts.of(step, takeFromStash && !stashFirst));
		}
		addActsDoneAnywhere(steps, step);
		return steps;
	}

	/**
	 * The master scroll book stays out of the Wilderness. Its own teleport there reads the loose scroll, taken out with the
	 * bank closed (a click in the bank interface deposits), then the book is deposited; any other teleport there banks a
	 * carried book. Each sub-step is done by what is carried and whether the bank is open, so a new plan picks up where
	 * the player is.
	 */
	private static List<SubStep> withBookBanked(List<SubStep> steps, boolean carriesBook, boolean wildernessTarget)
	{
		for (int i = 0; i < steps.size(); i++)
		{
			SubStep teleport = steps.get(i);
			CatalogRow row = teleport.getKind() == SubStep.Kind.TELEPORT ? teleport.getRow() : null;
			boolean bookRow = row != null && BookScrolls.isTakenOutAtBank(row, true);
			if (row != null && (bookRow || carriesBook && BookScrolls.intoWilderness(row)))
			{
				return bookBanked(steps, i, bookRow);
			}
		}
		return carriesBook && wildernessTarget ? withBookDeposited(steps) : steps;
	}

	/** Walking into the Wilderness: the plan's bank stop takes the book. */
	private static List<SubStep> withBookDeposited(List<SubStep> steps)
	{
		List<SubStep> out = new ArrayList<>(steps);
		int bank = lastBefore(out, out.size(), SubStep.Kind.BANK);
		if (bank >= 0)
		{
			out.add(bank + 1, deposit());
		}
		else
		{
			out.addAll(0, List.of(bankAgain(null), deposit(), closeBank()));
		}
		return List.copyOf(out);
	}

	private static List<SubStep> bookBanked(List<SubStep> steps, int teleportAt, boolean bookRow)
	{
		List<SubStep> out = new ArrayList<>(steps);
		SubStep teleport = out.get(teleportAt);
		String scroll = bookRow ? BookScrolls.scrollName(teleport.getRow()) : null;
		if (bookRow)
		{
			out.set(teleportAt, teleport.toBuilder().text("Read the " + scroll + " scroll").actItemNamed(scroll.toLowerCase())
				.actOption(READ_OPTION).build());
		}
		int bank = lastBefore(out, teleportAt, SubStep.Kind.BANK);
		int withdraw = lastBefore(out, teleportAt, SubStep.Kind.WITHDRAW);
		boolean bookFromBank = withdraw >= 0 && out.get(withdraw).getBankItems().contains(BookScrolls.BOOK);
		WorldPoint bankTile = bank >= 0 ? out.get(bank).getWaypoint() : null;
		if (bookRow && (bookFromBank || bank < 0))
		{
			out.addAll(withdraw >= 0 ? withdraw + 1 : 0, List.of(takeOut(teleport.getRow()), bankAgain(bankTile), deposit(), closeBank()));
			return List.copyOf(out);
		}
		if (bank < 0)
		{
			out.addAll(0, List.of(bankAgain(null), deposit(), closeBank()));
			return List.copyOf(out);
		}
		out.add(bank + 1, deposit());
		if (bookRow)
		{
			out.add(bank, takeOut(teleport.getRow()));
		}
		return List.copyOf(out);
	}

	private static int lastBefore(List<SubStep> steps, int before, SubStep.Kind kind)
	{
		for (int i = before - 1; i >= 0; i--)
		{
			if (steps.get(i).getKind() == kind)
			{
				return i;
			}
		}
		return -1;
	}

	/** Opens the book, then Remove on the scroll's entry; {@code row} is the book's teleport. */
	private static SubStep takeOut(CatalogRow row)
	{
		String scroll = BookScrolls.scrollName(row);
		return SubStep.builder().kind(SubStep.Kind.ACT).text("Open the master scroll book: take out a " + scroll)
			.detail("With the bank closed").row(row).actItemNamed(SCROLL_BOOK).actOption(OPEN_OPTION)
			.carriedWhenDone(scroll.toLowerCase()).done(SubStep.Done.ITEM_CARRIED).build();
	}

	private static SubStep bankAgain(WorldPoint bank)
	{
		return SubStep.builder().kind(SubStep.Kind.BANK).text("Open the bank").detail("To deposit the master scroll book")
			.waypoint(bank).build();
	}

	private static SubStep deposit()
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text("Deposit the master scroll book").detail("It stays out of the Wilderness")
			.actItemNamed(SCROLL_BOOK).done(SubStep.Done.ITEM_GONE).build();
	}

	private static SubStep closeBank()
	{
		return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text("Close the bank").build();
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
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue")
				.goal(leg.getTo()).radius(radius).build();
		}
		String text = !stage.getNpcIds().isEmpty() ? "Walk to the outlined NPC"
			: !stage.getObjectIds().isEmpty() ? "Walk to the outlined object" : "Walk to the marker";
		return SubStep.builder().kind(SubStep.Kind.WALK).text(text).waypoint(leg.getTo()).goal(leg.getTo()).radius(radius)
			.npcIds(stage.getNpcIds()).objectIds(stage.getObjectIds()).build();
	}

	/** Ends when the stage is dropped from the step, which brings a new plan. */
	static SubStep stageAct(Stage stage)
	{
		return SubStep.builder().kind(SubStep.Kind.ACT).text(stage.getAct()).detail(stage.getDetail()).stage(stage)
			.npcIds(stage.getNpcIds()).objectIds(stage.getObjectIds()).build();
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

	private static SubStep walk(Leg leg, Leg next, Step step, boolean takeFromStash)
	{
		WorldPoint stash = takeFromStash ? stashTile(step) : null;
		if (stash != null && step.getTargets().contains(leg.getTo()))
		{
			return stashWalk(stash);
		}
		if (step.getWaypointAct() != null && step.getTargets().contains(leg.getTo()))
		{
			return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to the marker")
				.waypoint(leg.getTo()).goal(leg.getTo()).radius(step.isActAnywhere() ? ARRIVE_TILES : PROBE_ARRIVE_TILES).build();
		}
		if (step.getTargets().contains(leg.getTo()))
		{
			int radius = step.getKind() == TargetKind.AREA ? AREA_ARRIVE_TILES : ARRIVE_TILES;
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue")
				.goal(leg.getTo()).radius(radius).build();
		}
		return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to " + walkGoal(next))
			.waypoint(leg.getTo()).goal(leg.getTo()).radius(ARRIVE_TILES).build();
	}

	/** The step's way in, when the player is on it: near its start, any floor, and not yet inside. */
	public static SubStep goingIn(Step step, WorldPoint at)
	{
		StepIndex.WayIn way = step == null || step.getData() == null ? null : step.getData().getEntry();
		boolean byStart = way != null && at != null && way.getTile() != null && at.distanceTo2D(way.getTile()) <= ON_WAY_IN_TILES;
		boolean onIt = way != null && (byStart || way.isPartWay(at)) && !way.isInside(at);
		return onIt ? enter(step.getData()) : null;
	}

	/**
	 * A fairy ring, ride or spirit tree used where it stands: its own sub-step leads the line there and colours its menu
	 * from the start, so no "Walk to" comes before it.
	 */
	private static boolean boardedThere(Leg next)
	{
		return next != null && (next.getKind() == Leg.Kind.FAIRY_RING
			|| next.getKind() == Leg.Kind.TELEPORT && next.getRow() != null && next.getRow().isLocated() && !next.getRow().isInHouse());
	}

	private static SubStep stashWalk(WorldPoint stash)
	{
		return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to the STASH unit").waypoint(stash).goal(stash).radius(ARRIVE_TILES).build();
	}

	/** The STASH unit stands outside the step's way in (the Agility Pyramid's, at its foot): its items come first. */
	private static boolean stashOutsideWayIn(Step step)
	{
		WorldPoint stash = stashTile(step);
		StepIndex.WayIn way = step.getData() == null ? null : step.getData().getEntry();
		return stash != null && way != null && !way.isInside(stash);
	}

	/** An exit leg that is the step's own hand-written way in, named by its text instead of "Take the exit". */
	private static boolean isWayIn(Exit exit, Step step)
	{
		StepIndex.WayIn way = step.getData() == null ? null : step.getData().getEntry();
		return way != null && exit.getOrigin().equals(way.getTile()) && exit.getDestination().equals(way.getTo());
	}

	static WorldPoint stashTile(Step step)
	{
		return step.getData() == null || step.getData().getStash() == null ? null : step.getData().getStash().getTile();
	}

	/** The last walk to the step starts outside its hand-written way in. */
	private static boolean entersStepArea(Leg leg, Step step)
	{
		StepIndex.WayIn way = step.getData() == null ? null : step.getData().getEntry();
		return way != null && step.getTargets().contains(leg.getTo()) && !way.isInside(leg.getFrom());
	}

	/** Before going in, the gear the way in needs worn is put on. */
	private static void addStageAct(List<SubStep> steps, Stage stage)
	{
		steps.addAll(stageActs(stage));
	}

	/** Gear the act wants worn goes on just before, so each part of a three-step can wear its own. */
	static List<SubStep> stageActs(Stage stage)
	{
		if (stage.getWorn().isEmpty())
		{
			return List.of(stageAct(stage));
		}
		List<SubStep> acts = new ArrayList<>(equip(stage.getWorn()));
		acts.add(stageAct(stage));
		return List.copyOf(acts);
	}

	/** Put on the needs named, done once worn items alone meet them. */
	static List<SubStep> equip(List<String> wornNeeds)
	{
		return List.of(SubStep.builder().kind(SubStep.Kind.ACT).text("Equip the " + String.join(" and ", wornNeeds))
			.wornNeeds(wornNeeds).done(SubStep.Done.GEAR_WORN).tab(StepGuide.Tab.INVENTORY).build());
	}

	/** A wide wanderer is looked for rather than walked to: where it wanders goes under the walk. */
	private static List<SubStep> withWanderNote(List<SubStep> steps, StepIndex.Entry data)
	{
		StepIndex.Wander wander = data == null ? null : data.getWander();
		if (wander == null || wander.getText() == null)
		{
			return steps;
		}
		return steps.stream().map(sub -> sub.getKind() == SubStep.Kind.WALK_TO_CLUE ? sub.toBuilder().detail(wander.getText()).build() : sub)
			.collect(Collectors.toUnmodifiableList());
	}

	static List<SubStep> withWayInEquipped(List<SubStep> steps, StepIndex.Entry data)
	{
		List<StepIndex.Requires> worn = data == null ? List.of()
			: data.getRequires().stream().filter(StepIndex.Requires::isWear).collect(Collectors.toList());
		if (worn.isEmpty())
		{
			return steps;
		}
		SubStep equip = SubStep.builder().kind(SubStep.Kind.ACT)
			.text("Equip the " + worn.stream().map(StepIndex.Requires::getName).collect(Collectors.joining(" and ")))
			.actItems(worn.stream().flatMap(need -> need.getIds().stream()).collect(Collectors.toUnmodifiableSet()))
			.done(SubStep.Done.WAY_IN_WORN).tab(StepGuide.Tab.INVENTORY).build();
		List<SubStep> out = new ArrayList<>();
		for (SubStep sub : steps)
		{
			if (sub.getKind() == SubStep.Kind.ENTER)
			{
				out.add(equip);
			}
			out.add(sub);
		}
		return List.copyOf(out);
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
		if (row.getMenu() != null && row.getMenu().startsWith(NPC_MENU))
		{
			return "the outlined NPC";
		}
		return row.getCategory() == Category.QUETZAL ? "Renu" : RIDE_PLACES.getOrDefault(row.getCategory(), "the marker");
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
		return SubStep.builder().kind(SubStep.Kind.TELEPORT).text(withKey(teleportText(row), hotkey(row, house))).detail(teleportDetail(row))
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
			return null;
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
			: menu != null && menu.contains(MENU_STEP) ? menu.substring(menu.lastIndexOf(MENU_STEP) + MENU_STEP.length())
			: row.getName().substring(row.getName().lastIndexOf(": ") + 2);
		Matcher key = LIST_HOTKEY.matcher(label);
		return key.find() ? key.group(1).charAt(0) : 0;
	}

	/**
	 * The hotkey in the menu line's colour: the list label's own ("diary cape: B. Hatius Cosaintus"), else put before
	 * the destination as a list label shows it ("Jewellery box: 7. Burthorpe").
	 */
	static String withKey(String text, char key)
	{
		if (key == 0)
		{
			return text;
		}
		String coloured = Matcher.quoteReplacement(MenuLines.keyText(key));
		String inLabel = text.replaceFirst("(?<=: )" + Pattern.quote(String.valueOf(key)) + "(?=[.:] )", coloured);
		int colon = text.lastIndexOf(": ");
		return !inLabel.equals(text) || colon < 0 ? inLabel : text.substring(0, colon + 2) + MenuLines.keyText(key) + ". "
			+ text.substring(colon + 2);
	}

	static String houseText(CatalogRow row)
	{
		String name = CLICK_NOTE.matcher(row.getName()).replaceFirst("");
		int colon = name.indexOf(": ");
		if (row.getCategory() == Category.PORTAL)
		{
			return "Enter the " + name.substring(colon + 2) + " portal";
		}
		return colon < 0 ? "Use the " + name.toLowerCase() : name;
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
			.text(coded ? "Fairy ring " + MenuLines.menuColoured(landing.getCode()) : "Fairy ring to " + where)
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
		return SubStep.builder().kind(SubStep.Kind.ACT).text(actText(step)).build();
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
