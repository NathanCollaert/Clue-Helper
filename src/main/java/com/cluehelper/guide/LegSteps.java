package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.ItemUse;
import com.cluehelper.route.Exit;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Route;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

/** The sub-step for each kind of route leg: what it says, where it leads and where it lands. */
final class LegSteps
{
	/** A feel's information depends on its exact tile, so the walk ends on the marker. */
	private static final int PROBE_ARRIVE_TILES = 0;
	private static final String FAIRY_RING_PREFIX = "Fairy ring";
	private static final String CODE_SEPARATOR = "-";
	private static final String TALK_TO = "Talk to";
	private static final String NPC_MENU = "npc ";
	private static final Map<Category, String> RIDE_PLACES = Map.of(Category.BOAT, "the boat", Category.CANOE,
		"the canoe station", Category.MINECART, "the minecart", Category.LEVER, "the lever", Category.BALLOON,
		"the balloon basket", Category.MUSHTREE, "the mushtree", Category.WILDERNESS_OBELISK, "the obelisk");
	private static final Pattern LIST_HOTKEY = Pattern.compile("^([0-9A-Z])[.:] ");
	private static final Pattern CLICK_NOTE = Pattern.compile(" \\((left|right)-click\\)$");
	private static final Map<String, String> CHANGE_WARNINGS = Map.of(
		"Ancient Pyramid altar", "Drains your Prayer, and the way in crosses desert heat",
		"Astral altar", "Aggressive suqahs nearby",
		"Tyss", "Right-click Tyss: Spellbook");

	private LegSteps()
	{
	}

	static SubStep walkToStage(Leg leg, Stage stage)
	{
		int radius = stage.getRadius() > 0 ? stage.getRadius() : SubSteps.ARRIVE_TILES;
		if (!stage.isOurMarker())
		{
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue")
				.goal(leg.getTo()).radius(radius).build();
		}
		String text = !stage.getNpcIds().isEmpty() ? "Walk to the outlined NPC"
			: !stage.getObjectIds().isEmpty() ? "Walk to the outlined object" : "Walk to the marker";
		return SubStep.builder().kind(SubStep.Kind.WALK).text(text).waypoint(leg.getTo()).goal(leg.getTo()).radius(radius)
			.npcIds(stage.getNpcIds()).objectIds(stage.getObjectIds()).option(stage.getOption()).spans(stage.getSpans()).build();
	}

