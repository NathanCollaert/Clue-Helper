package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.transport.*;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Leg
{
	public enum Kind
	{
		WALK,
		WILDERNESS_EXIT,
		TELEPORT,
		SWAP_TELEPORT,
		FAIRY_RING,
		SPELLBOOK_CHANGE,
		BANK
	}

	Kind kind;
	WorldPoint from;
	WorldPoint to;
	int halfTicks;
	/** The teleport's row, or a fairy ring's entry ring; null for walks, exits, spellbook changes and bank stops. */
	CatalogRow row;
	CatalogRow landing;
	SpellbookChange change;
	/** For a pending Spellbook Swap, the temporary book. */
	Book bookAfter;

	public static Leg walk(WorldPoint from, WorldPoint to, int halfTicks, Book book)
	{
		return new Leg(Kind.WALK, from, to, halfTicks, null, null, null, book);
	}

	/** Due south until out of the Wilderness, a tile per half-tick. */
	public static Leg wildernessExit(WorldPoint from, int exitY, int halfTicks, Book book)
	{
		return new Leg(Kind.WILDERNESS_EXIT, from, new WorldPoint(from.getX(), exitY, from.getPlane()), halfTicks, null, null, null, book);
	}

	public static Leg teleport(WorldPoint from, CatalogRow row, int halfTicks, Book book)
	{
		return new Leg(Kind.TELEPORT, from, row.getDestination(), halfTicks, row, null, null, book);
	}

	/** A Spellbook Swap cast, then the teleport it enables. */
	public static Leg swapTeleport(WorldPoint from, CatalogRow row, SpellbookChange swap, int halfTicks, Book book)
	{
		return new Leg(Kind.SWAP_TELEPORT, from, row.getDestination(), halfTicks, row, null, swap, book);
	}

	public static Leg fairyRing(WorldPoint from, CatalogRow entry, CatalogRow landing, int halfTicks, Book book)
	{
		return new Leg(Kind.FAIRY_RING, from, landing.getDestination(), halfTicks, entry, landing, null, book);
	}

	public static Leg spellbookChange(WorldPoint at, SpellbookChange change, int halfTicks, Book bookAfter)
	{
		return new Leg(Kind.SPELLBOOK_CHANGE, at, at, halfTicks, null, null, change, bookAfter);
	}

	public static Leg bank(WorldPoint at, int halfTicks, Book book)
	{
		return new Leg(Kind.BANK, at, at, halfTicks, null, null, null, book);
	}

	public int getTicks()
	{
		return (halfTicks + 1) / 2;
	}

	public boolean isTransport()
	{
		return kind == Kind.TELEPORT || kind == Kind.SWAP_TELEPORT || kind == Kind.FAIRY_RING;
	}

	public String describe()
	{
		switch (kind)
		{
			case WALK:
				return "walk " + getTicks();
			case WILDERNESS_EXIT:
				return "walk south " + getTicks();
			case TELEPORT:
				return row.getName();
			case SWAP_TELEPORT:
				return "Spellbook Swap · " + row.getName();
			case FAIRY_RING:
				return landing.getName();
			case SPELLBOOK_CHANGE:
				return change.getName() + " → " + bookAfter;
			case BANK:
				return "bank";
			default:
				throw new IllegalArgumentException("unhandled leg kind " + kind);
		}
	}
}
