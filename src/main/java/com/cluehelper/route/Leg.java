package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.SpellbookChange;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One step of a route. Ticks are half-ticks internally; {@link #getTicks()} rounds up. */
@Value
public class Leg
{
	public enum Kind
	{
		WALK,
		/** Walking due south out of the Wilderness to where more teleports work. */
		WILDERNESS_EXIT,
		TELEPORT,
		/** Spellbook Swap fused with the spell it enables (PLAN §5.4). */
		SWAP_TELEPORT,
		FAIRY_RING,
		SPELLBOOK_CHANGE,
		BANK
	}

	Kind kind;
	WorldPoint from;
	WorldPoint to;
	int halfTicks;
	/** The teleport, fairy-ring entry or Swap's spell; null for walks, changes and bank stops. */
	CatalogRow row;
	/** For a fairy ring, the landing row (its code). */
	CatalogRow landing;
	SpellbookChange change;
	/** The book after this leg; for a pending Spellbook Swap, the temporary book. */
	Book bookAfter;

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
			default:
				return "bank";
		}
	}
}
