package com.cluehelper.transport;

import com.cluehelper.Tables;
import com.cluehelper.guide.MenuLines;
import com.cluehelper.player.HouseState;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;

/**
 * What the guide shows and clicks for a catalog row, worked out from its name and menu cell. The menu cell names the
 * line the row picks as a kind and what to look for: {@code nexus} (struct id, map region), {@code box} (script 1688's
 * index), {@code list} (a 187 line), {@code op} (the object's own option; a {@code >} after it picks a 187 line next),
 * {@code npc} (the option of the NPC whose id is in objectId), {@code quetzal} (a Quetzal table id, then its icon's x and y on the map), {@code boat} (the
 * dock the Teleport to Boat list names as the boat's location).
 */
final class RowParts
{
	private static final String MENU_STEP = " > ";
	private static final Pattern OPENS_MENU = Pattern.compile("^(nexus|box|list|quetzal|boat) ");
	private static final String MINIGAME_SUFFIX = " Minigame Teleport";
	private static final Pattern BRACKETED_NOTE = Pattern.compile(" \\([^)]*\\)$");
	/** A jewellery box's or mounted item's "7. " before its place. */
	private static final Pattern HOTKEY_PREFIX = Pattern.compile("^[0-9A-Z]\\. ");
	/** The teleport words before or after a place, or a name that is "Teleport" alone. */
	private static final Pattern TELEPORT_WORDS = Pattern.compile(
		"^Teleport to |^Teleport$|(" + MINIGAME_SUFFIX + "| [Tt]eleport( scroll)?| tablet)$");
	private static final String FAIRY_RING_PREFIX = "Fairy ring";
	private static final Map<Category, String> RIDE_PLACES = Tables.byKey("ride places", Category::valueOf, Tables.Row::text);
	private static final Pattern LIST_HOTKEY = Pattern.compile("^([0-9A-Z])[.:] ");
	/** The mushtree map's places in its hotkey order, 1 to 4: its buttons carry no number. */
	private static final List<String> MUSHTREE_PLACES = Tables.keys("mushtree places");
	private static final Pattern CLICK_NOTE = Pattern.compile(" \\((left|right)-click\\)$");
	private static final Map<String, String> CHANGE_WARNINGS = Tables.byKey("spellbook change warnings", Tables.Row::text);
	private static final String TRAVEL_OPTION = "Travel";
	private static final String TREE_OPTION = "Tree";
	/** The patch objects are multilocs on their transmit varbits; the grown tree is the form they show. */
	private static final Set<Integer> PLANTED_SPIRIT_TREES = Tables.idSet("planted spirit trees");
	private static final String BOOK_ROW_PREFIX = "Master scroll book: ";

	/** The line's colours only mark where the key and the grey part are, to split the line the way the plugin joins it. */
	private static final String GREY = "<col=bebebe>";
	private static final String END = "</col>";
	private static final Pattern COLOURED_KEY = Pattern.compile("(<col=[0-9a-f]{6}>[^<]+</col>)\\. ");
	private static final String HOTKEY_DASH = " - ";
	private static final String CAST = "Cast";
	private static final String BREAK = "Break";
	private static final String READ = "Read";
	private static final String USE = "Use";
	private static final String TABLET = " tablet";
	private static final String TAB = " tab";
	private static final String TELEPORT_TO = "Teleport to ";
	/** A verb, then "the" and what it is done to. */
	private static final Pattern VERB_THE = Pattern.compile("^(\\w+) the (.+)$");
	/** A key for the rows whose key only the game knows, to check the line still has a place for it. */
	private static final char SAMPLE_KEY = 'A';

	private RowParts()
	{
	}

