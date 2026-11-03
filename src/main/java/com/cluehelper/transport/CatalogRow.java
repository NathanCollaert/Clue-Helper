package com.cluehelper.transport;

import com.cluehelper.player.PositionRules;
import java.util.List;
import java.util.regex.Pattern;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/**
 * Fairy rings come as Shortest Path has them: an entry row (origin, no destination) per ring you can stand at, and a
 * landing row (destination and code, no origin) per code; any entry reaches any landing.
 */
@Value
@Builder(toBuilder = true)
public class CatalogRow
{
	private static final String QUETZAL_MENU = "quetzal ";
	private static final String BOAT_MENU = "boat ";
	private static final Pattern OPENS_MENU = Pattern.compile("^(nexus|box|list|quetzal|boat) ");
	/** Between a menu cell's option and the list line it opens. */
	public static final String MENU_STEP = " > ";
	private static final String MINIGAME_SUFFIX = " Minigame Teleport";
	private static final Pattern BRACKETED_NOTE = Pattern.compile(" \\([^)]*\\)$");
	/** A jewellery box's or mounted item's "7. " before its place. */
	private static final Pattern HOTKEY_LABEL = Pattern.compile("^[0-9A-Z]\\. ");
	/** The teleport words before or after a place, or a name that is "Teleport" alone. */
	private static final Pattern TELEPORT_WORDS = Pattern.compile(
		"^Teleport to |^Teleport$|(" + MINIGAME_SUFFIX + "| [Tt]eleport( scroll)?| tablet)$");

	String name;
	Category category;
	/** Null for a teleport and for a fairy-ring landing. */
	WorldPoint origin;
	/** Null for a fairy-ring entry row. */
	WorldPoint destination;
	/** Null except on a fairy-ring landing row. */
	String code;
	int duration;
	int menuTicks;
	@Builder.Default
	List<Spend> spends = List.of();
	Requirements requirements;
	/**
	 * How to find the row's line in the menu it opens, as a kind and what to look for: {@code nexus} (struct id, map
	 * region), {@code box} (script 1688's index), {@code list} (a 187 line), {@code op} (the object's own option; a
	 * {@code >} after it picks a 187 line next), {@code npc} (the option of the NPC whose id is in objectId),
	 * {@code boat} (the dock the Teleport to Boat list names as the boat's location); null otherwise.
	 */
	String menu;
	/** -1 when none. */
	@Builder.Default
	int objectId = -1;

	public boolean isInHouse()
	{
		return PositionRules.POH_NODE.equals(origin);
	}

	/** Used where it stands, like house furniture or a spirit tree; fairy rings are entries and landings instead. */
	public boolean isLocated()
	{
		return origin != null && category != Category.FAIRY_RING;
	}

	/** Its menu is the quetzal map: {@code quetzal <table id>}. */
	public boolean isQuetzalMap()
	{
		return menu != null && menu.startsWith(QUETZAL_MENU);
	}

	/** -1 when the row isn't the quetzal map. */
	public int quetzalTableId()
	{
		return isQuetzalMap() ? Integer.parseInt(menu.substring(QUETZAL_MENU.length())) : -1;
	}

	/** Its first click opens a list or map the place is picked from (the portal nexus), so that click teleports nowhere yet. */
	public boolean opensMenu()
	{
		return menu != null && (OPENS_MENU.matcher(menu).find() || menu.contains(MENU_STEP));
	}

	/** The dock a boat teleport's list names as the boat's location, or null when the row isn't a boat teleport. */
	public String boatDock()
	{
		return menu != null && menu.startsWith(BOAT_MENU) ? menu.substring(BOAT_MENU.length()) : null;
	}

	public String minigameName()
	{
		return name.replace(MINIGAME_SUFFIX, "");
	}

	/**
	 * Where the row goes, as its name says it: the part after the last colon, without the teleport words round it;
	 * empty when the name doesn't say.
	 */
	public String destination()
	{
		String place = BRACKETED_NOTE.matcher(name).replaceFirst("");
		int colon = place.lastIndexOf(": ");
		String last = colon >= 0 ? HOTKEY_LABEL.matcher(place.substring(colon + 2)).replaceFirst("") : place;
		String stripped = TELEPORT_WORDS.matcher(last).replaceAll("");
		boolean onlyAnItem = colon < 0 && stripped.equals(place);
		return onlyAnItem ? "" : stripped;
	}

	/** The place after the row name's colon, without its bracketed note. */
	public String nexusPlace()
	{
		String place = name.substring(name.indexOf(": ") + 2);
		int bracket = place.indexOf(" (");
		return bracket < 0 ? place : place.substring(0, bracket);
	}
}
