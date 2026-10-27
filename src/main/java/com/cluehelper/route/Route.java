package com.cluehelper.route;

import com.cluehelper.player.*;
import com.cluehelper.transport.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder
public class Route
{
	public static final String WALK = "walk";

	List<Leg> legs;
	int halfTicks;
	/** Half-ticks plus the ranking-only consumable, cape-use and stranding penalties. */
	int rankHalfTicks;
	boolean banked;
	/** Spellbook Swap included. */
	boolean changed;
	/** Lunar while a Spellbook Swap is pending. */
	Book startBook;
	Book endBook;
	/** Keyed like {@code item:1712}, {@code cooldown:892}, {@code cape}. */
	Map<String, Integer> spent;
	RowAvailability.Confidence confidence;
	boolean wilderness;
	@Builder.Default
	List<ItemUse> withdraw = List.of();
	/** The withdraw list needs more slots than are free; the extra click is counted in the ticks. */
	boolean depositFirst;
	boolean pinIncluded;
	/** Banked, but with no bank leg: the route starts with the withdraw list. */
	boolean openBankStart;

	public int getTicks()
	{
		return (halfTicks + 1) / 2;
	}

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

	public String getFirstTransportKey()
	{
		Leg first = getFirstTransport();
		return first == null ? WALK : transportKey(first);
	}

	/**
	 * A way into the house followed by the house's own transport counts as that transport, or one house route would
	 * hide all others from the alternatives.
	 */
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
	 * Alternatives leave this out too, or every way to reach a bank would come back as an alternative with the same
	 * teleport after it.
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

	private static String transportKey(Leg leg)
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