	/** The row with its parts; {@link #withSingleLandings} needs the whole catalog. */
	static CatalogRow of(CatalogRow row, String menu)
	{
		CatalogRow.CatalogRowBuilder parts = row.toBuilder()
			.detail(teleportDetail(row, menu))
			.approach(approach(row, menu))
			.options(optionPath(row))
			.spell(isSpell(row) ? spellName(row.getName()) : null)
			.scroll(row.getName().startsWith(BOOK_ROW_PREFIX) ? row.getName().substring(BOOK_ROW_PREFIX.length()).split(": ", 2)[0] : null)
			.opensMenu(menu != null && (OPENS_MENU.matcher(menu).find() || menu.contains(MENU_STEP)));
		line(parts, row, menu);
		if (row.isLocated())
		{
			located(parts, row, menu);
		}
		return text(parts, row, menu).build();
	}

	/** Spellbook changes: the line before the book, its detail, and the place a walk heads for. */
	static SpellbookChange of(SpellbookChange change)
	{
		String text = change.isAnywhere() ? capeName(change) + ": Spellbook" : change.getName();
		String detail = !change.isAnywhere() ? warning(change)
			: change.getName().contains("(worn)") ? "Right-click the worn cape in the equipment tab" : "Right-click the cape: Spellbook";
		return change.toBuilder().text(text).detail(detail).place(place(change)).build();
	}

	/**
	 * The row's name ends in where its items land, and they land nowhere else, so it holds no option to pick (Shortest
	 * Path's "Royal seed pod: Grand Tree"): any left click of the item's own is the teleport.
	 */
	static List<CatalogRow> withSingleLandings(List<CatalogRow> rows)
	{
		return rows.stream().map(row -> row.toBuilder().singleLanding(namesItsOnlyLanding(row, rows)).build())
			.collect(Collectors.toUnmodifiableList());
	}

	private static boolean namesItsOnlyLanding(CatalogRow row, List<CatalogRow> rows)
	{
		Set<Integer> items = itemIds(row);
		return !items.isEmpty() && !row.getOptions().isEmpty() && rows.stream()
			.noneMatch(other -> !Objects.equals(other.getDestination(), row.getDestination())
				&& !Collections.disjoint(itemIds(other), items));
	}

	private static Set<Integer> itemIds(CatalogRow row)
	{
		return row.getRequirements().getItems().stream().flatMap(ItemClause::ids).collect(Collectors.toSet());
	}

	private static void line(CatalogRow.CatalogRowBuilder parts, CatalogRow row, String menu)
	{
		if (row.getCategory() == Category.MINIGAME)
		{
			parts.line(MenuLine.MINIGAME).lineName(row.getName().replace(MINIGAME_SUFFIX, ""));
			return;
		}
		String[] cell = menu == null ? new String[]{""} : menu.split(" ", 2);
		switch (cell[0])
		{
			case "quetzal":
			{
				String[] site = cell[1].split(" ");
				parts.line(MenuLine.QUETZAL).icon(new int[]{Integer.parseInt(site[1]), Integer.parseInt(site[2])});
				break;
			}
			case "nexus":
			{
				String[] nexus = cell[1].split(" ", 2);
				parts.line(MenuLine.NEXUS).lineId(Integer.parseInt(nexus[0])).lineKey(nexus[1]).lineName(nexusPlace(row));
				break;
			}
			case "box":
				parts.line(MenuLine.BOX).lineId(Integer.parseInt(cell[1]));
				break;
			case "boat":
				parts.line(MenuLine.BOAT).lineName(cell[1]);
				break;
			default:
				break;
		}
	}

