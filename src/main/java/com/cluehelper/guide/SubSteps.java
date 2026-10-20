package com.cluehelper.guide;

import com.cluehelper.player.Book;
import com.cluehelper.player.Container;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.ItemUse;
import com.cluehelper.route.Leg;
import com.cluehelper.route.Route;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.SpadeNeed;
import com.cluehelper.step.Step;
import com.cluehelper.step.TargetKind;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Category;
import com.cluehelper.transport.SpellbookChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;

/** Turns a committed route and its step into the sub-steps StepGuide walks through (PLAN §5.5). */
public final class SubSteps
{
	/** Close enough to a waypoint of ours to use what is there (a fairy ring, an altar). */
	static final int ARRIVE_TILES = 3;
	/** Catalog landings spread 0–2 tiles (B09); some teleports land anywhere in a small area. */
	static final int LANDING_TILES = 10;
	/** An NPC that walks around: being in its area is enough. */
	static final int AREA_ARRIVE_TILES = 15;
	private static final String FAIRY_RING_PREFIX = "Fairy ring";
	private static final String MINIGAME_SUFFIX = " Minigame Teleport";
	private static final Pattern LIST_HOTKEY = Pattern.compile("^([0-9A-Z])[.:] ");
	private static final Pattern CLICK_NOTE = Pattern.compile(" \\((left|right)-click\\)$");
	private static final Map<String, String> CHANGE_WARNINGS = Map.of(
		"Ancient Pyramid altar", "Drains your Prayer, and the way in crosses desert heat",
		"Astral altar", "Aggressive suqahs nearby",
		"Tyss", "Right-click Tyss: Spellbook");

	private SubSteps()
	{
	}

	/** @param itemNames item names for the withdraw list; client thread */
	public static List<SubStep> of(Route route, Step step, IntFunction<String> itemNames)
	{
		return of(route, step, itemNames, HouseState.NONE);
	}

	/** @param house for the nexus hotkeys, which follow the player's own slot order */
	public static List<SubStep> of(Route route, Step step, IntFunction<String> itemNames, HouseState house)
	{
		List<SubStep> steps = new ArrayList<>();
		List<Leg> legs = route.getLegs();
		if (route.isOpenBankStart() && !route.getWithdraw().isEmpty())
		{
			steps.add(withdraw(route, itemNames));
		}
		for (int i = 0; i < legs.size(); i++)
		{
			Leg leg = legs.get(i);
			Leg next = i + 1 < legs.size() ? legs.get(i + 1) : null;
			switch (leg.getKind())
			{
				case WALK:
					steps.add(walk(leg, next, step));
					break;
				case WILDERNESS_EXIT:
					steps.add(SubStep.builder().kind(SubStep.Kind.WILDERNESS_EXIT).text("Walk south out of deep Wilderness")
						.detail("≈ " + leg.getTicks() + " ticks; teleports work from there").waypoint(leg.getTo()).goal(leg.getTo()).build());
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
		}
		steps.add(act(step));
		return steps;
	}

	/**
	 * The withdraw list. Items core's *clue* tag already shows are only counted in the text, but outlined in the bank like
	 * the rest (Nathan, B16: the spade must be easy to find).
	 */
	static SubStep withdraw(Route route, IntFunction<String> itemNames)
	{
		List<ItemUse> ours = route.getWithdraw().stream().filter(use -> !route.getWithdrawInCoreTag().contains(use.getItemId()))
			.collect(Collectors.toList());
		int fromCoreTag = route.getWithdraw().size() - ours.size();
		List<String> lines = new ArrayList<>();
		ours.forEach(use -> lines.add(itemNames.apply(use.getItemId()) + (use.getContainer() == Container.GROUP_STORAGE ? " (group storage)" : "")));
		if (fromCoreTag > 0)
		{
			lines.add(fromCoreTag + " from the Clue Scroll plugin's clue tag");
		}
		if (route.isDepositFirst())
		{
			lines.add(0, "Deposit first: not enough free slots");
		}
		String text = route.getWithdraw().isEmpty() ? "Close the bank" : "Withdraw " + String.join(", ", lines);
		return SubStep.builder().kind(SubStep.Kind.WITHDRAW).text(text)
			.detail(route.getWithdraw().isEmpty() ? null : "Then close the bank")
			.bankItems(route.getWithdraw().stream().map(ItemUse::getItemId).collect(Collectors.toUnmodifiableSet())).build();
	}

	private static SubStep walk(Leg leg, Leg next, Step step)
	{
		String ticks = "≈ " + leg.getTicks() + " ticks";
		if (step.getTargets().contains(leg.getTo()))
		{
			int radius = step.getKind() == TargetKind.AREA ? AREA_ARRIVE_TILES : ARRIVE_TILES;
			return SubStep.builder().kind(SubStep.Kind.WALK_TO_CLUE).text("Walk to the clue").detail(ticks + "; follow the Clue Scroll marker")
				.goal(leg.getTo()).radius(radius).build();
		}
		return SubStep.builder().kind(SubStep.Kind.WALK).text("Walk to " + walkGoal(next)).detail(ticks)
			.waypoint(leg.getTo()).goal(leg.getTo()).radius(ARRIVE_TILES).build();
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
			default:
				return "the marker";
		}
	}

