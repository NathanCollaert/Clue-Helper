package com.cluehelper.transport;

import com.cluehelper.player.PositionRules;
import java.util.*;
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
	/** -1 when none. */
	@Builder.Default
	int objectId = -1;
	/** The guide's line before its colon: the item, object or verb; the whole line when {@link #pick} is null. */
	String label;
	/** Shown grey after the label: the place or the tab; empty when only the key follows the label. */
	String pick;
	/** Its menu line's hotkey; null when it has none, or when only the house (a nexus) or the open list (a charter) knows it. */
	String key;
	/** The guide's second line, or null. */
	String detail;
	/** What a walk to it heads for: "the spirit tree", "Renu". */
	String approach;
	/** The options its held item's menu takes, one per colon of its name after the item. */
	@Builder.Default
	List<String> options = List.of();
	/** Where it stands: the objects whose menu it is picked from. */
	@Builder.Default
	Set<Integer> objects = Set.of();
	@Builder.Default
	Set<Integer> npcs = Set.of();
	/** The options clicked on its object or NPC, then the list line picked. */
	@Builder.Default
	List<String> clicks = List.of();
	@Builder.Default
	MenuLine line = MenuLine.LABEL;
	/** A nexus line's struct id or a jewellery box line's index. */
	int lineId;
	/** A nexus line's map region shield. */
	String lineKey;
	/** The cache's name of a nexus destination, a minigame or a boat's dock. */
	String lineName;
	/** Where a Quetzal site's icon sits on the map, x and y; null for any other line. */
	int[] icon;
	/** The spell's name, without its option; null when it isn't a spell. */
	String spell;
	/** The cache object of the spell, whose params name its spellbook button; -1 when it isn't a spell the cache lists. */
	@Builder.Default
	int spellObj = -1;
	/** The master scroll book's scroll it reads, as the loose scroll is named; null when it isn't a book teleport. */
	String scroll;
	/** Its first click opens a list or map the place is picked from (the portal nexus), so that click teleports nowhere yet. */
	boolean opensMenu;
	/** Its items teleport nowhere else, so any left click of their own is this teleport. */
	boolean singleLanding;

	public boolean isInHouse()
	{
		return PositionRules.POH_NODE.equals(origin);
	}

	/** Used where it stands, like house furniture or a spirit tree; fairy rings are entries and landings instead. */
	public boolean isLocated()
	{
		return origin != null && category != Category.FAIRY_RING;
	}
}