	/** The row's object or NPC, with the clicks its catalog line names. */
	private static void located(CatalogRow.CatalogRowBuilder parts, CatalogRow row, String menu)
	{
		Set<Integer> objects = objectsOf(row);
		parts.objects(objects);
		if (menu == null)
		{
			return;
		}
		String[] cell = menu.split(" ", 2);
		boolean outside = !row.isInHouse();
		switch (cell[0])
		{
			case "quetzal":
				parts.npcs(Set.of(NpcID.QUETZAL_CHILD_GREEN)).clicks(List.of(TRAVEL_OPTION));
				break;
			case "list":
				parts.clicks(listPath(row, outside, cell[1]));
				break;
			case "nexus":
				break;
			case "box":
				// The ornate box's own option repeats the last place it sent the player to: one click when it's this one.
				parts.clicks(List.of(destination(row)));
				break;
			case "npc":
				parts.objects(Set.of()).npcs(Set.of(row.getObjectId())).clicks(List.of(cell[1].split(MENU_STEP)));
				break;
			default:
				parts.clicks(List.of(cell[1].split(MENU_STEP)));
				break;
		}
	}

	/** On the house's spiritual fairy tree "Ring-configure" opens a list too, so the Tree option is named. */
	private static List<String> listPath(CatalogRow row, boolean outside, String line)
	{
		if (outside)
		{
			return List.of(TRAVEL_OPTION, line);
		}
		return row.getCategory() == Category.SPIRIT_TREE ? List.of(TREE_OPTION, line) : List.of(line);
	}

	private static Set<Integer> objectsOf(CatalogRow row)
	{
		HouseNeed need = row.isInHouse() ? row.getRequirements().getHouse() : null;
		if (need != null)
		{
			return Set.copyOf(need.getKind() == HouseNeed.Kind.NEXUS ? HouseState.NEXUS_OBJECTS : need.getIds());
		}
		if (row.getObjectId() == ObjectID.SPIRIT_TREE_FULLYGROWN)
		{
			return PLANTED_SPIRIT_TREES;
		}
		return row.getObjectId() < 0 ? Set.of() : Set.of(row.getObjectId());
	}

	/**
	 * The label, grey part and key the plugin joins into "ITEM: place - key", split from the line as it reads in full.
	 * Fails when a key the game gives later would not land where it does in the full line.
	 */
	private static CatalogRow.CatalogRowBuilder text(CatalogRow.CatalogRowBuilder parts, CatalogRow row, String menu)
	{
		if (row.getCategory() == Category.FAIRY_RING)
		{
			return parts.label(FAIRY_RING_PREFIX).pick(row.getCode() == null ? fairyRingPlace(row) : destination(row));
		}
		String text = teleportText(row, menu);
		boolean gameKnowsKey = menu != null && menu.startsWith("nexus ") || row.getCategory() == Category.CHARTER;
		char key = gameKnowsKey ? 0 : hotkey(row, menu);
		String shown = travelText(withKey(text, key), destination(row));
		String keySuffix = key == 0 ? null : HOTKEY_DASH + keyText(key);
		boolean keyShown = keySuffix != null && shown.endsWith(keySuffix);
		String body = keyShown ? shown.substring(0, shown.length() - keySuffix.length()) : shown;
		int grey = body.indexOf(": " + GREY);
		String label = grey < 0 ? body : body.substring(0, grey);
		String pick = grey < 0 ? null : body.substring(grey + 2 + GREY.length(), body.length() - END.length());
		String partKey = keyShown ? String.valueOf(key) : null;
		check(shown, join(label, pick, partKey), row);
		if (gameKnowsKey)
		{
			check(travelText(withKey(text, SAMPLE_KEY), destination(row)), join(label, pick, String.valueOf(SAMPLE_KEY)), row);
		}
		return parts.label(label).pick(pick).key(partKey);
	}

	private static void check(String shown, String joined, CatalogRow row)
	{
		if (!shown.equals(joined))
		{
			throw new IllegalStateException("'" + shown + "' of " + row.getName() + " doesn't split into its parts: '" + joined + "'");
		}
	}

	/** As the plugin joins the parts. */
	private static String join(String label, String pick, String key)
	{
		if (pick == null)
		{
			return label;
		}
		String place = pick.isEmpty() ? "" : GREY + pick + END;
		return label + ": " + (key == null ? place : withHotkey(place, keyText(key.charAt(0))));
	}

