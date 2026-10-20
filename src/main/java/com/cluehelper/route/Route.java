package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.player.ItemUse;
import com.cluehelper.player.PositionRules;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.RowAvailability;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	/** What to take from the bank or group storage: route items and the step's required items (PLAN §5.4). */
	@Builder.Default
	List<ItemUse> withdraw = List.of();
	/** Withdraw-list items core's *clue* bank tag already shows: our bank highlights leave them out. */
	@Builder.Default
	Set<Integer> withdrawInCoreTag = Set.of();
	/** The withdraw list needs more inventory slots than are free: deposit first (1 tick, counted). */
	boolean depositFirst;
	/** The bank stop's ticks include the PIN. */
	boolean pinIncluded;
	/** Planned at an open bank: the route starts with the withdraw list, no bank leg. */
	boolean openBankStart;

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

	/**
	 * The first transport with its destination, or "walk". Alternatives are searched without it. A way into the house
	 * followed by the house's own transport counts as that transport: otherwise one house route would hide all others.
	 */
	public String getFirstTransportKey()
	{
		Leg first = getFirstTransport();
		return first == null ? WALK : transportKey(first);
	}

	/** The first transport, past a way into the house that the house's own transport follows; null for a walk. */
	public Leg getFirstTransport()
	{
		List<Leg> transports = legs.stream().filter(Leg::isTransport).collect(Collectors.toList());
		if (transports.isEmpty())
		{
			return null;
		}
		Leg first = transports.get(0);
		boolean intoHouse = PositionRules.POH_NODE.equals(first.getTo()) && transports.size() > 1;
		return intoHouse ? transports.get(1) : first;
	}

	/**
	 * The first transport after the bank stop, or null without one. Alternatives leave it out too: otherwise every way
	 * to reach a bank comes back as an alternative with the same teleport after it.
	 */
	public String getAfterBankTransportKey()
	{
		boolean afterBank = false;
		for (Leg leg : legs)
		{
			if (leg.getKind() == Leg.Kind.BANK)
			{
				afterBank = true;
			}
			else if (afterBank && leg.isTransport())
			{
				return transportKey(leg);
			}
		}
		return null;
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
