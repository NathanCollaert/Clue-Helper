package com.cluehelper.transport;

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

	/** Usable from anywhere: neither a fairy-ring entry nor a fairy-ring landing. */
	public boolean isGlobal()
	{
		return origin == null && code == null;
	}
}