	private static String fairyRingPlace(CatalogRow landing)
	{
		String place = landing.getName();
		int colon = place.indexOf(": ");
		return place.startsWith(FAIRY_RING_PREFIX) && colon >= 0 ? place.substring(colon + 2) : place;
	}

	private static String approach(CatalogRow row, String menu)
	{
		if (row.getCategory() == Category.SPIRIT_TREE && !row.isInHouse())
		{
			return "the spirit tree";
		}
		if (menu != null && menu.startsWith("npc "))
		{
			return "the outlined NPC";
		}
		return row.getCategory() == Category.QUETZAL ? "Renu" : RIDE_PLACES.getOrDefault(row.getCategory(), "the marker");
	}

	private static String teleportText(CatalogRow row, String menu)
	{
		String name = row.getName();
		if (row.isLocated() || menu != null && menu.startsWith("quetzal "))
		{
			return houseText(row);
		}
		switch (row.getCategory())
		{
			case SPELL:
			case HOME:
				return "Cast " + name;
			case MINIGAME:
				return "Minigame teleport: " + name.replace(MINIGAME_SUFFIX, "");
			case TABLET:
				return "Break the " + name;
			case SCROLL:
				return name.contains(":") ? "Use " + name : "Read the " + name + " scroll";
			default:
				return "Use " + name;
		}
	}

	private static String teleportDetail(CatalogRow row, String menu)
	{
		boolean boat = menu != null && menu.startsWith("boat ");
		if (row.getCategory() == Category.SPELL && !boat && row.getName().contains(": "))
		{
			return lastOption(row) + ": its right-click option, unless Configure made it the left click";
		}
		return row.getCategory() == Category.SPIRIT_TREE && !row.isInHouse() ? "Travel" : null;
	}

