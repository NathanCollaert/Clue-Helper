package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.RowAvailability;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One planned route: its legs, its ticks (shown) and its rank (ticks plus ranking-only penalties). */
@Value
@Builder
public class Route
{
	public static final String WALK = "walk";

	List<Leg> legs;
	int halfTicks;
	/** Half-ticks plus consumable, cape-use and stranding penalties; what routes are ordered by. */
	int rankHalfTicks;
	boolean banked;
	/** Uses any spellbook change, Spellbook Swap included. */
	boolean changed;
	/** Uses a change that walks to an altar or Tyss (rests on estimated costs, so it needs the margin). */
	boolean walkedChange;
	/** The book the player is on for good: Lunar while a Spellbook Swap is pending. */
	Book startBook;
	Book endBook;
	/** Resource → units used: {@code item:1712}, {@code cooldown:892}, {@code cape}. */
	Map<String, Integer> spent;
	RowAvailability.Confidence confidence;
	/** The route lands in or walks through the Wilderness. */
	boolean wilderness;

	public int getTicks()
	{
		return (halfTicks + 1) / 2;
	}

	/** Route identity (PLAN §5.4): first transport or walk, bank stop, first transport after it, changes in order. */
	public String getIdentity()
	{
		StringBuilder id = new StringBuilder(getFirstTransportKey());
		boolean afterBank = false;
		for (Leg leg : legs)
		{
			if (leg.getKind() == Leg.Kind.BANK)
			{
				id.append(" | bank");
				afterBank = true;
			}
			else if (afterBank && leg.isTransport())
			{
				id.append(" | ").append(transportKey(leg));
				afterBank = false;
			}
		}
		String changes = legs.stream()
			.filter(leg -> leg.getKind() == Leg.Kind.SPELLBOOK_CHANGE || leg.getKind() == Leg.Kind.SWAP_TELEPORT)
			.map(leg -> leg.getKind() == Leg.Kind.SWAP_TELEPORT ? "Spellbook Swap" : leg.getChange().getName() + "→" + leg.getBookAfter())
			.collect(Collectors.joining(", "));
		return changes.isEmpty() ? id.toString() : id + " | " + changes;
	}

	/** The first transport with its destination, or "walk". Alternatives are searched without it. */
	public String getFirstTransportKey()
	{
		return legs.stream().filter(Leg::isTransport).findFirst().map(Route::transportKey).orElse(WALK);
	}

	static String transportKey(Leg leg)
	{
		return leg.getKind() == Leg.Kind.FAIRY_RING ? fairyKey(leg.getLanding()) : teleportKey(leg.getRow());
	}

	static String teleportKey(CatalogRow row)
	{
		WorldPoint to = row.getDestination();
		return row.getName() + "@" + to.getX() + "," + to.getY() + "," + to.getPlane();
	}

	static String fairyKey(CatalogRow landing)
	{
		return "fairy " + landing.getCode();
	}

	public String describe()
	{
		return legs.stream().map(Leg::describe).collect(Collectors.joining(" → "));
	}
}
