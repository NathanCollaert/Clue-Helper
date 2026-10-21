package com.cluehelper.transport;

import com.cluehelper.player.PositionRules;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * One teleport or transport of the bundled catalog. Fairy rings come as Shortest Path has them: an
 * entry row (origin, no destination) per ring you can stand at, and a landing row (destination and
 * code, no origin) per code; any entry reaches any landing.
 */
@Value
@Builder
public class CatalogRow
{
	/** Shortest Path's Display info, or the cache's place name for a fairy ring. */
	String name;
	Category category;
	/** Where the row starts; null for a teleport and for a fairy-ring landing. */
	WorldPoint origin;
	/** Null for a fairy-ring entry row. */
	WorldPoint destination;
	/** The fairy-ring code of a landing row; null otherwise. */
	String code;
	int duration;
	int menuTicks;
	@Builder.Default
	List<Spend> spends = List.of();
	Requirements requirements;
	/**
	 * How to find the row's line in the menu it opens (catalog Menu column, POH rows): {@code nexus 466 Wilderness}
	 * (struct id, map region), {@code box 10} (script 1688's index), {@code list Xeric's Inferno} (a 187 line),
	 * {@code op Karamja} (the object's own option); null otherwise.
	 */
	String menu;
	/** The object to use, from Shortest Path's menuOption menuTarget objectID column (a spirit tree); -1 when none. */
	@Builder.Default
	int objectId = -1;

	/** Starts at the POH node: house furniture, used from inside the player's own house. */
	public boolean isInHouse()
	{
		return PositionRules.POH_NODE.equals(origin);
	}

	/** Used where it stands: house furniture, a spirit tree or Renu (fairy rings are entries and landings instead). */
	public boolean isLocated()
	{
		return origin != null && category != Category.FAIRY_RING;
	}

	/** Usable from anywhere: neither a fairy-ring entry nor a fairy-ring landing. */
	public boolean isGlobal()
	{
		return origin == null && code == null;
	}
}