	/** 0 for a nexus line: its key is the house's. */
	private static char hotkey(CatalogRow row, String menu)
	{
		if (row.getCategory() == Category.MUSHTREE)
		{
			int place = MUSHTREE_PLACES.indexOf(lastOption(row));
			return place < 0 ? 0 : MenuLines.HOTKEYS.charAt(place);
		}
		if (menu != null && menu.startsWith("nexus "))
		{
			return 0;
		}
		if (menu != null && menu.startsWith("box "))
		{
			int line = Integer.parseInt(menu.substring("box ".length()));
			return line < MenuLines.HOTKEYS.length() ? MenuLines.HOTKEYS.charAt(line) : 0;
		}
		String label = menu != null && menu.startsWith("list ") ? menu.substring("list ".length())
			: menu != null && menu.contains(MENU_STEP) ? menu.substring(menu.lastIndexOf(MENU_STEP) + MENU_STEP.length())
			: lastOption(row);
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

	/**
	 * The hotkey in the menu line's colour: the one the list label already carries, else put before the destination as a
	 * list label shows it.
	 */
	private static String withKey(String text, char key)
	{
		if (key == 0)
		{
			return text;
		}
		String coloured = Matcher.quoteReplacement(keyText(key));
		String inLabel = text.replaceFirst("(?<=: )" + Pattern.quote(String.valueOf(key)) + "(?=[.:] )", coloured);
		int colon = text.lastIndexOf(": ");
		return !inLabel.equals(text) || colon < 0 ? inLabel : text.substring(0, colon + 2) + keyText(key) + ". "
			+ text.substring(colon + 2);
	}

	private static String keyText(char key)
	{
		return "<col=40d0ff>" + Character.toUpperCase(key) + END;
	}

	/** ITEM: DESTINATION HOTKEY, or VERB: THING for a teleport with no item. */
	private static String travelText(String text, String destination)
	{
		int colon = text.indexOf(": ");
		if (colon < 0)
		{
			return actionText(text, destination);
		}
		String item = text.substring(0, colon).replaceFirst("^Use ", "");
		Matcher hotkey = COLOURED_KEY.matcher(text.substring(colon + 2));
		String click = hotkey.replaceFirst("");
		return item + ": " + (hotkey.reset().find() ? withHotkey(coloured(click), hotkey.group(1)) : coloured(click));
	}

	private static String actionText(String text, String destination)
	{
		if (text.startsWith(CAST + " "))
		{
			return CAST + ": " + coloured(destination.isEmpty() ? text.substring(CAST.length() + 1) : destination);
		}
		Matcher action = VERB_THE.matcher(text);
		if (!action.matches())
		{
			return text;
		}
		String verb = action.group(1);
		String thing = action.group(2);
		if (BREAK.equals(verb))
		{
			return BREAK + ": " + coloured(tabName(thing));
		}
		return (READ.equals(verb) ? USE : verb) + ": " + coloured(capitalise(thing));
	}

	/** A tablet as its item is called: a teleport tab. */
	private static String tabName(String tablet)
	{
		if (!tablet.contains(TABLET))
		{
			return tablet + TAB;
		}
		return tablet.replace(TABLET, tablet.startsWith(TELEPORT_TO) ? TAB : " teleport" + TAB);
	}

	private static String withHotkey(String place, String hotkey)
	{
		return place.isEmpty() ? hotkey : place + HOTKEY_DASH + hotkey;
	}

	private static String coloured(String destination)
	{
		return GREY + destination + END;
	}

	private static String capitalise(String text)
	{
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	/**
	 * Where the row goes, as its name says it: the part after the last colon, without the teleport words round it;
	 * empty when the name doesn't say.
	 */
	private static String destination(CatalogRow row)
	{
		String place = BRACKETED_NOTE.matcher(row.getName()).replaceFirst("");
		int colon = place.lastIndexOf(": ");
		String last = colon >= 0 ? HOTKEY_PREFIX.matcher(place.substring(colon + 2)).replaceFirst("") : place;
		String stripped = TELEPORT_WORDS.matcher(last).replaceAll("");
		boolean onlyAnItem = colon < 0 && stripped.equals(place);
		return onlyAnItem ? "" : stripped;
	}

	/** The place after the row name's colon, without its bracketed note. */
	private static String nexusPlace(CatalogRow row)
	{
		String place = row.getName().substring(row.getName().indexOf(": ") + 2);
		int bracket = place.indexOf(" (");
		return bracket < 0 ? place : place.substring(0, bracket);
	}

	/** The options the row's name lists after its item, one per colon. */
	private static List<String> optionPath(CatalogRow row)
	{
		String[] parts = row.getName().split(": ");
		return parts.length < 2 ? List.of() : List.of(parts).subList(1, parts.length);
	}

	private static boolean isSpell(CatalogRow row)
	{
		return row.getCategory() == Category.SPELL || row.getCategory() == Category.HOME;
	}

	private static String spellName(String rowName)
	{
		String name = rowName;
		int colon = name.indexOf(':');
		if (colon >= 0)
		{
			name = name.substring(0, colon);
		}
		int bracket = name.indexOf(" (");
		return bracket >= 0 ? name.substring(0, bracket) : name;
	}

	/** The row's last option: its name after the last ": ". */
	private static String lastOption(CatalogRow row)
	{
		return row.getName().substring(row.getName().lastIndexOf(": ") + 2);
	}

	private static String capeName(SpellbookChange change)
	{
		return beforeColon(change.getName()).replace(" (worn)", "");
	}

	private static String place(SpellbookChange change)
	{
		String where = beforeColon(change.getName());
		return where.startsWith("Tyss") ? "Tyss" : "the " + where;
	}

	private static String beforeColon(String name)
	{
		int colon = name.indexOf(':');
		return colon < 0 ? name : name.substring(0, colon);
	}

	private static String warning(SpellbookChange change)
	{
		return CHANGE_WARNINGS.entrySet().stream().filter(e -> change.getName().startsWith(e.getKey()))
			.map(Map.Entry::getValue).findFirst().orElse(null);
	}
}
