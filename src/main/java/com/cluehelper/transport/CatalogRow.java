package com.cluehelper.transport;

import com.cluehelper.player.PositionRules;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Fairy rings come as Shortest Path has them: an entry row (origin, no destination) per ring you can stand at, and a
 * landing row (destination and code, no origin) per code; any entry reaches any landing.
 */
@Value
@Builder
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
	/**
	 * How to find the row's line in the menu it opens: {@code nexus 466 Wilderness} (struct id, map region),
	 * {@code box 10} (script 1688's index), {@code list Xeric's Inferno} (a 187 line), {@code op Karamja} (the
	 * object's own option); null otherwise.
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

	public boolean isGlobal()
	{
		return origin == null && code == null;
	}
}
