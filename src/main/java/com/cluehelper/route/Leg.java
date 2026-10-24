package com.cluehelper.route;

import com.cluehelper.player.Book;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.SpellbookChange;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
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