	/** The Swap's book is its spell's: after the fused cast the leg is back on Lunar. */
	private static SubStep swap(Leg leg)
	{
		Book book = leg.getRow().getRequirements().getBook();
		return SubStep.builder().kind(SubStep.Kind.SPELLBOOK_SWAP).text("Spellbook Swap to " + bookName(book))
			.detail("Right-click Spellbook Swap, then pick the book").book(book).change(leg.getChange()).build();
	}

	private static SubStep teleport(Leg leg, HouseState house)
	{
		CatalogRow row = leg.getRow();
		return SubStep.builder().kind(SubStep.Kind.TELEPORT).text(teleportText(row)).detail(withHotkey(teleportDetail(row), hotkey(row, house)))
			.goal(leg.getTo()).radius(LANDING_TILES).row(row).build();
	}

	static String teleportText(CatalogRow row)
	{
		String name = row.getName();
		if (row.getCategory().isHouse())
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
		if (row.getCategory().isHouse())
		{
			return houseDetail(row);
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

	/** "Castle Wars Minigame Teleport" → "Castle Wars", as the Grouping dropdown lists it. */
	static String minigameName(CatalogRow row)
	{
		return row.getName().replace(MINIGAME_SUFFIX, "");
	}

	/**
	 * The key that picks the row's line in the list it opens (Nathan, B16): the nexus slot's, the jewellery box line's, a
	 * 187 list line's ("5: Feldip Hills"), or the number a catalog name gives ("Achievement diary cape: 7. Kaleb Paramaya");
	 * 0 when there is none.
	 */
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

	/** "Portal nexus → Annakarl": the piece, then the place, without the left/right-click note of the catalog name. */
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

	/** How to pick it: the nexus map region first, the box's teleport menu, or the object's own option. */
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

	/** The nexus destination as the cache names it: "Portal nexus: Respawn (Camelot)" → "Respawn". */
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
			.detail(coded ? where : null)
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

	/** What to do on arrival; the Clue Scroll plugin's overlay shows the details. */
	static SubStep act(Step step)
	{
		if (needsItemFromNpc(step))
		{
			String what = hasAny(step.getObjectNames()) ? "the " + step.getObjectNames().get(0).toLowerCase() : "it";
			return SubStep.builder().kind(SubStep.Kind.ACT).text("Search " + what)
				.detail("Get the key from " + step.getNpcs().get(0) + " first; the Clue Scroll plugin says how").build();
		}
		return SubStep.builder().kind(SubStep.Kind.ACT).text(actText(step)).detail("The Clue Scroll plugin shows the details").build();
	}

	/**
	 * A cryptic naming both an NPC and an object: the NPC holds the key the object needs (12 of core's 13 such steps;
	 * Juna's "speak to the guardian" is the other). Kill-for-key sub-steps with the NPC's place are B20's.
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

	/** Whether {@code tile} is within {@code radius} of {@code goal} on the same plane. */
	static boolean near(WorldPoint tile, WorldPoint goal, int radius)
	{
		return tile != null && goal != null && tile.getPlane() == goal.getPlane() && tile.distanceTo2D(goal) <= radius;
	}
}