	/** Core's *clue* tag items are named too, and outlined in the bank like ours, so the spade is easy to find. */
	static SubStep withdraw(Route route, Function<ItemUse, String> itemLines)
	{
		if (route.getWithdraw().isEmpty())
		{
			return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text("Close the bank").build();
		}
		List<String> items = route.getWithdraw().stream().map(use -> itemLine(use, itemLines)).collect(Collectors.toList());
		String text = route.isDepositFirst() ? "Deposit to free slots, then withdraw:" : "Withdraw from the bank:";
		return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text(text).items(List.copyOf(items))
			.bankItems(route.getWithdraw().stream().map(ItemUse::getItemId).collect(Collectors.toUnmodifiableSet())).build();
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
			return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to the marker")
				.waypoint(leg.getTo()).goal(leg.getTo()).radius(step.isActAnywhere() ? SubSteps.ARRIVE_TILES : PROBE_ARRIVE_TILES).build();
		}
		if (step.getTargets().contains(leg.getTo()))
		{
			int radius = step.getKind() == TargetKind.AREA ? SubSteps.AREA_ARRIVE_TILES : SubSteps.ARRIVE_TILES;
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue")
				.goal(leg.getTo()).radius(radius).build();
		}
		return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to " + walkGoal(next))
			.waypoint(leg.getTo()).goal(leg.getTo()).radius(SubSteps.ARRIVE_TILES).build();
	}

	static SubStep stashWalk(WorldPoint stash)
	{
		return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to the STASH unit").waypoint(stash).goal(stash).radius(SubSteps.ARRIVE_TILES).build();
	}

	static SubStep enter(StepIndex.Entry data)
	{
		StepIndex.WayIn way = data.getEntry();
		String needs = data.getRequires().stream().map(StepIndex.Requires::getName).collect(Collectors.joining(", "));
		String option = way.getOption() == null || way.getOption().isEmpty() ? null : way.getOption();
		String detail = option == null ? needs : needs.isEmpty() ? option : option + "; needs " + needs;
		return SubStep.builder().kind(SubStep.Kind.ENTER).text("Go in: " + way.getText()).detail(detail.isEmpty() ? null : detail)
			.waypoint(way.getTile()).wayIn(way).build();
	}

	static SubStep exit(Leg leg, Exit exit)
	{
		return SubStep.builder().kind(SubStep.Kind.EXIT).text(exitText(exit))
			.waypoint(exit.getOrigin()).goal(leg.getTo()).radius(SubSteps.LANDING_TILES).exit(exit).build();
	}

	/** The Swap's book is its spell's, not the leg's: after the cast the player is back on Lunar. */
	static SubStep swap(Leg leg)
	{
		Book book = leg.getRow().getRequirements().getBook();
		return SubStep.builder().kind(SubStep.Kind.SPELLBOOK_SWAP).text("Spellbook Swap to " + book.label())
			.detail("Right-click Spellbook Swap, then pick the book").book(book).change(leg.getChange()).build();
	}

	static SubStep teleport(Leg leg, HouseState house)
	{
		CatalogRow row = leg.getRow();
		WorldPoint standsAt = row.isLocated() && !row.isInHouse() ? leg.getFrom() : null;
		return SubStep.builder().kind(SubStep.Kind.TELEPORT).text(withKey(teleportText(row), hotkey(row, house))).detail(teleportDetail(row))
			.waypoint(standsAt).goal(leg.getTo()).radius(SubSteps.LANDING_TILES).row(row).build();
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
			steps.add(SubStep.builder().kind(SubStep.Kind.FAIRY_RING).text("Fairy ring " + MenuLines.menuColoured(hops[i]))
				.detail("Code " + (i + 1) + " of " + hops.length + (last ? "" : ", then dial the next where you land"))
				.waypoint(i == 0 ? leg.getFrom() : null).goal(last ? leg.getTo() : null).radius(SubSteps.LANDING_TILES)
				.row(landing.toBuilder().code(hops[i]).build()).build());
		}
		return List.copyOf(steps);
	}

	private static SubStep oneRing(Leg leg)
	{
		CatalogRow landing = leg.getLanding();
		String place = landing.getName();
		int colon = place.indexOf(": ");
		String where = place.startsWith(FAIRY_RING_PREFIX) && colon >= 0 ? place.substring(colon + 2) : place;
		boolean coded = landing.getCode() != null && landing.getCode().length() == 3;
		return SubStep.builder().kind(SubStep.Kind.FAIRY_RING)
			.text(coded ? "Fairy ring " + MenuLines.menuColoured(landing.getCode()) : "Fairy ring to " + where)
			.waypoint(leg.getFrom()).goal(leg.getTo()).radius(SubSteps.LANDING_TILES).row(landing).build();
	}

	static SubStep change(Leg leg)
	{
		SpellbookChange change = leg.getChange();
		String book = leg.getBookAfter().label();
		if (change.isAnywhere())
		{
			return SubStep.builder().kind(SubStep.Kind.CHANGE_BOOK).text(capeName(change) + ": Spellbook → " + book)
				.detail(change.getName().contains("(worn)") ? "Right-click the worn cape in the equipment tab" : "Right-click the cape: Spellbook")
				.book(leg.getBookAfter()).change(change).build();
		}
		return SubStep.builder().kind(SubStep.Kind.CHANGE_BOOK).text(change.getName() + " → " + book)
			.detail(warning(change)).waypoint(change.getTile()).book(leg.getBookAfter()).change(change).build();
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

	private static String teleportText(CatalogRow row)
	{
		String name = row.getName();
		if (row.isLocated() || row.isQuetzalMap())
		{
			return houseText(row);
		}
		switch (row.getCategory())
		{
			case SPELL:
			case HOME:
				return "Cast " + name;
			case MINIGAME:
				return "Minigame teleport: " + row.minigameName();
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
		if (row.isQuetzalMap())
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

	private static char hotkey(CatalogRow row, HouseState house)
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
			: menu != null && menu.contains(RowTargets.MENU_STEP) ? menu.substring(menu.lastIndexOf(RowTargets.MENU_STEP) + RowTargets.MENU_STEP.length())
			: row.getName().substring(row.getName().lastIndexOf(": ") + 2);
		Matcher key = LIST_HOTKEY.matcher(label);
		return key.find() ? key.group(1).charAt(0) : 0;
	}

	private static String houseText(CatalogRow row)
	{
		String name = CLICK_NOTE.matcher(row.getName()).replaceFirst("");
		int colon = name.indexOf(": ");
		if (row.getCategory() == Category.PORTAL)
		{
			return "Enter the " + name.substring(colon + 2) + " portal";
		}
		return colon < 0 ? "Use the " + name.toLowerCase() : name;
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
}
