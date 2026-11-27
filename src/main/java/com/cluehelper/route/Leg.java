package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.transport.*;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

@Value
@Builder(toBuilder = true)
public class Leg
{
	private static final int EXIT_COLUMNS = 8;

	public enum Kind
	{
		WALK,
		WILDERNESS_EXIT,
		TELEPORT,
		SWAP_TELEPORT,
		FAIRY_RING,
		SPELLBOOK_CHANGE,
		BANK;

		public boolean isTransport()
		{
			return this == TELEPORT || this == SWAP_TELEPORT || this == FAIRY_RING;
		}
	}

	@NonNull
	Kind kind;
	@NonNull
	WorldPoint from;
	@NonNull
	WorldPoint to;
	int halfTicks;
	/** The teleport's row, or a fairy ring's entry ring; null for walks, exits, spellbook changes and bank stops. */
	CatalogRow row;
	CatalogRow landing;
	SpellbookChange change;
	/** The book after the leg; for a pending Spellbook Swap, the temporary book. */
	Book bookAfter;

	public static Leg teleport(WorldPoint from, CatalogRow row, int halfTicks, Book book)
	{
		return builder().kind(Kind.TELEPORT).from(from).to(row.getDestination()).halfTicks(halfTicks).row(row).bookAfter(book).build();
	}

	/**
	 * Where a walk due south out of the Wilderness ends, a tile per half-tick: on the last column of its group of
	 * {@link #EXIT_COLUMNS}, as a few tiles aside cost no time, and a step aside between plans doesn't move the end.
	 */
	static WorldPoint wildernessExitEnd(WorldPoint from, int exitY)
	{
		return new WorldPoint(from.getX() | EXIT_COLUMNS - 1, exitY, from.getPlane());
	}

	public int getTicks()
	{
		return (halfTicks + 1) / 2;
	}

	public boolean isTransport()
	{
		return kind.isTransport();
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
